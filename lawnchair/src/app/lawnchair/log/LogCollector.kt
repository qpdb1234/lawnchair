/*
 * Copyright 2022, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.log

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Comprehensive runtime log collector.
 *
 * When enabled it:
 *  - streams the whole system logcat (all buffers) into rotating files under
 *    [cacheDir]/logs, so nothing the launcher or surrounding system emits is lost;
 *  - persists structured entries written through the [log] helpers (tagged with the
 *    launcher's own lifecycle / preference actions);
 *  - installs an [Thread.UncaughtExceptionHandler] that dumps every crash to
 *    [cacheDir]/logs/crashes before chaining to the previous handler.
 *
 * Everything is written to disk and can later be bundled by [LogExporter].
 */
object LogCollector {
    private const val TAG = "LawnchairLogCollector"

    /** A single log file rolls over once it grows past this size. */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** How many rolled-over files to keep around before the oldest are pruned. */
    private const val KEEP_ROTATED = 5

    private const val INTERNAL_LOG = "launcher-internal.log"
    private const val LOGCAT_LOG = "logcat-live.log"

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val ioLock = Any()

    @Volatile
    private var running = false
    private var logDir: File? = null
    private var logcatProcess: java.lang.Process? = null
    private var logcatThread: Thread? = null
    private var writer = Executors.newSingleThreadExecutor()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    val isEnabled: Boolean get() = running

    fun logsDir(context: Context): File =
        File(context.applicationContext.cacheDir, "logs").apply { mkdirs() }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled == running) return
        if (enabled) start(context) else stop()
    }

    @Synchronized
    private fun start(context: Context) {
        if (running) return
        running = true
        val dir = logsDir(context)
        logDir = dir
        if (writer.isShutdown) writer = Executors.newSingleThreadExecutor()
        installCrashHandler(dir)
        startLogcatStream(dir)
        log("I", TAG, "Log collection started (pid=${Process.myPid()}, sdk=${Build.VERSION.SDK_INT})")
        Log.i(TAG, "Log collection started")
    }

    @Synchronized
    private fun stop() {
        if (!running) return
        log("I", TAG, "Log collection stopped")
        running = false
        stopLogcatStream()
        previousHandler?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
        previousHandler = null
        writer.shutdown()
        logDir = null
        Log.i(TAG, "Log collection stopped")
    }

    // region public logging helpers

    fun v(tag: String, msg: String) = log("V", tag, msg, null)
    fun d(tag: String, msg: String) = log("D", tag, msg, null)
    fun i(tag: String, msg: String) = log("I", tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log("W", tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log("E", tag, msg, tr)

    fun log(level: String, tag: String, msg: String, tr: Throwable? = null) {
        if (!running) return
        val dir = logDir ?: return
        val line = formatEntry(level, tag, msg, tr)
        runCatching { writer.execute { appendRotating(File(dir, INTERNAL_LOG), line) } }
    }

    // endregion

    private fun formatEntry(level: String, tag: String, msg: String, tr: Throwable?): String {
        val sb = StringBuilder()
        sb.append(timeFormat.format(Date()))
            .append(' ').append(level).append('/').append(tag).append(": ").append(msg)
        if (tr != null) sb.append('\n').append(Log.getStackTraceString(tr))
        return sb.toString()
    }

    private fun appendRotating(target: File, text: String) {
        synchronized(ioLock) {
            runCatching {
                if (target.exists() && target.length() > MAX_FILE_BYTES) {
                    rotate(target)
                }
                FileWriter(target, true).use { it.append(text).append('\n') }
            }
        }
    }

    private fun rotate(target: File) {
        val parent = target.parentFile ?: return
        val stamped = File(parent, "${target.nameWithoutExtension}_${fileFormat.format(Date())}_${System.nanoTime()}.log")
        if (target.renameTo(stamped)) {
            pruneOld(parent, target.nameWithoutExtension)
        }
    }

    private fun pruneOld(dir: File, baseName: String) {
        val rotated = dir.listFiles { f -> f.name.startsWith("${baseName}_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        rotated.drop(KEEP_ROTATED).forEach { it.delete() }
    }

    // region crash handler

    private fun installCrashHandler(dir: File) {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Write synchronously: the process is about to die.
            runCatching {
                val crashDir = File(dir, "crashes").apply { mkdirs() }
                val file = File(crashDir, "crash_${fileFormat.format(Date())}_${thread.id}.txt")
                val sb = StringBuilder()
                sb.append("Thread: ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
                sb.append("Time: ").append(timeFormat.format(Date())).append('\n')
                sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                sb.append("App: ").append(dir.parentFile?.name ?: "?").append('\n')
                sb.append("-----\n")
                sb.append(Log.getStackTraceString(throwable))
                file.writeText(sb.toString())
                pruneCrashes(crashDir)
            }
            runCatching {
                appendRotating(File(dir, INTERNAL_LOG), formatEntry("E", "UncaughtException",
                    throwable.javaClass.name + ": " + throwable.message, throwable))
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun pruneCrashes(crashDir: File) {
        val files = crashDir.listFiles { f -> f.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(KEEP_ROTATED).forEach { it.delete() }
    }

    // endregion

    // region logcat streaming

    private fun startLogcatStream(dir: File) {
        val out = File(dir, LOGCAT_LOG)
        val process = try {
            ProcessBuilder("logcat", "-b", "all", "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            Log.w(TAG, "Unable to start logcat stream", e)
            return
        }
        logcatProcess = process
        val thread = Thread {
            var bytes = if (out.exists()) out.length() else 0L
            runCatching {
                FileWriter(out, true).use { fw ->
                    process.inputStream.bufferedReader().use { reader ->
                        var flushCounter = 0
                        while (running) {
                            val line = reader.readLine() ?: break
                            fw.write(line)
                            fw.write("\n")
                            bytes += line.length + 1
                            if (++flushCounter >= 64) {
                                fw.flush()
                                flushCounter = 0
                            }
                            if (bytes > MAX_FILE_BYTES) {
                                fw.flush()
                                rotate(out)
                                bytes = 0L
                            }
                        }
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.name = "LawnchairLogcatStream"
        logcatThread = thread
        thread.start()
    }

    private fun stopLogcatStream() {
        logcatProcess?.destroy()
        logcatProcess = null
        logcatThread = null
    }

    // endregion
}

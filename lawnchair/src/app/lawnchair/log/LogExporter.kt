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

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import app.lawnchair.LawnchairApp
import com.android.launcher3.R
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bundles every piece of diagnostic information into a single text file and exports it:
 *  - device / build / app metadata,
 *  - a full `logcat -b all` snapshot (last [MAX_LOGCAT_LINES] lines),
 *  - the launcher's own structured log ([LogCollector] internal log),
 *  - the tail of the live logcat stream files,
 *  - any recorded crashes.
 *
 * The report is saved both to the public Downloads directory (best effort) and kept in
 * the cache so it can be shared through [app.lawnchair.bugreport.FileProvider].
 */
object LogExporter {
    private const val TAG = "LawnchairLogExporter"
    private const val MAX_LOGCAT_LINES = 6000
    private const val MAX_SECTION_BYTES = 1_500_000L

    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Builds the report off the main thread, saves it to Downloads and opens a share
     * chooser. [context] should be an [Activity] so the chooser can be launched; when it
     * is not, only the file export (Downloads) is performed.
     */
    fun exportAndShare(context: Context) {
        val appContext = context.applicationContext
        Thread {
            val result = runCatching { buildReport(appContext) }
            val file = result.getOrNull()
            val error = result.exceptionOrNull()
            if (file != null) {
                val downloadUri = runCatching { saveToDownloads(appContext, file) }.getOrNull()
                Log.i(TAG, "Report exported to ${file.absolutePath}, downloadUri=$downloadUri")
            }
            val activity = context as? Activity
            val uiMsg = if (file != null) {
                R.string.log_export_success
            } else {
                R.string.log_export_failed
            }
            appContext.mainHandler().post {
                Toast.makeText(appContext, uiMsg, Toast.LENGTH_LONG).show()
                if (file != null && activity != null) {
                    runCatching { activity.startActivity(createShareIntent(appContext, file)) }
                        .onFailure {
                            Toast.makeText(appContext, R.string.log_export_no_app, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            if (error != null) Log.e(TAG, "Export failed", error)
        }.also { it.isDaemon = true }.start()
    }

    private fun Context.mainHandler(): android.os.Handler =
        android.os.Handler(android.os.Looper.getMainLooper())

    private fun buildReport(context: Context): File {
        val dir = LogCollector.logsDir(context)
        val timestamp = fileFormat.format(Date())
        val report = File(dir, "lawnchair-logs-$timestamp.txt")
        val sb = StringBuilder()

        appendHeader(context, sb, timestamp)
        appendSection(sb, "SYSTEM LOGCAT SNAPSHOT", dumpLogcat())
        appendFileSection(sb, "LAUNCHER INTERNAL LOG", File(dir, "launcher-internal.log"))
        dir.listFiles { f -> f.name.startsWith("logcat-live") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { appendFileSection(sb, "LIVE LOGCAT (${it.name})", it) }
        val crashDir = File(dir, "crashes")
        crashDir.listFiles { f -> f.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { appendFileSection(sb, "CRASH (${it.name})", it) }

        report.writeText(sb.toString())
        return report
    }

    private fun appendHeader(context: Context, sb: StringBuilder, timestamp: String) {
        val pm = context.packageManager
        val pkg = context.packageName
        val versionName = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "?"
        @Suppress("DEPRECATION")
        val versionCode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkg, 0).longVersionCode.toString()
            } else {
                pm.getPackageInfo(pkg, 0).versionCode.toString()
            }
        }.getOrNull() ?: "?"
        val runtime = Runtime.getRuntime()

        sb.append("================================================================\n")
        sb.append(" Lawnchair Log Export\n")
        sb.append("================================================================\n")
        sb.append("Exported:            ").append(timestamp).append('\n')
        sb.append("Uptime:              ").append(SystemClock.elapsedRealtime() / 1000L).append("s\n")
        sb.append("\n[ Device ]\n")
        sb.append("Manufacturer:        ").append(Build.MANUFACTURER).append('\n')
        sb.append("Model:               ").append(Build.MODEL).append('\n')
        sb.append("Device:              ").append(Build.DEVICE).append('\n')
        sb.append("Product:             ").append(Build.PRODUCT).append('\n')
        sb.append("Hardware:            ").append(Build.HARDWARE).append('\n')
        sb.append("Android SDK:         ").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("Android Release:     ").append(Build.VERSION.RELEASE).append('\n')
        sb.append("Build:               ").append(Build.DISPLAY).append('\n')
        sb.append("\n[ App ]\n")
        sb.append("Package:             ").append(pkg).append('\n')
        sb.append("Version:             ").append(versionName).append(" (").append(versionCode).append(")\n")
        sb.append("Log collection:      ").append(if (LogCollector.isEnabled) "ENABLED" else "DISABLED").append('\n')
        sb.append("Default launcher:    ").append(currentDefaultLauncher(context)).append('\n')
        sb.append("\n[ Memory ]\n")
        sb.append("Max heap (MB):       ").append(runtime.maxMemory() / 1048576L).append('\n')
        sb.append("Total heap (MB):     ").append(runtime.totalMemory() / 1048576L).append('\n')
        sb.append("Free heap (MB):      ").append(runtime.freeMemory() / 1048576L).append('\n')
        sb.append("\n")
    }

    private fun currentDefaultLauncher(context: Context): String {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                }
                resolved?.activityInfo?.packageName ?: "unknown"
            } else {
                Settings.Secure.getString(context.contentResolver, "default_launcher_package_name") ?: "unknown"
            }
        }.getOrDefault("unknown")
    }

    private fun appendSection(sb: StringBuilder, title: String, content: String) {
        sb.append("----------------------------------------------------------------\n")
        sb.append("== ").append(title).append(" ==\n")
        sb.append("----------------------------------------------------------------\n")
        sb.append(content)
        if (!content.endsWith("\n")) sb.append('\n')
        sb.append('\n')
    }

    private fun appendFileSection(sb: StringBuilder, title: String, file: File) {
        if (!file.exists()) return
        val content = runCatching { readTail(file, MAX_SECTION_BYTES) }.getOrDefault("<unable to read ${file.name}>\n")
        appendSection(sb, "$title (${file.length()} bytes)", content)
    }

    private fun readTail(file: File, maxBytes: Long): String {
        val lines = file.inputStream().bufferedReader().use { it.readLines() }
        var bytes = 0L
        val out = ArrayDeque<String>()
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            bytes += line.length + 1
            if (bytes > maxBytes) break
            out.addFirst(line)
        }
        return out.joinToString("\n") + "\n"
    }

    private fun dumpLogcat(): String {
        return runCatching {
            val process = ProcessBuilder("logcat", "-d", "-b", "all", "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val buffer = ArrayDeque<String>()
            while (true) {
                val line = reader.readLine() ?: break
                buffer.addLast(line)
                while (buffer.size > MAX_LOGCAT_LINES) buffer.removeFirst()
            }
            process.waitFor()
            buffer.joinToString("\n") + "\n"
        }.getOrElse {
            "<logcat dump failed: ${Log.getStackTraceString(it)}>\n"
        }
    }

    private fun saveToDownloads(context: Context, source: File): Uri? {
        val name = source.name
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: return null
            uri
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists()) downloads.mkdirs()
            val dest = File(downloads, name)
            source.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
        }
    }

    private fun createShareIntent(context: Context, file: File): Intent {
        val uri = LawnchairApp.getUriForFile(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Lawnchair logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.export_logs_label))
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return chooser
    }
}

package com.android.launcher3;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// ============ v104-Java: request plumbing for the local HTTP / MCP channels ============
//
// The seven pure helpers - read the socket, take the request apart, turn a query into
// intent extras, write the answer back - live in AdbControlHttpText; each method below
// delegates so every caller still resolves to "AdbControlHttp.<name>" with no churn.
//
// runOnMain is the method that needs a launcher class (it posts the AdbControlMainTask
// Runnable on the main looper). The listener and its lifecycle live in
// AdbControlHttpServer, the JSON-RPC dialect in AdbControlMcp.
public class AdbControlHttp {

    // Read up to the blank line that ends the request headers (at most 16 KiB).
    public static String readHeaders(InputStream in) {
        return AdbControlHttpText.readHeaders(in);
    }

    // Read the body: at most len more bytes, returned as text.
    public static String readBody(InputStream in, int len) {
        return AdbControlHttpText.readBody(in, len);
    }

    // Content-Length of a request text, 0 when absent or unparsable.
    public static int contentLength(String headers) {
        return AdbControlHttpText.contentLength(headers);
    }

    // The "*" of "GET /cmd?cmd=list_icons HTTP/1.1", "" when the line is malformed.
    public static String requestTarget(String request) {
        return AdbControlHttpText.requestTarget(request);
    }

    // One URL query parameter, decoded, or null when the key is not there.
    public static String param(String query, String key) {
        return AdbControlHttpText.param(query, key);
    }

    // Query string -> the extras handleCommand() reads.
    public static void applyQuery(Intent intent, String query) {
        AdbControlHttpText.applyQuery(intent, query);
    }

    // Run one command on the launcher main thread and wait for it, like the v108
    // provider does: the workspace writes belong there, the socket must not touch them.
    public static boolean runOnMain(Context context, Intent intent, String prefKey, String channel) {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AdbControlMainTask task = new AdbControlMainTask(context, intent, prefKey, channel, latch);
            new Handler(Looper.getMainLooper()).post(task);
            latch.await(60000L, TimeUnit.MILLISECONDS);
            return task.isHandled();
        } catch (Throwable t) {
            return false;
        }
    }

    // Write the whole response: status line, two headers, the body, then close.
    public static void respond(OutputStream out, int code, String reason, String contentType,
                               String body) {
        AdbControlHttpText.respond(out, code, reason, contentType, body);
    }
}

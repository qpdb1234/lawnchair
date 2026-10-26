package com.android.launcher3;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

// ============ v110: AI entry point #7 - local HTTP server (REST) ============
//
// A loopback-only HTTP listener inside the launcher process, so a caller that has no adb at
// all (Termux, Tasker, another app, a local MCP bridge) can drive the same command set with
// plain text requests, and a PC can join in through   adb forward tcp:9091 tcp:9091
//
//   GET /help                    what is available
//   GET /list                    the rows on the desktop pages only, inline
//   GET /state?pkg=com.foo       where that package sits, in any container
//   GET /cmd?cmd=list_icons      the command dialect of the other channels
//   GET /cmd?cmd=move_icon&package_name=com.foo&target_cellX=0&target_cellY=1
//   POST /mcp                    JSON-RPC, see AdbControlMcp
//
// Idle cost while enabled is one thread parked in accept(): no timer, no polling, no wakeups,
// so the CPU stays flat between requests. While the switch is off nothing is bound at all, so
// the cost is exactly zero - the listener is created by the settings screen when the switch
// turns on, and revived the next time the settings screen binds that switch (which is also how
// it comes back after a reboot of the phone).
//
// Bound to 127.0.0.1 on purpose: nothing is reachable from the network, only from the device
// itself. Both switches are checked on every request, so the REST and the MCP dialect can be
// enabled and disabled independently and take effect immediately.
//
// /cmd runs the mutation on the launcher main thread through the same AdbControlMainTask the
// v108 provider uses, and answers with the position the database reports afterwards - so what
// an AI gets back is verified against the workspace, not just "the command was dispatched".
//
// run() is the accept loop, so this implements Runnable. Without it the thread dies on its
// first instruction with IncompatibleClassChangeError and takes the launcher process down.
public class AdbControlHttpServer implements Runnable {

    private static volatile ServerSocket sServer;
    private static volatile Thread sThread;

    private final Context mContext;

    private AdbControlHttpServer(Context context) {
        mContext = context.getApplicationContext();
    }

    // Bind 127.0.0.1:9091 and hand the accept loop to its own thread.
    private static void tryStart(Context context) {
        try {
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            ServerSocket server = new ServerSocket(9091, 4, addr);
            sServer = server;
            AdbControlHttpServer httpServer = new AdbControlHttpServer(context);
            Thread thread = new Thread(httpServer, "LauncherAiHttp");
            sThread = thread;
            thread.start();
            Log.i("AdbControl", "http server listening on 127.0.0.1:9091");
        } catch (Throwable t) {
            Log.e("AdbControl", t.toString());
        }
    }

    // The one page "GET /help" (and a bare "GET /") answers.
    //
    // Every sentence here is a promise about a query that exists elsewhere: /list is
    // AdbControlQuery.listText over container=-100, /state is stateOf over every container,
    // and the method list is the switch board of AdbControlMcp.handle(). Saying fewer rows or
    // fewer methods than those really cover is what the v119 bench cases (h-help-*) catch.
    private static String usage() {
        return "Lawnchair launcher control API\n\nGET /help\nGET /list                  the rows"
                + " on the desktop pages only (id, title, intent, container, screen, x, y, type)"
                + "\nGET /state?pkg=com.foo    where that package sits, page, hotseat or folder"
                + "\nGET /cmd?cmd=list_icons"
                + "\nGET /cmd?cmd=move_icon&package_name=com.foo&target_cellX=0&target_cellY=1&screen=0"
                + "\nGET /cmd?cmd=add_icon&package_name=com.foo&target_cellX=0&target_cellY=1"
                + "\nGET /cmd?cmd=remove_icon&package_name=com.foo"
                + "\nGET /cmd?cmd=reload              make the launcher re-read the desktop from"
                + " its database\nPOST /mcp                  JSON-RPC (initialize, ping,"
                + " tools/list, tools/call, notifications/*)\n\ncmd, package_name, target_cellX,"
                + " target_cellY and screen are the same parameters\nthe adb broadcast / activity"
                + " / service / provider channels take. Only reload and\nlist_icons take no"
                + " parameter at all. The two reads cover different rows:\nan icon in the hotseat"
                + " or inside a folder is listed by /state and not by /list, and the\nthree write"
                + " commands see the desktop pages only - such an icon is answered as not\nfound"
                + " on workspace. /state names the container its row sits in. A folder row"
                + " is\nitself listed with a null title and intent, because a folder has no"
                + " component of\nits own. package_name is matched\nliterally - a % or _ in it is"
                + " plain text - and it has to name one package; a cell\noutside the current grid"
                + " or one that another icon already holds is refused rather\nthan written: 409"
                + " here, isError for\nMCP. Removals and moves of a package that is not on the"
                + " desktop are answered as ok:\nthe desktop already looks the way you asked. One"
                + " package can sit on two cells at\nonce; then remove_icon takes every one of"
                + " them\nand move_icon moves the first it finds. Mutations run on the launcher"
                + " main\nthread, so the reply already reflects the new workspace, and /cmd echoes"
                + " back where\nthe package ended up. Only 127.0.0.1 is served; from a PC\nuse: adb"
                + " forward tcp:9091 tcp:9091\n";
    }

    // Accept until the listening socket goes away. One request per connection, so a broken or
    // slow client costs that connection only - and run() always closes it.
    @Override
    public void run() {
        try {
            while (true) {
                ServerSocket server = sServer;
                if (server == null || server.isClosed()) {
                    break;
                }
                Socket socket = server.accept();
                try {
                    route(mContext, socket);
                } catch (Throwable t) {
                    // an answer that never arrives is impossible to debug from the outside, so
                    // the reason why this one connection was dropped does go into the log.
                    Log.e("AdbControl", t.toString());
                }
                try {
                    socket.close();
                } catch (Throwable t) {
                    // ignore: the loop condition decides when to stop
                }
            }
        } catch (Throwable t) {
            Log.i("AdbControl", t.toString());
        }
    }

    // Start or stop the listener so that it matches the two switches. Called from the settings
    // screen only, never from a background thread.
    public static synchronized void syncFromPrefs(Context context) {
        boolean want = false;
        if (AdbControlQuery.gateOn(context, "pref_enable_adb_http")) {
            want = true;
        } else if (AdbControlQuery.gateOn(context, "pref_enable_adb_mcp")) {
            want = true;
        }
        boolean alive = false;
        Thread thread = sThread;
        if (thread != null) {
            alive = thread.isAlive();
        }
        if (!want) {
            if (alive) {
                stop();
            }
            return;
        }
        if (alive) {
            return;
        }
        tryStart(context);
    }

    // Closing the listening socket makes accept() throw, which ends run().
    public static synchronized void stop() {
        try {
            ServerSocket server = sServer;
            if (server != null) {
                server.close();
            }
        } catch (Throwable t) {
            // fall through to the cleanup below regardless
        }
        sServer = null;
        Thread thread = sThread;
        if (thread != null) {
            thread.interrupt();
        }
        sThread = null;
        Log.i("AdbControl", "http server stopped");
    }

    // One connection: read the request, answer it. Everything below runs on the listener
    // thread; run() catches whatever this method throws and closes the socket.
    private static void route(Context context, Socket socket) throws Exception {
        socket.setSoTimeout(20000);
        InputStream in = socket.getInputStream();
        String headers = AdbControlHttp.readHeaders(in);
        String body = AdbControlHttp.readBody(in, AdbControlHttp.contentLength(headers));
        String target = AdbControlHttp.requestTarget(headers);

        // "GET /cmd?a=b HTTP/1.1" -> path, query
        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            query = target.substring(q + 1);
            path = target.substring(0, q);
        }

        OutputStream out = socket.getOutputStream();

        // 404 unless a handler below says otherwise
        int code = 404;
        String reason = "Not Found";
        String contentType = "text/plain";
        String response = "unknown path, see /help";

        // ---- POST /mcp: the JSON-RPC dialect, on its own switch ----
        if ("/mcp".equals(path)) {
            if (AdbControlQuery.gateOn(context, "pref_enable_adb_mcp")) {
                response = AdbControlMcp.handle(context, body);
                if (response != null) {
                    code = 200;
                    reason = "OK";
                    contentType = "application/json";
                } else {
                    response = "";
                    code = 202;
                    reason = "Accepted";
                    contentType = "application/json";
                }
            } else {
                code = 403;
                reason = "Forbidden";
                contentType = "text/plain";
                response = "switch off: enable the launcher MCP setting first";
            }
            AdbControlHttp.respond(out, code, reason, contentType, response);
            return;
        }

        // ---- /help and / : the usage page, no switch needed ----
        if ("/help".equals(path) || "/".equals(path)) {
            response = usage();
            code = 200;
            reason = "OK";
            contentType = "text/plain";
            AdbControlHttp.respond(out, code, reason, contentType, response);
            return;
        }

        // ---- the REST dialect: one gate for all of it ----
        if (!AdbControlQuery.gateOn(context, "pref_enable_adb_http")) {
            code = 403;
            reason = "Forbidden";
            contentType = "text/plain";
            response = "switch off: enable the launcher HTTP setting first";
            AdbControlHttp.respond(out, code, reason, contentType, response);
            return;
        }

        if ("/list".equals(path)) {
            response = AdbControlQuery.listText(context);
            code = 200;
            reason = "OK";
            contentType = "text/plain";
            AdbControlHttp.respond(out, code, reason, contentType, response);
            return;
        }

        if ("/state".equals(path)) {
            String pkg = AdbControlHttp.param(query, "pkg");
            if (pkg == null) {
                pkg = AdbControlHttp.param(query, "package_name");
            }
            // an empty pkg= names no package, and the pattern built from it would match every
            // row on the desktop - answer 400 the same way a missing pkg= is answered.
            pkg = AdbControlText.namedPkg(pkg);
            if (pkg != null) {
                response = AdbControlQuery.stateOf(context, pkg);
                code = 200;
                reason = "OK";
                contentType = "text/plain";
            } else {
                code = 400;
                reason = "Bad Request";
                contentType = "text/plain";
                response = "usage: /state?pkg=com.example.app";
            }
            AdbControlHttp.respond(out, code, reason, contentType, response);
            return;
        }

        if ("/cmd".equals(path)) {
            Intent intent = new Intent();
            AdbControlHttp.applyQuery(intent, query);
            String cmd = AdbControlHttp.param(query, "cmd");
            // a read is answered from the database right here, no main thread needed. The name
            // is matched case insensitively because the runner below folds its own command name:
            // an uppercase spelling that only differs in case has to take the same branch, or it
            // would be carried out by the runner and come back as a bare "ok" with no icons in it.
            if ("list_icons".equalsIgnoreCase(cmd)) {
                response = AdbControlQuery.listText(context);
                code = 200;
                reason = "OK";
                contentType = "text/plain";
                AdbControlHttp.respond(out, code, reason, contentType, response);
                return;
            }
            boolean handled = AdbControlHttp.runOnMain(context, intent, "pref_enable_adb_http",
                    "http");
            StringBuilder sb = new StringBuilder();
            if (handled) {
                sb.append("ok");
                code = 200;
                reason = "OK";
            } else {
                sb.append("not handled: no cmd, an unknown cmd, a parameter the launcher refuses"
                        + " (blank package_name, a cell off the grid, a cell already taken, a"
                        + " package with no launch intent), a row the library would not take (an"
                        + " id already in use after an edit made outside the launcher) or the main"
                        + " thread did not answer within 60s");
                code = 409;
                reason = "Conflict";
            }
            String echoPkg = AdbControlText.namedPkg(AdbControlHttp.param(query, "package_name"));
            if (echoPkg != null) {
                sb.append('\n');
                sb.append(AdbControlQuery.stateOf(context, echoPkg));
            }
            response = sb.toString();
            contentType = "text/plain";
            AdbControlHttp.respond(out, code, reason, contentType, response);
            return;
        }

        // none of /mcp /help / /list /state /cmd: the 404 preset above stands.
        AdbControlHttp.respond(out, code, reason, contentType, response);
    }
}

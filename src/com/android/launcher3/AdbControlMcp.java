package com.android.launcher3;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

// ============ v110: AI entry point #8 - MCP (JSON-RPC 2.0) over the local server ====
//
// Model Context Protocol server side of the v110 listener: an AI client that already speaks
// MCP (Claude Desktop, any MCP SDK, an agent framework) can attach to the launcher and drive
// the workspace with tool calls instead of shell commands - over "adb forward tcp:9091
// tcp:9091" or from an on-device bridge.
//
//   curl -X POST http://127.0.0.1:9091/mcp -d '{"jsonrpc":"2.0","id":1,
//        "method":"tools/call","params":{"name":"move_icon",
//        "arguments":{"package_name":"com.foo","target_cellX":0,"target_cellY":1}}}'
//
// What is implemented is the part a tool-calling client exercises: initialize, ping,
// tools/list, tools/call and notifications/* with no id (answered with 202 and no body; the
// same name carrying an id is a request and gets an answer). Server-side streaming, sampling
// and resources are not: the launcher exposes tools, nothing more, so there is no session
// state, no polling and no extra thread - every request rides the one accept() loop of
// AdbControlHttpServer and is answered synchronously.
//
// Gate: the "pref_enable_adb_mcp" switch, checked per request, so the plain REST dialect and
// MCP can be turned on independently. The tool set is the v107 command set; mutations go
// through AdbControlHttp.runOnMain() and are verified against the favorites table before the
// answer is built.
//
// The pure JSON assembly (envelope / errorObj / toolsListResult / toolResult / applyToolArgs)
// lives in AdbControlMcpJson; only the launcher-coupled flow (the tools/call branch needing a
// Context, and the read/listText + mutation/runOnMain inside callTool) stays here.
public class AdbControlMcp {

    // One tool call -> the MCP "result" object: a single text content entry plus the isError
    // flag, which is what a client shows its model.
    private static JSONObject callMethod(Context context, JSONObject request) {
        JSONObject result = null;
        try {
            JSONObject params = request.optJSONObject("params");
            String name = null;
            JSONObject args = null;
            if (params != null) {
                name = params.optString("name", "");
                args = params.optJSONObject("arguments");
            }
            String text = callTool(context, name, args);
            // the content + isError assembly lives in AdbControlMcpJson.toolResult; a put that
            // could fail propagates to the catchall below exactly as the inline code did.
            result = AdbControlMcpJson.toolResult(text);
        } catch (Throwable t) {
            // a failure of the JSON plumbing itself is still reported as a tool result, so that
            // the client sees why rather than a broken connection.
            try {
                return new JSONObject("{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"error: the launcher could not carry out the call\"}],"
                        + "\"isError\":true}");
            } catch (Exception e) {
                return new JSONObject();
            }
        }
        return result;
    }

    // A tool name and its arguments -> plain text, "error: .." when it did not work.
    private static String callTool(Context context, String name, JSONObject args) {
        if (name == null) {
            return "error: no tool name given";
        }
        // matched ignoring case, the way the REST server matches its own read: a spelling that
        // only differs in case used to drop through to the branch below, which handed the name
        // to the runner - and the runner folds it, listed the icons into logcat and answered
        // true, so the client got a bare "ok" where the list should have been. The writes are
        // unaffected either way: they take that same branch on purpose, since the runner is
        // what performs them.
        if ("list_icons".equalsIgnoreCase(name)) {
            return AdbControlQuery.listText(context);
        }
        Intent intent = new Intent();
        intent.putExtra("cmd", name);
        // applyToolArgs returns the trimmed package or null; null also leaves the state echo
        // below skipped, exactly as the inline smali set the package to 0 when no usable name
        // came with the call.
        String pkg = null;
        if (args != null) {
            pkg = AdbControlMcpJson.applyToolArgs(intent, args);
        }
        boolean ok = AdbControlHttp.runOnMain(context, intent, "pref_enable_adb_mcp", "mcp");
        if (ok) {
            StringBuilder sb = new StringBuilder();
            sb.append("ok\n");
            if (pkg != null) {
                sb.append('\n');
                sb.append(AdbControlQuery.stateOf(context, pkg));
            }
            return sb.toString();
        }
        // v121: the list of refusals has to name the one the v121 fix introduced as well. A
        // write can be refused by the library itself - the row id the launcher's in-memory
        // counter handed out was already taken, so the insert wrote nothing. Before v121 that
        // case answered isError:false with no row in the database; now it is refused here and
        // said plainly.
        return "error: the launcher refused the call - unknown tool, a parameter it will not"
                + " take (no package_name, a cell off the workspace grid, a cell another icon"
                + " already holds, a package with no launch intent), a row the library would"
                + " not take (an id already in use after an edit made outside the launcher) or"
                + " the main thread not answering within 60s. logcat -s AdbControl says which.";
    }

    // One request text -> one response text, or null when the client only informed us
    // (notifications/* with no id), which the listener then answers with 202 and no body. An
    // id is a request for an answer, so it always gets one, even when the method name is one
    // that only informs.
    public static String handle(Context context, String request) {
        try {
            String text = (request != null) ? request : "{}";
            JSONObject req = new JSONObject(text);
            String method = req.optString("method", "");
            Object id = req.opt("id");

            // ---- initialize ----
            if ("initialize".equals(method)) {
                JSONObject result = new JSONObject("{\"protocolVersion\":\"2024-11-05\","
                        + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":"
                        + "\"lawnchair-launcher\",\"version\":\"1.10\"}}");
                return AdbControlMcpJson.envelope(id, result, null);
            }
            // ---- ping ----
            if ("ping".equals(method)) {
                JSONObject result = new JSONObject();
                return AdbControlMcpJson.envelope(id, result, null);
            }
            // ---- tools/list ----
            if ("tools/list".equals(method)) {
                JSONObject result = AdbControlMcpJson.toolsListResult();
                return AdbControlMcpJson.envelope(id, result, null);
            }
            // ---- tools/call ----
            if ("tools/call".equals(method)) {
                JSONObject result = callMethod(context, req);
                return AdbControlMcpJson.envelope(id, result, null);
            }
            // ---- notifications/*: a bare notification is acknowledged with an empty answer.
            //      Only a bare one: a message that carries an id is not a notification but a
            //      request, and a request answered with nothing leaves that id unanswered - the
            //      caller cannot tell "you informed me, there is no reply" apart from "this port
            //      is dead", and can only wait for its own timeout. So an id sends the same
            //      method name to the branch below, which answers it as what it then is: a
            //      method this server cannot be asked about, named back together with the id it
            //      was given.
            if (method.startsWith("notifications/") && id == null) {
                return null;
            }
            // ---- anything else is not a method this server knows ----
            JSONObject error = AdbControlMcpJson.errorObj(-32601, "method not found: " + method);
            return AdbControlMcpJson.envelope(id, null, error);
        } catch (Throwable t) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":"
                    + "\"the launcher could not answer the request\"}}";
        }
    }
}

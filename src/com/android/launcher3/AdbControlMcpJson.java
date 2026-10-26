package com.android.launcher3;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * v104-Java: the pure JSON-RPC response assembly of the MCP channel (entry point #8),
 * moved out of hand-written smali in AdbControlMcp into ordinary Java. Everything here
 * only touches org.json (part of the platform jar) - no launcher class, no Context - so
 * it compiles in mod-src and reaches the app through the javac/d8/baksmali pipeline.
 *
 * AdbControlMcp keeps the launcher-coupled methods (callTool needs the main-thread run
 * plus listText/stateOf; handle needs Context for the tools/call branch) and the JSON
 * parsing/dispatch flow around them, delegating just these text-building pieces so the
 * wire bytes the regression bench asserts stay identical:
 *
 *   envelope       - the {"jsonrpc":"2.0","id":..,"result"/"error":..} wrapper.
 *   errorObj       - the {"code":..,"message":".."} error body.
 *   toolsListResult- the tool schema (the v107 command set + reload), spelled out once.
 *   toolResult     - a tool's text -> the {"content":[{"type":"text","text":..}],
 *                    "isError":..} result the client shows its model.
 *
 * The throws-JSONException signatures mirror the smali, which had no local try on these
 * puts: a failure propagates to the caller's try (handle's catchall answers -32603,
 * callMethod's catchall returns its fallback object) exactly as before.
 */
public final class AdbControlMcpJson {

    private AdbControlMcpJson() {
    }

    // The full tools/list schema as one text block, equivalent to building it object by
    // object but far shorter. A description is what a model chooses its arguments by, so
    // each sentence holds for the tool it labels: list_icons is the desktop pages only
    // (container=-100, not the hotseat, not folder children); remove_icon deletes every
    // desktop row of the package but leaves a hotseat/folder row of the same app alone.
    private static final String TOOLS_SCHEMA =
            "[{\"name\":\"list_icons\",\"description\":\"List the icons on the desktop"
            + " pages of the launcher with id, title, intent, container, screen, cellX, "
            + "cellY and itemType. An icon in the hotseat or inside a folder is not in "
            + "this list.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[]}},{\"name\":\"move_icon\",\"description\":\"Move an "
            + "existing workspace icon to a cell of the launcher desktop. When the "
            + "package sits on more than one cell, the first one found is moved.\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"package_name\":"
            + "{\"type\":\"string\",\"description\":\"Package of the icon to move.\"},"
            + "\"target_cellX\":{\"type\":\"integer\",\"description\":\"Column to move "
            + "it to.\"},\"target_cellY\":{\"type\":\"integer\",\"description\":\"Row to "
            + "move it to.\"},\"screen\":{\"type\":\"integer\",\"description\":\"Desktop "
            + "page, -1 for the current one.\"}},\"required\":[\"package_name\","
            + "\"target_cellX\",\"target_cellY\"]}},{\"name\":\"add_icon\","
            + "\"description\":\"Add an app icon to a cell the caller names - the "
            + "launcher does not pick a free cell for it.\",\"inputSchema\":{\"type\":"
            + "\"object\",\"properties\":{\"package_name\":{\"type\":\"string\","
            + "\"description\":\"Package whose launcher activity is added.\"},"
            + "\"target_cellX\":{\"type\":\"integer\",\"description\":\"Column of that "
            + "cell, 0 based. Required, like the row: omitting it or sending -1 is "
            + "refused, not guessed.\"},\"target_cellY\":{\"type\":\"integer\","
            + "\"description\":\"Row of that cell, 0 based. Required.\"},"
            + "\"screen\":{\"type\":\"integer\",\"description\":\"Desktop page, 0 for "
            + "the first one. An add has no page it sits on already, so a page it does "
            + "not name is the first page.\"}},\"required\":[\"package_name\","
            + "\"target_cellX\",\"target_cellY\"]}},{\"name\":\"remove_icon\","
            + "\"description\":\"Remove every icon of that app from the desktop pages; "
            + "an app placed on two cells loses both. A hotseat or folder icon of the "
            + "same app is left alone.\",\"inputSchema\":{\"type\":\"object\","
            + "\"properties\":{\"package_name\":{\"type\":\"string\",\"description\":"
            + "\"Package whose desktop icons are removed.\"}},\"required\":["
            + "\"package_name\"]}},{\"name\":\"reload\",\"description\":\"Make the "
            + "launcher re-read the desktop from its database. Use this after the "
            + "favorites table was edited directly with root or Shizuku. Takes no "
            + "arguments.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[]}}]";

    /**
     * {"jsonrpc":"2.0","id":..,"result":..} or {...,"error":..}. One of result/error is
     * given, the other stays null and is then simply left out of the object.
     */
    public static String envelope(Object id, JSONObject result, JSONObject error)
            throws JSONException {
        JSONObject o = new JSONObject();
        o.put("jsonrpc", "2.0");
        if (id != null) {
            o.put("id", id);
        }
        if (error != null) {
            o.put("error", error);
        } else {
            o.put("result", result);
        }
        return o.toString();
    }

    /** {"code":..,"message":".."}. */
    public static JSONObject errorObj(int code, String message) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("code", code);
        o.put("message", message);
        return o;
    }

    /**
     * The tools a client may call. An unreadable schema must not take the connection
     * down, so the whole build is wrapped and, on any failure, answers the correct
     * envelope with an empty tool list instead - exactly as the smali catchall did.
     */
    public static JSONObject toolsListResult() {
        try {
            JSONArray tools = new JSONArray(TOOLS_SCHEMA);
            JSONObject o = new JSONObject();
            o.put("tools", tools);
            return o;
        } catch (Exception e) {
            try {
                JSONObject o = new JSONObject();
                o.put("tools", new JSONArray());
                return o;
            } catch (Exception e2) {
                return new JSONObject();
            }
        }
    }

    /**
     * One tool's plain-text answer -> the MCP result object: a single text content entry
     * plus the isError flag (a text beginning with "error" is what a client shows its
     * model as a failure). Key order (content, then isError) matches the smali.
     */
    public static JSONObject toolResult(String text) throws JSONException {
        boolean isError = text.startsWith("error");
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", text);
        content.put(item);
        result.put("content", content);
        result.put("isError", isError);
        return result;
    }

    /**
     * Map a tools/call "arguments" object onto the extras handleCommand() reads: the
     * trimmed package name (null when the caller gave none - the same null the smali
     * handed to the state-echo guard, so it is skipped too) and the three cell
     * coordinates, each defaulting to -1. The "cmd" extra is set by the caller from
     * the tool name. Intent + org.json + namedPkg only - no launcher class - so this
     * compiles in mod-src. Returns the trimmed package, or null.
     */
    public static String applyToolArgs(Intent intent, JSONObject args) {
        // v114 guard shared with the executor and the REST server: isEmpty() alone let
        // "   " through as a name, and an untrimmed echo made " pkg " answer ok next to
        // "absent:  pkg  has no workspace item". namedPkg trims a real name and returns
        // null for none, so the extra stays unset and the state echo is skipped.
        String pkg = AdbControlText.namedPkg(args.optString("package_name", ""));
        if (pkg != null) {
            intent.putExtra("package_name", pkg);
        }
        intent.putExtra("target_cellX", args.optInt("target_cellX", -1));
        intent.putExtra("target_cellY", args.optInt("target_cellY", -1));
        intent.putExtra("screen", args.optInt("screen", -1));
        return pkg;
    }
}

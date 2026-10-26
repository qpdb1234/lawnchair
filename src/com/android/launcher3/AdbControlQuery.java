package com.android.launcher3;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import com.android.launcher3.model.ModelDbController;

// ============ v110: read side of the local HTTP / MCP channels ============
//
// The v107/v108 channels answer through logcat, which is fine over adb but useless to a
// caller that has no adb - exactly the caller the HTTP and MCP channels exist for. This
// class runs the same favorites queries and returns the text instead of logging it, so a
// socket response can carry real data.
//
// It is deliberately read-only: the workspace writes stay in AdbControlReceiver
// .handleCommand(), which remains the single implementation of the command set. Reading
// needs no main thread (plain SQLite reads through ModelDbController), so the socket
// worker answers these itself.
public class AdbControlQuery {

    // The per-channel switch, read straight from the file the settings UI writes.
    public static boolean gateOn(Context context, String key) {
        try {
            SharedPreferences sp =
                    context.getSharedPreferences("com.android.launcher3.prefs", 0);
            return sp.getBoolean(key, false);
        } catch (Throwable t) {
            return false;
        }
    }

    // Shared scanner: one favorites query, every row appended one per line.
    // selection null -> the desktop pages only, container=-100.
    private static String scan(Context context, String selection, String[] selectionArgs) {
        StringBuilder sb = new StringBuilder();
        Cursor cursor = null;
        try {
            ModelDbController controller = LauncherAppState.getInstance(context)
                    .getModel().getModelDbController();
            String[] projection = new String[] {
                    "_id", "title", "intent", "container", "screen", "cellX", "cellY", "itemType"
            };
            String sel = selection;
            String[] args = selectionArgs;
            if (sel == null) {
                sel = "container=-100";
                args = null;
            }
            cursor = controller.query("favorites", projection, sel, args, "_id");
            while (cursor != null && cursor.moveToNext()) {
                AdbControlText.row(sb, cursor);
                sb.append('\n');
            }
            if (cursor != null) {
                cursor.close();
            }
        } catch (Throwable t) {
            if (cursor != null) {
                cursor.close();
            }
            sb.append("query failed: ").append(t.toString());
        }
        return sb.toString();
    }

    // The icons on the desktop pages, container=-100 - the answer for "list_icons" and for
    // GET /list. An icon in the hotseat (-101) or inside a folder is not returned here;
    // stateOf below sees those rows, which is why the two reads cover different rows.
    public static String listText(Context context) {
        return scan(context, null, null);
    }

    // Where the package sits now, in whatever container that is - a page, the hotseat or a
    // folder - so a caller can verify that a mutation took effect.
    public static String stateOf(Context context, String pkg) {
        // v113: the caller's text goes through likePattern, so "%" and "_" in a package name
        // are plain text here too - the same rule the mutations use.
        String pattern = AdbControlText.likePattern(pkg);
        String[] args = new String[] { pattern };
        String result = scan(context, "intent LIKE ? ESCAPE '\\'", args);
        if (result.isEmpty()) {
            return AdbControlText.absentText(pkg);
        }
        return result;
    }
}

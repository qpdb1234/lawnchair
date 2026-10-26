package com.android.launcher3;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.util.Log;

import com.android.launcher3.model.ModelDbController;

// ============ v107: ADB control interface (AI tooling entry point) ============
//
// Lets an external controller (MCP server / script / human at a shell) drive the workspace
// layout through "adb shell am broadcast".
//
// The launcher's own LauncherProvider refuses ContentResolver calls from its own process
// ("Same process should call model directly"), so this receiver does exactly what that
// message asks: it writes the favorites table through LauncherModel.getModelDbController()
// and then calls model.forceReload() on the main thread (onReceive already runs on main,
// same as the provider's MAIN_EXECUTOR.submit(model::forceReload)).
//
// Security gates (both must pass):
//   1. the receiver is protected by android:permission=DUMP in the manifest, which only adb
//      shell / root hold - third-party apps cannot send it;
//   2. a runtime switch "pref_enable_adb_control", default OFF, shown in the launcher
//      settings screen. It lives in the same prefs file the settings UI writes
//      ("com.android.launcher3.prefs").
//
// Commands (string extra "cmd", or the last segment of the action, e.g.
// com.android.launcher3.adb.MOVE_ICON):
//   list_icons
//   move_icon    --es package_name X --ei target_cellX N --ei target_cellY N [--ei screen N]
//   add_icon     --es package_name X --ei target_cellX N --ei target_cellY N [--ei screen N]
//   remove_icon  --es package_name X
//   reload       (v111, param-free)
//
// Results go to logcat tag "AdbControl" (adb logcat -s AdbControl).
//
// This is the source-tree port of the hand-written smali executor. The pure text helpers
// (namedPkg / likePattern / cmdFrom / row / absentText) live in AdbControlText; the reads the
// HTTP / MCP channels share live in AdbControlQuery. In the source tree LauncherModel /
// ModelDbController / InvariantDeviceProfile are directly importable, so the executor is
// ordinary Java. Every branch and log string is kept verbatim from the smali (including the
// "wrote nothing must not report success" retry added in v121/B20).
public class AdbControlReceiver extends BroadcastReceiver {

    private static final String TAG = "AdbControl";

    // public since v111: AdbControlShortcuts needs the model to rebind the app list after it
    // enables or disables the desktop control icon.
    public static LauncherModel getModel(Context context) {
        return LauncherAppState.getInstance(context).getModel();
    }

    // The workspace grid is not unbounded: a row written outside it stays in the database, but
    // the model drops it while loading, which silently destroys the icon the caller asked to
    // move. Columns and rows come from the current device profile, the page count from the
    // pages that already exist plus the one new page a caller may be about to fill.
    private static boolean cellOnGrid(Context context, int cellX, int cellY, int screen) {
        InvariantDeviceProfile idp = LauncherAppState.getIDP(context);
        if (idp != null) {
            if (cellX >= idp.numColumns) {
                return false;
            }
            if (cellY >= idp.numRows) {
                return false;
            }
        }
        // no page named means "the page it is already on", which cannot be too far away
        if (screen < 0) {
            return true;
        }
        LauncherModel model = getModel(context);
        if (model == null) {
            return true;
        }
        int max = maxWorkspaceScreen(model.getModelDbController()) + 1;
        return screen <= max;
    }

    // Highest screen index in use on the workspace, -1 when the workspace is empty.
    private static int maxWorkspaceScreen(ModelDbController controller) {
        Cursor cursor = controller.query("favorites", new String[] { "screen" },
                "container=-100", null, "screen DESC");
        if (cursor == null) {
            return -1;
        }
        if (cursor.moveToNext()) {
            int screen = cursor.getInt(0);
            cursor.close();
            return screen;
        }
        cursor.close();
        return -1;
    }

    // Screen the given row currently sits on, so a move that does not name a page can still be
    // checked against the page it would stay on.
    private static int rowScreen(ModelDbController controller, String id) {
        Cursor cursor = controller.query("favorites", new String[] { "screen" },
                "_id=" + id, null, null);
        if (cursor == null) {
            return -1;
        }
        if (cursor.moveToNext()) {
            int screen = cursor.getInt(0);
            cursor.close();
            return screen;
        }
        cursor.close();
        return -1;
    }

    // _id of the workspace row sitting on one exact cell, null when the cell is free. Widgets
    // that span several cells are stored on their top-left one, so the check covers the cell
    // the caller names - which is what a single-span icon needs.
    private static String cellOccupant(ModelDbController controller, int screen, int cellX,
            int cellY) {
        String selection = "container=-100 AND screen=" + screen
                + " AND cellX=" + cellX + " AND cellY=" + cellY;
        Cursor cursor = controller.query("favorites", new String[] { "_id" },
                selection, null, null);
        if (cursor != null) {
            if (cursor.moveToNext()) {
                String id = String.valueOf(cursor.getInt(0));
                cursor.close();
                return id;
            }
            cursor.close();
        }
        return null;
    }

    // _id of the first workspace row whose intent component starts with pkg, or null when the
    // package is not placed on any workspace screen.
    private static String findWorkspaceId(ModelDbController controller, String pkg) {
        // v113: the caller's text is escaped, so it can only ever name one package.
        String pattern = AdbControlText.likePattern(pkg);
        Cursor cursor = controller.query("favorites", new String[] { "_id" },
                "container=-100 AND intent LIKE ? ESCAPE '\\'", new String[] { pattern }, null);
        if (cursor != null) {
            if (cursor.moveToNext()) {
                String id = String.valueOf(cursor.getInt(0));
                cursor.close();
                return id;
            }
            cursor.close();
        }
        return null;
    }

    private static void doList(Context context) {
        LauncherModel model = getModel(context);
        ModelDbController controller = model.getModelDbController();
        Cursor cursor = controller.query("favorites", new String[] {
                "_id", "title", "intent", "container", "screen", "cellX", "cellY", "itemType"
        }, "container=-100", null, "_id");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                // doList's logcat line omits the container field: this listing only ever
                // queries container=-100, so the value would repeat on every line. It is not
                // the same set of fields as AdbControlText.row (which keeps container for the
                // /state read), so the formatting stays here rather than delegating.
                StringBuilder sb = new StringBuilder();
                sb.append("ICON|id=").append(cursor.getInt(0));
                sb.append("|title=").append(cursor.getString(1));
                sb.append("|intent=").append(cursor.getString(2));
                sb.append("|screen=").append(cursor.getInt(4));
                sb.append("|x=").append(cursor.getInt(5));
                sb.append("|y=").append(cursor.getInt(6));
                sb.append("|type=").append(cursor.getInt(7));
                Log.i(TAG, sb.toString());
            }
            cursor.close();
        }
        Log.i(TAG, "list-done");
    }

    private static boolean doMove(Context context, String pkgRaw, int cellX, int cellY,
            int screen) {
        String pkg = AdbControlText.namedPkg(pkgRaw);
        if (pkg == null || cellX < 0 || cellY < 0) {
            Log.e(TAG, "move: need package_name + target_cellX + target_cellY");
            return false;
        }
        // v113: a cell outside the grid is written and then dropped by the model, which loses
        // the icon, so the request is refused before anything is touched.
        if (!cellOnGrid(context, cellX, cellY, screen)) {
            Log.e(TAG, "move: target cell is not on the grid");
            return false;
        }
        LauncherModel model = getModel(context);
        ModelDbController controller = model.getModelDbController();
        String id = findWorkspaceId(controller, pkg);
        if (id == null) {
            // "the icon is not on the workspace" counts as accepted - the wanted state is what
            // the caller asked for either way.
            Log.w(TAG, "move: not found on workspace");
            return true;
        }
        // v113: the cell has to be free, or hold this very row - two icons on one cell is how a
        // move quietly deletes one of them.
        // a move that names a page keeps that page; only when none was named is the page the
        // row actually sits on looked up, because that is the cell the icon would end up in.
        int scr = screen;
        if (scr < 0) {
            scr = rowScreen(controller, id);
        }
        String occupant = cellOccupant(controller, scr, cellX, cellY);
        if (occupant != null && !occupant.equals(id)) {
            Log.w(TAG, "move: target cell already taken");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("cellX", Integer.valueOf(cellX));
        cv.put("cellY", Integer.valueOf(cellY));
        if (screen >= 0) {
            cv.put("screen", Integer.valueOf(screen));
        }
        int rows = controller.update("favorites", cv, "_id=?", new String[] { id });
        if (rows > 0) {
            model.forceReload();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("moved ").append(pkg).append(" to (").append(cellX).append(",")
                .append(cellY).append(") rows=").append(rows);
        Log.i(TAG, sb.toString());
        return true;
    }

    private static boolean doAdd(Context context, String pkgRaw, int cellX, int cellY,
            int screen) {
        String pkg = AdbControlText.namedPkg(pkgRaw);
        if (pkg == null || cellX < 0 || cellY < 0) {
            Log.e(TAG, "add: need package_name + target_cellX + target_cellY");
            return false;
        }
        // the page an unnamed add lands on is the first one, and that is what gets checked
        if (screen < 0) {
            screen = 0;
        }
        if (!cellOnGrid(context, cellX, cellY, screen)) {
            Log.e(TAG, "add: target cell is not on the grid");
            return false;
        }
        PackageManager pm = context.getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
        if (launchIntent == null) {
            Log.w(TAG, "add: no launch intent for package");
            return false;
        }
        ApplicationInfo appInfo;
        String title;
        // Lawnchair note: the standard SDK declares getApplicationInfo as throwing the checked
        // NameNotFoundException (the AOSP platform build the mod came from did not force it).
        // launchIntent is already non-null so the package exists and this cannot realistically
        // throw; it is caught to satisfy javac and reported as a refusal like any other failure.
        try {
            appInfo = pm.getApplicationInfo(pkg, 0);
            title = pm.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "add: no application info for package");
            return false;
        }

        LauncherModel model = getModel(context);
        ModelDbController controller = model.getModelDbController();
        String existingId = findWorkspaceId(controller, pkg);
        if (existingId != null) {
            // "that icon is already there" counts as accepted.
            Log.w(TAG, "add: already on workspace");
            return true;
        }
        // v113: two icons on one cell - the model keeps one and the other disappears.
        String occupant = cellOccupant(controller, screen, cellX, cellY);
        if (occupant != null) {
            Log.w(TAG, "add: target cell already taken");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("intent", launchIntent.toUri(Intent.URI_ANDROID_APP_SCHEME));
        cv.put("container", Integer.valueOf(-100));
        cv.put("screen", Integer.valueOf(screen));
        cv.put("cellX", Integer.valueOf(cellX));
        cv.put("cellY", Integer.valueOf(cellY));
        Integer one = Integer.valueOf(1);
        cv.put("spanX", one);
        cv.put("spanY", one);
        cv.put("itemType", Integer.valueOf(0));

        // v121: the id of a new row comes from a counter the launcher keeps in memory and only
        // reads back from the library when the database is opened (DatabaseHelper.mMaxItemId).
        // A row written straight into the favorites table is handed the next id by SQLite
        // instead, so the counter ends up one behind an id that is already used and the next add
        // asks for that same id; the insert then fails on the primary key and returns -1, which
        // used to fail silently (B20). generateNewItemId adds to the counter before it answers,
        // so asking again walks past the collided rows. Five covers a bulk edit without making a
        // caller wait long. When even that runs out, the command says it wrote nothing instead
        // of claiming the icon is on the desktop - which the doors answer as a refusal.
        int rowId = 0;
        boolean inserted = false;
        for (int attempt = 0; attempt < 5; attempt++) {
            cv.put("_id", Integer.valueOf(controller.generateNewItemId()));
            rowId = controller.insert("favorites", cv);
            if (rowId > 0) {
                inserted = true;
                break;
            }
        }
        if (!inserted) {
            Log.e(TAG, "add: the database refused the row");
            return false;
        }
        // unchanged from before the retry loop existed: a row that landed re-reads the desktop
        model.forceReload();
        Log.i(TAG, "added " + pkg + " rowId=" + rowId);
        return true;
    }

    private static boolean doRemove(Context context, String pkgRaw) {
        String pkg = AdbControlText.namedPkg(pkgRaw);
        if (pkg == null) {
            Log.e(TAG, "remove: need package_name");
            return false;
        }
        LauncherModel model = getModel(context);
        ModelDbController controller = model.getModelDbController();
        String id = findWorkspaceId(controller, pkg);
        if (id == null) {
            // nothing to take away is a request that was carried out - it just did not write.
            Log.w(TAG, "remove: not found on workspace");
            return true;
        }
        // v120/B19: the id above is only an existence test. One package can own several
        // workspace rows - the user can drop the same app on two cells - and a delete of that
        // single _id answered "removed X rows=1" while the second icon stayed on the screen,
        // even though every reader walks the whole cursor and had shown both. So the write takes
        // the same set the read shows: every workspace row of that package, and nothing else.
        String pattern = AdbControlText.likePattern(pkg);
        // container=-100 is the hard boundary: without it this would also delete the dock row
        // and every row inside a folder that belongs to the same package.
        int rows = controller.delete("favorites",
                "container=-100 AND intent LIKE ? ESCAPE '\\'", new String[] { pattern });
        if (rows > 0) {
            model.forceReload();
        }
        Log.i(TAG, "removed " + pkg + " rows=" + rows);
        return true;
    }

    // v111: the param-free member of the command set. Everything else already reloads the
    // workspace after it wrote a row, so this is only useful on its own - it is what makes the
    // "edit launcher.db with sqlite3, no switch at all" recipe in the settings notes take
    // effect without also sending a move/add/remove, and it is the second static shortcut of
    // the desktop control icon.
    private static void doReload(Context context) {
        LauncherModel model = getModel(context);
        if (model == null) {
            Log.w(TAG, "reload: no model instance");
            return;
        }
        model.forceReload();
        Log.i(TAG, "workspace reloaded");
    }

    // The broadcast entry point kept for v107 compatibility: same command runner, own settings
    // switch "pref_enable_adb_control".
    @Override
    public void onReceive(Context context, Intent intent) {
        handleCommand(context, intent, "pref_enable_adb_control", "broadcast");
    }

    // ============ shared command runner for every AI entry point ============
    //
    // Used by AdbControlReceiver (broadcast), AdbControlActivity (no-UI activity),
    // AdbControlService (short-lived service), AdbControlProvider (content call),
    // AdbControlLinkActivity (deep link) and AdbControlShortcutActivity (v111 shortcut).
    // Commands: list_icons / move_icon / add_icon / remove_icon / reload (v111, param-free).
    // Every entry point passes its own settings switch so a channel can be enabled without
    // enabling the others; "channel" only shows up in logcat.
    //
    // Runs on the caller's main thread (onReceive / onCreate / onStartCommand are all
    // main-thread callbacks; the provider posts to main explicitly), which is exactly what
    // writing through ModelDbController + model.forceReload() wants.
    //
    // Returns true when the command was accepted and did what it could, false when it was
    // refused: the switch is off, there is no / an unknown cmd, no intent, or a mutation was
    // rejected (blank package_name, a cell off the grid, a cell that is already taken, a
    // package with no launch intent, a row the library would not take because an id the
    // launcher handed out was already in use - v121) or the runner threw. "the icon is not on
    // the workspace" and "that icon is already there" do count as accepted - the wanted state
    // is what the caller asked for either way. The http /cmd route and MCP tools/call forward
    // this, so a refusal answers 409 / isError instead of "ok". What this returns must match
    // what the database ended up holding: an add that wrote no row may not report success.
    public static boolean handleCommand(Context context, Intent intent, String prefKey,
            String channel) {
        try {
            if (intent == null) {
                Log.w(TAG, "no intent delivered");
                return false;
            }
            // gate: the per-channel switch (same prefs file the settings UI writes)
            SharedPreferences sp =
                    context.getSharedPreferences("com.android.launcher3.prefs", 0);
            if (!sp.getBoolean(prefKey, false)) {
                Log.w(TAG, "rejected[" + channel + "] switch " + prefKey
                        + " is off (enable it in launcher settings)");
                return false;
            }
            // cmd: the string extra "cmd", else the last dotted segment of the action, folded
            // to lower case with Locale.ROOT. Resolution + normalization live in
            // AdbControlText.cmdFrom; null means "no command given".
            String cmd = AdbControlText.cmdFrom(intent);
            if (cmd == null) {
                Log.w(TAG, "missing cmd");
                return false;
            }
            // audit trail: which channel asked for what
            Log.i(TAG, "[" + channel + "] cmd=" + cmd);

            String pkg = AdbControlText.namedPkg(intent.getStringExtra("package_name"));
            // "" and all-blank count as "no package given" for every mutation, so a missing
            // extra can never turn into "delete / move everything".
            int cellX = intent.getIntExtra("target_cellX", -1);
            int cellY = intent.getIntExtra("target_cellY", -1);
            int screen = intent.getIntExtra("screen", -1);

            if ("list_icons".equals(cmd)) {
                doList(context);
                return true;
            } else if ("move_icon".equals(cmd)) {
                return doMove(context, pkg, cellX, cellY, screen);
            } else if ("add_icon".equals(cmd)) {
                return doAdd(context, pkg, cellX, cellY, screen);
            } else if ("remove_icon".equals(cmd)) {
                return doRemove(context, pkg);
            } else if ("reload".equals(cmd)) {
                doReload(context);
                return true;
            }
            Log.w(TAG, "unknown cmd: " + cmd);
        } catch (Throwable t) {
            Log.e(TAG, "error: " + t);
        }
        // whatever reaches this point was not handled: unknown cmd, no cmd at all, no intent or
        // a throw. Say so to the caller rather than claiming success.
        return false;
    }
}

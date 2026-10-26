package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

// ============ v111: the component half of entry point #9 ============
//
// Entry point 9 hangs its commands on static (manifest) shortcuts, and the platform only
// parses those for a component that has an intent filter with MAIN + LAUNCHER. That component
// is the activity-alias "com.android.launcher3.LauncherControl", which is declared disabled in
// the manifest: while the switch is off it does not exist for the package manager, so no icon,
// no long-press menu, no shortcut data - the idle cost of a closed channel is not just zero
// CPU, it is zero footprint.
//
// syncFromPrefs() compares the switch with the enabled-state the package manager actually
// reports and does nothing when they already agree, so opening the screen repeatedly is free.
// A real change is followed by a model reload, because the app list the workspace binds is
// only rebuilt on a reload - the launcher does not listen to component state changes.
//
// Called from the settings screen whenever the switch for channel 9 is bound or moved. The
// state is re-asserted every time the settings screen is opened: no boot receiver, no service,
// no thread. An app may toggle its own components without any permission.
//
// Because nine rows share the hook, a real change is applied several times in a row:
// setComponentEnabledSetting() is handed to the package service, so the next read can still
// report the old state. Re-applying the same value is harmless, and it stops on its own as
// soon as the read catches up.
public class AdbControlShortcuts {

    // The workspace only learns about the alias appearing or going away when the model reads
    // the app list again.
    private static void reloadWorkspace(Context context) {
        LauncherModel model = AdbControlReceiver.getModel(context);
        if (model != null) {
            model.forceReload();
        }
    }

    // Make the control icon match the switch. Cheap and idempotent: it writes to the package
    // manager only when the two actually disagree.
    public static void syncFromPrefs(Context context) {
        try {
            SharedPreferences sp =
                    context.getSharedPreferences("com.android.launcher3.prefs", 0);
            boolean want = sp.getBoolean("pref_enable_adb_shortcut", false);
            // what follows reads how the icon looks to the platform.
            ComponentName alias = new ComponentName(context.getPackageName(),
                    "com.android.launcher3.LauncherControl");
            PackageManager pm = context.getPackageManager();
            int state = pm.getComponentEnabledSetting(alias);
            // COMPONENT_ENABLED_STATE_DEFAULT is 0, _ENABLED is 1, _DISABLED is 2 (and there is
            // a _DISABLED_USER = 3 as well). Comparing raw ints against one another would have
            // to know every answer the package service can give, so the comparison is made as
            // booleans: the icon is up if and only if the service says ENABLED, and every other
            // answer - DEFAULT, which for this alias means the manifest's "false", DISABLED,
            // DISABLED_USER - means it is down.
            boolean up = (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            if (up != want) {
                // ask for the state the switch wants. FLAG_DONT_KILL_APP - never restart the
                // desktop over a row.
                int newState = want ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                pm.setComponentEnabledSetting(alias, newState, PackageManager.DONT_KILL_APP);
                // the word comes from the switch, which is the thing that just got applied
                Log.i("AdbControl", "[shortcut] control icon " + (want ? "enabled" : "disabled"));
                reloadWorkspace(context);
            }
        } catch (Throwable t) {
            Log.e("AdbControl", t.toString());
        }
    }
}

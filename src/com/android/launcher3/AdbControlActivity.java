package com.android.launcher3;

import android.app.Activity;
import android.os.Bundle;

// ============ v108: AI entry point #2 - headless Activity (am start) ============
//
// Same command set as the v107 broadcast receiver, but reached through
//   adb shell am start -n <pkg>/com.android.launcher3.AdbControlActivity --es cmd list_icons
//
// Idle cost is exactly zero: an Activity is only instantiated when somebody starts it, so
// nothing runs while it is not called. onCreate() executes the command and finishes right
// away, which is also what the Theme.NoDisplay contract requires on Android 12+ (no window
// is ever drawn, noHistory + excludeFromRecents leave no trace in the task stack).
//
// Gates: android:permission="android.permission.DUMP" in the manifest (only adb shell / root
// hold it) plus the runtime switch "pref_enable_adb_activity". Commands are delegated to
// AdbControlReceiver.handleCommand() so there is only one implementation of the parsing +
// workspace writes.
public class AdbControlActivity extends Activity {

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        AdbControlReceiver.handleCommand(this, getIntent(), "pref_enable_adb_activity",
                "activity");
        finish();
    }
}

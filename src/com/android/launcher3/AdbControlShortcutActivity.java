package com.android.launcher3;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

// ============ v111: AI entry point #9 - static shortcut (remote action) ============
//
// The headless target of the shortcuts declared in res/xml/launcher_control_shortcuts.xml.
// The system hands us the intent it built from that XML, so the command travels in the intent
// action (com.android.launcher3.adb.LIST_ICONS -> "list_icons", which is exactly how
// handleCommand() already derives a command from a broadcast action): no parsing, no URL, no
// adb, no permission in front of the call.
//
// Idle cost: an Activity only exists while somebody starts it, so a switch that is off holds
// nothing at all - not even a component, because the control icon it hangs on is an
// activity-alias that AdbControlShortcuts keeps disabled while the switch is off.
//
// Read commands report to logcat only (adb logcat -s AdbControl); a shortcut is a one-way
// trigger, there is no result channel back to whoever tapped it.
public class AdbControlShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        try {
            // everything that touches the incoming intent, including finish(), stays inside one
            // try block.
            Intent intent = getIntent();
            if (intent != null) {
                // audit trail: which shortcut was fired (the alias is the only way in, and it
                // is invisible while the switch is off, so a stray log line here is worth having)
                Log.i("AdbControl", "[shortcut] action=" + intent.getAction());
                // the intent from the shortcut XML already carries action and extras, so it can
                // be handed to the shared runner unchanged - one implementation for every entry
                AdbControlReceiver.handleCommand(this, intent, "pref_enable_adb_shortcut",
                        "shortcut");
            }
            finish();
        } catch (Throwable t) {
            Log.e("AdbControl", t.toString());
            finish();
        }
    }
}

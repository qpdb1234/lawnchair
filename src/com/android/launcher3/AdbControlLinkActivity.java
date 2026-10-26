package com.android.launcher3;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

// ============ v110: AI entry point #5 - deep link (custom URL scheme) ============
//
// The same command set as the v107/v108 channels, reached through an ordinary VIEW intent, so
// the caller needs no adb and no DUMP permission:
//   adb shell am start -a android.intent.action.VIEW \
//        -d "launcher://control?cmd=move_icon&package_name=X&target_cellX=0&target_cellY=1"
// and, from any app on the device (Termux, Tasker, a browser page, a local MCP bridge), the
// same Uri through startActivity().
//
// Idle cost is exactly zero: an Activity only exists while somebody starts it, so nothing is
// registered, polled or kept resident. The command parameters have to travel in the URL query
// string because a deep link cannot attach extras.
//
// Security: this is deliberately the ONE entry point without a system permission in front of
// it - that is precisely what makes it usable without adb. While "pref_enable_adb_link" is
// on, any app on the device may rearrange the workspace through it, hence the switch defaults
// to off, says so in its summary and every call is logged with the calling package
// (logcat -s AdbControl).
//
// Commands are delegated to AdbControlReceiver.handleCommand(), like every other channel, so
// there is still only one implementation of parsing + workspace writes. Reads (list_icons)
// report to logcat only - a deep link has no result channel back to the caller; use the v110
// local HTTP channel for inline output.
public class AdbControlLinkActivity extends Activity {

    // Copy one URL query parameter into the intent the command runner reads.
    private static void putStr(Intent target, String key, String value) {
        if (value != null) {
            target.putExtra(key, value);
        }
    }

    // Same, for the numeric parameters, which handleCommand() reads with getIntExtra and
    // therefore have to arrive as ints. An unparsable value is skipped silently: the runner
    // then sees the -1 default, which is how "not given" already works for the other channels.
    private static void putNum(Intent target, String key, String value) {
        if (value == null) {
            return;
        }
        try {
            target.putExtra(key, Integer.parseInt(value));
        } catch (Exception e) {
            // ignored: the numeric getter falls back to -1
        }
    }

    // launcher://host/control?cmd=..&package_name=..&target_cellX=..&target_cellY=..&screen=..
    private static void applyParams(Intent target, Intent caller) {
        if (caller == null) {
            return;
        }
        Uri data = caller.getData();
        if (data == null) {
            return;
        }
        putStr(target, "cmd", data.getQueryParameter("cmd"));
        putStr(target, "package_name", data.getQueryParameter("package_name"));
        putNum(target, "target_cellX", data.getQueryParameter("target_cellX"));
        putNum(target, "target_cellY", data.getQueryParameter("target_cellY"));
        putNum(target, "screen", data.getQueryParameter("screen"));
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        try {
            Intent intent = new Intent();
            Intent caller = getIntent();
            applyParams(intent, caller);
            // audit trail: this channel has no system permission in front of it, so every call
            // records who asked and with what URL
            Log.i("AdbControl", "[deeplink] from=" + getCallingPackage()
                    + " uri=" + caller.getDataString());
            AdbControlReceiver.handleCommand(this, intent, "pref_enable_adb_link", "deeplink");
            finish();
        } catch (Throwable t) {
            Log.e("AdbControl", t.toString());
            finish();
        }
    }
}

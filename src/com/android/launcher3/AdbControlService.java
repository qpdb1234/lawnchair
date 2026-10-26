package com.android.launcher3;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

// ============ v108: AI entry point #3 - short-lived Service (am startservice) ============
//
// Same command set as the broadcast receiver, reached through
//   adb shell am start-service -n <pkg>/com.android.launcher3.AdbControlService --es cmd list_icons
//
// Idle cost is zero: the Service object is not created until something starts it.
// onStartCommand() runs the command and calls stopSelf(), so the component lives only for the
// duration of one request; START_NOT_STICKY keeps the system from resurrecting it afterwards.
//
// Chosen over IntentService because IntentService is deprecated and would still have to post
// forceReload() back to main anyway - the workspace writes here are a handful of SQLite
// statements, so running them on the main thread of a service that exists for a few
// milliseconds is cheaper than a worker handler.
//
// Gates: android:permission="android.permission.DUMP" + switch "pref_enable_adb_service".
public class AdbControlService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AdbControlReceiver.handleCommand(this, intent, "pref_enable_adb_service", "service");
        stopSelf();
        return START_NOT_STICKY;
    }
}

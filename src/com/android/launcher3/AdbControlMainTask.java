package com.android.launcher3;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.concurrent.CountDownLatch;

// v108 helper for AdbControlProvider: call() runs on a binder thread, while the
// workspace writes (ModelDbController + LauncherModel.forceReload) belong on the
// launcher main thread like every other entry point. This Runnable posts the work
// to main and releases the binder thread once it is done.
public class AdbControlMainTask implements Runnable {

    private volatile boolean mHandled = false;

    private final String mChannel;

    private final Context mContext;

    private final Intent mIntent;

    private final CountDownLatch mLatch;

    private final String mPrefKey;

    public AdbControlMainTask(Context context, Intent intent, String prefKey, String channel,
                              CountDownLatch latch) {
        mHandled = false;
        mContext = context;
        mIntent = intent;
        mPrefKey = prefKey;
        mChannel = channel;
        mLatch = latch;
    }

    // True when the switch was on and the command reached the dispatcher; read by
    // AdbControlProvider.call() after the latch releases the binder thread.
    public boolean isHandled() {
        return mHandled;
    }

    @Override
    public void run() {
        try {
            mHandled = AdbControlReceiver.handleCommand(mContext, mIntent, mPrefKey, mChannel);
        } catch (Throwable t) {
            Log.e("AdbControl", "provider task failed");
        }
        mLatch.countDown();
    }
}

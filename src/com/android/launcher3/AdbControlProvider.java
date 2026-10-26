package com.android.launcher3;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// ============ v108: AI entry point #4 - lazily initialised ContentProvider ============
//
// Reached through "content call", which is handy for tools that already speak the content URI
// dialect:
//   adb shell content call --uri content://<pkg>.aicontrol --method list_icons
//   adb shell content call --uri content://<pkg>.aicontrol --method move_icon \
//        --extra package_name:s:X --extra target_cellX:i:0 --extra target_cellY:i:1
//   (extras land in the Bundle of call(); "cmd" is taken from --method, an explicit
//    --extra cmd:s:<command> wins because it is put last)
//
// This is the one channel that costs something at process start - the system instantiates
// every provider of an app - so onCreate() deliberately does nothing at all (no db handle, no
// config read, not even a log line): the idle cost is one empty object, and all real work
// happens in call().
//
// Gates: android:permission="android.permission.DUMP" + switch "pref_enable_adb_provider".
//
// call() arrives on a binder thread; the command is posted to the launcher main thread and the
// binder thread waits (max 60s) so the caller gets a synchronous answer. Detailed results stay
// in logcat, tag "AdbControl".
//
// v110 entry point #6: the same authority also takes rows, which is the one way to hand over a
// *batch* of operations in a single round trip:
//   adb shell content insert --uri content://<pkg>.aicontrol \
//        --bind cmd:s:move_icon --bind package_name:s:X --bind target_cellX:i:0 --bind target_cellY:i:1
//   (an app-side ContentProviderClient.bulkInsert() may pass many rows at once)
//
// Gates: android:permission="android.permission.DUMP" + switch "pref_enable_adb_batch". Like
// call() this arrives on a binder thread, so each row is posted to the launcher main thread and
// awaited before the next one starts - that keeps the caller's order and gives it a synchronous
// per-row answer.
//
// Port note (Lawnchair): the manifest authority is "${applicationId}.aicontrol", so the URI
// this provider hands back is built from getPackageName() at runtime instead of hardcoding a
// single distribution's package.
public class AdbControlProvider extends ContentProvider {

    // One ContentValues cell -> one Intent extra, in the shape handleCommand() reads. A row
    // may carry a value as text or as a number ("--bind x:s:3" vs "--bind x:i:3"), so anything
    // that is not a String goes through parseInt(); a value that is neither is passed on as
    // text, where the numeric getters simply fall back to their default.
    private static void applyValue(Intent intent, String key, Object value) {
        if (value == null || key == null) {
            return;
        }
        if (value instanceof String) {
            intent.putExtra(key, (String) value);
            return;
        }
        String text = value.toString();
        try {
            intent.putExtra(key, Integer.parseInt(text));
        } catch (Exception e) {
            // not a number after all: hand the text over, the numeric getters then fall back
            // to their -1 default exactly like an omitted column would.
            intent.putExtra(key, text);
        }
    }

    // Run the rows in order on the main thread and count the accepted ones; a row the gate
    // rejects (switch off) or the dispatcher does not know is simply not counted.
    private int runRows(ContentValues[] rows) {
        int accepted = 0;
        if (rows == null) {
            return accepted;
        }
        for (ContentValues row : rows) {
            if (row == null) {
                continue;
            }
            Intent intent = new Intent();
            applyValue(intent, "cmd", row.get("cmd"));
            applyValue(intent, "package_name", row.get("package_name"));
            applyValue(intent, "target_cellX", row.get("target_cellX"));
            applyValue(intent, "target_cellY", row.get("target_cellY"));
            applyValue(intent, "screen", row.get("screen"));
            try {
                CountDownLatch latch = new CountDownLatch(1);
                Context context = getContext();
                AdbControlMainTask task = new AdbControlMainTask(context, intent,
                        "pref_enable_adb_batch", "batch", latch);
                new Handler(Looper.getMainLooper()).post(task);
                // the same 60s grace call() gives the main thread
                latch.await(60000L, TimeUnit.MILLISECONDS);
                if (task.isHandled()) {
                    accepted++;
                }
            } catch (Throwable t) {
                Log.e("AdbControl", t.toString());
            }
        }
        return accepted;
    }

    // The authority this provider is published under, derived from the running package so the
    // returned row URI matches the manifest's "${applicationId}.aicontrol".
    private String authority() {
        Context context = getContext();
        String pkg = (context != null) ? context.getPackageName() : "com.android.launcher3";
        return pkg + ".aicontrol";
    }

    // Lazy by design: touching no database and no preferences keeps the startup cost of this
    // provider at a bare object allocation.
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // v110: one row through the same runner bulkInsert() uses.
        //
        // v113: returning null tells the caller "insert failed", and ContentResolver users -
        // the ones an AI writes for the provider channel - read that as the row not being there
        // at all. A provider is expected to hand back the address of what it created, so the
        // row gets one. These rows are commands rather than table entries, so the number is a
        // count of the rows this call accepted: it lines up with the log line and with what
        // list_icons / /state then shows.
        try {
            int count = runRows(new ContentValues[] { values });
            if (count > 0) {
                return Uri.parse("content://" + authority() + "/row/" + count);
            }
            return null;
        } catch (Throwable t) {
            Log.e("AdbControl", t.toString());
            return null;
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        // v110: "how many rows actually ran" is the answer an automated caller needs.
        try {
            return runRows(values);
        } catch (Throwable t) {
            Log.e("AdbControl", t.toString());
            return 0;
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // method is used as the command; extras carry the command parameters.
        try {
            // rebuild an Intent so the shared command runner sees one input format
            Intent intent = new Intent();
            if (extras != null) {
                intent.putExtras(extras);
            }
            if (method != null) {
                intent.putExtra("cmd", method);
            }
            CountDownLatch latch = new CountDownLatch(1);
            Context context = getContext();
            AdbControlMainTask task = new AdbControlMainTask(context, intent,
                    "pref_enable_adb_provider", "provider", latch);
            new Handler(Looper.getMainLooper()).post(task);
            // wait for the main-thread run
            boolean completed = latch.await(60000L, TimeUnit.MILLISECONDS);
            boolean handled = task.isHandled();
            Bundle result = new Bundle();
            result.putBoolean("handled", handled);
            result.putBoolean("completed", completed);
            result.putString("result", "see: adb logcat -d -s AdbControl");
            return result;
        } catch (Throwable t) {
            Bundle err = new Bundle();
            err.putString("error", t.toString());
            return err;
        }
    }
}

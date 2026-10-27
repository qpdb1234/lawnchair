package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;
import android.os.Process;

import com.android.launcher3.util.ComponentKey;
import com.patrykmichalik.opto.core.PreferenceExtensionsKt;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import app.lawnchair.preferences2.PreferenceManager2;
/**
 * Utility class to filter out components from various lists
 */
public class AppFilter {

    private final Set<ComponentName> mFilteredComponents;
    private final String mHostPackageName;

    public AppFilter(Context context) {
        mHostPackageName = context.getPackageName();
        mFilteredComponents = Arrays.stream(
                context.getResources().getStringArray(R.array.filtered_components))
                .map(ComponentName::unflattenFromString)
                .collect(Collectors.toSet());
    }

    public boolean shouldShowApp(ComponentName app) {
        // Never list the launcher itself. filtered_components only drops the main
        // launcher activity, but this package also exposes the AI-control shortcut
        // host alias (a second MAIN/LAUNCHER component), so exclude the whole host
        // package at the shared chokepoint used by the drawer and every picker.
        if (mHostPackageName.equals(app.getPackageName())) {
            return false;
        }
        return !mFilteredComponents.contains(app);
    }
}

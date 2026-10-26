package app.lawnchair.ui.preferences.destinations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.ui.preferences.components.WallpaperAccessPermissionDialog
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.DividerColumn
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.util.FileAccessManager
import app.lawnchair.util.FileAccessState
import com.android.launcher3.R

// Moved out of the former "Experimental features" screen: wallpaper blur is now a regular
// Home screen wallpaper option. The switch and its two sliders are gated on wallpaper access
// permission, requested through the same dialog the experimental screen used.
@Composable
fun WallpaperBlurPreference() {
    val context = LocalContext.current
    val prefs = preferenceManager()
    val enableWallpaperBlur = prefs.enableWallpaperBlur.getAdapter()
    val fileAccessManager = remember { FileAccessManager.getInstance(context) }
    val allFilesAccessState by fileAccessManager.allFilesAccessState.collectAsStateWithLifecycle()
    val wallpaperAccessState by fileAccessManager.wallpaperAccessState.collectAsStateWithLifecycle()
    val hasPermission = wallpaperAccessState != FileAccessState.Denied
    var showPermissionDialog by remember { mutableStateOf(false) }

    SwitchPreference(
        checked = hasPermission && enableWallpaperBlur.state.value,
        onCheckedChange = {
            if (!hasPermission) {
                showPermissionDialog = true
            } else {
                enableWallpaperBlur.onChange(it)
            }
        },
        label = stringResource(id = R.string.wallpaper_blur),
    )
    ExpandAndShrink(visible = hasPermission && enableWallpaperBlur.state.value) {
        DividerColumn {
            SliderPreference(
                label = stringResource(id = R.string.wallpaper_background_blur),
                adapter = prefs.wallpaperBlur.getAdapter(),
                step = 5,
                valueRange = 0..100,
                showUnit = "%",
            )
            SliderPreference(
                label = stringResource(id = R.string.wallpaper_background_blur_factor),
                adapter = prefs.wallpaperBlurFactorThreshold.getAdapter(),
                step = 1F,
                valueRange = 0F..10F,
            )
        }
    }
    if (showPermissionDialog) {
        WallpaperAccessPermissionDialog(
            managedFilesChecked = allFilesAccessState != FileAccessState.Denied,
            onDismiss = {
                showPermissionDialog = false
            },
            onPermissionRequest = { fileAccessManager.refresh() },
        )
    }
    LifecycleResumeEffect(Unit) {
        showPermissionDialog = false
        fileAccessManager.refresh()
        onPauseOrDispose { }
    }
}

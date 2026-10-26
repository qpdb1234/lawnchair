package app.lawnchair.ui.preferences.destinations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.preferences.ResetPreferences
import app.lawnchair.preferences.SettingsCategory
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.GestureHandlerPreference
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.android.launcher3.R
import com.android.launcher3.Utilities
import kotlinx.coroutines.launch

@Composable
fun GesturePreferences(
    modifier: Modifier = Modifier,
) {
    val prefs = preferenceManager2()
    val gncPrefs = preferenceManager()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    PreferenceLayout(
        label = stringResource(id = R.string.gestures_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup {
            GestureHandlerPreference(
                adapter = prefs.doubleTapGestureHandler.getAdapter(),
                label = stringResource(id = R.string.gesture_double_tap),
            )
            GestureHandlerPreference(
                adapter = prefs.swipeUpGestureHandler.getAdapter(),
                label = stringResource(id = R.string.gesture_swipe_up),
            )
            GestureHandlerPreference(
                adapter = prefs.swipeDownGestureHandler.getAdapter(),
                label = stringResource(id = R.string.gesture_swipe_down),
            )
            GestureHandlerPreference(
                adapter = prefs.homePressGestureHandler.getAdapter(),
                label = stringResource(id = R.string.gesture_home_tap),
            )
            GestureHandlerPreference(
                adapter = prefs.backPressGestureHandler.getAdapter(),
                label = stringResource(id = R.string.gesture_back_tap),
            )
        }
        // Moved out of the former "Experimental features" screen.
        PreferenceGroup {
            SwitchPreference(
                adapter = gncPrefs.enableGnc.getAdapter(),
                label = stringResource(id = R.string.gesturenavcontract_label),
                description = stringResource(id = R.string.gesturenavcontract_description),
                enabled = Utilities.ATLEAST_Q,
            )
            SwitchPreference(
                adapter = prefs.iconSwipeGestures.getAdapter(),
                label = stringResource(R.string.icon_swipe_gestures),
                description = stringResource(R.string.icon_swipe_gestures_description),
            )
        }
        PreferenceGroup {
            ClickablePreference(
                label = stringResource(id = R.string.reset_category_label),
                confirmationText = stringResource(id = R.string.reset_category_confirmation),
            ) {
                scope.launch { ResetPreferences.resetCategory(context, SettingsCategory.Gestures) }
            }
        }
    }
}

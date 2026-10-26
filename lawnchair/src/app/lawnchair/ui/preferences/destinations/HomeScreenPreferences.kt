/*
 * Copyright 2022, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.destinations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.LawnchairApp
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.nexuslauncher.OverlayCallbackImpl
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences.ResetPreferences
import app.lawnchair.preferences.SettingsCategory
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.theme.color.ColorMode
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.FeedPreference
import app.lawnchair.ui.preferences.components.GestureHandlerPreference
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.OverlayHandlerPreference
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.navigation.HomeScreenGrid
import app.lawnchair.ui.preferences.navigation.HomeScreenHiddenApps
import app.lawnchair.util.collectAsStateBlocking
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.Utilities
import kotlinx.coroutines.launch

object HomeScreenRoutes {
    const val GRID = "grid"
    const val POPUP_EDITOR = "popup_editor"
}

@Composable
fun HomeScreenPreferences(
    modifier: Modifier = Modifier,
) {
    val prefs = preferenceManager()
    val prefs2 = preferenceManager2()
    val scope = rememberCoroutineScope()
    PreferenceLayout(
        label = stringResource(id = R.string.home_screen_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        val lockHomeScreenAdapter = prefs2.lockHomeScreen.getAdapter()
        val context = LocalContext.current

        PreferenceGroup(heading = stringResource(id = R.string.general_label)) {
            GestureHandlerPreference(
                adapter = prefs2.doubleTapGestureHandler.getAdapter(),
                label = stringResource(id = R.string.gesture_double_tap),
            )
            SwitchPreference(
                prefs.infiniteScrolling.getAdapter(),
                label = stringResource(id = R.string.infinite_scrolling_label),
                description = stringResource(id = R.string.infinite_scrolling_description),
            )
            val classicDragAdapter = prefs2.classicDragMode.getAdapter()
            SwitchPreference(
                adapter = classicDragAdapter,
                label = stringResource(id = R.string.classic_drag_mode_label),
                description = stringResource(id = R.string.classic_drag_mode_description),
            )
            ExpandAndShrink(visible = classicDragAdapter.state.value) {
                SliderPreference(
                    label = stringResource(id = R.string.classic_drag_edge_label),
                    adapter = prefs2.classicDragEdgeFraction.getAdapter(),
                    step = 0.01f,
                    valueRange = 0.05F..0.30F,
                    showAsPercentage = true,
                )
            }
        }
        PreferenceGroup(heading = stringResource(id = R.string.home_screen_actions)) {
            ClickablePreference(
                label = stringResource(id = R.string.remove_all_views_from_home_screen),
                confirmationText = stringResource(id = R.string.remove_all_views_from_home_screen_desc),
                onClick = {
                    scope.launch {
                        LauncherAppState.getInstance(context).clearAllViewsFromHomeScreen()
                    }
                },
            )
        }
        PreferenceGroup(heading = stringResource(id = R.string.minus_one)) {
            val feedAvailable = OverlayCallbackImpl.minusOneAvailable(LocalContext.current)
            val enableFeedAdapter = prefs2.enableFeed.getAdapter()
            SwitchPreference(
                adapter = enableFeedAdapter,
                label = stringResource(id = R.string.minus_one_enable),
                description = if (feedAvailable) null else stringResource(id = R.string.minus_one_unavailable),
                enabled = feedAvailable,
            )
            ExpandAndShrink(visible = feedAvailable && enableFeedAdapter.state.value) {
                FeedPreference()
            }
        }
        PreferenceGroup(heading = stringResource(R.string.style)) {
            HomeScreenTextColorPreference()
            OverlayHandlerPreference(
                adapter = prefs2.closingAppOverlay.getAdapter(),
                label = stringResource(id = R.string.app_closing_animation),
            )
        }
        PreferenceGroup(heading = stringResource(id = R.string.wallpaper)) {
            SwitchPreference(
                prefs.wallpaperScrolling.getAdapter(),
                label = stringResource(id = R.string.wallpaper_scrolling_label),
            )
            if (Utilities.ATLEAST_R) {
                SwitchPreference(
                    prefs2.wallpaperDepthEffect.getAdapter(),
                    label = stringResource(id = R.string.wallpaper_depth_effect_label),
                    description = stringResource(id = R.string.wallpaper_depth_effect_description),
                )
            }
            SwitchPreference(
                adapter = prefs2.showTopShadow.getAdapter(),
                label = stringResource(id = R.string.show_sys_ui_scrim),
            )
            // Moved out of the former "Experimental features" screen.
            WallpaperBlurPreference()
        }
        PreferenceGroup(heading = stringResource(id = R.string.layout)) {
            val columns by prefs.workspaceColumns.getAdapter()
            val rows by prefs.workspaceRows.getAdapter()
            NavigationActionPreference(
                label = stringResource(id = R.string.home_screen_grid),
                destination = HomeScreenGrid,
                subtitle = stringResource(id = R.string.x_by_y, columns, rows),
            )
            // No-drawer mode: pick the apps the flatten pass keeps off the home screen.
            val hiddenHomeApps = prefs2.hiddenHomeApps.getAdapter().state.value
            NavigationActionPreference(
                label = stringResource(id = R.string.hide_apps_from_home_screen_label),
                destination = HomeScreenHiddenApps,
                subtitle = LocalContext.current.resources.getQuantityString(R.plurals.apps_count, hiddenHomeApps.size, hiddenHomeApps.size),
            )
            SwitchPreference(
                adapter = lockHomeScreenAdapter,
                label = stringResource(id = R.string.home_screen_lock),
                description = stringResource(id = R.string.home_screen_lock_description),
            )
            SwitchPreference(
                adapter = prefs2.enableDotPagination.getAdapter(),
                label = stringResource(id = R.string.show_dot_pagination_label),
                description = stringResource(id = R.string.show_dot_pagination_description),
            )
            // Moved out of the former "Experimental features" screen.
            SwitchPreference(
                adapter = prefs.workspaceIncreaseMaxGridSize.getAdapter(),
                label = stringResource(id = R.string.workspace_increase_max_grid_size_label),
                description = stringResource(id = R.string.workspace_increase_max_grid_size_description),
            )
        }
        PreferenceGroup(heading = stringResource(id = R.string.popup_menu)) {
            SwitchPreference(
                adapter = prefs2.enableMaterialUPopUp.getAdapter(),
                label = stringResource(id = R.string.show_material_u_popup_label),
                description = stringResource(id = R.string.show_material_u_popup_description),
            )
            LauncherPopupPreferenceItem()
            IconMenuPopupPreferenceItem()
        }
        PreferenceGroup(heading = stringResource(id = R.string.status_bar_label)) {
            val showStatusBarAdapter = prefs2.showStatusBar.getAdapter()
            SwitchPreference(
                adapter = showStatusBarAdapter,
                label = stringResource(id = R.string.show_status_bar),
            )
            ExpandAndShrink(visible = showStatusBarAdapter.state.value) {
                SwitchPreference(
                    adapter = prefs2.darkStatusBar.getAdapter(),
                    label = stringResource(id = R.string.dark_status_bar_label),
                )
            }
            ExpandAndShrink(visible = showStatusBarAdapter.state.value && LawnchairApp.isRecentsEnabled) {
                SwitchPreference(
                    adapter = prefs2.statusBarClock.getAdapter(),
                    label = stringResource(id = R.string.status_bar_clock_label),
                    description = stringResource(id = R.string.status_bar_clock_description),
                )
            }
        }
        PreferenceGroup(heading = stringResource(id = R.string.icons)) {
            SliderPreference(
                label = stringResource(id = R.string.icon_sizes),
                adapter = prefs2.homeIconSizeFactor.getAdapter(),
                step = 0.1f,
                valueRange = 0.5F..1.5F,
                showAsPercentage = true,
                percentOffset = -20,
            )
            val homeScreenLabelsAdapter = prefs2.showIconLabelsOnHomeScreen.getAdapter()
            SwitchPreference(
                adapter = homeScreenLabelsAdapter,
                label = stringResource(id = R.string.show_labels),
            )
            ExpandAndShrink(visible = homeScreenLabelsAdapter.state.value) {
                SliderPreference(
                    label = stringResource(id = R.string.label_size),
                    adapter = prefs2.homeIconLabelSizeFactor.getAdapter(),
                    step = 0.1f,
                    valueRange = 0.5F..1.5F,
                    showAsPercentage = true,
                    percentOffset = 10,
                )
            }
        }
        val overrideRepo = IconOverrideRepository.INSTANCE.get(LocalContext.current)
        val customIconsCount by remember { overrideRepo.observeCount() }.collectAsStateBlocking()
        ExpandAndShrink(visible = customIconsCount > 0) {
            PreferenceGroup {
                ClickablePreference(
                    label = stringResource(id = R.string.reset_custom_icons),
                    confirmationText = stringResource(id = R.string.reset_custom_icons_confirmation),
                    onClick = { scope.launch { overrideRepo.deleteAll() } },
                )
            }
        }
        PreferenceGroup(heading = stringResource(id = R.string.widget_button_text)) {
            SwitchPreference(
                adapter = prefs2.roundedWidgets.getAdapter(),
                label = stringResource(id = R.string.force_rounded_widgets),
            )
            SwitchPreference(
                adapter = prefs2.allowWidgetOverlap.getAdapter(),
                label = stringResource(id = R.string.allow_widget_overlap),
            )
            SwitchPreference(
                adapter = prefs2.widgetUnlimitedSize.getAdapter(),
                label = stringResource(id = R.string.widget_unlimited_size_label),
                description = stringResource(id = R.string.widget_unlimited_size_description),
            )
            SwitchPreference(
                adapter = prefs2.forceWidgetResize.getAdapter(),
                label = stringResource(id = R.string.force_widget_resize_label),
                description = stringResource(id = R.string.force_widget_resize_description),
            )
        }
        PreferenceGroup {
            ClickablePreference(
                label = stringResource(id = R.string.reset_category_label),
                confirmationText = stringResource(id = R.string.reset_category_confirmation),
                onClick = {
                    scope.launch { ResetPreferences.resetCategory(context, SettingsCategory.HomeScreen) }
                },
            )
        }
    }
}

@Composable
fun HomeScreenTextColorPreference(
    modifier: Modifier = Modifier,
) {
    ListPreference(
        adapter = preferenceManager2().workspaceTextColor.getAdapter(),
        entries = ColorMode.entries(),
        label = stringResource(id = R.string.home_screen_text_color),
        modifier = modifier,
    )
}

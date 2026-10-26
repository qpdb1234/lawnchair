/*
 * Copyright 2024, Lawnchair
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

package app.lawnchair.preferences

import android.content.Context
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.restartLauncher

/**
 * Top level settings sections that expose a "reset this section to defaults" action. The keys of
 * every section are listed below and split across the two preference stores the app uses: the
 * opto DataStore ([PreferenceManager2]) and the legacy SharedPreferences ([PreferenceManager]).
 *
 * Restoring a setting to its default is done by deleting its stored key: both stores fall back to
 * the shipped default whenever a key is absent. Only pure configuration keys are reset; user data
 * such as the home screen layout (kept in the model DB), hidden app lists, the selected icon pack
 * and per-app icon/gesture overrides are never touched (see [KEEP_DATASTORE_KEYS] /
 * [KEEP_SHARED_PREFS_KEYS]). Any key that is not explicitly classified is treated as preserved,
 * so a classification gap can only ever mean "not reset", never "data lost".
 */
enum class SettingsCategory {
    General,
    HomeScreen,
    Smartspace,
    Dock,
    AppDrawer,
    Search,
    Folders,
    Gestures,
    Quickstep,
}

object ResetPreferences {

    // DataStore keys never reset by the global reset (user data / internal migration flags). The
    // legacy ':' guarded per-app gesture keys are additionally skipped inside removeAllKeysExcept.
    private val KEEP_DATASTORE_KEYS = setOf(
        "hidden_apps",
        "hidden_home_apps",
        "legacy_popup_options_migrated",
    )

    // SharedPreferences keys never reset by the global reset (selected icon pack, per-app renames,
    // and the debug menu switch which is developer oriented rather than a normal setting).
    private val KEEP_SHARED_PREFS_KEYS = setOf(
        "pref_iconPackPackage",
        "pref_themedIconPackPackage",
        "pref_appNameMap",
        "pref_enableDebugMenu",
    )

    /**
     * Every classification table lists the DataStore key names ([Section.dataStoreKeys]) and the
     * legacy SharedPreferences key names ([Section.sharedPrefsKeys]) owned by that section.
     */
    private class Section(
        val dataStoreKeys: Set<String>,
        val sharedPrefsKeys: Set<String>,
    )

    private val sections = mapOf(
        SettingsCategory.General to Section(
            dataStoreKeys = setOf(
                "accent_color", "color_style", "icon_shape", "custom_icon_shape",
                "always_reload_icons", "notification_dot_color", "notification_dot_text_color",
                "show_notification_count", "enable_font_selection", "additional_fonts",
                "app_language", "log_collection_enabled",
            ),
            sharedPrefsKeys = setOf(
                "pref_allowRotation", "themed_icons", "drawer_themed_icons",
                "tint_icon_pack_backgrounds", "prefs_wrapAdaptive",
                "prefs_transparentIconBackground", "pref_coloredBackgroundLightness",
                "pref_shadowBGIcons", "pref_launcherTheme", "pref_workspaceFont",
                "pref_fontHeading", "pref_fontHeadingMedium", "pref_fontBody",
                "pref_fontBodyMedium",
            ),
        ),
        SettingsCategory.HomeScreen to Section(
            dataStoreKeys = setOf(
                "lock_home_screen", "classic_drag_mode", "classic_drag_edge_fraction",
                "show_top_shadow", "workspace_text_color",
                "closing_app_overlay", "home_icon_size_factor", "home_icon_label_size_factor",
                "show_icon_labels_on_home_screen", "enable_dot_pagination",
                "page_indicator_height_factor", "show_status_bar", "dark_status_bar",
                "status_bar_clock", "enable_wallpaper_depth_effect", "enable_material_u_popup",
                "launcher_popup_order", "icon_menu_order", "lock_home_screen_on_popup",
                "edit_home_screen_on_popup", "show_system_settings_entry_on_popup", "enable_feed",
                "rounded_widgets", "allow_widget_overlap", "force_widget_resize",
                "widget_unlimited_size", "double_tap_gesture_handler",
            ),
            sharedPrefsKeys = setOf(
                "pref_infiniteScrolling", "pref_wallpaperScrolling", "pref_workspaceColumns",
                "pref_workspaceRows", "pref_workspace_increase_max_grid_size",
                "pref_enableWallpaperBlur", "pref_wallpaperBlur", "pref_wallpaperBlurFactor",
                "pref_feedProvider", "pref_ignoreFeedWhitelist",
            ),
        ),
        SettingsCategory.Smartspace to Section(
            dataStoreKeys = setOf(
                "enable_smartspace", "enable_smartspace_calendar_selection", "smartspace_mode",
                "enable_smartspace_aag_widget", "enable_smartspace_battery_status",
                "enable_smartspace_now_playing", "smartspace_show_date", "smartspace_show_time",
                "smartspace_time_format", "smartspace_calendar", "smartspace_max_count",
            ),
            sharedPrefsKeys = emptySet(),
        ),
        SettingsCategory.Dock to Section(
            dataStoreKeys = setOf(
                "hotseat_mode", "pref_show_hotseat", "themed_hotseat_qsb",
                "dock_search_bar_provider", "dock_search_bar_force_website",
                "use_drawer_search_icon", "hotseat_bg_color", "stroke_color",
                "enable_label_dock", "hotseat_bottom_factor",
            ),
            sharedPrefsKeys = setOf(
                "pref_hotseatColumns", "pref_hotseatBG", "pref_hotseatBGTransparency",
                "pref_hotseatBGHRinsetLeft", "pref_hotseatBGVRinsetTop",
                "pref_hotseatBGHRinsetRight", "pref_hotseatBGVRinsetBottom",
                "pref_hotseatQsbCornerRadius", "pref_searchHotseatTranparency",
                "pref_searchStrokeWidth",
            ),
        ),
        SettingsCategory.AppDrawer to Section(
            dataStoreKeys = setOf(
                "app_drawer_bg_color", "all_apps_search_bar_background", "drawer_columns",
                "drawer_icon_size_factor", "drawer_icon_label_size_factor",
                "drawer_cell_height_factor", "drawer_left_right_factor",
                "show_icon_labels_in_drawer", "two_line_all_apps", "all_apps_show_scrollbar",
                "all_apps_remember_position", "show_suggested_apps_at_drawer_top",
                "no_drawer", "show_component_names",
            ),
            sharedPrefsKeys = setOf(
                "pref_add_icon_to_home", "pref_allapps_bulk_icon_loading", "pref_drawerList",
                "pref_drawerListOrder", "pref_drawerOpacity",
            ),
        ),
        SettingsCategory.Search to Section(
            dataStoreKeys = setOf(
                "search_algorithm", "web_suggestion_provider", "web_suggestion_provider_url",
                "web_suggestions_provider_suggestions_url", "web_suggestion_provider_name",
                "enable_fuzzy_search", "max_search_result_count", "max_suggestion_result_count",
                "max_files_result_count", "max_people_result_count", "max_web_suggestion_delay",
                "max_settings_entry_result_count", "max_recent_result_count",
                "hide_app_drawer_search_bar", "auto_show_keyboard_in_drawer",
                "show_hidden_apps_in_search", "hidden_apps_in_search",
                "dock_search_bar_provider", "dock_search_bar_force_website",
                "themed_hotseat_qsb", "use_drawer_search_icon",
            ),
            sharedPrefsKeys = setOf(
                "device_search", "pref_searchResultShortcuts", "pref_searchResultPeople",
                "pref_searchResultPixelTips", "pref_searchResultSettings",
                "pref_searchResultCalculator", "pref_searchResultApps", "pref_searchResultFiles",
                "pref_searchResultAllFiles", "pref_searchResultAudio",
                "pref_searchResultVisualMedia", "pref_searchResultStartPageSuggestion",
                "pref_searchResultSettingsEntry", "pref_searchResultRecentSuggestion",
                "pref_hotseatQsbCornerRadius", "pref_searchHotseatTranparency",
                "pref_searchStrokeWidth",
            ),
        ),
        SettingsCategory.Folders to Section(
            dataStoreKeys = setOf(
                "folder_color", "folder_columns", "folder_background_opacity",
                "folder_preview_background_opacity", "home_icon_label_folder_size_factor",
                "show_icon_labels_on_home_screen_folder",
            ),
            sharedPrefsKeys = setOf("pref_folderRows"),
        ),
        SettingsCategory.Gestures to Section(
            dataStoreKeys = setOf(
                "double_tap_gesture_handler", "swipe_up_gesture_handler",
                "swipe_down_gesture_handler", "home_press_gesture_handler",
                "back_press_gesture_handler", "icon_swipe_gestures",
            ),
            sharedPrefsKeys = setOf("pref_enableGnc"),
        ),
        SettingsCategory.Quickstep to Section(
            dataStoreKeys = setOf("enable_taskbar_on_phone"),
            sharedPrefsKeys = setOf(
                "pref_overrideWindowCornerRadius", "pref_windowCornerRadius",
                "pref_recentActionOrder", "pref_recentsActionScreenshot",
                "pref_recentsActionShare", "pref_recentsActionLens", "pref_clearAllAsAction",
                "pref_lockedAsAction", "pref_recentsTranslucentBackground",
                "pref_recentTranslucentBackgroundAlpha",
            ),
        ),
    )

    /** Restores the settings belonging to [category] to their defaults, then restarts the launcher. */
    suspend fun resetCategory(context: Context, category: SettingsCategory) {
        val section = sections[category] ?: return
        PreferenceManager2.getInstance(context).removeKeys(section.dataStoreKeys)
        PreferenceManager.getInstance(context).removeKeys(section.sharedPrefsKeys)
        restartLauncher(context)
    }

    /** Restores every configurable setting to its defaults, then restarts the launcher. */
    suspend fun resetAll(context: Context) {
        PreferenceManager2.getInstance(context).removeAllKeysExcept(KEEP_DATASTORE_KEYS)
        PreferenceManager.getInstance(context).removeAllRegisteredKeysExcept(KEEP_SHARED_PREFS_KEYS)
        restartLauncher(context)
    }
}

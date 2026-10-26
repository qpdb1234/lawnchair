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

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import com.android.launcher3.AdbControlHttpServer
import com.android.launcher3.AdbControlShortcuts
import com.android.launcher3.R

// ============ Mod port: the settings half of the AI access channels ============
//
// In the original design these nine switches were ordinary android SwitchPreferences on a
// PreferenceFragment screen, so the legacy framework wrote them straight into the
// "com.android.launcher3.prefs" SharedPreferences file - which is exactly the file every command
// executor gates on (AdbControlReceiver.handleCommand -> getSharedPreferences(...).getBoolean).
//
// Lawnchair's settings are Compose and its own switches are backed by a DataStore preference
// manager, which would persist to a different store the Java gates never read. Rather than rewrite
// the eleven verbatim-ported Java classes to hunt through a DataStore, this screen writes the same
// nine keys to the same SharedPreferences file the mod used. One file, and the executor stays
// byte-for-byte faithful.
//
// The two side-effecting channels are re-asserted the same way the mod's ControlSwitchPreference
// did in setChecked(): toggling HTTP (7), MCP (8) or the shortcut icon (9) calls both
// AdbControlHttpServer.syncFromPrefs() and AdbControlShortcuts.syncFromPrefs(), and opening the
// screen re-runs them once (the LaunchedEffect below). That is what starts or stops the loopback
// listener immediately and enables or disables the LauncherControl icon after a reboot, with no
// boot receiver, no service and no thread of its own.

@Composable
fun AiControlPreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sp = remember {
        context.getSharedPreferences("com.android.launcher3.prefs", Context.MODE_PRIVATE)
    }
    // Re-assert the live side effects whenever this screen is shown, standing in for the mod's
    // switch re-binding on every inflate: bringing the HTTP server and the control icon back in
    // line with their switches after a reboot, purely by opening settings.
    LaunchedEffect(Unit) {
        AdbControlHttpServer.syncFromPrefs(context)
        AdbControlShortcuts.syncFromPrefs(context)
    }
    // Channels 7, 8 and 9 own a running consequence, so a change to any of them re-syncs both
    // helpers exactly like the mod's ControlSwitchPreference did, regardless of which one moved.
    val syncSideEffects: (Boolean) -> Unit = {
        AdbControlHttpServer.syncFromPrefs(context)
        AdbControlShortcuts.syncFromPrefs(context)
    }
    PreferenceLayout(
        label = stringResource(id = R.string.ai_control_category_title),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup(
            description = stringResource(id = R.string.ai_control_category_summary),
        ) {
            ChannelSwitch(sp, "pref_enable_adb_control", R.string.adb_control_title, R.string.adb_control_summary)
            ChannelSwitch(sp, "pref_enable_adb_activity", R.string.adb_activity_title, R.string.adb_activity_summary)
            ChannelSwitch(sp, "pref_enable_adb_service", R.string.adb_service_title, R.string.adb_service_summary)
            ChannelSwitch(sp, "pref_enable_adb_provider", R.string.adb_provider_title, R.string.adb_provider_summary)
            ChannelSwitch(sp, "pref_enable_adb_link", R.string.adb_link_title, R.string.adb_link_summary)
            ChannelSwitch(sp, "pref_enable_adb_batch", R.string.adb_batch_title, R.string.adb_batch_summary)
            ChannelSwitch(sp, "pref_enable_adb_http", R.string.adb_http_title, R.string.adb_http_summary, onToggled = syncSideEffects)
            ChannelSwitch(sp, "pref_enable_adb_mcp", R.string.adb_mcp_title, R.string.adb_mcp_summary, onToggled = syncSideEffects)
            ChannelSwitch(sp, "pref_enable_adb_shortcut", R.string.adb_shortcut_title, R.string.adb_shortcut_summary, onToggled = syncSideEffects)
        }
        PreferenceGroup {
            InfoPreference(R.string.ai_direct_db_title, R.string.ai_direct_db_summary)
            InfoPreference(R.string.ai_cache_freeze_title, R.string.ai_cache_freeze_summary)
        }
    }
}

// One channel row: a plain two-state switch whose only storage is the shared prefs file the
// executor reads. The value is mirrored in Compose state so the toggle repaints immediately,
// while the write goes through SharedPreferences.edit().apply() (asynchronous, same as the
// framework's own SwitchPreference).
@Composable
private fun ChannelSwitch(
    sp: SharedPreferences,
    key: String,
    @StringRes title: Int,
    @StringRes summary: Int,
    onToggled: (Boolean) -> Unit = {},
) {
    val checked = remember { mutableStateOf(sp.getBoolean(key, false)) }
    SwitchPreference(
        checked = checked.value,
        onCheckedChange = { value ->
            sp.edit().putBoolean(key, value).apply()
            checked.value = value
            onToggled(value)
        },
        label = stringResource(id = title),
        description = stringResource(id = summary),
    )
}

// The two read-only notes at the bottom of the mod's category (direct DB editing, cached-app
// freezer): a title and a description with no control, so a bare PreferenceTemplate.
@Composable
private fun InfoPreference(
    @StringRes title: Int,
    @StringRes summary: Int,
) {
    PreferenceTemplate(
        title = { Text(text = stringResource(id = title)) },
        description = { Text(text = stringResource(id = summary)) },
    )
}

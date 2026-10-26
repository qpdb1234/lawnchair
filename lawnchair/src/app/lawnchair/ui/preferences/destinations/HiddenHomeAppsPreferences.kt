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

package app.lawnchair.ui.preferences.destinations

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.AppItem
import app.lawnchair.ui.preferences.components.AppItemPlaceholder
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.util.App
import app.lawnchair.util.appsState
import com.android.launcher3.R

/**
 * Mirror of [HiddenAppsPreferences] for the no-drawer "hide from home screen" list: picks the
 * component keys AllAppsToWorkspace keeps off the workspace. Toggling an entry recreates the
 * launcher (see PreferenceManager2.hiddenHomeApps), so the flatten pass re-runs immediately.
 */
@Composable
fun HiddenHomeAppsPreferences(
    modifier: Modifier = Modifier,
) {
    val adapter = preferenceManager2().hiddenHomeApps.getAdapter()
    val hiddenApps by adapter.state
    val pageTitle =
        if (hiddenApps.isEmpty()) {
            stringResource(id = R.string.hide_apps_from_home_screen_label)
        } else {
            stringResource(id = R.string.hidden_home_apps_label_with_count, hiddenApps.size)
        }
    val apps by appsState(comparator = hiddenAppsComparator(hiddenApps))
    val state = rememberLazyListState()
    PreferenceScaffold(
        label = pageTitle,
        actions = {
            if (apps.isNotEmpty()) {
                ListSortingOptions(
                    originalList = apps,
                    filteredList = hiddenApps,
                    onUpdateList = {
                        adapter.onChange(it)
                    },
                )
            }
        },
        modifier = modifier,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) {
        Crossfade(targetState = apps.isNotEmpty(), label = "") { present ->
            if (present) {
                PreferenceLazyColumn(it, state = state) {
                    val toggleHiddenApp = { app: App ->
                        val key = app.key.toString()
                        val newSet = apps.asSequence()
                            .filter { hiddenApps.contains(it.key.toString()) }
                            .map { it.key.toString() }
                            .toMutableSet()
                        val isHidden = !hiddenApps.contains(key)
                        if (isHidden) newSet.add(key) else newSet.remove(key)
                        adapter.onChange(newSet)
                    }
                    preferenceGroupItems(
                        items = apps,
                        isFirstChild = true,
                        dividerStartIndent = 40.dp,
                    ) { _, app ->
                        AppItem(
                            app = app,
                            onClick = toggleHiddenApp,
                        ) {
                            Checkbox(
                                checked = hiddenApps.contains(app.key.toString()),
                                onCheckedChange = null,
                            )
                        }
                    }
                }
            } else {
                PreferenceLazyColumn(it, enabled = false) {
                    preferenceGroupItems(
                        count = 20,
                        isFirstChild = true,
                        dividerStartIndent = 40.dp,
                    ) {
                        AppItemPlaceholder {
                            Spacer(Modifier.width(24.dp))
                        }
                    }
                }
            }
        }
    }
}

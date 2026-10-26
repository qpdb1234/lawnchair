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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.ui.language.AppLanguage
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import com.android.launcher3.R

/**
 * Per-app language picker.
 *
 * Reuses the shared [ListPreference] sheet, whose default summary shows the currently selected
 * language. The stored tag is applied to the launcher process the moment it changes. Language names
 * stay in their own script (never translated) so a user who cannot read the current interface
 * language can still find their own; only the "follow system" entry is translated.
 */
@Composable
fun AppLanguagePreference(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val adapter = PreferenceManager2.getInstance(context).appLanguage.getAdapter()

    val systemDefault = stringResource(R.string.language_system_default)
    val entries = buildList {
        add(ListPreferenceEntry(value = "", label = { systemDefault }))
        for (language in AppLanguage.LANGUAGES) {
            add(ListPreferenceEntry(value = language.tag, label = { language.name }))
        }
    }

    ListPreference(
        entries = entries,
        value = adapter.state.value,
        onValueChange = { tag ->
            adapter.onChange(tag)
            AppLanguage.applyLocale(context, tag)
        },
        label = stringResource(R.string.language_title),
        modifier = modifier,
    )
}

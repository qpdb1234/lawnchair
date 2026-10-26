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

package app.lawnchair.ui.language

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.util.Log
import com.patrykmichalik.opto.core.firstBlocking
import app.lawnchair.preferences2.PreferenceManager2

/**
 * Per-app interface language.
 *
 * The choice is stored as a BCP 47 tag in [PreferenceManager2.appLanguage] and handed to the
 * framework [LocaleManager] so only the launcher's own UI changes language, never the rest of the
 * system. An empty tag means "follow the system language" and resets the per-app override.
 *
 * Per-app locales are a Android 13 feature; on older versions the choice is remembered but nothing
 * is applied, matching the shipped mod. The set call can still be rejected (for example when the
 * process is not the app's main process), so it is guarded and logged rather than allowed to crash.
 */
object AppLanguage {

    private const val TAG = "LauncherLanguage"

    /** A picker entry: the BCP 47 tag and its name, always written in that language's own script. */
    data class Language(val tag: String, val name: String)

    /** The languages this APK is translated into, mirroring the mod's language_values array. */
    val LANGUAGES = listOf(
        Language("en", "English"),
        Language("zh-CN", "简体中文"),
        Language("zh-TW", "繁體中文"),
        Language("ja", "日本語"),
        Language("ko", "한국어"),
        Language("fr", "Français"),
        Language("de", "Deutsch"),
        Language("es", "Español"),
        Language("pt-BR", "Português (Brasil)"),
        Language("ru", "Русский"),
        Language("it", "Italiano"),
        Language("pl", "Polski"),
        Language("nl", "Nederlands"),
        Language("sv", "Svenska"),
        Language("tr", "Türkçe"),
        Language("cs", "Čeština"),
        Language("uk", "Українська"),
        Language("ar", "العربية"),
        Language("iw", "עברית"),
        Language("hi", "हिन्दी"),
        Language("in", "Bahasa Indonesia"),
        Language("vi", "Tiếng Việt"),
        Language("th", "ไทย"),
    )

    /**
     * Applies [tag] to the launcher process. Empty resets to the system language. Safe to call on
     * any API level; below Android 13 it only records the value without effect.
     */
    @JvmStatic
    fun applyLocale(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.w(TAG, "per-app languages need Android 13")
            return
        }
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: run {
            Log.w(TAG, "no system locale service")
            return
        }
        val locales = if (tag.isBlank()) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(tag)
        }
        runCatching { localeManager.applicationLocales = locales }
            .onSuccess { Log.i(TAG, "locales set to $tag") }
            .onFailure { Log.w(TAG, "setApplicationLocales rejected", it) }
    }

    /** Re-applies the stored language, used if the launcher ever needs to sync after a restore. */
    fun applyStoredLocale(context: Context) {
        val tag = PreferenceManager2.getInstance(context).appLanguage.firstBlocking()
        applyLocale(context, tag)
    }
}

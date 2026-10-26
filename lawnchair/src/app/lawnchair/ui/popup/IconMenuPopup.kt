package app.lawnchair.ui.popup

import android.content.Context
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.R
import com.android.launcher3.popup.SystemShortcut
import com.patrykmichalik.opto.core.firstBlocking

/**
 * Which rows the long press menu of an app icon shows, and in which order.
 *
 * The menu is assembled by PopupContainerWithArrow.showForIcon, which turns the stream of
 * SystemShortcut.Factory from Launcher.getSupportedShortcuts into a list and pairs it with the
 * number of app provided deep shortcuts. applyOrder is the single hook that filters and reorders
 * that list in place and gates the deep shortcut count, mirroring the shipped PopupContainerWithArrow
 * patch. An entry is identified by the simple name of its SystemShortcut subclass, which the APK
 * keeps unobfuscated; the app provided shortcuts have no stable name, so they are gated as one
 * group under the id Deep.
 *
 * The stored string is empty until the user edits the setting, which means "exactly what upstream
 * ships": an untouched install is reordered by nothing and shows every row.
 */
object IconMenuPopup {

    /** Group id of the shortcuts the app itself contributes; gated as one whole. */
    const val DEEP = "Deep"

    /** The ids the settings screen offers. Anything else the launcher adds stays untouched. */
    private val KNOWN = listOf("AppInfo", "Widgets", "Customize", "PauseApps", "UnInstall", DEEP)

    /** The order an untouched menu would be shown in, which is what an empty setting means. */
    val DEFAULT_ORDER = listOf(
        LauncherOptionPopupItem("AppInfo", true),
        LauncherOptionPopupItem("Widgets", true),
        LauncherOptionPopupItem("Customize", true),
        LauncherOptionPopupItem("PauseApps", true),
        LauncherOptionPopupItem("UnInstall", true),
        LauncherOptionPopupItem(DEEP, true),
    )

    fun getMetadataForOption(identifier: String): LauncherOptionMetadata = when (identifier) {
        "AppInfo" -> LauncherOptionMetadata(R.string.app_info_drop_target_label, R.drawable.ic_info_no_shadow)
        "Widgets" -> LauncherOptionMetadata(R.string.widget_button_text, R.drawable.ic_widget)
        "Customize" -> LauncherOptionMetadata(R.string.action_customize, R.drawable.ic_edit)
        "PauseApps" -> LauncherOptionMetadata(R.string.paused_apps_drop_target_label, R.drawable.ic_hourglass_top)
        "UnInstall" -> LauncherOptionMetadata(R.string.uninstall_drop_target_label, R.drawable.ic_uninstall_no_shadow)
        DEEP -> LauncherOptionMetadata(R.string.icon_menu_deep_label, R.drawable.ic_deep_shortcuts)
        else -> LauncherOptionMetadata(R.string.app_label, R.drawable.ic_widget)
    }

    /** The stored order, or the default one when the user never changed it. */
    @JvmStatic
    fun order(context: Context): List<LauncherOptionPopupItem> {
        val raw = PreferenceManager2.getInstance(context).iconMenuOrder.firstBlocking()
        if (raw.isBlank()) return DEFAULT_ORDER
        val stored = raw.toLauncherOptions().filter { it.identifier.isNotBlank() }
        // A stored order that lost an entry must not hide it from the settings screen either.
        val present = stored.map { it.identifier }.toSet()
        return stored + DEFAULT_ORDER.filter { it.identifier !in present }
    }

    /** True when the entry named here is switched off. Used by the widget live-update hook. */
    @JvmStatic
    fun isEntryHidden(context: Context, id: String): Boolean {
        val raw = PreferenceManager2.getInstance(context).iconMenuOrder.firstBlocking()
        if (raw.isBlank()) return false
        return order(context).firstOrNull { it.identifier == id }?.let { !it.isEnabled } ?: false
    }

    /**
     * Drops the switched-off known entries from the list and puts the rest in the stored order.
     * Unknown entries are kept, never hidden, and pushed to the end in the order they arrived.
     * Returns the (possibly zeroed) deep shortcut count. The list is the ArrayList that
     * Collectors.toList() gave the caller, so it is safe to rewrite in place.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun applyOrder(context: Context, shortcutsRaw: MutableList<*>, deepCount: Int): Int {
        // The Java caller holds a raw List<SystemShortcut>; bind it through the star projection
        // so the call site needs no generics rewrite, then treat the elements as shortcuts.
        val shortcuts = shortcutsRaw as MutableList<SystemShortcut<*>>
        val raw = PreferenceManager2.getInstance(context).iconMenuOrder.firstBlocking()
        if (raw.isBlank()) return deepCount
        val entries = order(context)
        val disabled = entries.filter { !it.isEnabled }.mapTo(HashSet()) { it.identifier }
        val rank = entries.withIndex().associate { (i, e) -> e.identifier to i }
        val gatedDeep = if (DEEP in disabled) 0 else deepCount

        val kept = ArrayList<SystemShortcut<*>>(shortcuts.size)
        for (shortcut in shortcuts) {
            val id = idOf(shortcut)
            if (id in KNOWN && id in disabled) continue
            kept.add(shortcut)
        }
        // Stable sort keeps unknown entries (rank null) in their arrival order at the tail.
        kept.sortBy { rank[idOf(it)] ?: Int.MAX_VALUE }

        shortcuts.clear()
        shortcuts.addAll(kept)
        return gatedDeep
    }

    /** The name of an entry, taken from the SystemShortcut subclass the launcher made. */
    private fun idOf(shortcut: SystemShortcut<*>): String {
        var type: Class<*>? = shortcut.javaClass
        while (type != null) {
            val name = type.simpleName
            if (!name.isNullOrEmpty()) return name
            // An anonymous subclass has no name of its own; the type it extends does.
            type = type.superclass
        }
        return ""
    }
}

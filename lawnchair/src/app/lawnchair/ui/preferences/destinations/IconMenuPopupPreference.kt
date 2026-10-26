package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.popup.IconMenuPopup
import app.lawnchair.ui.popup.LauncherOptionPopupItem
import app.lawnchair.ui.popup.toLauncherOptions
import app.lawnchair.ui.popup.toOptionOrderString
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroupHeading
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.reorderable.ReorderableDragHandle
import app.lawnchair.ui.preferences.components.reorderable.ReorderablePreferenceGroup
import app.lawnchair.ui.preferences.components.reorderable.ReorderableSwitchPreference
import app.lawnchair.ui.preferences.navigation.HomeScreenIconMenuEditor
import com.android.launcher3.R

/** The entry on the home screen settings that opens the icon menu editor. */
@Composable
fun IconMenuPopupPreferenceItem(
    modifier: Modifier = Modifier,
) {
    val navController = LocalNavController.current
    ClickablePreference(
        modifier = modifier,
        label = stringResource(R.string.edit_icon_menu_items),
        onClick = {
            navController.navigate(HomeScreenIconMenuEditor)
        },
    )
}

@Composable
private fun displayOrder(raw: String): List<LauncherOptionPopupItem> {
    if (raw.isBlank()) return IconMenuPopup.DEFAULT_ORDER
    val stored = raw.toLauncherOptions().filter { it.identifier.isNotBlank() }
    val present = stored.map { it.identifier }.toSet()
    return stored + IconMenuPopup.DEFAULT_ORDER.filter { it.identifier !in present }
}

@Composable
fun IconMenuPopupPreference(
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    val optionsPref = prefs2.iconMenuOrder.getAdapter()

    var optionsList = displayOrder(optionsPref.state.value)

    PreferenceLayout(
        label = stringResource(R.string.icon_menu_editor),
        backArrowVisible = !LocalIsExpandedScreen.current,
        isExpandedScreen = true,
        modifier = modifier,
    ) {
        Column {
            PreferenceGroupHeading(stringResource(R.string.icon_menu_editor_description))
        }

        ReorderablePreferenceGroup(
            label = stringResource(R.string.icon_menu_items),
            items = optionsList,
            defaultList = IconMenuPopup.DEFAULT_ORDER,
            onOrderChange = {
                optionsList = it
                optionsPref.onChange(it.toOptionOrderString())
            },
        ) { item, index, _, onDraggingChange ->
            val metadata = IconMenuPopup.getMetadataForOption(item.identifier)
            val interactionSource = remember { MutableInteractionSource() }

            ReorderableSwitchPreference(
                label = stringResource(metadata.label),
                checked = item.isEnabled,
                onCheckedChange = {
                    optionsList[index].isEnabled = it
                    optionsPref.onChange(optionsList.toOptionOrderString())
                },
                dragHandle = {
                    ReorderableDragHandle(
                        scope = this,
                        interactionSource = interactionSource,
                        isDraggable = true,
                        onDragStop = {
                            onDraggingChange(false)
                        },
                    )
                },
                enabled = true,
                interactionSource = interactionSource,
            )
        }
    }
}

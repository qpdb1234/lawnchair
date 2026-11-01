package app.lawnchair.ui.popup

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Toast
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings
import com.android.launcher3.R
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderGridOrganizer
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.popup.SystemShortcut
import com.patrykmichalik.opto.core.firstBlocking
import java.text.Collator

/**
 * Moves the long pressed icon into one of the folders that already live on the home screen.
 *
 * The row is offered by [ADD_TO_FOLDER] and only exists while there is something to do: the icon
 * has to lie loose on a page or in the dock — an icon that already belongs to a folder is not
 * offered twice — and at least one folder has to be bound on the workspace. The click closes the
 * menu and opens a single choice list of those folders; the OK button stays dead until one entry is
 * picked, so the move happens exactly when the user confirms it.
 *
 * The move itself is the model half of what dropping an icon onto a folder does. A [WorkspaceItemInfo]
 * that lives inside a folder keeps its own database row whose container is the folder's id, so the
 * icon is unmounted from its page, appended to the folder's contents at the next rank, and that rank
 * is persisted. Binding a folder always creates its [Folder] view (ItemInflater goes through
 * FolderIcon.inflateFolderAndIcon), and that view is registered as a listener even while closed, so
 * [FolderInfo.add] reaches Folder.onAdd, which is where the row is written. The write below repeats
 * the same values for the case where no such listener is around, which content-wise cannot diverge:
 * the rank and the cell it maps to are settled here first.
 */
class AddToFolderShortcut {

    companion object {
        private const val TAG = "AddToFolderShortcut"

        @JvmStatic
        val ADD_TO_FOLDER = SystemShortcut.Factory { activity: LawnchairLauncher, itemInfo: ItemInfo, originalView: View ->
            // Locking the home screen hides every row that would rearrange it.
            if (PreferenceManager2.getInstance(activity).lockHomeScreen.firstBlocking()) return@Factory null
            if (!isLooseWorkspaceIcon(itemInfo)) return@Factory null
            if (collectFolders(activity).isEmpty()) return@Factory null
            AddToFolder(activity, itemInfo, originalView)
        }

        /** True for an icon lying directly on a page or in the dock that a folder can hold. */
        private fun isLooseWorkspaceIcon(itemInfo: ItemInfo): Boolean {
            if (itemInfo !is WorkspaceItemInfo) return false
            if (!Folder.willAccept(itemInfo)) return false
            return itemInfo.container == LauncherSettings.Favorites.CONTAINER_DESKTOP ||
                    itemInfo.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT
        }

        /** Every folder currently bound on the workspace, the dock included. */
        private fun collectFolders(launcher: Launcher): MutableList<FolderInfo> {
            val folders = ArrayList<FolderInfo>()
            launcher.workspace.mapOverItems { info, _ ->
                if (info is FolderInfo) folders.add(info)
                // Always false: the scan stops on the first match, and every folder is wanted.
                false
            }
            return folders
        }

        /** The name the user gave the folder, or a placeholder while it has none. */
        private fun labelFor(context: Context, folder: FolderInfo): CharSequence {
            val title = folder.title
            return if (title.isNullOrEmpty()) {
                context.getString(R.string.add_to_folder_unnamed)
            } else {
                title
            }
        }
    }

    class AddToFolder(
        target: LawnchairLauncher,
        itemInfo: ItemInfo,
        originalView: View,
    ) : SystemShortcut<LawnchairLauncher>(
        R.drawable.ic_folder,
        R.string.add_to_folder_label,
        target,
        itemInfo,
        originalView,
    ) {

        override fun onClick(view: View) {
            val launcher = mTarget
            AbstractFloatingView.closeAllOpenViews(launcher)
            val item = mItemInfo as? WorkspaceItemInfo ?: return

            // The menu was assembled before this click; scan again so folders created or emptied
            // in the meantime are not offered under a stale name.
            val folders = collectFolders(launcher)
            if (folders.isEmpty()) {
                Toast.makeText(launcher, R.string.add_to_folder_empty, Toast.LENGTH_SHORT).show()
                return
            }
            val collator = Collator.getInstance()
            folders.sortWith { a, b ->
                collator.compare(labelFor(launcher, a).toString(), labelFor(launcher, b).toString())
            }

            var selected = -1
            val dialog = AlertDialog.Builder(launcher)
                .setTitle(R.string.add_to_folder_dialog_title)
                .setSingleChoiceItems(
                    folders.map { labelFor(launcher, it) }.toTypedArray(),
                    -1,
                ) { d, which ->
                    selected = which
                    (d as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                }
                .setPositiveButton(R.string.folder_add_ok) { _, _ ->
                    if (selected >= 0) addToFolder(launcher, item, folders[selected])
                }
                .setNegativeButton(R.string.folder_add_cancel, null)
                .create()
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        }

        private fun addToFolder(launcher: Launcher, item: WorkspaceItemInfo, folder: FolderInfo) {
            try {
                val workspace = launcher.workspace
                val icon = workspace.getHomescreenIconByItemId(item.id) ?: return
                // Unmount the icon before it becomes a member: once the item points at the folder,
                // the page it used to sit on no longer knows where it was.
                workspace.removeWorkspaceItem(icon)

                val rank = folder.getContents().size
                folder.add(item, rank, true)
                // Folder.onAdd has usually persisted this already; repeating it costs one update
                // and keeps the row correct when no Folder view is listening.
                FolderGridOrganizer.createFolderGridOrganizer(launcher.deviceProfile)
                    .setFolderInfo(folder)
                    .updateRankAndPos(item, rank)
                launcher.modelWriter.addOrMoveItemInDatabase(item, folder.id, 0, item.cellX, item.cellY)
                workspace.requestLayout()

                Toast.makeText(
                    launcher,
                    launcher.getString(R.string.add_to_folder_done, labelFor(launcher, folder)),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (t: Throwable) {
                // A failed move must leave the icon where it was rather than half moved.
                Log.w(TAG, "failed to add the icon to a folder", t)
                Toast.makeText(launcher, R.string.add_to_folder_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}

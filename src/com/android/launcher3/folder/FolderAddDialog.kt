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

package com.android.launcher3.folder

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.android.launcher3.CellLayout
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings
import com.android.launcher3.R
import com.android.launcher3.model.ModelWriter
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import java.text.Collator

/**
 * Folder membership editor for a desktop folder.
 *
 * An ADD button in the folder footer opens a grid of every launchable app. Apps that are already
 * members of this folder are shown pre-checked, making the dialog a full membership editor rather
 * than a one-way picker. Confirming runs two phases (mirroring the mod's ConfirmListener):
 *
 *  1. add   - every checked app that is not already a member is appended with [FolderInfo.add],
 *             which the open [Folder] persists through its `onAdd` listener.
 *  2. sync  - the resulting membership (`selComps`) is reconciled against the rest of the launcher:
 *             [removeDesktopDuplicates] deletes loose desktop copies, [removeFolderDuplicates]
 *             deletes copies living inside other folders, and [removeUnchecked] moves members that
 *             were unchecked back onto the current desktop page.
 *
 * The mod's file logging / watchdog / background class-preload scaffolding was purely reverse-
 * engineering instrumentation and is intentionally not carried over; the move-unchecked step runs
 * synchronously here instead of through the mod's two-phase StepMover.
 */
object FolderAddDialog {
    private const val TAG = "FolderAddDialog"

    @JvmStatic
    fun show(folder: Folder) {
        val context = folder.context
        try {
            val launcher = Launcher.getLauncher(context) ?: return
            val info = folder.info

            // Membership keys, used to pre-check the grid.
            val preselect = HashSet<String>()
            for (content in info.getContents()) {
                selKey(content)?.let { preselect.add(it) }
            }

            // Apps already filed inside some *other* folder are not offered here (avoids a single
            // app appearing in two folders at once).
            val exclude = collectOtherFolderKeys(launcher, info)

            val seen = HashSet<String>()
            val list = ArrayList<AppInfo>()
            val apps = launcher.appsView.appsStore.apps
            for (app in apps) {
                if (app == null) {
                    continue
                }
                val key = selKey(app) ?: continue
                if (exclude.contains(key)) {
                    continue
                }
                if (seen.add(key)) {
                    list.add(app)
                }
            }

            if (list.isEmpty()) {
                Toast.makeText(context, R.string.folder_add_empty, Toast.LENGTH_SHORT).show()
                return
            }

            val collator = Collator.getInstance()
            list.sortWith { a, b ->
                collator.compare(a.title?.toString() ?: "", b.title?.toString() ?: "")
            }

            val adapter = AppsAdapter(context, list, preselect)
            val density = context.resources.displayMetrics.density
            val grid = GridView(context).apply {
                numColumns = 4
                this.adapter = adapter
                horizontalSpacing = (8 * density).toInt()
                verticalSpacing = (8 * density).toInt()
                setOnItemClickListener { _, _, position, _ -> adapter.toggle(position) }
            }

            AlertDialog.Builder(context)
                .setTitle(R.string.folder_add_dialog_title)
                .setView(grid)
                .setPositiveButton(R.string.folder_add_ok) { dialog, _ ->
                    dialog.dismiss()
                    onConfirm(launcher, folder, info, list, adapter.selectedKeys())
                }
                .setNegativeButton(R.string.folder_add_cancel, null)
                .show()
        } catch (t: Throwable) {
            // Never let the editor take the launcher down with it.
            Log.w(TAG, "failed to show add-apps dialog", t)
            Toast.makeText(context, "Add failed: $t", Toast.LENGTH_LONG).show()
        }
    }

    /** Keys of apps that live inside any folder other than [self]. */
    private fun collectOtherFolderKeys(launcher: Launcher, self: FolderInfo): HashSet<String> {
        val exclude = HashSet<String>()
        try {
            val workspace = launcher.workspace
            for (p in 0 until workspace.childCount) {
                val page = workspace.getChildAt(p) as? CellLayout ?: continue
                val container = page.shortcutsAndWidgets ?: continue
                for (i in 0 until container.childCount) {
                    val fi = container.getChildAt(i)?.tag as? FolderInfo ?: continue
                    if (fi !== self) {
                        for (item in fi.getContents()) {
                            selKey(item)?.let { exclude.add(it) }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "failed to scan other folders", e)
        }
        return exclude
    }

    private fun onConfirm(
        launcher: Launcher,
        folder: Folder,
        info: FolderInfo,
        list: List<AppInfo>,
        selectedKeys: Set<String>,
    ) {
        val context = folder.context
        try {
            val folderId = info.id

            // ---- Phase 1: ensure every checked app is a member ----
            for (app in list) {
                val key = selKey(app) ?: continue
                if (!selectedKeys.contains(key)) {
                    continue
                }
                var present = false
                for (existing in info.getContents()) {
                    if (selKey(existing) == key) {
                        present = true
                        break
                    }
                }
                if (!present) {
                    val item = app.makeWorkspaceItem(context)
                    item.container = folderId
                    info.add(item, info.getContents().size, true)
                }
            }

            // ---- Phase 2: reconcile the resulting membership with the rest of the launcher ----
            // selComps = the keys that should end up inside this folder and nowhere else.
            val selComps = HashSet<String>()
            val dialogKeys = HashSet<String>()
            for (app in list) {
                val key = selKey(app) ?: continue
                dialogKeys.add(key)
                if (selectedKeys.contains(key)) {
                    selComps.add(key)
                }
            }
            // Implicit-keep: folder members the dialog could never present (no resolvable
            // component, hidden, etc.) have no checkbox and must not be swept back to the desktop.
            for (existing in info.getContents()) {
                val key = selKey(existing) ?: continue
                if (!dialogKeys.contains(key)) {
                    selComps.add(key)
                }
            }

            removeDesktopDuplicates(launcher, selComps)
            removeFolderDuplicates(launcher, folderId, selComps)
            removeUnchecked(launcher, info, selComps)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to reconcile folder membership", t)
            Toast.makeText(context, "Update failed: $t", Toast.LENGTH_LONG).show()
        }
    }

    /** Delete loose desktop copies of any app that is now a folder member. */
    private fun removeDesktopDuplicates(launcher: Launcher, selComps: Set<String>) {
        val workspace = launcher.workspace
        val writer = launcher.modelWriter
        for (p in 0 until workspace.childCount) {
            val page = workspace.getChildAt(p) as? CellLayout ?: continue
            val container = page.shortcutsAndWidgets ?: continue
            for (i in container.childCount - 1 downTo 0) {
                try {
                    val child = container.getChildAt(i) ?: continue
                    val wii = child.tag as? WorkspaceItemInfo ?: continue
                    val key = selKey(wii)
                    if (key != null && selComps.contains(key)) {
                        writer.deleteItemFromDatabase(wii, "folder_add_cleanup")
                        page.removeView(child)
                    }
                } catch (e: Throwable) {
                    // A single bad icon must not abort the whole sweep (partial delete is not
                    // transactional); log and keep going.
                    Log.w(TAG, "desktop dedup item failed", e)
                }
            }
        }
    }

    /** Delete copies of folder members that live inside other folders (cross-folder dedup). */
    private fun removeFolderDuplicates(launcher: Launcher, folderId: Int, selComps: Set<String>) {
        val workspace = launcher.workspace
        val writer = launcher.modelWriter
        for (p in 0 until workspace.childCount) {
            val page = workspace.getChildAt(p) as? CellLayout ?: continue
            val container = page.shortcutsAndWidgets ?: continue
            for (i in 0 until container.childCount) {
                val fi = container.getChildAt(i)?.tag as? FolderInfo ?: continue
                if (fi.id == folderId) {
                    continue
                }
                val toRemove = ArrayList<ItemInfo>()
                for (item in fi.getContents()) {
                    val key = selKey(item)
                    if (key != null && selComps.contains(key)) {
                        toRemove.add(item)
                    }
                }
                if (toRemove.isEmpty()) {
                    continue
                }
                for (item in toRemove) {
                    writer.deleteItemFromDatabase(item, "folder_add_cross")
                }
                fi.removeAll(toRemove, true)
            }
        }
    }

    /** Move members the user unchecked back onto the first free cell of the current page. */
    private fun removeUnchecked(launcher: Launcher, info: FolderInfo, selComps: Set<String>) {
        val workspace = launcher.workspace
        val writer = launcher.modelWriter
        val inflater = launcher.itemInflater ?: return

        val currentPage = workspace.currentPage
        val currentLayout = workspace.getChildAt(currentPage) as? CellLayout ?: return
        val screenId = workspace.getScreenIdForPageIndex(currentPage)

        val unchecked = ArrayList<ItemInfo>()
        for (item in info.getContents()) {
            val key = selKey(item)
            // Absent from selComps == the user unchecked a member that could be presented.
            if (key != null && !selComps.contains(key)) {
                unchecked.add(item)
            }
        }
        if (unchecked.isEmpty()) {
            return
        }

        val grid = currentLayout.occupied ?: return
        val moved = ArrayList<ItemInfo>()
        for (item in unchecked) {
            val cell = IntArray(2)
            if (grid.findVacantCell(cell, 1, 1)) {
                // Reserve so the next moved icon gets a different cell.
                grid.markCells(cell[0], cell[1], 1, 1, true)
                item.container = LauncherSettings.Favorites.CONTAINER_DESKTOP
                item.screenId = screenId
                item.cellX = cell[0]
                item.cellY = cell[1]
                item.spanX = 1
                item.spanY = 1
                moved.add(item)
                writer.moveItemInDatabase(
                    item,
                    LauncherSettings.Favorites.CONTAINER_DESKTOP,
                    screenId,
                    cell[0],
                    cell[1],
                )
            }
            // No free cell: leave the icon in the folder rather than dropping it.
        }

        info.removeAll(moved, true)

        // ModelWriter only notifies *other* callbacks (it skips the owning Launcher), so mount the
        // relocated icons on the workspace ourselves, the same way bind-inflation does.
        for (item in moved) {
            try {
                val view = inflater.inflateItem(item, writer) ?: continue
                workspace.addInScreenFromBind(view, item)
            } catch (e: Throwable) {
                Log.w(TAG, "failed to mount moved icon", e)
            }
        }
        workspace.requestLayout()
    }

    /** Membership key: (target component, user serial). */
    private fun selKey(info: ItemInfo): String? {
        val cn = info.targetComponent ?: return null
        return cn.flattenToString() + "\u0001" + info.user
    }

    /** GridView adapter: icon + label per app, a corner check badge while selected. */
    private class AppsAdapter(
        private val context: Context,
        private val apps: List<AppInfo>,
        initialSelected: Set<String>,
    ) : BaseAdapter() {

        private val selected = HashSet(initialSelected)

        fun toggle(position: Int) {
            val key = FolderAddDialog.selKey(apps[position]) ?: return
            if (!selected.remove(key)) {
                selected.add(key)
            }
            notifyDataSetChanged()
        }

        fun selectedKeys(): Set<String> = HashSet(selected)

        override fun getCount(): Int = apps.size

        override fun getItem(position: Int): Any = apps[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val density = context.resources.displayMetrics.density
            val iconSize = (50 * density).toInt()
            val padding = (8 * density).toInt()
            val app = apps[position]
            val isSelected = FolderAddDialog.selKey(app)?.let { selected.contains(it) } ?: false

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(padding, padding, padding, padding)
            }

            val frame = FrameLayout(context)
            val icon = ImageView(context).apply {
                setImageDrawable(app.newIcon(context))
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize)
            }
            frame.addView(icon)

            if (isSelected) {
                val badgeSize = (14 * density).toInt()
                val check = TextView(context).apply {
                    text = "\u2713"
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFF1E88E5.toInt())
                    }
                    layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                        gravity = Gravity.TOP or Gravity.END
                    }
                }
                frame.addView(check)
            }

            frame.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            root.addView(frame)

            val label = TextView(context).apply {
                text = app.title
                setTextColor(0xFF000000.toInt())
                gravity = Gravity.CENTER_HORIZONTAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                isSingleLine = true
            }
            root.addView(label)

            root.setBackgroundColor(if (isSelected) 0x30FFFFFF else 0x00000000)
            return root
        }
    }
}

/*
 * Copyright (C) 2024 Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.model

import android.content.Context
import android.util.Log
import android.util.LongSparseArray
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.ContentWriter
import com.android.launcher3.util.GridOccupancy
import com.android.launcher3.util.IntArray
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Flattens the entire app drawer onto the workspace ("no drawer" mode).
 *
 * Every app that is not already present on the workspace (nor inside a folder / hotseat) is
 * placed into the first vacant grid cell across existing pages, creating new pages as needed.
 * Rows are written straight to the favorites database and mirrored into the background data
 * model, then bound on the UI thread through [BgDataModel.Callbacks.bindAppsAdded].
 *
 * The operation is idempotent: [alreadyOnWorkspace] skips anything already placed, so repeated
 * loads only add genuinely new apps. Apps listed in the hiddenHomeApps preference ("hide from home
 * screen") are never flattened, and copies that are already on the workspace get stripped instead.
 * The launcher's own icon is likewise never flattened, and stale rows pointing at it written before
 * that filter existed (or migrated across a package-name change) get stripped by
 * [removeSelfPackageApps] so it can never linger on the home screen forever.
 */
object AllAppsToWorkspace {

    private const val TAG = "AllAppsToWorkspace"

    /** Result of a single placement pass. */
    private class Placement {
        val newScreens = IntArray()
        val addedItems = ArrayList<WorkspaceItemInfo>()

        fun isEmpty(): Boolean = addedItems.isEmpty()

        fun copyNewScreens(): IntArray {
            val copy = IntArray()
            for (i in 0 until newScreens.size()) {
                copy.add(newScreens.get(i))
            }
            return copy
        }
    }

    /** Items stripped by a hidden-home-apps pass, together with their database ids. */
    private class Removal {
        val items = ArrayList<ItemInfo>()
        val ids = ArrayList<Long>()

        fun isEmpty(): Boolean = items.isEmpty()
    }

    /**
     * Returns true when [item] already lives somewhere in the model (workspace, hotseat or any
     * folder). All bound items - including folder children - are present in [itemsIdMap], so a
     * single traversal is enough. Items without a target component are treated as already placed
     * so they are never flattened.
     */
    private fun alreadyOnWorkspace(dataModel: BgDataModel, item: WorkspaceItemInfo): Boolean {
        val target = item.targetComponent ?: return true
        synchronized(dataModel) {
            for (info in dataModel.itemsIdMap) {
                if (info is WorkspaceItemInfo &&
                    info.user == item.user &&
                    info.targetComponent == target
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** Returns the occupancy grid for [screenId], lazily allocating one of [countX]x[countY]. */
    private fun gridFor(
        occupancy: LongSparseArray<GridOccupancy>,
        screenId: Int,
        countX: Int,
        countY: Int,
    ): GridOccupancy {
        var grid = occupancy[screenId.toLong()]
        if (grid == null) {
            grid = GridOccupancy(countX, countY)
            occupancy.put(screenId.toLong(), grid)
        }
        return grid
    }

    /**
     * Places all drawer apps onto the workspace, persisting new rows to the database.
     * Must be executed on the model thread with a fully loaded data model.
     */
    private fun placeAllAppsOnWorkspace(
        app: LauncherAppState,
        dataModel: BgDataModel,
        apps: AllAppsList,
    ): Placement {
        val placement = Placement()
        if (apps.data.isEmpty()) {
            return placement
        }

        val context: Context = app.context
        val dbController = app.model.modelDbController
        val idp = app.invariantDeviceProfile
        val countX = idp.numColumns
        val countY = idp.numRows
        val occupancy = LongSparseArray<GridOccupancy>()

        val screens: IntArray
        synchronized(dataModel) {
            screens = dataModel.collectWorkspaceScreens()
            for (info in dataModel.itemsIdMap) {
                if (info.container != Favorites.CONTAINER_DESKTOP) {
                    continue
                }
                gridFor(occupancy, info.screenId, countX, countY).markCells(info, true)
            }
        }

        val selfPackage = context.packageName
        val hiddenHomeApps = runBlocking {
            PreferenceManager2.getInstance(context).hiddenHomeApps.get().first()
        }
        val xy = kotlin.IntArray(2)
        for (appInfo in ArrayList(apps.data)) {
            val component = appInfo?.componentName ?: continue
            // Never flatten the launcher's own icon onto the workspace. In no-drawer mode every
            // drawer app is mirrored to the home screen, which would otherwise drop a redundant
            // "Lawnchair" icon among the very apps it launches.
            if (component.packageName == selfPackage) {
                continue
            }
            val target = appInfo.targetComponent ?: continue
            // "Hide from home screen" selections stay off the workspace entirely.
            if (hiddenHomeApps.contains(ComponentKey(target, appInfo.user).toString())) {
                continue
            }
            val item = WorkspaceItemInfo(appInfo)
            if (alreadyOnWorkspace(dataModel, item)) {
                continue
            }

            val spanX = Math.max(1, item.spanX)
            val spanY = Math.max(1, item.spanY)
            var screenId = -1

            // Look for a vacancy on existing pages, left to right.
            for (i in 0 until screens.size()) {
                val candidate = screens.get(i)
                if (gridFor(occupancy, candidate, countX, countY).findVacantCell(xy, spanX, spanY)) {
                    screenId = candidate
                    break
                }
            }

            // No room anywhere: mint a brand new page.
            if (screenId < 0) {
                screenId = dbController.newScreenId
                screens.add(screenId)
                placement.newScreens.add(screenId)
                val grid = gridFor(occupancy, screenId, countX, countY)
                if (!grid.findVacantCell(xy, spanX, spanY)) {
                    Log.e(TAG, "Brand new screen has no room, skipping $appInfo")
                    continue
                }
            }

            gridFor(occupancy, screenId, countX, countY)
                .markCells(xy[0], xy[1], spanX, spanY, true)

            item.container = Favorites.CONTAINER_DESKTOP
            item.screenId = screenId
            item.cellX = xy[0]
            item.cellY = xy[1]
            item.id = dbController.generateNewItemId()

            val writer = ContentWriter(context)
            item.onAddToDatabase(writer)
            writer.put(Favorites._ID, item.id)
            dbController.insert(Favorites.TABLE_NAME, writer.getValues(context))
            dataModel.addItem(context, item, true)

            placement.addedItems.add(item)
            Log.d(
                TAG,
                "Placed " + item.targetComponent + " on screen " + screenId +
                    " at " + item.cellX + "," + item.cellY,
            )
        }

        Log.d(
            TAG,
            "Placed " + placement.addedItems.size + " apps on the workspace" +
                (if (placement.newScreens.size() > 0) ", created " + placement.newScreens.size() + " new pages" else ""),
        )
        return placement
    }

    /**
     * Strips app shortcuts whose component key is listed in the hiddenHomeApps preference from the
     * workspace, hotseat and folders, deleting their favorites rows. Runs before the placement
     * pass, so the freed cells are reused by the apps that remain visible.
     */
    private fun removeHiddenHomeApps(
        app: LauncherAppState,
        dataModel: BgDataModel,
    ): Removal {
        val removal = Removal()
        val context: Context = app.context
        val hiddenKeys = runBlocking {
            PreferenceManager2.getInstance(context).hiddenHomeApps.get().first()
        }
        if (hiddenKeys.isEmpty()) {
            return removal
        }
        val dbController = app.model.modelDbController
        synchronized(dataModel) {
            for (info in dataModel.itemsIdMap.toList()) {
                if (info.id < 0 || info.itemType != ITEM_TYPE_APPLICATION) {
                    continue
                }
                val key = info.getComponentKey()?.toString() ?: continue
                if (!hiddenKeys.contains(key)) {
                    continue
                }
                dbController.delete(Favorites.TABLE_NAME, "_id=?", arrayOf(info.id.toString()))
                dataModel.removeItem(context, info)
                removal.items.add(info)
                removal.ids.add(info.id.toLong())
            }
        }
        if (!removal.isEmpty()) {
            Log.d(TAG, "Stripped " + removal.items.size + " hidden apps from the workspace")
        }
        return removal
    }

    /**
     * Strips leftover copies of the launcher's own icon from the workspace, hotseat and folders.
     * The placement pass never flattens selfPackage, but favorites rows that point at the launcher
     * itself and predate that filter would otherwise sit on the home screen forever, since nothing
     * removes them. Runs before the placement pass, so the freed cells are reused immediately.
     */
    private fun removeSelfPackageApps(
        app: LauncherAppState,
        dataModel: BgDataModel,
    ): Removal {
        val removal = Removal()
        val context: Context = app.context
        val selfPackage = context.packageName
        val dbController = app.model.modelDbController
        synchronized(dataModel) {
            for (info in dataModel.itemsIdMap.toList()) {
                if (info.id < 0 || info.itemType != ITEM_TYPE_APPLICATION) {
                    continue
                }
                if (info.targetComponent?.packageName != selfPackage) {
                    continue
                }
                dbController.delete(Favorites.TABLE_NAME, "_id=?", arrayOf(info.id.toString()))
                dataModel.removeItem(context, info)
                removal.items.add(info)
                removal.ids.add(info.id.toLong())
            }
        }
        if (!removal.isEmpty()) {
            Log.d(TAG, "Stripped " + removal.items.size + " stale self-package icons from the workspace")
        }
        return removal
    }

    /**
     * Schedules a flatten pass on the model thread. Safe to call repeatedly; guarded internally by
     * [LauncherModel.isModelLoaded] and de-duplicated by [alreadyOnWorkspace].
     */
    @JvmStatic
    fun enqueue(app: LauncherAppState) {
        try {
            app.model.enqueueModelUpdateTask { controller, dataModel, appsList ->
                try {
                    // Only flatten while no-drawer mode is active. Callers (the initial bind pass and
                    // the package-add model task) may invoke this unconditionally; with the drawer
                    // enabled, newly installed apps must stay in the drawer instead of being mirrored
                    // onto the workspace, so bail out before touching the database.
                    val noDrawer = runBlocking {
                        PreferenceManager2.getInstance(controller.app.context)
                            .noDrawer.get().first()
                    }
                    if (!noDrawer) {
                        return@enqueueModelUpdateTask
                    }
                    val removal = removeHiddenHomeApps(controller.app, dataModel)
                    val selfRemoval = removeSelfPackageApps(controller.app, dataModel)
                    if (!removal.isEmpty() || !selfRemoval.isEmpty()) {
                        val ids = ArrayList<Long>(removal.ids.size + selfRemoval.ids.size)
                        ids.addAll(removal.ids)
                        ids.addAll(selfRemoval.ids)
                        controller.scheduleCallbackTask { callbacks ->
                            callbacks.bindWorkspaceComponentsRemoved { info -> ids.contains(info.id.toLong()) }
                        }
                    }
                    val placement = placeAllAppsOnWorkspace(controller.app, dataModel, appsList)
                    if (!placement.isEmpty()) {
                        val newScreens = placement.copyNewScreens()
                        val addAnimated = ArrayList<ItemInfo>(placement.addedItems)
                        val addNotAnimated = ArrayList<ItemInfo>()
                        controller.scheduleCallbackTask { callbacks ->
                            callbacks.bindAppsAdded(newScreens, addNotAnimated, addAnimated)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to flatten apps onto workspace", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to enqueue AllAppsToWorkspace", e)
        }
    }
}

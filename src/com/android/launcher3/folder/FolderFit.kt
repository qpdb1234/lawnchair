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

/**
 * Works out how many columns and rows the content of an opened folder gets, adapted from
 * an earlier home-screen mod.
 *
 * [FolderGridOrganizer.calculateGridSize] shrinks its grid to the number of icons, but only to the
 * closest thing to a square: three icons became 2x2 and left one cell empty, seven became 3x3 and
 * left two, eight became 3x3 and left one. The empty cells read as "a block of background with no
 * icon in it" on a small folder, so this starts from the grids that leave no cell empty at all.
 *
 * Leaving no cell empty is not enough on its own: five icons have exactly one zero-cost grid, a
 * strip of one row and five columns, which reads as wrong in a different way. A shape only keeps
 * its zero-cost grid while it is reasonably square and gives up one cell to buy a squarer outline
 * once it would otherwise be long and thin. Five therefore become 3x2 with a single empty cell,
 * while three stay 3x1 and eight stay 4x2.
 *
 * Called from [FolderGridOrganizer.calculateGridSize], which owns the two fields it replaces. It
 * answers -1 whenever it wants nothing to say - a folder that fills every cell it is allowed, a
 * count that does not make sense, or any trouble at all - and the caller then keeps the grid it
 * worked out itself.
 */
object FolderFit {

    /**
     * How much wider a grid than it is tall may be before it counts as a strip rather than a shape.
     * Two covers 3x1 and 4x2 and rejects 5x1 and 6x1.
     */
    private const val MAX_DIFFERENCE = 2

    /**
     * @param count the number of icons in the folder
     * @param maxX  the widest grid the device profile allows for one folder page
     * @param maxY  the tallest grid the device profile allows for one folder page
     * @return countX in the high 16 bits and countY in the low 16, or -1 to leave the grid the
     *         caller has already worked out alone
     */
    @JvmStatic
    fun tightGrid(count: Int, maxX: Int, maxY: Int): Int {
        if (count < 1 || maxX < 1 || maxY < 1) {
            return -1
        }
        if (count >= maxX * maxY) {
            // Nothing to shave: the folder wants every cell the profile allows, and the pages
            // after the first one are full anyway.
            return -1
        }
        // Grow the number of empty cells the folder may spend, one at a time, and stop at the
        // first budget that yields a shape which is not a strip. A budget of zero is tried first,
        // so a folder that can be filled exactly keeps its empty cell free.
        var fallback = -1
        for (spare in 0..maxX * maxY) {
            var grid = search(count, maxX, maxY, spare, true)
            if (grid < 0) {
                // A profile that is taller than it is wide has no wider-than-tall grid to offer
                // for this count; take the best of whatever there is.
                grid = search(count, maxX, maxY, spare, false)
            }
            if (grid < 0) {
                continue
            }
            if (difference(grid) <= MAX_DIFFERENCE) {
                return grid
            }
            if (fallback < 0) {
                // Every reachable shape is a strip, so the cheapest of them will do. This only
                // happens on a profile that is far from square itself.
                fallback = grid
            }
        }
        return fallback
    }

    /** Columns minus rows of the grid encoded in a [tightGrid] answer, ignoring sign. */
    private fun difference(grid: Int): Int {
        val x = grid ushr 16
        val y = grid and 0xffff
        return if (x > y) x - y else y - x
    }

    /**
     * @param spare    the largest number of empty cells the grid may leave behind
     * @param wideOnly drop the candidates that are taller than they are wide, so that a folder
     *                 grows sideways before it grows downwards
     */
    private fun search(count: Int, maxX: Int, maxY: Int, spare: Int, wideOnly: Boolean): Int {
        var bestX = -1
        var bestY = -1
        var bestWaste = Int.MAX_VALUE
        var bestDiff = Int.MAX_VALUE
        for (y in 1..maxY) {
            for (x in 1..maxX) {
                if (wideOnly && x < y) {
                    continue
                }
                val cells = x * y
                if (cells < count) {
                    continue
                }
                val waste = cells - count
                if (waste > spare) {
                    continue
                }
                val diff = if (x > y) x - y else y - x
                if (diff < bestDiff ||
                    (diff == bestDiff && (waste < bestWaste || (waste == bestWaste && x > bestX)))
                ) {
                    bestDiff = diff
                    bestWaste = waste
                    bestX = x
                    bestY = y
                }
            }
        }
        if (bestX < 1 || bestY < 1) {
            return -1
        }
        return (bestX shl 16) or bestY
    }
}

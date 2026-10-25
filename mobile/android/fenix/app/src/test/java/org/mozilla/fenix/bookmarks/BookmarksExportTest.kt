/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BookmarksExportTest {
    @Test
    fun `exporting closes the menu, then says how many bookmarks were saved or that none could be`() {
        val withMenu = BookmarksState.default.copy(rootMenuShown = true)

        assertFalse(bookmarksReducer(withMenu, ExportAction.ExportFileClicked).rootMenuShown)
        assertEquals(
            BookmarksSnackbarState.Exported(12),
            bookmarksReducer(BookmarksState.default, ExportAction.ExportFinished(12)).bookmarksSnackbarState,
        )
        assertEquals(
            BookmarksSnackbarState.ExportFailed,
            bookmarksReducer(BookmarksState.default, ExportAction.ExportFinished(null)).bookmarksSnackbarState,
        )
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.bookmarks

import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarksFileTest {
    private val titles =
        mapOf("menu" to "Bookmarks Menu", "toolbar" to "Bookmarks Toolbar", "unfiled" to "Other Bookmarks")

    private fun node(
        type: BookmarkNodeType,
        guid: String,
        title: String?,
        url: String? = null,
        children: List<BookmarkNode>? = null,
    ) = BookmarkNode(type, guid, null, null, title, url, 1_700_000_000_123, 1_700_000_500_999, children)

    private fun bookmark(guid: String, title: String?, url: String) = node(BookmarkNodeType.ITEM, guid, title, url)

    private fun folder(guid: String, title: String?, vararg children: BookmarkNode) =
        node(BookmarkNodeType.FOLDER, guid, title, children = children.toList())

    private val tree = folder(
        BookmarkRoot.Root.id,
        "root",
        folder(BookmarkRoot.Menu.id, "menu", bookmark("m1", "Mozilla", "https://www.mozilla.org/")),
        folder(BookmarkRoot.Toolbar.id, "toolbar"),
        folder(
            BookmarkRoot.Unfiled.id,
            "unfiled",
            folder("f2", "Read later", bookmark("u1", "", "https://example.com/later")),
        ),
        folder(
            BookmarkRoot.Mobile.id,
            "mobile",
            bookmark("b1", "Tom & Jerry's \"<best>\"\nepisodes", "https://example.com/?a=1&b=\"2\""),
            node(BookmarkNodeType.SEPARATOR, "s1", null),
            folder("f1", "News", bookmark("b2", "BBC", "https://www.bbc.com/")),
        ),
    )

    @Test
    fun `the file starts like the bookmark files browsers export`() {
        val lines = BookmarksFile.html(tree, titles).lines()

        assertEquals("<!DOCTYPE NETSCAPE-Bookmark-file-1>", lines.first())
        assertTrue("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">" in lines)
        assertEquals(listOf("<TITLE>Bookmarks</TITLE>", "<H1>Bookmarks</H1>", "<DL><p>"), lines.subList(5, 8))
    }

    @Test
    fun `the phone's bookmarks come first, then the computers' folders that have anything`() {
        val lines = BookmarksFile.html(tree, titles).lines()

        assertEquals(
            listOf(
                "    <DT><A HREF=\"https://example.com/?a=1&amp;b=&quot;2&quot;\"$DATES>" +
                    "Tom &amp; Jerry&#39;s &quot;&lt;best&gt;&quot; episodes</A>",
                "    <HR>",
                "    <DT><H3$DATES>News</H3>",
                "    <DL><p>",
                "        <DT><A HREF=\"https://www.bbc.com/\"$DATES>BBC</A>",
                "    </DL><p>",
                "    <DT><H3$DATES>Bookmarks Menu</H3>",
                "    <DL><p>",
                "        <DT><A HREF=\"https://www.mozilla.org/\"$DATES>Mozilla</A>",
                "    </DL><p>",
                "    <DT><H3$DATES UNFILED_BOOKMARKS_FOLDER=\"true\">Other Bookmarks</H3>",
                "    <DL><p>",
                "        <DT><H3$DATES>Read later</H3>",
                "        <DL><p>",
                // A bookmark without a title shows its address, as the bookmarks screen does.
                "            <DT><A HREF=\"https://example.com/later\"$DATES>https://example.com/later</A>",
                "        </DL><p>",
                "    </DL><p>",
                "</DL><p>",
                "",
            ),
            lines.drop(lines.indexOf("<DL><p>") + 1),
        )
    }

    @Test
    fun `the computers' toolbar is marked so browsers can put it back in their toolbar`() {
        val toolbar = folder(BookmarkRoot.Toolbar.id, "toolbar", bookmark("t1", "Docs", "https://docs.example/"))
        val lines = BookmarksFile.html(folder(BookmarkRoot.Root.id, "root", toolbar), titles).lines()

        assertTrue("    <DT><H3$DATES PERSONAL_TOOLBAR_FOLDER=\"true\">Bookmarks Toolbar</H3>" in lines)
    }

    @Test
    fun `the count takes every bookmark in every folder, not folders or separators`() {
        assertEquals(4, BookmarksFile.bookmarkCount(tree))
    }

    private companion object {
        const val DATES = " ADD_DATE=\"1700000000\" LAST_MODIFIED=\"1700000500\""
    }
}

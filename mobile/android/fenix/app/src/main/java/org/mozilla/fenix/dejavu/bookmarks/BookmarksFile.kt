/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.bookmarks

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.BookmarksStorage

/**
 * Bookmarks as the HTML file that Firefox, Chrome, Edge, Safari and most other browsers export and import, in the
 * Netscape bookmark file format they share.
 */
object BookmarksFile {
    const val MIME_TYPE = "text/html"
    const val NAME = "bookmarks.html"

    private const val INDENT = "    "
    private const val MILLIS_PER_SECOND = 1000

    /**
     * Writes every bookmark to the file at [uri], see [html].
     *
     * @return How many bookmarks the file has, or `null` when it could not be written.
     */
    suspend fun export(context: Context, storage: BookmarksStorage, uri: Uri, rootTitles: Map<String, String>): Int? =
        withContext(Dispatchers.IO) {
            runCatching {
                val tree = checkNotNull(storage.getTree(BookmarkRoot.Root.id, recursive = true).getOrThrow())
                checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { writer ->
                    writer.write(html(tree, rootTitles))
                }
                bookmarkCount(tree)
            }.getOrNull()
        }

    /**
     * The file for [tree], every bookmark from its root: the bookmarks of this phone first, then the folders Firefox
     * keeps on computers (Bookmarks Menu, Bookmarks Toolbar and Other Bookmarks) when they have anything, named by
     * [rootTitles] like the bookmarks screen names them.
     */
    fun html(tree: BookmarkNode, rootTitles: Map<String, String>): String = buildString {
        appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        appendLine("<!-- This is an automatically generated file.")
        appendLine("     It will be read and overwritten.")
        appendLine("     DO NOT EDIT! -->")
        appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        appendLine("<TITLE>Bookmarks</TITLE>")
        appendLine("<H1>Bookmarks</H1>")
        appendLine("<DL><p>")
        val (mobile, computers) = tree.children.orEmpty().partition { it.guid == BookmarkRoot.Mobile.id }
        mobile.flatMap { it.children.orEmpty() }.forEach { appendNode(it, INDENT) }
        computers.filterNot { it.children.isNullOrEmpty() }.forEach { root ->
            val title = rootTitles[root.title] ?: root.title.orEmpty()
            appendFolder(root, title, INDENT, rootAttribute(root.guid))
        }
        appendLine("</DL><p>")
    }

    /** How many bookmarks [node] has, itself included, in every folder inside it. */
    fun bookmarkCount(node: BookmarkNode): Int = when (node.type) {
        BookmarkNodeType.ITEM -> if (node.url != null) 1 else 0
        BookmarkNodeType.FOLDER -> node.children.orEmpty().sumOf(::bookmarkCount)
        BookmarkNodeType.SEPARATOR -> 0
    }

    private fun StringBuilder.appendNode(node: BookmarkNode, indent: String) {
        when (node.type) {
            BookmarkNodeType.ITEM -> node.url?.let { url ->
                val title = node.title?.takeIf { it.isNotBlank() } ?: url
                appendLine("$indent<DT><A HREF=\"${escape(url)}\"${dates(node)}>${escape(title)}</A>")
            }
            BookmarkNodeType.FOLDER -> appendFolder(node, node.title.orEmpty(), indent, attribute = "")
            BookmarkNodeType.SEPARATOR -> appendLine("$indent<HR>")
        }
    }

    private fun StringBuilder.appendFolder(folder: BookmarkNode, title: String, indent: String, attribute: String) {
        appendLine("$indent<DT><H3${dates(folder)}$attribute>${escape(title)}</H3>")
        appendLine("$indent<DL><p>")
        folder.children.orEmpty().forEach { appendNode(it, indent + INDENT) }
        appendLine("$indent</DL><p>")
    }

    /** What marks the computers' toolbar and other bookmarks folders, so browsers can put them back in their place. */
    private fun rootAttribute(guid: String): String = when (guid) {
        BookmarkRoot.Toolbar.id -> " PERSONAL_TOOLBAR_FOLDER=\"true\""
        BookmarkRoot.Unfiled.id -> " UNFILED_BOOKMARKS_FOLDER=\"true\""
        else -> ""
    }

    private fun dates(node: BookmarkNode): String =
        listOf("ADD_DATE" to node.dateAdded, "LAST_MODIFIED" to node.lastModified)
            .filter { (_, millis) -> millis > 0 }
            .joinToString("") { (name, millis) -> " $name=\"${millis / MILLIS_PER_SECOND}\"" }

    /** [text] safe inside the file's tags and quotes, on one line, since some browsers read the file line by line. */
    private fun escape(text: String): String = buildString {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                '\n', '\r' -> append(' ')
                else -> append(char)
            }
        }
    }
}

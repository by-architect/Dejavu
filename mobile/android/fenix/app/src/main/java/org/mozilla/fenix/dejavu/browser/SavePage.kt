/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Response
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONObject
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components

/**
 * Saves the shown page into one HTML file in Downloads, the way it shows: the built-in extension in
 * assets/extensions/dejavu-save-page copies the page without its scripts and with its pictures, styles and fonts inside
 * the file, and the file then downloads the way a PDF of the page does. The extension waits in every page for the app
 * to ask, so the pages of every tab are listened to.
 */
object SavePage {
    private const val EXTENSION_ID = "save-page@dejavu"
    private const val EXTENSION_URL = "resource://android/assets/extensions/dejavu-save-page/"
    private const val PORT_NAME = "dejavuSavePage"
    private const val FOLDER = "dejavu_saved_pages"
    private const val TIMEOUT_MS = 120_000L
    private const val HTTP_OK = 200

    private val logger = Logger("SavePage")
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Only used on the main thread, where the extension's messages arrive.
    private var extension: WebExtension? = null
    private val copies = mutableMapOf<String, PageCopy>()

    /** Adds the extension that copies pages to [engine], and listens to the pages of every tab in [store]. */
    fun install(engine: Engine, store: BrowserStore) {
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { installed ->
                extension = installed
                store.flowScoped(dispatcher = Dispatchers.Main) { flow -> listen(flow, installed) }
            },
            onError = { logger.error("Could not install the extension that saves pages", it) },
        )
    }

    private suspend fun listen(flow: Flow<BrowserState>, extension: WebExtension) {
        flow.map { state -> (state.tabs + state.customTabs).mapNotNull { it.engineState.engineSession } }
            .distinctUntilChanged()
            .collect { sessions ->
                sessions.filterNot { extension.hasContentMessageHandler(it, PORT_NAME) }
                    .forEach { extension.registerContentMessageHandler(it, PORT_NAME, Messages) }
            }
    }

    /** Saves the page [tab] shows into Downloads, saying so, or that it cannot. */
    fun save(context: Context, tab: TabSessionState) {
        val appContext = context.applicationContext
        val session = tab.engineState.engineSession
        val port = session?.let { extension?.getConnectedPort(PORT_NAME, it) }
        if (port == null) {
            Toast.makeText(appContext, R.string.dejavu_save_page_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (copies.values.any { it.tabId == tab.id }) return
        val id = UUID.randomUUID().toString()
        copies[id] = PageCopy(appContext, id, tab, session)
        port.postMessage(JSONObject().put("action", "save").put("id", id))
        Toast.makeText(appContext, R.string.dejavu_save_page_saving, Toast.LENGTH_SHORT).show()
        mainHandler.postDelayed({ end(id, saved = false) }, TIMEOUT_MS)
    }

    private object Messages : MessageHandler {
        override fun onPortMessage(message: Any, port: Port) {
            val json = message as? JSONObject ?: return
            val id = json.optString("id")
            val copy = copies[id] ?: return
            when {
                json.has("part") -> copy.add(json.getString("part"))
                json.optBoolean("done") -> end(id, saved = true)
                else -> end(id, saved = false)
            }
        }

        override fun onPortDisconnected(port: Port) {
            copies.values.filter { it.session == port.engineSession }.map { it.id }.forEach { end(it, saved = false) }
        }
    }

    private fun end(id: String, saved: Boolean) {
        val copy = copies.remove(id) ?: return
        val context = copy.context
        context.components.applicationScope.launch(Dispatchers.Main) {
            val file = copy.finish(keep = saved)
            val body = file?.let { withContext(Dispatchers.IO) { openAndRemove(it) } }
            if (body == null) {
                Toast.makeText(context, R.string.dejavu_save_page_failed, Toast.LENGTH_LONG).show()
            } else {
                download(context, copy, body.first, body.second)
            }
        }
    }

    /** The file's content and length, after taking the file away: what is read from it stays until it is closed. */
    private fun openAndRemove(file: File): Pair<InputStream, Long>? = runCatching {
        val length = file.length()
        val stream = file.inputStream()
        file.delete()
        stream to length
    }.getOrNull()

    /**
     * Downloads the saved page like Fenix downloads a PDF of a page, with no question first. The browser shows the
     * download of its shown tab, and any other tab's goes on with only its notification.
     */
    private fun download(context: Context, copy: PageCopy, content: InputStream, length: Long) {
        val download = DownloadState(
            url = copy.url,
            fileName = savedPageName(copy.title, copy.url),
            contentType = SAVED_PAGE_TYPE,
            contentLength = length,
            private = copy.private,
            skipConfirmation = true,
            response = Response(
                url = copy.url,
                status = HTTP_OK,
                headers = MutableHeaders(),
                body = Response.Body(content),
            ),
        )
        val store = context.components.core.store
        if (store.state.selectedTabId == copy.tabId) {
            store.dispatch(ContentAction.UpdateDownloadAction(copy.tabId, download))
        } else {
            store.dispatch(DownloadAction.AddDownloadAction(download))
        }
    }

    /** A page being saved: the parts of its file, written in the order they come, away from the main thread. */
    private class PageCopy(val context: Context, val id: String, tab: TabSessionState, val session: EngineSession) {
        val tabId: String = tab.id
        val url: String = tab.content.url
        val title: String = tab.content.title
        val private: Boolean = tab.content.private

        // Only read away from the main thread, since finding the cache folder reads the disk.
        private val file by lazy { File(File(context.cacheDir, FOLDER), "$id.html") }
        private val parts = Channel<String>(Channel.UNLIMITED)
        private val writing = context.components.applicationScope.async(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { out ->
                for (part in parts) out.write(part)
            }
        }

        fun add(part: String) {
            parts.trySend(part)
        }

        /** The written file once all of it is there, or `null` without one, when [keep] is false or writing failed. */
        suspend fun finish(keep: Boolean): File? {
            parts.close()
            if (!keep) writing.cancel()
            val written = keep && runCatching { writing.await() }.isSuccess
            return withContext(Dispatchers.IO) {
                if (written) {
                    file
                } else {
                    file.delete()
                    null
                }
            }
        }
    }
}

private const val SAVED_PAGE_TYPE = "text/html"
private const val MAX_NAME_LENGTH = 100
private val UNSAFE_NAME_CHARACTERS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
private val SPACES = Regex("\\s+")

/** The name the page titled [title] at [url] is saved under: its title made fit for a file name, else its site's. */
internal fun savedPageName(title: String, url: String): String {
    val site = runCatching { URI(url).host }.getOrNull().orEmpty().removePrefix("www.")
    val name = title.ifBlank { site }
        .replace(UNSAFE_NAME_CHARACTERS, " ")
        .replace(SPACES, " ")
        .trim()
        .trim('.')
        .take(MAX_NAME_LENGTH)
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        .trim()
    return "${name.ifBlank { "page" }}.html"
}

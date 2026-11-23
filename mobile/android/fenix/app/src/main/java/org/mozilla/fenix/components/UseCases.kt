/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import android.content.Context
import android.os.StrictMode
import android.util.Size
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.state.searchEngines
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.storage.BookmarksStorage
import mozilla.components.concept.storage.HistoryStorage
import mozilla.components.feature.accounts.push.CloseTabsUseCases
import mozilla.components.feature.app.links.AppLinksUseCases
import mozilla.components.feature.contextmenu.ContextMenuUseCases
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.pwa.WebAppShortcutManager
import mozilla.components.feature.pwa.WebAppUseCases
import mozilla.components.feature.search.SearchUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.SettingsUseCases
import mozilla.components.feature.session.TrackingProtectionUseCases
import mozilla.components.feature.syncedtabs.commands.SyncedTabsCommands
import mozilla.components.feature.tabs.CustomTabsUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.feature.top.sites.TopSitesStorage
import mozilla.components.feature.top.sites.TopSitesUseCases
import mozilla.components.lib.crash.CrashReporter
import mozilla.components.service.mars.MozAdsClientProvider
import mozilla.components.service.mars.MozAdsUseCases
import mozilla.components.support.locale.LocaleManager
import mozilla.components.support.locale.LocaleUseCases
import mozilla.components.support.utils.DefaultDownloadFileUtils
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.components.bookmarks.BookmarksUseCase
import org.mozilla.fenix.components.bookmarks.LastSavedFolderCache
import org.mozilla.fenix.components.share.DefaultShareSheetLauncher
import org.mozilla.fenix.components.share.ShareSheetLauncher
import org.mozilla.fenix.components.usecases.FenixBrowserUseCases
import org.mozilla.fenix.components.usecases.ShareUseCases
import org.mozilla.fenix.dejavu.resolveSearchKeyword
import org.mozilla.fenix.dejavu.withDefaultEngineKeywords
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.home.mars.MARSUseCases
import org.mozilla.fenix.pbmlock.PrivateBrowsingLockUseCases
import org.mozilla.fenix.perf.StrictModeManager
import org.mozilla.fenix.perf.lazyMonitored
import org.mozilla.fenix.settings.downloads.DownloadLocationManager
import org.mozilla.fenix.wallpapers.WallpapersUseCases

/**
 * Component group for all use cases. Use cases are provided by feature modules and can be triggered by UI interactions.
 */
@Suppress("LongParameterList")
class UseCases(
    private val context: Context,
    private val crashReporter: Lazy<CrashReporter>,
    private val engine: Lazy<Engine>,
    private val store: Lazy<BrowserStore>,
    private val shortcutManager: Lazy<WebAppShortcutManager>,
    private val topSitesStorage: Lazy<TopSitesStorage>,
    private val bookmarksStorage: Lazy<BookmarksStorage>,
    private val historyStorage: Lazy<HistoryStorage>,
    private val lastSavedFolderCache: Lazy<LastSavedFolderCache>,
    private val syncedTabsCommands: Lazy<SyncedTabsCommands>,
    adsClientProvider: Lazy<MozAdsClientProvider>,
    appStore: Lazy<AppStore>,
    client: Lazy<Client>,
    strictMode: Lazy<StrictModeManager>,
) {
    /** Use cases that provide engine interactions for a given browser session. */
    val sessionUseCases by lazyMonitored { SessionUseCases(store.value) }

    /** Use cases that provide tab management. */
    val tabsUseCases: TabsUseCases by lazyMonitored { TabsUseCases(store.value) }

    /** Use cases for managing custom tabs. */
    val customTabsUseCases: CustomTabsUseCases by lazyMonitored {
        CustomTabsUseCases(store.value, sessionUseCases.loadUrl)
    }

    /** Use cases that provide search engine integration. */
    val searchUseCases by lazyMonitored {
        SearchUseCases(
            store.value,
            tabsUseCases,
            sessionUseCases,
        )
    }

    /** Use cases that provide settings management. */
    val settingsUseCases by lazyMonitored { SettingsUseCases(engine.value, store.value) }

    val appLinksUseCases by lazyMonitored { AppLinksUseCases(context.applicationContext) }

    val webAppUseCases by lazyMonitored {
        WebAppUseCases(context, store.value, shortcutManager.value)
    }

    val downloadUseCases by lazyMonitored {
        DownloadsUseCases(
            store = store.value,
            downloadFileUtils =
                DefaultDownloadFileUtils(
                    context = context.applicationContext,
                    downloadLocation = {
                        DownloadLocationManager(context.components.settings, context.contentResolver).defaultLocation
                    },
                ),
        )
    }

    val contextMenuUseCases by lazyMonitored { ContextMenuUseCases(store.value) }

    val trackingProtectionUseCases by lazyMonitored { TrackingProtectionUseCases(store.value, engine.value) }

    /** Use cases that provide top sites management. */
    val topSitesUseCase by lazyMonitored { TopSitesUseCases(topSitesStorage.value) }

    /** Use cases that handle locale management. */
    val localeUseCases by lazyMonitored { LocaleUseCases(store.value) }

    /** Use cases that provide bookmark management. */
    val bookmarksUseCases by lazyMonitored {
        BookmarksUseCase(bookmarksStorage.value, historyStorage.value, lastSavedFolderCache.value)
    }

    val wallpaperUseCases by lazyMonitored {
        // Required to even access context.filesDir property and to retrieve current locale
        val (rootStorageDirectory, currentLocale) =
            strictMode.value.allowViolation(StrictMode::allowThreadDiskReads) {
                val rootStorageDirectory = context.filesDir
                val currentLocale =
                    LocaleManager.getCurrentLocale(context)?.toLanguageTag()
                        ?: LocaleManager.getSystemDefault().toLanguageTag()
                rootStorageDirectory to currentLocale
            }
        WallpapersUseCases(
            context.components.settings,
            rootStorageDirectory,
            appStore.value,
            client.value,
            rootStorageDirectory,
            currentLocale,
            getDisplaySize = { displaySize(context) },
        )
    }

    val closeSyncedTabsUseCases by lazyMonitored { CloseTabsUseCases(syncedTabsCommands.value) }

    val marsUseCases by lazyMonitored { MARSUseCases(client.value) }

    val mozAdsUseCases by lazyMonitored {
        MozAdsUseCases(
            adsClientProvider = adsClientProvider,
            crashReporter = crashReporter.value,
        )
    }

    val fenixBrowserUseCases by lazyMonitored {
        refreshBookmarkKeywords()
        FenixBrowserUseCases(
            appStore = appStore.value,
            tabsUseCases = tabsUseCases,
            loadUrlUseCase = sessionUseCases.loadUrl,
            searchUseCases = searchUseCases,
            homepageTitle = context.getString(R.string.tab_tray_homepage_tab),
            profiler = engine.value.profiler,
            keywordResolver = { input -> resolveKeyword(input) },
        )
    }

    /** Dejavu: the engine (null to open a URL) and the text for typed "keyword text", or null without a keyword. */
    private fun resolveKeyword(input: String): Pair<SearchEngine?, String>? {
        val settings = context.components.settings
        val search = store.value.state.search
        val engines = search.searchEngines
        val engineKeywords = settings.engineKeywords.withDefaultEngineKeywords(engines.map { it.id })
        val bookmarkUrls = settings.bookmarkKeywordUrls
        // "@name": installed engines in the user's order, without app engines and disabled shortcuts.
        val atEngines = engines
            .filter { it.type != SearchEngine.Type.APPLICATION && it.id !in search.disabledSearchEngineIds }
            .map { it.id to it.name }
        val match = resolveSearchKeyword(
            input,
            engineKeywords,
            settings.bookmarkKeywords,
            bookmarkUrls::get,
            atEngines,
        ) ?: return null
        if (match.engineId == null) {
            // The URL comes from the cache, check in the background that the bookmark still exists.
            refreshBookmarkKeywords()
            return null to match.text
        }
        val engine = engines.find { it.id == match.engineId }
        if (engine == null && search.complete) {
            // The engine was deleted somewhere that left its keyword behind.
            settings.engineKeywords = settings.engineKeywords.filterValues { it != match.engineId }
        }
        return engine?.let { it to match.text }
    }

    /**
     * Dejavu: reads the URLs of the bookmarks that have a keyword into `Settings.bookmarkKeywordUrls` and drops the
     * keywords of deleted bookmarks. A failed read keeps the keyword.
     */
    fun refreshBookmarkKeywords() {
        context.components.applicationScope.launch(Dispatchers.IO) {
            val settings = context.components.settings
            val guids = settings.bookmarkKeywords.values.filterNot { it.contains(':') }.toSet()
            val nodes = guids.associateWith { bookmarksStorage.value.getBookmark(it) }
            val gone = nodes.filterValues { it.isSuccess && it.getOrNull() == null }.keys
            val urls = nodes.mapNotNull { (guid, node) -> node.getOrNull()?.url?.let { guid to it } }.toMap()
            if (gone.isNotEmpty()) settings.bookmarkKeywords = settings.bookmarkKeywords.filterValues { it !in gone }
            settings.bookmarkKeywordUrls = settings.bookmarkKeywordUrls - gone + urls
        }
    }

    val shareSheetLauncher: ShareSheetLauncher by lazyMonitored {
        DefaultShareSheetLauncher(
            applicationContext = context.applicationContext,
            homeActivityClass = HomeActivity::class.java,
            crashReporter = crashReporter.value,
        )
    }

    /** Use cases for sharing content via the system share sheet or the in-app share fragment. */
    val shareUseCases by lazyMonitored {
        ShareUseCases(
            browserStore = store.value,
            shareSheetLauncher = shareSheetLauncher,
            settings = context.components.settings,
        )
    }

    val privateBrowsingLockUseCases by lazyMonitored {
        PrivateBrowsingLockUseCases(appStore.value)
    }

    companion object {
        /** The size, in pixels, of the display a full screen wallpaper has to cover. */
        @VisibleForTesting
        internal fun displaySize(context: Context): Size {
            val metrics = context.resources.displayMetrics
            return Size(metrics.widthPixels, metrics.heightPixels)
        }
    }
}

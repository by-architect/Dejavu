/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.search

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import mozilla.components.browser.state.search.DefaultSearchEngineProvider
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.state.SearchState
import mozilla.components.browser.state.state.searchEngines
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.awesomebar.AwesomeBar.Suggestion
import mozilla.components.concept.awesomebar.AwesomeBar.SuggestionItem
import mozilla.components.concept.awesomebar.AwesomeBar.SuggestionProvider
import mozilla.components.concept.engine.EngineSession.LoadUrlFlags
import mozilla.components.feature.search.SearchUseCases.SearchUseCase
import mozilla.components.feature.session.SessionUseCases.LoadUrlUseCase
import mozilla.components.feature.tabs.TabsUseCases.SelectTabUseCase
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.components.metrics.MetricsUtils
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.search.SearchEngineSource
import org.mozilla.fenix.search.awesomebar.DefaultSuggestionIconProvider
import org.mozilla.fenix.search.awesomebar.DefaultSuggestionsStringsProvider
import org.mozilla.fenix.search.awesomebar.SearchSuggestionsProvidersBuilder
import org.mozilla.fenix.search.awesomebar.toSearchProviderState
import org.mozilla.fenix.search.createInitialSearchFragmentState
import org.mozilla.fenix.search.shouldShowSearchSuggestions

/** The engines "@name" picks from: the installed ones in the user's order, without app engines and disabled ones. */
fun SearchState.atEngines(): List<SearchEngine> =
    searchEngines.filter { it.type != SearchEngine.Type.APPLICATION && it.id !in disabledSearchEngineIds }

/**
 * The engines whose name matches "@[typed]", in [engines]' order: the ones whose name starts with it first, then the
 * ones with a word that does. Null when [text] does not start with "@" or nothing matches.
 */
internal fun enginesFor(text: String, engines: List<SearchEngine>): List<SearchEngine>? {
    if (!text.startsWith('@') || text.contains(' ')) return null
    val typed = text.drop(1).lowercase()
    if (typed.isEmpty()) return engines.ifEmpty { null }
    val starts = engines.filter { it.name.lowercase().startsWith(typed) }
    val words = engines.filter { engine ->
        engine !in starts && engine.name.lowercase().split(' ', '-', '.').any { it.startsWith(typed) }
    }
    return (starts + words).ifEmpty { null }
}

/**
 * The engine that a space typed after "@name" in [text] picks: the one whose name is the typed name, or the only one
 * that matches it.
 */
internal fun enginePickedBySpace(text: String, engines: List<SearchEngine>): SearchEngine? {
    if (!text.startsWith('@') || !text.endsWith(' ')) return null
    val typed = text.drop(1).dropLast(1)
    if (typed.isEmpty() || typed.contains(' ')) return null
    engines.find { it.name.equals(typed, ignoreCase = true) }?.let { return it }
    return enginesFor("@$typed", engines)?.singleOrNull()
}

/** The "@engine" lookups of a search field, on the engines of the moment. */
class TypedEngines(private val store: BrowserStore) {
    private fun engines() = store.state.search.atEngines()

    /** The engines a field lists for [text], or null when it lists its usual suggestions. */
    fun listed(text: String): List<SearchEngine>? = enginesFor(text, engines())

    /** The engine that typing a space after "@name" picks. */
    fun pickedBySpace(text: String): SearchEngine? = enginePickedBySpace(text, engines())
}

/** The engines that match the typed "@name", with their icon, in the user's order. */
class EngineShortcutProvider(
    private val context: Context,
    private val engines: TypedEngines,
    private val onPick: (SearchEngine) -> Unit,
) : SuggestionProvider {
    override val id = "dejavu_engine_shortcuts"

    override fun groupTitle(): String = context.getString(R.string.preferences_search_engines)

    override suspend fun onInputChanged(text: String): List<SuggestionItem> {
        val listed = engines.listed(text).orEmpty()
        return listed.mapIndexed { index, engine ->
            Suggestion(
                provider = this,
                id = engine.id,
                title = engine.name,
                icon = engine.icon,
                // The rows are sorted by score, this keeps the user's order.
                score = listed.size - index,
                onSuggestionClicked = { onPick(engine) },
            )
        }
    }
}

/**
 * The suggestions of the address bar for a search field of Dejavu's own, like the sidebar's: history, bookmarks,
 * open tabs when [showOpenTabs], and the suggestions of [engine]. A private field shows no history.
 */
@Suppress("LongParameterList")
internal fun webSuggestionProviders(
    activity: HomeActivity,
    isPrivate: Boolean,
    engine: SearchEngine?,
    showOpenTabs: Boolean,
    typedQuery: () -> String,
    onLoad: (url: String, forceSearch: Boolean, engine: SearchEngine?, flags: LoadUrlFlags) -> Unit,
    onSelectTab: (tabId: String) -> Unit,
    picked: Boolean = false,
): List<SuggestionProvider> {
    val components = activity.components
    val state = createInitialSearchFragmentState(activity, components, null, null, MetricsUtils.Source.NONE).let {
        if (!isPrivate) {
            it
        } else {
            it.copy(
                showSearchTermHistory = false,
                showAllHistorySuggestions = false,
                showHistorySuggestionsForCurrentEngine = false,
                showRecentSearches = false,
                showSearchSuggestionsFromCurrentEngine =
                    shouldShowSearchSuggestions(BrowsingMode.Private, components.settings),
            )
        }
    }.let {
        if (showOpenTabs) it else it.copy(showAllSessionSuggestions = false, showSessionSuggestionsForCurrentEngine = false)
    }.copy(
        searchEngineSource = when {
            engine == null -> SearchEngineSource.None
            picked -> SearchEngineSource.Shortcut(engine)
            else -> SearchEngineSource.Default(engine)
        },
    )
    val builder = SearchSuggestionsProvidersBuilder(
        components = components,
        scope = ProcessLifecycleOwner.get().lifecycleScope,
        browsingModeManager = activity.browsingModeManager,
        includeSelectedTab = true,
        loadUrlUseCase = object : LoadUrlUseCase {
            override fun invoke(
                url: String,
                flags: LoadUrlFlags,
                additionalHeaders: Map<String, String>?,
                originalInput: String?,
            ) = onLoad(url, false, null, flags)
        },
        searchUseCase = object : SearchUseCase {
            override fun invoke(searchTerms: String, searchEngine: SearchEngine?, parentSessionId: String?) {
                onLoad(searchTerms, true, searchEngine, LoadUrlFlags.none())
            }
        },
        selectTabUseCase = object : SelectTabUseCase {
            override fun invoke(tabId: String) = onSelectTab(tabId)
        },
        suggestionsStringsProvider = DefaultSuggestionsStringsProvider(
            activity,
            DefaultSearchEngineProvider(components.core.store),
        ),
        suggestionIconProvider = DefaultSuggestionIconProvider(activity),
        onSearchEngineSuggestionSelected = {},
    )
    val engineShortcuts = setOfNotNull(builder.searchEngineSuggestionProvider)
    return builder.getProvidersToAdd(state.toSearchProviderState()).toList() - engineShortcuts
}

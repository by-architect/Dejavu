/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.R
import org.mozilla.fenix.components.components
import org.mozilla.fenix.search.ext.searchEngineShortcuts

/** An engine that "@" and the letters typed after it match, with the places in its name the letters matched. */
internal data class EngineMatch(val engine: SearchEngine, val matched: List<Int>)

/**
 * The engines "@" in [text] picks from, as fzf matches: the letters typed after "@" appear in this order anywhere in
 * the name, so "ddg" finds DuckDuckGo. The best matches come first, else [engines]' order. Null when [text] is no
 * "@name" or nothing matches, so the field searches as usual.
 */
internal fun enginesMatching(text: String, engines: List<SearchEngine>): List<EngineMatch>? {
    if (!text.startsWith('@') || text.contains(' ')) return null
    val typed = text.drop(1)
    return engines
        .mapNotNull { engine -> fuzzyMatch(typed, engine.name)?.let { (score, at) -> Triple(engine, score, at) } }
        .sortedByDescending { it.second }
        .map { EngineMatch(it.first, it.third) }
        .ifEmpty { null }
}

/**
 * How well [typed] matches [name], fzf style, and where its letters are in [name]; null when they are not all in it,
 * in order. Letters at the start of the name, of a word or of a capital ("Duck|Duck|Go") and letters that follow each
 * other count more.
 */
internal fun fuzzyMatch(typed: String, name: String): Pair<Int, List<Int>>? {
    val pattern = typed.lowercase()
    val lower = name.lowercase()
    val matched = mutableListOf<Int>()
    var score = 0
    for (i in lower.indices) {
        if (matched.size == pattern.length) break
        if (lower[i] != pattern[matched.size]) continue
        score += 1
        if (matched.lastOrNull() == i - 1) score += CONSECUTIVE_BONUS
        score += when {
            i == 0 -> START_BONUS
            !name[i - 1].isLetterOrDigit() -> WORD_BONUS
            name[i].isUpperCase() && name[i - 1].isLowerCase() -> WORD_BONUS
            else -> 0
        }
        matched += i
    }
    return if (matched.size == pattern.length) score to matched else null
}

private const val CONSECUTIVE_BONUS = 4
private const val START_BONUS = 8
private const val WORD_BONUS = 6

/** The engines "@" picks from, as the chips show them: the search engines, then the bookmarks, tabs and history. */
@Composable
internal fun rememberAtEngines(): List<SearchEngine> {
    val engines by components.core.store.observeAsComposableState { it.search.searchEngineShortcuts }
    return remember(engines) {
        val (general, local) = engines.partition { it.type != SearchEngine.Type.APPLICATION }
        general + local
    }
}

/**
 * Moves through [count] listed engines with the arrow keys and Tab, and picks the [highlighted] one with Enter; returns
 * whether [event] was used. [onHighlight] gets the newly highlighted row.
 */
internal fun handleEngineKey(
    event: KeyEvent,
    count: Int,
    highlighted: Int,
    onHighlight: (Int) -> Unit,
    onPick: () -> Unit,
): Boolean {
    val down = when (event.key) {
        Key.DirectionDown -> true
        Key.DirectionUp -> false
        Key.Tab -> !event.isShiftPressed
        Key.Enter, Key.NumPadEnter -> {
            if (event.type == KeyEventType.KeyDown) onPick()
            return true
        }
        else -> return false
    }
    if (event.type == KeyEventType.KeyDown) onHighlight((highlighted + if (down) 1 else count - 1) % count)
    return true
}

/**
 * The engines "@name" matches, in place of the suggestions, with the letters that matched in bold and the
 * [highlighted] one that Enter picks marked.
 */
@Composable
internal fun EngineList(
    matches: List<EngineMatch>,
    highlighted: Int,
    onPick: (SearchEngine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(highlighted) {
        val visible = listState.layoutInfo.visibleItemsInfo
        // The header is the first item.
        val row = highlighted + 1
        if (visible.none { it.index == row && it.offset >= 0 } || visible.lastOrNull()?.index == row) {
            listState.animateScrollToItem((row - 1).coerceAtLeast(0))
        }
    }
    val colors = MaterialTheme.colorScheme
    LazyColumn(state = listState, modifier = modifier) {
        item {
            Text(
                text = stringResource(R.string.preferences_search_engines),
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        itemsIndexed(matches, key = { _, match -> match.engine.id }) { index, match ->
            val engine = match.engine
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ENGINE_ROW_HEIGHT)
                    .background(if (index == highlighted) colors.secondaryContainer else Color.Transparent)
                    .clickable { onPick(engine) }
                    .padding(horizontal = 20.dp),
            ) {
                val painter = remember(engine.id) { BitmapPainter(engine.icon.asImageBitmap()) }
                if (engine.type == SearchEngine.Type.APPLICATION) {
                    Icon(
                        painter = painter,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(ENGINE_ICON),
                    )
                } else {
                    Image(painter = painter, contentDescription = null, modifier = Modifier.size(ENGINE_ICON))
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = buildAnnotatedString {
                        engine.name.forEachIndexed { i, letter ->
                            if (i in match.matched) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.primary)) {
                                    append(letter)
                                }
                            } else {
                                append(letter)
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (index == highlighted) colors.onSecondaryContainer else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val ENGINE_ROW_HEIGHT = 52.dp
private val ENGINE_ICON = 24.dp

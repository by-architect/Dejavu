/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu

import org.json.JSONObject
import java.net.URLEncoder

/** A typed keyword match: search [engineId] for [text], or, when [engineId] is null, open the URL [text]. */
data class KeywordMatch(val engineId: String?, val text: String)

/**
 * Splits "keyword rest" and looks the keyword up in [engineKeywords] (keyword to engine id) or [bookmarkKeywords]
 * (keyword to bookmark guid, or to a URL as saved by older versions). [bookmarkUrl] gives a bookmark's URL by guid.
 * An engine keyword needs search text. A bookmark keyword works alone too: its %s becomes the rest, URL encoded, and a
 * bookmark without %s just opens.
 * "@name rest" searches the first of [engines] (id to name, in the user's order) whose name (spaces removed), id or
 * keyword starts with name, ignoring case. Without a match or without rest it falls through, so "@user" still searches.
 */
fun resolveSearchKeyword(
    input: String,
    engineKeywords: Map<String, String>,
    bookmarkKeywords: Map<String, String>,
    bookmarkUrl: (String) -> String? = { null },
    engines: List<Pair<String, String>> = emptyList(),
): KeywordMatch? {
    val parts = input.trim().split(" ", limit = 2)
    val rest = parts.getOrElse(1) { "" }.trim()
    val word = parts[0].lowercase()
    engineKeywords[word]?.let { return if (rest.isEmpty()) null else KeywordMatch(it, rest) }
    val target = bookmarkKeywords[word] ?: return engineByPrefix(word, rest, engines, engineKeywords)
    val url = if (target.contains(':')) target else bookmarkUrl(target) ?: return null
    // Spaces as %20, which works in paths too, where "+" would stay a plus.
    return KeywordMatch(null, url.replace("%s", URLEncoder.encode(rest, "UTF-8").replace("+", "%20")))
}

/** The "@name rest" part of [resolveSearchKeyword], [word] already lowercase. */
private fun engineByPrefix(
    word: String,
    rest: String,
    engines: List<Pair<String, String>>,
    engineKeywords: Map<String, String>,
): KeywordMatch? {
    val prefix = word.removePrefix("@")
    if (!word.startsWith('@') || prefix.isEmpty() || rest.isEmpty()) return null
    val id = engines.firstOrNull { (id, name) ->
        name.lowercase().filterNot { it.isWhitespace() }.startsWith(prefix) ||
            id.lowercase().startsWith(prefix) ||
            engineKeywords.any { (keyword, owner) -> owner == id && keyword.startsWith(prefix) }
    }?.first ?: return null
    return KeywordMatch(id, rest)
}

/**
 * What already uses [keyword]: an engine id from [engineKeywords] (pass them with their defaults), a bookmark guid or
 * old URL from [bookmarkKeywords]. Null when it is free or blank.
 */
fun keywordOwner(
    keyword: String,
    engineKeywords: Map<String, String>,
    bookmarkKeywords: Map<String, String>,
): String? {
    val word = keyword.trim().lowercase()
    if (word.isEmpty()) return null
    return engineKeywords[word]?.takeIf { it.isNotEmpty() }
        ?: bookmarkKeywords[word]
}

/** Returns a copy where [value] has [keyword] as its only keyword, or none when [keyword] is blank. */
fun Map<String, String>.withKeyword(keyword: String, value: String): Map<String, String> =
    filterValues { it != value } + if (keyword.isBlank()) emptyMap() else mapOf(keyword.trim().lowercase() to value)

private val DEFAULT_ENGINE_KEYWORDS = mapOf("g" to "google", "d" to "ddg")

/**
 * The engine keywords with the defaults "g" for Google and "d" for DuckDuckGo added for the installed [engineIds] the
 * user gave no keyword. A saved "" marks a default the user cleared.
 */
fun Map<String, String>.withDefaultEngineKeywords(engineIds: List<String>): Map<String, String> =
    filterValues { it.isNotEmpty() } + DEFAULT_ENGINE_KEYWORDS.mapNotNull { (keyword, idStart) ->
        engineIds.firstOrNull { it.startsWith(idStart) }
            ?.takeIf { keyword !in this && it !in values }
            ?.let { keyword to it }
    }

/** [withKeyword] for an engine, remembering a cleared default keyword so it does not come back. */
fun Map<String, String>.withEngineKeyword(
    keyword: String,
    engineId: String,
    engineIds: List<String>,
): Map<String, String> {
    val cleared = withDefaultEngineKeywords(engineIds).keywordFor(engineId)
        .takeIf { keyword.isBlank() && it in DEFAULT_ENGINE_KEYWORDS }
    return withKeyword(keyword, engineId) + listOfNotNull(cleared?.let { it to "" })
}

fun Map<String, String>.keywordFor(value: String): String = entries.find { it.value == value }?.key.orEmpty()

fun String.toKeywordMap(): Map<String, String> =
    runCatching { JSONObject(this).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }
        .getOrDefault(emptyMap())

fun Map<String, String>.toKeywordJson(): String = JSONObject(this).toString()

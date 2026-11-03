/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchKeywordsTest {
    private val engines = mapOf("w" to "wikipedia")
    private val bookmarks = mapOf("gh" to "https://github.com/search?q=%s")

    @Test
    fun `resolves engine and bookmark keywords`() {
        assertEquals(KeywordMatch("wikipedia", "cats and dogs"), resolveSearchKeyword("W cats and dogs", engines, bookmarks))
        assertEquals(
            KeywordMatch(null, "https://github.com/search?q=a%20b%26c"),
            resolveSearchKeyword("gh a b&c", engines, bookmarks),
        )
    }

    @Test
    fun `bookmark keywords follow the bookmark guid and still read old URL entries`() {
        val urls = mapOf("guid1" to "https://example.com/new?q=%s")
        val saved = mapOf("ex" to "guid1", "gh" to "https://github.com/search?q=%s", "gone" to "guid2")
        assertEquals(
            KeywordMatch(null, "https://example.com/new?q=cats"),
            resolveSearchKeyword("ex cats", engines, saved, urls::get),
        )
        assertEquals(
            KeywordMatch(null, "https://github.com/search?q=cats"),
            resolveSearchKeyword("gh cats", engines, saved, urls::get),
        )
        assertNull(resolveSearchKeyword("gone cats", engines, saved, urls::get))
    }

    @Test
    fun `falls through without keyword or rest`() {
        assertNull(resolveSearchKeyword("x cats", engines, bookmarks))
        assertNull(resolveSearchKeyword("w", engines, bookmarks))
        assertNull(resolveSearchKeyword("w   ", engines, bookmarks))
    }

    @Test
    fun `default keywords search Google and DuckDuckGo until the user changes them`() {
        val ids = listOf("google-b-m", "ddg", "wikipedia")
        val defaults = emptyMap<String, String>().withDefaultEngineKeywords(ids)
        assertEquals(KeywordMatch("google-b-m", "cats"), resolveSearchKeyword("g cats", defaults, emptyMap()))
        assertEquals(KeywordMatch("ddg", "cats"), resolveSearchKeyword("d cats", defaults, emptyMap()))
        val googleOnly = emptyMap<String, String>().withDefaultEngineKeywords(listOf("google"))
        assertNull(resolveSearchKeyword("d cats", googleOnly, emptyMap()))
        val cleared = emptyMap<String, String>().withEngineKeyword("", "ddg", ids)
        assertNull(resolveSearchKeyword("d cats", cleared.withDefaultEngineKeywords(ids), emptyMap()))
        val renamed = emptyMap<String, String>().withEngineKeyword("gg", "google-b-m", ids)
            .withDefaultEngineKeywords(ids)
        assertNull(resolveSearchKeyword("g cats", renamed, emptyMap()))
    }

    @Test
    fun `bookmark keywords open alone, and substitute or ignore the rest`() {
        val urls = mapOf("plain" to "https://example.com/", "query" to "https://example.com/?q=%s")
        val saved = mapOf("ex" to "plain", "q" to "query")
        assertEquals(KeywordMatch(null, "https://example.com/"), resolveSearchKeyword("ex", engines, saved, urls::get))
        assertEquals(
            KeywordMatch(null, "https://example.com/"),
            resolveSearchKeyword("ex cats", engines, saved, urls::get),
        )
        assertEquals(
            KeywordMatch(null, "https://example.com/?q=cats%20dogs"),
            resolveSearchKeyword("Q cats dogs", engines, saved, urls::get),
        )
        assertEquals(
            KeywordMatch(null, "https://example.com/?q="),
            resolveSearchKeyword("q", engines, saved, urls::get),
        )
    }

    @Test
    fun `bookmark keywords encode spaces so they work in paths, and keep a typed plus`() {
        val saved = mapOf("wiki" to "https://en.wikipedia.org/wiki/%s")
        assertEquals(
            KeywordMatch(null, "https://en.wikipedia.org/wiki/New%20York"),
            resolveSearchKeyword("wiki New York", engines, saved),
        )
        assertEquals(
            KeywordMatch(null, "https://en.wikipedia.org/wiki/1%2B1%20%3D%202"),
            resolveSearchKeyword("wiki 1+1 = 2", engines, saved),
        )
    }
    @Test
    fun `keywordOwner finds collisions across engines and bookmarks`() {
        val withDefaults = engines.withDefaultEngineKeywords(listOf("google", "wikipedia"))
        assertEquals("wikipedia", keywordOwner(" W ", withDefaults, bookmarks))
        assertEquals("google", keywordOwner("g", withDefaults, bookmarks))
        assertEquals("https://github.com/search?q=%s", keywordOwner("gh", withDefaults, bookmarks))
        assertNull(keywordOwner("free", withDefaults, bookmarks))
        assertNull(keywordOwner("  ", withDefaults, bookmarks))
        // A cleared default keyword is saved as "" and frees the keyword.
        assertNull(keywordOwner("d", mapOf("d" to ""), bookmarks))
    }

    private val installed = listOf(
        "google-b-m" to "Google",
        "ddg" to "DuckDuckGo",
        "wikipedia" to "Wikipedia (en)",
        "custom-1" to "Good Reads",
    )

    private fun at(input: String, keywords: Map<String, String> = engines) =
        resolveSearchKeyword(input, keywords, bookmarks, engines = installed)

    @Test
    fun `at name searches the engine whose name starts with it`() {
        assertEquals(KeywordMatch("ddg", "cats dogs"), at("@duck cats dogs"))
        assertEquals(KeywordMatch("wikipedia", "cats"), at("@Wikipedia(en) cats"))
    }

    @Test
    fun `at name matches the engine keyword and id`() {
        assertEquals(KeywordMatch("wikipedia", "cats"), at("@wk cats", mapOf("wk" to "wikipedia")))
        assertEquals(KeywordMatch("custom-1", "cats"), at("@custom cats"))
    }

    @Test
    fun `at name takes the first match in the user's order`() {
        assertEquals(KeywordMatch("google-b-m", "cats"), at("@goo cats"))
        assertEquals(KeywordMatch("custom-1", "cats"), at("@good cats"))
        val reordered = resolveSearchKeyword("@goo cats", engines, bookmarks, engines = installed.reversed())
        assertEquals(KeywordMatch("custom-1", "cats"), reordered)
    }

    @Test
    fun `at name ignores case and spaces in engine names`() {
        assertEquals(KeywordMatch("custom-1", "Cats"), at("@GOODREADS Cats"))
        assertEquals(KeywordMatch("ddg", "cats"), at("  @DuckDuck   cats  "))
    }

    @Test
    fun `at name falls through without a match, without text, or without a name`() {
        assertNull(at("@someone cats"))
        assertNull(at("@goo"))
        assertNull(at("@goo   "))
        assertNull(at("@ cats"))
        assertNull(at("goo cats"))
        assertNull(resolveSearchKeyword("@goo cats", engines, bookmarks))
    }

    @Test
    fun `an exact keyword starting with at wins over the engine prefix`() {
        assertEquals(KeywordMatch("ddg", "cats"), at("@g cats", mapOf("@g" to "ddg")))
    }

    @Test
    fun `withKeyword replaces old keyword`() {
        assertEquals(mapOf("wk" to "wikipedia"), engines.withKeyword("WK", "wikipedia"))
        assertEquals(emptyMap<String, String>(), engines.withKeyword("", "wikipedia"))
    }
}

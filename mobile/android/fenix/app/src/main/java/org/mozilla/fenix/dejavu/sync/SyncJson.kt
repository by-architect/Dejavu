/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON helpers for sync records. [canonical] writes object keys in sorted order at every level, so two records with
 * the same content always give the same text. That is how a local record is compared with the server's copy.
 */
internal object SyncJson {
    private const val MAX_EXACT_INTEGER = 1e15

    fun canonical(value: Any?): String = buildString { write(this, value, sortKeys = true) }

    fun stringify(value: Any?): String = buildString { write(this, value, sortKeys = false) }

    fun copy(value: JSONObject): JSONObject = JSONObject(stringify(value))

    fun parseObject(text: String?): JSONObject? = text?.let { runCatching { JSONObject(it) }.getOrNull() }

    @Suppress("CognitiveComplexMethod")
    private fun write(out: StringBuilder, value: Any?, sortKeys: Boolean) {
        when (value) {
            null, JSONObject.NULL -> out.append("null")
            is JSONObject -> {
                out.append('{')
                val keys = value.keys().asSequence().toList().let { if (sortKeys) it.sorted() else it }
                keys.forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    quote(out, key)
                    out.append(':')
                    write(out, value.opt(key), sortKeys)
                }
                out.append('}')
            }
            is JSONArray -> {
                out.append('[')
                for (index in 0 until value.length()) {
                    if (index > 0) out.append(',')
                    write(out, value.opt(index), sortKeys)
                }
                out.append(']')
            }
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Number -> out.append(number(value))
            else -> quote(out, value.toString())
        }
    }

    /** Integral numbers are written without a fraction, like JavaScript does, so 1.0 and 1 compare equal. */
    private fun number(value: Number): String = when (value) {
        is Int, is Long, is Short, is Byte -> value.toString()
        else -> {
            val double = value.toDouble()
            when {
                double.isNaN() || double.isInfinite() -> "null"
                double == Math.rint(double) && abs(double) < MAX_EXACT_INTEGER -> double.toLong().toString()
                else -> double.toString()
            }
        }
    }

    @Suppress("MagicNumber")
    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (char in text) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (char < ' ' || char == ' ' || char == ' ') {
                    out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }
}

/** The string at [key], or `null` when it is missing, `null` or not a string. */
internal fun JSONObject.string(key: String): String? = opt(key) as? String

/** The strings of the array at [key], or `null` when there is no array. Other values are skipped. */
internal fun JSONObject.strings(key: String): List<String>? =
    optJSONArray(key)?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? String } }

/** Puts [value], writing JSON `null` instead of removing [key] when it is `null`, as sync records keep null fields. */
internal fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

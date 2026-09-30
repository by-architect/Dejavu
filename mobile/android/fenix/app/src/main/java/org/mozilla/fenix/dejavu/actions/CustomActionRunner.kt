/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import android.content.Context
import android.net.Uri
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Header
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import org.json.JSONObject
import org.mozilla.fenix.R

/**
 * Outcome of running a custom action on some tabs.
 *
 * @property sent Requests answered with a 2xx status.
 * @property failed Requests that got no answer or another status, redirects included.
 * @property failedStatus Status of the last failed request that was answered, if any.
 */
data class ActionRunResult(val sent: Int, val failed: Int, val failedStatus: Int?) {
    /** Tells how running [action] on [total] tabs went, like "Sent: Karakeep" or "Failed: code 302 (Karakeep)". */
    fun message(context: Context, action: CustomAction, total: Int): String {
        if (failed == 0) return context.getString(R.string.dejavu_custom_action_sent, action.name)
        val reason = failedStatus?.let { context.getString(R.string.dejavu_custom_action_failed_code, it, action.name) }
            ?: context.getString(R.string.dejavu_custom_action_failed_no_answer, action.name)
        return if (total > 1) context.getString(R.string.dejavu_custom_action_failed_some, reason, failed, total) else reason
    }
}

/**
 * Sends [CustomAction] requests through Firefox's network stack. Cookies are never sent and nothing is cached; the
 * response body is ignored.
 */
class CustomActionRunner(private val client: Client) {
    suspend fun run(action: CustomAction, contexts: List<ActionContext>): ActionRunResult = withContext(Dispatchers.IO) {
        var sent = 0
        var failed = 0
        var failedStatus: Int? = null
        contexts.forEach { context ->
            val status = runCatching { send(action, context) }.getOrNull()
            if (status != null && status in SUCCESS_STATUSES) {
                sent++
            } else {
                failed++
                if (status != null) failedStatus = status
            }
        }
        ActionRunResult(sent, failed, failedStatus)
    }

    private fun send(action: CustomAction, context: ActionContext): Int {
        val url = renderTemplate(action.url, context) { Uri.encode(it) }.trim()
        require(url.startsWith("https://") || url.startsWith("http://")) { "Only http and https URLs are allowed" }

        val headers = action.headers.filter { it.name.isNotBlank() }.map { header ->
            Header(header.name.trim(), renderTemplate(header.value, context) { it.replace(LINE_BREAKS, " ") })
        }
        val isJson = headers.any { it.name.equals("Content-Type", ignoreCase = true) && "json" in it.value.lowercase() }
        val body = if (action.method.hasBody && action.body.isNotEmpty()) {
            val rendered = renderTemplate(action.body, context) { value ->
                if (isJson) JSONObject.quote(value).removeSurrounding("\"") else value
            }
            Request.Body.fromString(rendered)
        } else {
            null
        }

        val request = Request(
            url = url,
            method = action.method.method,
            headers = MutableHeaders(headers),
            connectTimeout = CONNECT_TIMEOUT_SECONDS to TimeUnit.SECONDS,
            readTimeout = READ_TIMEOUT_SECONDS to TimeUnit.SECONDS,
            body = body,
            redirect = Request.Redirect.MANUAL,
            cookiePolicy = Request.CookiePolicy.OMIT,
            useCaches = false,
            private = true,
        )
        return client.fetch(request).use { it.status }
    }

    private companion object {
        val SUCCESS_STATUSES = 200..299
        val LINE_BREAKS = Regex("[\\r\\n]+")
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.actions

import mozilla.components.concept.fetch.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP methods a custom action can send with. The response is never shown, so these only push data out.
 *
 * @property method The request method.
 * @property hasBody Whether the request carries the action's body.
 */
enum class HttpMethod(val method: Request.Method, val hasBody: Boolean) {
    GET(Request.Method.GET, false),
    POST(Request.Method.POST, true),
    PUT(Request.Method.PUT, true),
    DELETE(Request.Method.DELETE, false),
}

/** A request header of a [CustomAction]. Both parts may contain [ActionVariable] placeholders. */
data class CustomHeader(val name: String, val value: String)

/**
 * A user defined tab action that sends an HTTP request for every tab it runs on, like a `curl` command.
 *
 * @property id Stable identifier.
 * @property name Label of the action's button.
 * @property method HTTP method.
 * @property url Request URL. May contain [ActionVariable] placeholders, which are URL encoded.
 * @property headers Request headers.
 * @property body Request body for methods that have one. Placeholders are JSON escaped when the Content-Type is JSON.
 */
data class CustomAction(
    val id: String,
    val name: String,
    val method: HttpMethod,
    val url: String,
    val headers: List<CustomHeader>,
    val body: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("method", method.name)
        .put("url", url)
        .put("headers", JSONArray().apply { headers.forEach { put(JSONObject().put("name", it.name).put("value", it.value)) } })
        .put("body", body)

    companion object {
        fun fromJson(json: JSONObject): CustomAction? = runCatching {
            val headers = json.optJSONArray("headers")
            CustomAction(
                id = json.getString("id"),
                name = json.getString("name"),
                method = HttpMethod.valueOf(json.getString("method")),
                url = json.getString("url"),
                headers = (0 until (headers?.length() ?: 0)).map { i ->
                    val header = headers!!.getJSONObject(i)
                    CustomHeader(header.getString("name"), header.getString("value"))
                },
                body = json.optString("body"),
            )
        }.getOrNull()
    }
}

/** Values a [CustomAction] can use through `${key}` placeholders. */
enum class ActionVariable(val key: String) {
    WEBSITE_URL("websiteurl"),
    WEBSITE_TITLE("websitetitle"),
    WEBSITE_HOST("websitehost"),
    CONTAINER("container"),
    WORKSPACE("workspace"),
    FOLDER_PATH("folderpath"),
    DATE("date"),
    ;

    val token: String
        get() = "\${$key}"
}

/**
 * The values of the [ActionVariable]s for one tab.
 *
 * @property url Page URL.
 * @property title Page title.
 * @property container Container name, empty without a container.
 * @property workspace Workspace name.
 * @property folderPath Folders of a pinned tab separated by "/", empty when it is not in a folder.
 * @property date Time the action runs, ISO 8601.
 */
data class ActionContext(
    val url: String,
    val title: String,
    val container: String,
    val workspace: String,
    val folderPath: String,
    val date: String,
) {
    fun valueOf(variable: ActionVariable): String = when (variable) {
        ActionVariable.WEBSITE_URL -> url
        ActionVariable.WEBSITE_TITLE -> title
        ActionVariable.WEBSITE_HOST -> runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        ActionVariable.CONTAINER -> container
        ActionVariable.WORKSPACE -> workspace
        ActionVariable.FOLDER_PATH -> folderPath
        ActionVariable.DATE -> date
    }
}

/** Replaces every [ActionVariable] placeholder of [template] with its value in [context], passed through [encode]. */
fun renderTemplate(template: String, context: ActionContext, encode: (String) -> String): String =
    ActionVariable.entries.fold(template) { text, variable ->
        if (variable.token in text) text.replace(variable.token, encode(context.valueOf(variable))) else text
    }

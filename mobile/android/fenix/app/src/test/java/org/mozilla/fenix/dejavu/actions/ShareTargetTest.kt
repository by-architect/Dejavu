/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareTargetTest {
    private val whatsApp = CustomAction.shareAction(
        id = "a",
        name = "WhatsApp: Alice",
        target = ShareTarget("com.whatsapp", "com.whatsapp.contact.picker.ContactPicker", "+905321234567"),
    )

    @Test
    fun `a share action comes back from its JSON`() {
        assertEquals(whatsApp, CustomAction.fromJson(JSONObject(whatsApp.toJson().toString())))
    }

    @Test
    fun `a share action keeps an empty request for older versions`() {
        val json = whatsApp.toJson()
        assertEquals("GET", json.getString("method"))
        assertEquals("", json.getString("url"))
        assertEquals(0, json.getJSONArray("headers").length())
        assertEquals("", json.getString("body"))
    }

    @Test
    fun `an action saved before share actions sends a request`() {
        val json = JSONObject(
            """{"id":"b","name":"Karakeep","method":"POST","url":"https://example.com","headers":[],"body":"{}"}""",
        )
        assertNull(CustomAction.fromJson(json)?.share)
    }

    @Test
    fun `phone numbers keep only their digits after a plus`() {
        assertEquals("+15551234567", RecipientKind.PHONE.normalize(" +1 (555) 123-4567 "))
        assertTrue(RecipientKind.PHONE.isValid("+1 (555) 123-4567"))
        assertFalse(RecipientKind.PHONE.isValid("call me"))
    }

    @Test
    fun `usernames drop their at sign and the link they were copied from`() {
        assertEquals("alice", RecipientKind.USERNAME.normalize("@alice"))
        assertEquals("alice", RecipientKind.USERNAME.normalize("https://t.me/alice"))
        assertFalse(RecipientKind.USERNAME.isValid("alice smith"))
    }

    @Test
    fun `email addresses need an at sign between two parts`() {
        assertTrue(RecipientKind.EMAIL.isValid("bob@example.com"))
        assertFalse(RecipientKind.EMAIL.isValid("bob"))
        assertFalse(RecipientKind.EMAIL.isValid("@example.com"))
    }
}

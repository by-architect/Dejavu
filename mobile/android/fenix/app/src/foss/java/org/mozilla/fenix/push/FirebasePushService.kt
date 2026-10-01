/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import mozilla.components.concept.push.PushService

/**
 * The F-Droid build's stand-in for the Firebase push service.
 *
 * Firebase Cloud Messaging is not free software, so it is not part of this build. Nothing starts this service: web
 * push needs a Firebase project id, and Dejavu ships none, so [org.mozilla.fenix.components.Push] never builds a push
 * feature in any build. It stays a [Service] because the manifest declares it as one.
 */
class FirebasePushService : Service(), PushService {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun start(context: Context) = Unit

    override fun stop() = Unit

    override fun deleteToken() = Unit

    override fun isServiceAvailable(context: Context): Boolean = false
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.navOptions
import org.mozilla.fenix.R

/** How a settings screen comes in and goes back: from the side, as Firefox's own settings screens do. */
internal val SETTINGS_SLIDE: NavOptions = navOptions {
    anim {
        enter = R.anim.slide_in_right
        exit = R.anim.slide_out_left
        popEnter = R.anim.slide_in_left
        popExit = R.anim.slide_out_right
    }
}

/** Opens the settings screen, graph or action [destination] with [args], sliding in from the side. */
internal fun NavController.slideTo(@IdRes destination: Int, args: Bundle? = null) =
    navigate(destination, args, SETTINGS_SLIDE)

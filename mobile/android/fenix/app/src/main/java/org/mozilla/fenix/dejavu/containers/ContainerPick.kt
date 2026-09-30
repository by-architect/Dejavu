/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.containers

/** Which container a new tab opens in. */
sealed interface ContainerPick {
    /** Stable form used to save the pick. */
    val key: String

    data object NoContainer : ContainerPick {
        override val key = "none"
    }

    /** A new temporary container, see [TemporaryContainers]. */
    data object Temporary : ContainerPick {
        override val key = "temporary"
    }

    data class Container(val contextId: String) : ContainerPick {
        override val key: String
            get() = CONTAINER_PREFIX + contextId
    }

    companion object {
        private const val CONTAINER_PREFIX = "container:"

        fun fromKey(key: String?): ContainerPick? = when {
            key == NoContainer.key -> NoContainer
            key == Temporary.key -> Temporary
            key != null && key.startsWith(CONTAINER_PREFIX) -> Container(key.removePrefix(CONTAINER_PREFIX))
            else -> null
        }
    }
}

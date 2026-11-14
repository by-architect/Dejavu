/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.share.viewholders

import android.view.View
import androidx.annotation.LayoutRes
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.fenix.R
import org.mozilla.fenix.databinding.AppShareListItemBinding
import org.mozilla.fenix.share.DefaultShareController.Companion.ACTION_COPY_LINK_TO_CLIPBOARD
import org.mozilla.fenix.share.ShareToAppsInteractor
import org.mozilla.fenix.share.listadapters.AppShareOption

class AppViewHolder(
    itemView: View,
    @get:VisibleForTesting val interactor: ShareToAppsInteractor,
) : RecyclerView.ViewHolder(itemView) {

    private var application: AppShareOption? = null

    init {
        itemView.setOnClickListener {
            application?.let { app ->
                interactor.onShareToApp(app)
            }
        }
        // Dejavu: holding an app offers to add a share action for it, see ShareToAppsInteractor.onAddToActions.
        itemView.setOnLongClickListener {
            val app = application?.takeIf { it.canBeAction } ?: return@setOnLongClickListener false
            interactor.onAddToActions(app)
            true
        }
    }

    fun bind(item: AppShareOption) {
        application = item
        val binding = AppShareListItemBinding.bind(itemView)
        binding.appName.text = item.name
        binding.appIcon.setImageDrawable(item.icon)
        // Dejavu: screen readers name holding an app "Add to actions"; the copy item is no app, so it cannot be held.
        itemView.isLongClickable = item.canBeAction
        ViewCompat.replaceAccessibilityAction(
            itemView,
            AccessibilityActionCompat.ACTION_LONG_CLICK,
            itemView.context.getString(R.string.dejavu_share_add_to_actions).takeIf { item.canBeAction },
            null,
        )
    }

    companion object {
        @LayoutRes val LAYOUT_ID = R.layout.app_share_list_item
    }
}

// Dejavu: every item of the share sheet's apps can become a share action, except the one that copies the link.
private val AppShareOption.canBeAction: Boolean
    get() = packageName != ACTION_COPY_LINK_TO_CLIPBOARD

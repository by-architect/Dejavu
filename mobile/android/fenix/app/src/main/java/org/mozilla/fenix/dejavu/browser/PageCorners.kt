/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Rounds the corners of the page on the side of the docked sidebar. The page is a SurfaceView, which a rounded clip
 * does not cut, so this draws the sidebar's own background over the two corners instead, see [workspaceTheme]. It
 * takes no touches.
 */
@Composable
fun PageCorners() {
    val theme = shownSidebarTheme()
    Box(modifier = Modifier.fillMaxSize()) {
        listOf(Alignment.TopStart to true, Alignment.BottomStart to false).forEach { (alignment, top) ->
            Box(
                modifier = Modifier
                    .align(alignment)
                    .size(CornerRadius)
                    .clip(cornerOutside(top))
                    .background(MaterialTheme.colorScheme.surface)
                    .workspaceTheme(theme),
            )
        }
    }
}

/** The part of a square outside the quarter circle that rounds its corner, at the top or bottom, on the start side. */
private fun cornerOutside(top: Boolean) = GenericShape { size, layoutDirection ->
    val start = layoutDirection == LayoutDirection.Ltr
    val circle = Path().apply {
        addOval(
            Rect(
                left = if (start) 0f else -size.width,
                top = if (top) 0f else -size.height,
                right = if (start) size.width * 2 else size.width,
                bottom = if (top) size.height * 2 else size.height,
            ),
        )
    }
    val square = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
    op(square, circle, PathOperation.Difference)
}

private val CornerRadius = 16.dp

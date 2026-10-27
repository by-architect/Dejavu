/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.geckoview

import android.graphics.Bitmap

/**
 * Dejavu: pictures the top row of pixels of what [view] shows, scaled down to [samples] pixels, for the colors along the
 * page's top edge; `null` while [view] shows no page. Only GeckoView's own package reaches the display that pictures a
 * part of the page instead of all of it.
 */
fun captureTopEdge(view: GeckoView, samples: Int): GeckoResult<Bitmap>? = captureRow(view, 0, samples)

/**
 * Dejavu: pictures the row of pixels [y] pixels down what [view] shows, scaled down to [samples] pixels, like
 * [captureTopEdge] does with its top row; `null` while [view] shows no page.
 */
fun captureRow(view: GeckoView, y: Int, samples: Int): GeckoResult<Bitmap>? {
    val display = view.session?.display ?: return null
    if (view.width == 0 || view.height == 0) return null
    return display.screenshot().source(0, y.coerceIn(0, view.height - 1), view.width, 1).size(samples, 1).capture()
}

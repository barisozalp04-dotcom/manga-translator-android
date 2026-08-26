package com.manga.translate.rendering

import android.graphics.RectF

/**
 * Places translated text inside a bubble. Cross-page union bubbles keep a single
 * layout origin at the top of the previous-page union so the next page can draw
 * the same layout as a continuation.
 */
internal object BubbleTextPlacement {
    private const val CROSS_PAGE_OVERFLOW_PX = 1f

    fun spillsAcrossPage(rect: RectF, pageHeight: Int): Boolean {
        if (pageHeight <= 0) return false
        return rect.top < -CROSS_PAGE_OVERFLOW_PX ||
            rect.bottom > pageHeight + CROSS_PAGE_OVERFLOW_PX
    }

    fun horizontalTextLeft(rect: RectF, layoutWidth: Int): Float {
        return (rect.left + rect.right - layoutWidth) / 2f
    }

    fun horizontalTextTop(
        rect: RectF,
        layoutHeight: Int,
        startFromTop: Boolean
    ): Float {
        return if (startFromTop) {
            rect.top
        } else {
            (rect.top + rect.bottom - layoutHeight) / 2f
        }
    }

    fun verticalExtraTopPadding(
        rect: RectF,
        layoutHeight: Float,
        startFromTop: Boolean
    ): Float {
        if (startFromTop) return 0f
        return (rect.height() - layoutHeight) / 2f
    }
}

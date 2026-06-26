package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

internal object BubbleColorSampler {

    private const val SAMPLE_STEP = 4

    fun sampleBackgroundColor(
        bitmap: Bitmap?,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Int? {
        val src = bitmap ?: return null
        val bitmapWidth = src.width
        val bitmapHeight = src.height
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null

        val leftPx = left.toInt().coerceIn(0, bitmapWidth - 1)
        val topPx = top.toInt().coerceIn(0, bitmapHeight - 1)
        val rightPx = right.toInt().coerceIn(leftPx + 1, bitmapWidth)
        val bottomPx = bottom.toInt().coerceIn(topPx + 1, bitmapHeight)

        val regionWidth = rightPx - leftPx
        val regionHeight = bottomPx - topPx
        if (regionWidth <= 0 || regionHeight <= 0) return null

        val stepX = SAMPLE_STEP.coerceAtLeast(1)
        val stepY = SAMPLE_STEP.coerceAtLeast(1)

        val tempBitmap = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            null
        }
        val samplingBitmap = tempBitmap ?: src
        try {
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0

            val yEnd = bottomPx - 1
            val xEnd = rightPx - 1
            var y = topPx
            while (y <= yEnd) {
                var x = leftPx
                while (x <= xEnd) {
                    val pixel = samplingBitmap.getPixel(x, y)
                    r += Color.red(pixel)
                    g += Color.green(pixel)
                    b += Color.blue(pixel)
                    count++
                    x += stepX
                }
                y += stepY
            }

            if (count == 0) return null
            return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        } finally {
            tempBitmap?.recycle()
        }
    }

    fun sampleBackgroundColor(bitmap: Bitmap?, rect: RectF): Int? {
        return sampleBackgroundColor(bitmap, rect.left, rect.top, rect.right, rect.bottom)
    }
}

package com.manga.translate.platform

import android.graphics.Bitmap
import android.graphics.RectF

fun cropBitmap(source: Bitmap, rect: RectF): Bitmap? {
    val left = rect.left.toInt().coerceIn(0, source.width - 1)
    val top = rect.top.toInt().coerceIn(0, source.height - 1)
    val right = rect.right.toInt().coerceIn(1, source.width)
    val bottom = rect.bottom.toInt().coerceIn(1, source.height)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return null
    return Bitmap.createBitmap(source, left, top, width, height)
}

fun Bitmap?.recycleSafely() {
    if (this != null && !isRecycled) {
        recycle()
    }
}

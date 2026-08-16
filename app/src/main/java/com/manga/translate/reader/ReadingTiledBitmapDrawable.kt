package com.manga.translate.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.withTranslation

data class ReadingBitmapTile(
    val bitmap: Bitmap,
    val top: Int
)

class ReadingTiledBitmapDrawable(
    private val tiles: List<ReadingBitmapTile>,
    private val imageWidth: Int,
    private val imageHeight: Int
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val destRect = RectF()

    override fun draw(canvas: Canvas) {
        if (imageWidth <= 0 || imageHeight <= 0 || tiles.isEmpty()) return
        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        canvas.withTranslation(bounds.left.toFloat(), bounds.top.toFloat()) {
            scale(
                bounds.width() / imageWidth.toFloat(),
                bounds.height() / imageHeight.toFloat()
            )
            for (tile in tiles) {
                val bitmap = tile.bitmap
                if (bitmap.isRecycled) continue
                destRect.set(
                    0f,
                    tile.top.toFloat(),
                    bitmap.width.toFloat(),
                    (tile.top + bitmap.height).toFloat()
                )
                drawBitmap(bitmap, null, destRect, paint)
            }
        }
    }

    override fun getIntrinsicWidth(): Int = imageWidth

    override fun getIntrinsicHeight(): Int = imageHeight

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        fun empty(width: Int, height: Int): ReadingTiledBitmapDrawable {
            return ReadingTiledBitmapDrawable(
                tiles = emptyList(),
                imageWidth = width.coerceAtLeast(1),
                imageHeight = height.coerceAtLeast(1)
            )
        }

        fun single(bitmap: Bitmap): ReadingTiledBitmapDrawable {
            return ReadingTiledBitmapDrawable(
                tiles = listOf(ReadingBitmapTile(bitmap = bitmap, top = 0)),
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
        }
    }
}

package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import com.manga.translate.rendering.BubbleColorSampler
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleColorSamplerTest {

    @Test
    fun `black text does not turn a white background gray`() {
        val bitmap = sampledPattern(background = Color.WHITE, foreground = Color.BLACK, foregroundColumns = 1)

        assertEquals(Color.WHITE, sample(bitmap))
    }

    @Test
    fun `black text is removed before mixing a colored background`() {
        val background = Color.rgb(208, 176, 128)
        val bitmap = sampledPattern(background = background, foreground = Color.BLACK, foregroundColumns = 1)

        assertEquals(background, sample(bitmap))
    }

    @Test
    fun `black remains the result when it is the main color`() {
        val bitmap = sampledPattern(background = Color.BLACK, foreground = Color.WHITE, foregroundColumns = 1)

        assertEquals(Color.BLACK, sample(bitmap))
    }

    @Test
    fun `black text is removed from a mid gray background`() {
        val background = Color.rgb(128, 128, 128)
        val bitmap = sampledPattern(background = background, foreground = Color.BLACK, foregroundColumns = 1)

        assertEquals(background, sample(bitmap))
    }

    private fun sampledPattern(background: Int, foreground: Int, foregroundColumns: Int): Bitmap {
        return Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(background)
            for (sampleX in 0 until foregroundColumns) {
                for (y in 0 until height step 4) {
                    setPixel(sampleX * 4, y, foreground)
                }
            }
        }
    }

    private fun sample(bitmap: Bitmap): Int {
        return requireNotNull(
            BubbleColorSampler.sampleBackgroundColor(
                bitmap = bitmap,
                left = 0f,
                top = 0f,
                right = bitmap.width.toFloat(),
                bottom = bitmap.height.toFloat()
            )
        )
    }
}

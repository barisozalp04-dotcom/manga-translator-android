package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import com.manga.translate.detection.OnnxImagePreprocessor
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnnxImagePreprocessorTest {

    @Test
    fun `rgb chw buffer is normalized for Ultralytics inputs`() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.rgb(255, 128, 0))

        try {
            assertArrayEquals(
                floatArrayOf(1f, 128f / 255f, 0f),
                OnnxImagePreprocessor.bitmapToRgbChwFloat(bitmap),
                1e-6f
            )
        } finally {
            bitmap.recycle()
        }
    }
}

package com.manga.translate

import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PipelineBitmapDecoderTest {
    @Test
    fun `in-memory full-page crop owns its bitmap`() = runBlocking {
        val mutableSource = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val source = requireNotNull(mutableSource.copy(Bitmap.Config.ARGB_8888, false))
        mutableSource.recycle()

        val decoded = PipelineBitmapDecoder.openCropSource(source).use { cropSource ->
            cropSource.decodeRegion(
                RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
                maxEdge = DETECTION_MAX_EDGE
            )
        }

        assertNotNull(decoded)
        assertNotSame(source, decoded)
        decoded.recycleSafely()
        assertFalse(source.isRecycled)
        source.recycleSafely()
    }
}

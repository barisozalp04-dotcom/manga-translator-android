package com.manga.translate

import com.manga.translate.reader.ReadingBitmapDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingBitmapDecoderTest {

    @Test
    fun `long image sampling preserves readable width`() {
        val sample = ReadingBitmapDecoder.calculateInSampleSize(
            sourceWidth = 1080,
            sourceHeight = 24000,
            targetWidth = 2160,
            targetHeight = 4800
        )

        assertEquals(1, sample)
    }

    @Test
    fun `long image decode is planned as short source tiles`() {
        val tiles = ReadingBitmapDecoder.planSourceTiles(
            sourceWidth = 1080,
            sourceHeight = 24000,
            sampleSize = 1
        )

        assertEquals(7, tiles.size)
        assertEquals(0, tiles.first().top)
        assertEquals(1080, tiles.first().right)
        assertEquals(24000, tiles.last().bottom)
        tiles.forEach { tile ->
            val height = tile.bottom - tile.top
            assertTrue(height in 1..3883)
        }
    }

    @Test
    fun `regular image sampling still respects long edge guard`() {
        val sample = ReadingBitmapDecoder.calculateInSampleSize(
            sourceWidth = 12000,
            sourceHeight = 6000,
            targetWidth = 2160,
            targetHeight = 4800
        )

        assertEquals(2, sample)
    }

    @Test
    fun `tiled decode triggers for long images and high resolution pages`() {
        assertTrue(ReadingBitmapDecoder.shouldUseTiledDecode(1080, 24000))
        assertTrue(ReadingBitmapDecoder.shouldUseTiledDecode(4000, 7000))
        assertTrue(ReadingBitmapDecoder.shouldUseTiledDecode(5000, 5000))
        assertFalse(ReadingBitmapDecoder.shouldUseTiledDecode(1600, 2400))
        assertFalse(ReadingBitmapDecoder.shouldUseTiledDecode(2400, 3600))
    }

    @Test
    fun `decode sample size follows display scale for full-res layout`() {
        assertEquals(4, ReadingBitmapDecoder.calculateDecodeSampleSize(layoutSampleSize = 1, displayScale = 0.2f))
        assertEquals(2, ReadingBitmapDecoder.calculateDecodeSampleSize(layoutSampleSize = 1, displayScale = 0.5f))
        assertEquals(1, ReadingBitmapDecoder.calculateDecodeSampleSize(layoutSampleSize = 1, displayScale = 1.0f))
        assertEquals(1, ReadingBitmapDecoder.calculateDecodeSampleSize(layoutSampleSize = 1, displayScale = 2.5f))
        assertEquals(2, ReadingBitmapDecoder.calculateDecodeSampleSize(layoutSampleSize = 2, displayScale = 1.0f))
    }
}

package com.manga.translate

import com.manga.translate.platform.PipelineBitmapDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineBitmapSamplingTest {
    @Test
    fun `whole-image fallback bounds large decodes with power-of-two sampling`() {
        assertEquals(1, PipelineBitmapDecoder.calculateFallbackSampleSize(1280, 1847))
        assertEquals(2, PipelineBitmapDecoder.calculateFallbackSampleSize(4000, 6000))
        assertEquals(4, PipelineBitmapDecoder.calculateFallbackSampleSize(8000, 12000))
    }
}

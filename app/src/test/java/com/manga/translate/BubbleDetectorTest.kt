package com.manga.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleDetectorTest {
    @Test
    fun `balloon confidence uses conservative hard floor`() {
        assertEquals(
            TranslationCoreDefaults.MinBalloonConfidence,
            effectiveDetectionConfidenceThreshold(BubbleDetector.CLASS_BALLOON, 0.10f),
            1e-6f
        )
        assertEquals(
            0.25f,
            effectiveDetectionConfidenceThreshold(BubbleDetector.CLASS_BALLOON, 0.25f),
            1e-6f
        )
    }

    @Test
    fun `non-balloon classes keep configured confidence`() {
        assertEquals(
            0.10f,
            effectiveDetectionConfidenceThreshold(BubbleDetector.CLASS_TEXT, 0.10f),
            1e-6f
        )
    }
}

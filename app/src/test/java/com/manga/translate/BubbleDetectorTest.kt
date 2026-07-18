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

    @Test
    fun `comic output chooses the strongest class score`() {
        assertEquals(
            YoloClassScore(classId = BubbleDetector.CLASS_TEXT, confidence = 0.82f),
            bestYoloClassScore(floatArrayOf(320f, 320f, 100f, 80f, 0.12f, 0.82f))
        )
    }

    @Test
    fun `single class text output uses its only class score`() {
        assertEquals(
            YoloClassScore(classId = 0, confidence = 0.73f),
            bestYoloClassScore(floatArrayOf(320f, 320f, 100f, 80f, 0.73f))
        )
    }
}

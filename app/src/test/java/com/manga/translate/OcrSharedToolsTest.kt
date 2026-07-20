package com.manga.translate

import com.manga.translate.model.BubbleSource
import com.manga.translate.ocr.shouldRejectFreeTextWithoutLines
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSharedToolsTest {
    @Test
    fun `free text is rejected when an available line detector finds no lines`() {
        assertTrue(
            shouldRejectFreeTextWithoutLines(
                source = BubbleSource.TEXT_DETECTOR,
                lineDetectorAvailable = true,
                detectedLineCount = 0
            )
        )
    }

    @Test
    fun `line validation fails open for unavailable detector and normal bubbles`() {
        assertFalse(
            shouldRejectFreeTextWithoutLines(
                source = BubbleSource.TEXT_DETECTOR,
                lineDetectorAvailable = false,
                detectedLineCount = 0
            )
        )
        assertFalse(
            shouldRejectFreeTextWithoutLines(
                source = BubbleSource.BUBBLE_DETECTOR,
                lineDetectorAvailable = true,
                detectedLineCount = 0
            )
        )
        assertFalse(
            shouldRejectFreeTextWithoutLines(
                source = BubbleSource.TEXT_DETECTOR,
                lineDetectorAvailable = true,
                detectedLineCount = 1
            )
        )
    }
}

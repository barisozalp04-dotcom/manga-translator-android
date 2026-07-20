package com.manga.translate

import com.manga.translate.reader.canPanReadingImageHorizontally
import com.manga.translate.reader.resolveHorizontalEdgeSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingImageTransformControllerTest {
    @Test
    fun `fit height overflow can pan toward content that remains off screen`() {
        assertTrue(
            canPanReadingImageHorizontally(
                hasHorizontalOverflow = true,
                dragDeltaX = -24f,
                imageLeft = -300f,
                imageRight = 1300f,
                viewportWidth = 1000f
            )
        )
        assertTrue(
            canPanReadingImageHorizontally(
                hasHorizontalOverflow = true,
                dragDeltaX = 24f,
                imageLeft = -300f,
                imageRight = 1300f,
                viewportWidth = 1000f
            )
        )
    }

    @Test
    fun `horizontal drag is released for page turning at image edges`() {
        assertFalse(
            canPanReadingImageHorizontally(
                hasHorizontalOverflow = true,
                dragDeltaX = -24f,
                imageLeft = -600f,
                imageRight = 1000f,
                viewportWidth = 1000f
            )
        )
        assertFalse(
            canPanReadingImageHorizontally(
                hasHorizontalOverflow = true,
                dragDeltaX = 24f,
                imageLeft = 0f,
                imageRight = 1600f,
                viewportWidth = 1000f
            )
        )
    }

    @Test
    fun `unconsumed edge drag resolves to the existing page swipe directions`() {
        assertEquals(-1, resolveHorizontalEdgeSwipeDirection(-24f, 16f))
        assertEquals(1, resolveHorizontalEdgeSwipeDirection(24f, 16f))
        assertNull(resolveHorizontalEdgeSwipeDirection(12f, 16f))
    }
}

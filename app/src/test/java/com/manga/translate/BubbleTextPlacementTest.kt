package com.manga.translate

import android.graphics.RectF
import com.manga.translate.rendering.BubbleTextPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

class BubbleTextPlacementTest {
    @Test
    fun `union bubble that overflows the previous page starts text at the top`() {
        val union = RectF(100f, 1500f, 300f, 1900f)

        assertTrue(BubbleTextPlacement.spillsAcrossPage(union, pageHeight = 1600))
        assertEquals(
            1500f,
            BubbleTextPlacement.horizontalTextTop(union, layoutHeight = 80, startFromTop = true),
            0.01f
        )
    }

    @Test
    fun `projected continuation keeps the same top-origin layout in next-page coordinates`() {
        val previousUnion = RectF(100f, 1500f, 300f, 1900f)
        val projected = RectF(
            previousUnion.left,
            previousUnion.top - 1600f,
            previousUnion.right,
            previousUnion.bottom - 1600f
        )
        val layoutHeight = 80

        val previousTop = BubbleTextPlacement.horizontalTextTop(
            previousUnion,
            layoutHeight,
            startFromTop = true
        )
        val continuationTop = BubbleTextPlacement.horizontalTextTop(
            projected,
            layoutHeight,
            startFromTop = true
        )

        assertTrue(BubbleTextPlacement.spillsAcrossPage(projected, pageHeight = 1200))
        assertEquals(-100f, projected.top, 0.01f)
        assertEquals(previousTop - 1600f, continuationTop, 0.01f)
        assertEquals(projected.top, continuationTop, 0.01f)
    }

    @Test
    fun `in-page bubbles stay vertically centered`() {
        val rect = RectF(40f, 200f, 240f, 360f)

        assertFalse(BubbleTextPlacement.spillsAcrossPage(rect, pageHeight = 1600))
        assertEquals(
            240f,
            BubbleTextPlacement.horizontalTextTop(rect, layoutHeight = 80, startFromTop = false),
            0.01f
        )
        assertEquals(
            40f,
            BubbleTextPlacement.verticalExtraTopPadding(rect, layoutHeight = 80f, startFromTop = false),
            0.01f
        )
    }
}

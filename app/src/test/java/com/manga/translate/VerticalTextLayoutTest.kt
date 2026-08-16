package com.manga.translate

import android.text.TextPaint
import com.manga.translate.rendering.VerticalTextLayoutCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VerticalTextLayoutTest {
    @Test
    fun shortTextUsesOnlyRowsActuallyOccupied() {
        val paint = TextPaint().apply { textSize = 24f }
        val layout = VerticalTextLayoutCalculator.build(
            textPaint = paint,
            text = "短文",
            maxWidth = 500,
            maxHeight = 2_000,
            textSize = 24f
        )

        assertEquals(layout.lineHeight * 2f, layout.totalHeight, 0.01f)
        assertTrue(layout.totalHeight < layout.maxRows * layout.lineHeight)
    }
}

package com.manga.translate.rendering

import android.graphics.Canvas
import android.graphics.RectF
import android.text.TextPaint

/** Shared vertical glyph placement used by export, reader and floating overlays. */
internal object VerticalTextRenderer {
    fun draw(
        canvas: Canvas,
        text: String,
        rect: RectF,
        textPaint: TextPaint,
        layout: VerticalTextLayout,
        startFromTop: Boolean = false
    ) {
        val extraTop = BubbleTextPlacement.verticalExtraTopPadding(
            rect = rect,
            layoutHeight = layout.totalHeight,
            startFromTop = startFromTop
        )
        val dx = rect.right - ((rect.width() - layout.totalWidth) / 2f) - layout.columnWidth
        val dy = rect.top + extraTop - layout.fontMetrics.ascent
        var col = 0
        var row = 0
        for (ch in text) {
            if (ch == '\n') {
                col += 1
                row = 0
                continue
            }
            if (row >= layout.maxRows) {
                col += 1
                row = 0
            }
            if (col >= layout.columns) break
            val glyph = ch.toString()
            val charWidth = textPaint.measureText(glyph)
            val x = dx - col * layout.columnWidth + (layout.columnWidth - charWidth) / 2f
            val y = dy + row * layout.lineHeight
            canvas.drawText(glyph, x, y, textPaint)
            row += 1
        }
    }
}

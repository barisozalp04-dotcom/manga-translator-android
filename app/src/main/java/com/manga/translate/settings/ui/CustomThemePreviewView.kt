package com.manga.translate.settings.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.manga.translate.settings.CustomThemeColors
import com.manga.translate.theming.ThemePalette

class CustomThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var colors: CustomThemeColors = CustomThemeColors.DEFAULT
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val palette = ThemePalette.from(colors)
        val density = resources.displayMetrics.density
        val radius = 8f * density
        paint.color = palette.background
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
        val margin = 14f * density
        val card = RectF(margin, margin, width - margin, height - margin)
        paint.color = palette.surface
        canvas.drawRoundRect(card, radius, radius, paint)
        paint.shader = android.graphics.LinearGradient(
            card.left,
            card.top,
            card.right,
            card.top,
            palette.heroStart,
            palette.heroEnd,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            RectF(card.left, card.top, card.right, card.top + 32f * density),
            radius,
            radius,
            paint
        )
        paint.shader = null
        paint.color = palette.foreground
        canvas.drawRoundRect(
            RectF(card.left + 14f * density, card.top + 50f * density, card.right - 45f * density, card.top + 56f * density),
            3f * density,
            3f * density,
            paint
        )
        paint.color = palette.mutedForeground
        canvas.drawRoundRect(
            RectF(card.left + 14f * density, card.top + 65f * density, card.right - 70f * density, card.top + 70f * density),
            3f * density,
            3f * density,
            paint
        )
        val button = RectF(
            card.right - 92f * density,
            card.bottom - 36f * density,
            card.right - 14f * density,
            card.bottom - 12f * density
        )
        paint.color = palette.buttonFill
        canvas.drawRoundRect(
            button,
            8f * density,
            8f * density,
            paint
        )
        paint.color = palette.buttonText
        canvas.drawRoundRect(
            RectF(
                button.left + 18f * density,
                button.centerY() - 2f * density,
                button.right - 18f * density,
                button.centerY() + 2f * density
            ),
            2f * density,
            2f * density,
            paint
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = palette.outline
        canvas.drawRoundRect(card, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }
}

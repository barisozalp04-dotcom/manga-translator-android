package com.manga.translate.settings.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.manga.translate.settings.CustomThemeColors
import com.manga.translate.theming.ThemePalette

class CustomThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val radius = 8f * density
    private val lineRadius = 3f * density
    private val buttonTextRadius = 2f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundBounds = RectF()
    private val cardBounds = RectF()
    private val heroBounds = RectF()
    private val titleBounds = RectF()
    private val subtitleBounds = RectF()
    private val buttonBounds = RectF()
    private val buttonTextBounds = RectF()
    private var palette = ThemePalette.from(CustomThemeColors.DEFAULT)
    private var heroGradient: LinearGradient? = null
    var colors: CustomThemeColors = CustomThemeColors.DEFAULT
        set(value) {
            field = value
            palette = ThemePalette.from(value)
            updateHeroGradient()
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = palette.background
        canvas.drawRoundRect(backgroundBounds, radius, radius, paint)
        paint.color = palette.surface
        canvas.drawRoundRect(cardBounds, radius, radius, paint)
        paint.shader = heroGradient
        canvas.drawRoundRect(heroBounds, radius, radius, paint)
        paint.shader = null
        paint.color = palette.foreground
        canvas.drawRoundRect(titleBounds, lineRadius, lineRadius, paint)
        paint.color = palette.mutedForeground
        canvas.drawRoundRect(subtitleBounds, lineRadius, lineRadius, paint)
        paint.color = palette.buttonFill
        canvas.drawRoundRect(buttonBounds, radius, radius, paint)
        paint.color = palette.buttonText
        canvas.drawRoundRect(buttonTextBounds, buttonTextRadius, buttonTextRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = palette.outline
        canvas.drawRoundRect(cardBounds, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = 14f * density
        backgroundBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        cardBounds.set(margin, margin, w - margin, h - margin)
        heroBounds.set(cardBounds.left, cardBounds.top, cardBounds.right, cardBounds.top + 32f * density)
        titleBounds.set(
            cardBounds.left + 14f * density,
            cardBounds.top + 50f * density,
            cardBounds.right - 45f * density,
            cardBounds.top + 56f * density
        )
        subtitleBounds.set(
            cardBounds.left + 14f * density,
            cardBounds.top + 65f * density,
            cardBounds.right - 70f * density,
            cardBounds.top + 70f * density
        )
        buttonBounds.set(
            cardBounds.right - 92f * density,
            cardBounds.bottom - 36f * density,
            cardBounds.right - 14f * density,
            cardBounds.bottom - 12f * density
        )
        buttonTextBounds.set(
            buttonBounds.left + 18f * density,
            buttonBounds.centerY() - 2f * density,
            buttonBounds.right - 18f * density,
            buttonBounds.centerY() + 2f * density
        )
        updateHeroGradient()
    }

    private fun updateHeroGradient() {
        heroGradient = if (cardBounds.width() > 0f) {
            LinearGradient(
                cardBounds.left,
                cardBounds.top,
                cardBounds.right,
                cardBounds.top,
                palette.heroStart,
                palette.heroEnd,
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }
    }
}

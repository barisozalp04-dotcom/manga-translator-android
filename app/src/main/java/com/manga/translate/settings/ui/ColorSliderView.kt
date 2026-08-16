package com.manga.translate.settings.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class ColorSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val thumbRadius = 10f * density
    private val trackRadius = 5f * density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        color = Color.DKGRAY
    }
    private val trackBounds = RectF()
    private var gradientColors = intArrayOf(Color.BLACK, Color.WHITE)
    var value: Float = 0f
        set(newValue) {
            field = newValue.coerceIn(0f, 1f)
            invalidate()
        }
    var onValueChanged: ((Float) -> Unit)? = null

    fun setGradient(colors: IntArray) {
        gradientColors = colors
        updateTrackGradient()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val preferredHeight = (40f * resources.displayMetrics.density).roundToInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(preferredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(trackBounds, trackRadius, trackRadius, trackPaint)
        val x = trackBounds.left + value * trackBounds.width()
        thumbPaint.color = interpolateGradient(value)
        canvas.drawCircle(x, trackBounds.centerY(), thumbRadius, thumbPaint)
        canvas.drawCircle(x, trackBounds.centerY(), thumbRadius, thumbStrokePaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val centerY = h / 2f
        trackBounds.set(
            paddingLeft + thumbRadius,
            centerY - trackRadius,
            w - paddingRight - thumbRadius,
            centerY + trackRadius
        )
        updateTrackGradient()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromTouch(event.x)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float) {
        val left = paddingLeft + thumbRadius
        val right = width - paddingRight - thumbRadius
        if (right <= left) return
        value = ((x - left) / (right - left)).coerceIn(0f, 1f)
        onValueChanged?.invoke(value)
    }

    private fun updateTrackGradient() {
        if (trackBounds.width() <= 0f || gradientColors.isEmpty()) {
            trackPaint.shader = null
            return
        }
        trackPaint.shader = LinearGradient(
            trackBounds.left,
            trackBounds.centerY(),
            trackBounds.right,
            trackBounds.centerY(),
            gradientColors,
            null,
            Shader.TileMode.CLAMP
        )
    }

    private fun interpolateGradient(position: Float): Int {
        if (gradientColors.size == 1) return gradientColors[0]
        val scaled = position * (gradientColors.size - 1)
        val index = scaled.toInt().coerceAtMost(gradientColors.lastIndex - 1)
        val fraction = scaled - index
        return androidx.core.graphics.ColorUtils.blendARGB(
            gradientColors[index],
            gradientColors[index + 1],
            fraction
        )
    }
}

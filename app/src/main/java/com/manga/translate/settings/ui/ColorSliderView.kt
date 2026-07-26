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
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = Color.DKGRAY
    }
    private var gradientColors = intArrayOf(Color.BLACK, Color.WHITE)
    var value: Float = 0f
        set(newValue) {
            field = newValue.coerceIn(0f, 1f)
            invalidate()
        }
    var onValueChanged: ((Float) -> Unit)? = null

    fun setGradient(colors: IntArray) {
        gradientColors = colors
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
        val density = resources.displayMetrics.density
        val thumbRadius = 10f * density
        val track = RectF(
            paddingLeft + thumbRadius,
            height / 2f - 5f * density,
            width - paddingRight - thumbRadius,
            height / 2f + 5f * density
        )
        trackPaint.shader = LinearGradient(
            track.left,
            track.centerY(),
            track.right,
            track.centerY(),
            gradientColors,
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(track, 5f * density, 5f * density, trackPaint)
        trackPaint.shader = null
        val x = track.left + value * track.width()
        thumbPaint.color = interpolateGradient(value)
        canvas.drawCircle(x, track.centerY(), thumbRadius, thumbPaint)
        canvas.drawCircle(x, track.centerY(), thumbRadius, thumbStrokePaint)
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
        val thumbRadius = 10f * resources.displayMetrics.density
        val left = paddingLeft + thumbRadius
        val right = width - paddingRight - thumbRadius
        if (right <= left) return
        value = ((x - left) / (right - left)).coerceIn(0f, 1f)
        onValueChanged?.invoke(value)
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

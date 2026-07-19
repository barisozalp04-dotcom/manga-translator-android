package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.ImageView
import kotlin.math.abs

class ReadingImageTransformController(
    context: Context,
    private val imageView: ImageView,
    private val hasBubbleAt: (x: Float, y: Float) -> Boolean,
    private val onMatrixUpdated: () -> Unit,
    private val allowPanWhenOverflowing: Boolean = true
) {
    private val baseMatrix = Matrix()
    private val imageMatrix = Matrix()
    private val imageRect = RectF()
    private val panTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var imageUserScale = 1f
    private var minScale = 1f
    private var maxScale = 3f
    private var verticalPanEnabled = true
    private var isScaling = false
    private var scaleHandled = false
    private var isPanning = false
    private var panHorizontal = false
    private var panVertical = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var startTouchX = 0f
    private var startTouchY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                scaleHandled = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!isScaling) return false
                if (!hasContent()) return false
                val newScale = (imageUserScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = newScale / imageUserScale
                if (abs(factor - 1f) <= 0.001f) return false
                imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                imageUserScale = newScale
                scaleHandled = true
                fixTranslation()
                applyImageMatrix()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                scaleHandled = false
            }
        }
    )

    private var currentBitmap: Bitmap? = null
    private var contentWidth: Int = 0
    private var contentHeight: Int = 0

    init {
        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.adjustViewBounds = false
    }

    fun setCurrentBitmap(bitmap: Bitmap?) {
        currentBitmap = bitmap
        contentWidth = bitmap?.width ?: 0
        contentHeight = bitmap?.height ?: 0
    }

    fun setCurrentContent(width: Int, height: Int) {
        currentBitmap = null
        contentWidth = width.coerceAtLeast(0)
        contentHeight = height.coerceAtLeast(0)
    }

    fun setVerticalPanEnabled(enabled: Boolean) {
        verticalPanEnabled = enabled
    }

    fun reset(bitmap: Bitmap, mode: ReadingDisplayMode) {
        resetContent(bitmap.width, bitmap.height, mode)
    }

    fun resetContent(width: Int, height: Int, mode: ReadingDisplayMode) {
        contentWidth = width.coerceAtLeast(0)
        contentHeight = height.coerceAtLeast(0)
        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.adjustViewBounds = false
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        if (!hasContent()) return
        val drawableWidth = contentWidth.toFloat()
        val drawableHeight = contentHeight.toFloat()
        val scale = when (mode) {
            ReadingDisplayMode.FIT_WIDTH -> viewWidth / drawableWidth
            ReadingDisplayMode.FIT_HEIGHT -> viewHeight / drawableHeight
        }
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val scaledHeight = drawableHeight * scale
        val dy = if (mode == ReadingDisplayMode.FIT_WIDTH && scaledHeight > viewHeight) {
            0f
        } else {
            (viewHeight - scaledHeight) / 2f
        }
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        imageMatrix.set(baseMatrix)
        imageUserScale = 1f
        minScale = 1f
        maxScale = 3f
        applyImageMatrix()
    }

    fun handleTouch(event: MotionEvent): Boolean {
        if (!hasContent()) return false
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            return true
        }
        val zoomed = imageUserScale > minScale + 0.01f
        val overflowAxes = if (allowPanWhenOverflowing) computeOverflowAxes() else OverflowAxes()
        val allowPan = (zoomed || overflowAxes.any) && !hasBubbleAt(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = event.x
                startTouchY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning = false
                panHorizontal = false
                panVertical = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (isScaling) return true
                if (allowPan) {
                    val movedX = event.x - startTouchX
                    val movedY = event.y - startTouchY
                    if (!isPanning) {
                        val canPanHorizontally = zoomed && overflowAxes.horizontal
                        val canPanVertically = verticalPanEnabled && overflowAxes.vertical
                        val horizontalIntent =
                            abs(movedX) > panTouchSlop && abs(movedX) >= abs(movedY)
                        val verticalIntent =
                            abs(movedY) > panTouchSlop && abs(movedY) > abs(movedX)
                        when {
                            horizontalIntent && canPanHorizontally -> {
                                isPanning = true
                                panHorizontal = true
                                panVertical = false
                            }
                            verticalIntent && canPanVertically -> {
                                isPanning = true
                                panHorizontal = false
                                panVertical = true
                            }
                        }
                    }
                    if (isPanning) {
                        val dx = if (panHorizontal) event.x - lastTouchX else 0f
                        val dy = if (panVertical) event.y - lastTouchY else 0f
                        imageMatrix.postTranslate(dx, dy)
                        fixTranslation()
                        applyImageMatrix()
                        lastTouchX = event.x
                        lastTouchY = event.y
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = isPanning || isScaling || scaleHandled
                isPanning = false
                panHorizontal = false
                panVertical = false
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isScaling = false
                }
                scaleHandled = false
                return handled
            }
        }
        return isPanning || isScaling || scaleHandled
    }

    fun toggleDoubleTapZoom(x: Float, y: Float): Boolean {
        if (!hasContent()) return false
        if (imageView.width <= 0 || imageView.height <= 0) return false
        if (isZoomed()) {
            imageMatrix.set(baseMatrix)
            imageUserScale = minScale
        } else {
            val targetScale = 2f.coerceIn(minScale, maxScale)
            val factor = targetScale / imageUserScale
            imageMatrix.postScale(factor, factor, x, y)
            imageUserScale = targetScale
            fixTranslation()
        }
        applyImageMatrix()
        return true
    }

    fun isZoomed(): Boolean {
        return imageUserScale > minScale + 0.01f
    }

    fun currentContentZoomScale(): Float {
        return imageUserScale.coerceAtLeast(1f)
    }

    fun resetZoom() {
        if (!hasContent()) return
        imageMatrix.set(baseMatrix)
        imageUserScale = minScale
        applyImageMatrix()
    }

    fun computeImageDisplayRect(): RectF? {
        val drawable = imageView.drawable ?: return null
        val rect = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        imageView.imageMatrix.mapRect(rect)
        rect.offset(imageView.left.toFloat(), imageView.top.toFloat())
        return rect
    }

    private fun applyImageMatrix() {
        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.adjustViewBounds = false
        imageView.imageMatrix = Matrix(imageMatrix)
        imageView.requestLayout()
        imageView.invalidate()
        onMatrixUpdated()
    }

    private fun fixTranslation() {
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        if (!hasContent()) return
        imageRect.set(0f, 0f, contentWidth.toFloat(), contentHeight.toFloat())
        imageMatrix.mapRect(imageRect)
        var dx = 0f
        var dy = 0f
        if (imageRect.width() <= viewWidth) {
            dx = (viewWidth - imageRect.width()) / 2f - imageRect.left
        } else {
            if (imageRect.left > 0f) dx = -imageRect.left
            if (imageRect.right < viewWidth) dx = viewWidth - imageRect.right
        }
        if (imageRect.height() <= viewHeight) {
            dy = (viewHeight - imageRect.height()) / 2f - imageRect.top
        } else {
            if (imageRect.top > 0f) dy = -imageRect.top
            if (imageRect.bottom < viewHeight) dy = viewHeight - imageRect.bottom
        }
        imageMatrix.postTranslate(dx, dy)
    }

    private fun computeOverflowAxes(): OverflowAxes {
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return OverflowAxes()
        if (!hasContent()) return OverflowAxes()
        imageRect.set(0f, 0f, contentWidth.toFloat(), contentHeight.toFloat())
        imageMatrix.mapRect(imageRect)
        return OverflowAxes(
            horizontal = imageRect.width() > viewWidth + 0.5f,
            vertical = imageRect.height() > viewHeight + 0.5f
        )
    }

    private data class OverflowAxes(
        val horizontal: Boolean = false,
        val vertical: Boolean = false
    ) {
        val any: Boolean
            get() = horizontal || vertical
    }

    private fun hasContent(): Boolean {
        return contentWidth > 0 && contentHeight > 0
    }
}

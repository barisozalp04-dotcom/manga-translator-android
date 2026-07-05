package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.LruCache
import android.view.ViewTreeObserver
import androidx.appcompat.widget.AppCompatImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class ReadingRegionImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private data class TileKey(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val sampleSize: Int
    )

    private data class DecodeRequest(
        val key: TileKey,
        val sourceRect: Rect
    )

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val missingTilePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
    }
    private val contentMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val visibleRect = RectF()
    private val viewVisibleRect = Rect()
    private val prefetchRect = RectF()
    private val tileDrawRect = RectF()
    private val decodeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tileCache = object : LruCache<TileKey, Bitmap>(tileCacheMaxKb()) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }

        override fun entryRemoved(evicted: Boolean, key: TileKey, oldValue: Bitmap, newValue: Bitmap?) {
            if (newValue !== oldValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }
    private val decodeJobs = mutableMapOf<TileKey, Job>()
    private var source: ReadingRegionImageSource? = null
    private var decoder: BitmapRegionDecoder? = null
    private var decoderFile: File? = null
    private var generation = 0
    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener { invalidate() }

    fun setRegionSource(next: ReadingRegionImageSource?) {
        if (source == next) return
        generation += 1
        source = next
        decodeJobs.values.forEach { it.cancel() }
        decodeJobs.clear()
        tileCache.evictAll()
        closeDecoder()
        invalidate()
    }

    override fun setImageMatrix(matrix: Matrix?) {
        super.setImageMatrix(matrix)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val activeSource = source
        if (activeSource == null) {
            super.onDraw(canvas)
            return
        }
        if (width <= 0 || height <= 0) return
        if (!computeVisibleDisplayRect(activeSource)) return
        val visibleRequests = planTiles(activeSource, visibleRect)
        val prefetchRequests = planPrefetchTiles(activeSource, visibleRequests)
        cancelStaleDecodeJobs((visibleRequests + prefetchRequests).mapTo(hashSetOf()) { it.key })
        val save = canvas.save()
        canvas.concat(contentMatrix)
        for (request in visibleRequests) {
            val bitmap = tileCache.get(request.key)
            tileDrawRect.set(
                request.key.left.toFloat(),
                request.key.top.toFloat(),
                request.key.right.toFloat(),
                request.key.bottom.toFloat()
            )
            if (bitmap != null && !bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, null, tileDrawRect, paint)
            } else {
                canvas.drawRect(tileDrawRect, missingTilePaint)
                enqueueDecode(activeSource, request, priority = true)
            }
        }
        canvas.restoreToCount(save)
        for (request in prefetchRequests) {
            enqueueDecode(activeSource, request, priority = false)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener)
        generation += 1
        decodeJobs.values.forEach { it.cancel() }
        decodeJobs.clear()
        tileCache.evictAll()
        closeDecoder()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
    }

    private fun computeVisibleDisplayRect(activeSource: ReadingRegionImageSource): Boolean {
        contentMatrix.set(imageMatrix)
        if (!contentMatrix.invert(inverseMatrix)) return false
        if (!getLocalVisibleRect(viewVisibleRect)) return false
        visibleRect.set(viewVisibleRect)
        if (visibleRect.width() <= 0f || visibleRect.height() <= 0f) return false
        inverseMatrix.mapRect(visibleRect)
        val displayWidth = displayWidth(activeSource).toFloat()
        val displayHeight = displayHeight(activeSource).toFloat()
        if (!visibleRect.intersect(0f, 0f, displayWidth, displayHeight)) {
            return false
        }
        return visibleRect.width() > 0f && visibleRect.height() > 0f
    }

    private fun planTiles(
        activeSource: ReadingRegionImageSource,
        displayRect: RectF
    ): List<DecodeRequest> {
        val displayWidth = displayWidth(activeSource)
        val displayHeight = displayHeight(activeSource)
        val tileSize = displayTileSize()
        val leftIndex = floor(displayRect.left / tileSize).toInt().coerceAtLeast(0)
        val topIndex = floor(displayRect.top / tileSize).toInt().coerceAtLeast(0)
        val rightIndex = ceil(displayRect.right / tileSize).toInt().coerceAtLeast(leftIndex + 1)
        val bottomIndex = ceil(displayRect.bottom / tileSize).toInt().coerceAtLeast(topIndex + 1)
        val result = ArrayList<DecodeRequest>()
        for (tileY in topIndex until bottomIndex) {
            for (tileX in leftIndex until rightIndex) {
                val displayLeft = (tileX * tileSize).coerceAtMost(displayWidth)
                val displayTop = (tileY * tileSize).coerceAtMost(displayHeight)
                val displayRight = ((tileX + 1) * tileSize).coerceAtMost(displayWidth)
                val displayBottom = ((tileY + 1) * tileSize).coerceAtMost(displayHeight)
                if (displayRight <= displayLeft || displayBottom <= displayTop) continue
                val key = TileKey(
                    left = displayLeft,
                    top = displayTop,
                    right = displayRight,
                    bottom = displayBottom,
                    sampleSize = activeSource.sampleSize
                )
                result += DecodeRequest(
                    key = key,
                    sourceRect = Rect(
                        displayLeft * activeSource.sampleSize,
                        displayTop * activeSource.sampleSize,
                        (displayRight * activeSource.sampleSize).coerceAtMost(activeSource.sourceWidth),
                        (displayBottom * activeSource.sampleSize).coerceAtMost(activeSource.sourceHeight)
                    )
                )
            }
        }
        return result
    }

    private fun planPrefetchTiles(
        activeSource: ReadingRegionImageSource,
        visibleRequests: List<DecodeRequest>
    ): List<DecodeRequest> {
        if (visibleRequests.isEmpty()) return emptyList()
        val displayHeight = displayHeight(activeSource).toFloat()
        val verticalMargin = visibleRect.height() * PREFETCH_VIEWPORT_MULTIPLIER
        prefetchRect.set(
            visibleRect.left,
            (visibleRect.top - verticalMargin).coerceAtLeast(0f),
            visibleRect.right,
            (visibleRect.bottom + verticalMargin).coerceAtMost(displayHeight)
        )
        if (prefetchRect == visibleRect) return emptyList()
        val visibleKeys = visibleRequests.mapTo(hashSetOf()) { it.key }
        return planTiles(activeSource, prefetchRect)
            .filterNot { it.key in visibleKeys }
    }

    private fun cancelStaleDecodeJobs(activeKeys: Set<TileKey>) {
        if (decodeJobs.isEmpty()) return
        val iterator = decodeJobs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val key = entry.key
            val job = entry.value
            if (key !in activeKeys) {
                job.cancel()
                iterator.remove()
            }
        }
    }

    private fun enqueueDecode(
        activeSource: ReadingRegionImageSource,
        request: DecodeRequest,
        priority: Boolean
    ) {
        if (tileCache.get(request.key) != null || decodeJobs.containsKey(request.key)) return
        if (!priority && decodeJobs.size >= MAX_BACKGROUND_DECODE_JOBS) return
        val decodeGeneration = generation
        decodeJobs[request.key] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeRegion(activeSource, request.sourceRect)
            }
            decodeJobs.remove(request.key)
            if (decodeGeneration != generation || source != activeSource) {
                bitmap?.recycleSafely()
                return@launch
            }
            if (bitmap != null) {
                tileCache.put(request.key, bitmap)
                invalidate()
            }
        }
    }

    private suspend fun decodeRegion(activeSource: ReadingRegionImageSource, sourceRect: Rect): Bitmap? {
        if (sourceRect.width() <= 0 || sourceRect.height() <= 0) return null
        val decoder = synchronized(decodeLock) {
            ensureDecoder(activeSource)
        } ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = activeSource.sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return ImageProcessingGuards.withDecodePermit(
            width = sourceRect.width(),
            height = sourceRect.height(),
            tag = "ReadingRegionImageView"
        ) {
            synchronized(decodeLock) {
                if (decoder.isRecycled) {
                    null
                } else {
                    runCatching { decoder.decodeRegion(sourceRect, options) }.getOrNull()
                }
            }
        }
    }

    private fun ensureDecoder(activeSource: ReadingRegionImageSource): BitmapRegionDecoder? {
        if (decoderFile == activeSource.imageFile && decoder?.isRecycled == false) return decoder
        closeDecoder()
        decoder = runCatching { createBitmapRegionDecoder(activeSource.imageFile) }.getOrNull()
        decoderFile = if (decoder == null) null else activeSource.imageFile
        return decoder
    }

    private fun closeDecoder() {
        synchronized(decodeLock) {
            decoder?.let { if (!it.isRecycled) it.recycle() }
            decoder = null
            decoderFile = null
        }
    }

    private fun displayWidth(activeSource: ReadingRegionImageSource): Int {
        return ceilDiv(activeSource.sourceWidth, activeSource.sampleSize)
    }

    private fun displayHeight(activeSource: ReadingRegionImageSource): Int {
        return ceilDiv(activeSource.sourceHeight, activeSource.sampleSize)
    }

    private fun displayTileSize(): Int {
        val longestViewEdge = max(width, height).coerceAtLeast(DEFAULT_DISPLAY_TILE_SIZE)
        return longestViewEdge.coerceIn(DEFAULT_DISPLAY_TILE_SIZE, MAX_DISPLAY_TILE_SIZE)
    }

    private fun createBitmapRegionDecoder(imageFile: File): BitmapRegionDecoder {
        val path = imageFile.absolutePath
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(path)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(path, false)
        }
    }

    private fun ceilDiv(value: Int, divisor: Int): Int {
        return (value + divisor - 1) / divisor
    }

    private companion object {
        const val DEFAULT_DISPLAY_TILE_SIZE = 1024
        const val MAX_DISPLAY_TILE_SIZE = 2048
        const val PREFETCH_VIEWPORT_MULTIPLIER = 1.5f
        const val MAX_BACKGROUND_DECODE_JOBS = 4

        fun tileCacheMaxKb(): Int {
            val runtimeMaxKb = (Runtime.getRuntime().maxMemory() / 1024L).coerceAtLeast(1L)
            return (runtimeMaxKb / 20L).toInt().coerceIn(12 * 1024, 64 * 1024)
        }
    }
}

package com.manga.translate.reader

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
import androidx.core.graphics.withMatrix
import com.manga.translate.platform.ImageProcessingGuards
import com.manga.translate.platform.recycleSafely
import java.io.File
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object ReadingTileCacheBudget {
    private val lock = Any()
    private val caches = WeakHashMap<ReadingRegionImageView, Unit>()
    private var trimming = false

    private val maxKb: Int
        get() {
            val runtimeMaxKb = (Runtime.getRuntime().maxMemory() / 1024L).coerceAtLeast(1L)
            return (runtimeMaxKb / 6L).toInt().coerceIn(32 * 1024, 128 * 1024)
        }

    fun register(view: ReadingRegionImageView) {
        synchronized(lock) {
            caches[view] = Unit
            enforceLocked()
        }
    }

    fun changed() {
        synchronized(lock) {
            enforceLocked()
        }
    }

    private fun enforceLocked() {
        if (trimming) return
        var totalKb = caches.keys.sumOf { it.tileCacheSizeKb() }
        if (totalKb <= maxKb) return
        trimming = true
        try {
            // Detached holders are the safest place to reclaim first: they will
            // repopulate their viewport on attach without disrupting visible pages.
            caches.keys.filter { it.isTileCacheDetached() }.forEach { view ->
                if (totalKb <= maxKb) return@forEach
                view.trimTileCacheToKb(0)
                totalKb = caches.keys.sumOf { it.tileCacheSizeKb() }
            }
            while (totalKb > maxKb) {
                val largest = caches.keys.maxByOrNull { it.tileCacheSizeKb() } ?: break
                val excess = totalKb - maxKb
                largest.trimTileCacheToKb((largest.tileCacheSizeKb() - excess).coerceAtLeast(0))
                val updated = caches.keys.sumOf { it.tileCacheSizeKb() }
                if (updated >= totalKb) break
                totalKb = updated
            }
        } finally {
            trimming = false
        }
    }
}

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
        val decodeSampleSize: Int
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
    private val matrixValues = FloatArray(9)
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
            ReadingTileCacheBudget.changed()
        }
    }
    private val decodeJobs = mutableMapOf<TileKey, Job>()
    private var source: ReadingRegionImageSource? = null
    private var decoder: BitmapRegionDecoder? = null
    private var decoderFile: File? = null
    private var generation = 0
    private var lastDecodeSampleSize = 1
    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener { invalidate() }
    private var tileCacheDetached = false

    init {
        ReadingTileCacheBudget.register(this)
    }

    internal fun tileCacheSizeKb(): Int = tileCache.size()

    internal fun trimTileCacheToKb(maxSizeKb: Int) {
        tileCache.trimToSize(maxSizeKb.coerceAtLeast(0))
    }

    internal fun isTileCacheDetached(): Boolean = tileCacheDetached

    fun setRegionSource(next: ReadingRegionImageSource?) {
        if (source == next) return
        generation += 1
        source = next
        lastDecodeSampleSize = next?.layoutSampleSize?.coerceAtLeast(1) ?: 1
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
        val displayScale = currentDisplayScale()
        val decodeSampleSize = ReadingBitmapDecoder.calculateDecodeSampleSize(
            layoutSampleSize = activeSource.layoutSampleSize,
            displayScale = displayScale
        )
        if (decodeSampleSize != lastDecodeSampleSize) {
            lastDecodeSampleSize = decodeSampleSize
            // Keep lower-res tiles briefly as placeholders while sharper tiles load.
        }
        val tileSize = displayTileSize()
        val visibleRequests = planTiles(activeSource, visibleRect, decodeSampleSize, tileSize)
        val prefetchRequests = planPrefetchTiles(activeSource, visibleRequests, decodeSampleSize, tileSize)
        cancelStaleDecodeJobs((visibleRequests + prefetchRequests).mapTo(hashSetOf()) { it.key })
        canvas.withMatrix(contentMatrix) {
            for (request in visibleRequests) {
                tileDrawRect.set(
                    request.key.left.toFloat(),
                    request.key.top.toFloat(),
                    request.key.right.toFloat(),
                    request.key.bottom.toFloat()
                )
                val sharp = tileCache.get(request.key)
                if (sharp != null && !sharp.isRecycled) {
                    drawBitmap(sharp, null, tileDrawRect, paint)
                } else {
                    val fallback = findFallbackTile(request)
                    if (fallback != null && !fallback.isRecycled) {
                        drawBitmap(fallback, null, tileDrawRect, paint)
                    } else {
                        drawRect(tileDrawRect, missingTilePaint)
                    }
                    enqueueDecode(activeSource, request, priority = true)
                }
            }
        }
        for (request in prefetchRequests) {
            enqueueDecode(activeSource, request, priority = false)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tileCacheDetached = true
        viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener)
        generation += 1
        decodeJobs.values.forEach { it.cancel() }
        decodeJobs.clear()
        closeDecoder()
        // RecyclerView temporarily detaches cached holders while scrolling. Keep completed
        // tiles across that detach/attach boundary so a page does not return as transparent
        // blocks and decode the same viewport again. setRegionSource() still clears the cache
        // when the holder is recycled or rebound to another image.
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tileCacheDetached = false
        ReadingTileCacheBudget.register(this)
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

    private fun currentDisplayScale(): Float {
        contentMatrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val skewY = matrixValues[Matrix.MSKEW_Y]
        val scale = sqrt(scaleX * scaleX + skewY * skewY)
        return if (scale.isFinite() && scale > 0f) scale else 1f
    }

    private fun planTiles(
        activeSource: ReadingRegionImageSource,
        displayRect: RectF,
        decodeSampleSize: Int,
        tileSize: Int
    ): List<DecodeRequest> {
        val displayWidth = displayWidth(activeSource)
        val displayHeight = displayHeight(activeSource)
        val leftIndex = floor(displayRect.left / tileSize).toInt().coerceAtLeast(0)
        val topIndex = floor(displayRect.top / tileSize).toInt().coerceAtLeast(0)
        val rightIndex = ceil(displayRect.right / tileSize).toInt().coerceAtLeast(leftIndex + 1)
        val bottomIndex = ceil(displayRect.bottom / tileSize).toInt().coerceAtLeast(topIndex + 1)
        val layoutSample = activeSource.layoutSampleSize.coerceAtLeast(1)
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
                    decodeSampleSize = decodeSampleSize
                )
                result += DecodeRequest(
                    key = key,
                    sourceRect = Rect(
                        displayLeft * layoutSample,
                        displayTop * layoutSample,
                        (displayRight * layoutSample).coerceAtMost(activeSource.sourceWidth),
                        (displayBottom * layoutSample).coerceAtMost(activeSource.sourceHeight)
                    )
                )
            }
        }
        return result
    }

    private fun planPrefetchTiles(
        activeSource: ReadingRegionImageSource,
        visibleRequests: List<DecodeRequest>,
        decodeSampleSize: Int,
        tileSize: Int
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
        return planTiles(activeSource, prefetchRect, decodeSampleSize, tileSize)
            .filterNot { it.key in visibleKeys }
    }

    private fun findFallbackTile(request: DecodeRequest): Bitmap? {
        // Prefer any cached tile covering the same display rect at coarser sample.
        var sample = request.key.decodeSampleSize * 2
        while (sample <= 64) {
            val key = request.key.copy(decodeSampleSize = sample)
            val bitmap = tileCache.get(key)
            if (bitmap != null && !bitmap.isRecycled) return bitmap
            sample *= 2
        }
        // Last resort: any other sample for this rect.
        sample = 1
        while (sample < request.key.decodeSampleSize) {
            val key = request.key.copy(decodeSampleSize = sample)
            val bitmap = tileCache.get(key)
            if (bitmap != null && !bitmap.isRecycled) return bitmap
            sample *= 2
        }
        return null
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
                decodeRegion(activeSource, request.sourceRect, request.key.decodeSampleSize)
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

    private suspend fun decodeRegion(
        activeSource: ReadingRegionImageSource,
        sourceRect: Rect,
        decodeSampleSize: Int
    ): Bitmap? {
        if (sourceRect.width() <= 0 || sourceRect.height() <= 0) return null
        val decoder = synchronized(decodeLock) {
            ensureDecoder(activeSource)
        } ?: return null
        val sample = decodeSampleSize.coerceAtLeast(1)
        val outWidth = ceilDiv(sourceRect.width(), sample)
        val outHeight = ceilDiv(sourceRect.height(), sample)
        val preferArgb = ImageProcessingGuards.hasMemoryBudgetForBitmap(
            width = outWidth,
            height = outHeight,
            copies = 2
        )
        val configs = if (preferArgb) {
            listOf(Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565)
        } else {
            listOf(Bitmap.Config.RGB_565, Bitmap.Config.ARGB_8888)
        }
        for (config in configs) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = config
            }
            val bitmap = ImageProcessingGuards.withDecodePermit(
                width = outWidth,
                height = outHeight,
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
            if (bitmap != null) return bitmap
        }
        return null
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
        return ceilDiv(activeSource.sourceWidth, activeSource.layoutSampleSize.coerceAtLeast(1))
    }

    private fun displayHeight(activeSource: ReadingRegionImageSource): Int {
        return ceilDiv(activeSource.sourceHeight, activeSource.layoutSampleSize.coerceAtLeast(1))
    }

    private fun displayTileSize(): Int {
        // Fixed grid across zoom levels so coarser tiles can act as fallbacks while sharp tiles load.
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
        const val MAX_DISPLAY_TILE_SIZE = 1536
        const val PREFETCH_VIEWPORT_MULTIPLIER = 1.25f
        const val MAX_BACKGROUND_DECODE_JOBS = 6

        fun tileCacheMaxKb(): Int {
            val runtimeMaxKb = (Runtime.getRuntime().maxMemory() / 1024L).coerceAtLeast(1L)
            // ARGB tiles need more room; keep ~1/12 of heap, clamp 16–96MB.
            return (runtimeMaxKb / 12L).toInt().coerceIn(16 * 1024, 96 * 1024)
        }
    }
}

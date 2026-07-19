package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class PageRegionDetectionMode {
    FULL,
    TILED,
    TILED_LONG
}

internal data class DetectionTile(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    fun toRectF(): RectF {
        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }
}

internal data class BubblePriorityCandidate(
    val confidence: Float,
    val hasMaskContour: Boolean,
    val area: Float,
    val touchesInternalTileBoundary: Boolean = false
)

private data class DeduplicatedBubbleGroup(
    val detection: BubbleDetection,
    val suppressionRect: RectF
)

private data class TiledBubbleDetection(
    val detection: BubbleDetection,
    val touchesInternalTileBoundary: Boolean,
    val tileIndex: Int
)

internal fun shouldUseLongImageTiling(pageWidth: Int, pageHeight: Int): Boolean {
    if (pageWidth <= 0 || pageHeight <= 0) return false
    if (pageHeight < LONG_IMAGE_MIN_HEIGHT_PX) return false
    return pageHeight / pageWidth.toFloat() > LONG_IMAGE_ASPECT_THRESHOLD
}

internal fun shouldUseHighResolutionTiling(pageWidth: Int, pageHeight: Int): Boolean {
    if (pageWidth <= 0 || pageHeight <= 0) return false
    return pageWidth > DETECTION_TILE_EDGE_PX || pageHeight > DETECTION_TILE_EDGE_PX
}

internal fun shouldCombineFullPageDetection(pageWidth: Int, pageHeight: Int): Boolean {
    return shouldUseHighResolutionTiling(pageWidth, pageHeight) &&
        !shouldUseLongImageTiling(pageWidth, pageHeight)
}

internal fun planLongImageBubbleDetectionTiles(
    pageWidth: Int,
    pageHeight: Int
): List<DetectionTile> {
    if (pageWidth <= 0 || pageHeight <= 0) return emptyList()
    val tileHeight = longImageBubbleDetectionTileHeight(pageWidth, pageHeight)
    if (tileHeight <= 0) return emptyList()
    return planDetectionAxisStarts(
        length = pageHeight,
        tileSize = tileHeight,
        overlapRatio = LONG_IMAGE_TILE_OVERLAP_RATIO,
        overlapMinPx = 0
    ).map { tileTop ->
        DetectionTile(
            left = 0,
            top = tileTop,
            right = pageWidth,
            bottom = min(pageHeight, tileTop + tileHeight)
        )
    }
}

internal fun longImageBubbleDetectionTileHeight(pageWidth: Int, pageHeight: Int): Int {
    if (pageWidth <= 0 || pageHeight <= 0) return 0
    val baseTileHeight = (pageWidth * LONG_IMAGE_TILE_HEIGHT_WIDTH_RATIO)
        .roundToInt()
        .coerceAtMost(pageHeight)
    val remainingHeight = pageHeight - baseTileHeight
    return if (
        remainingHeight > 0 &&
        remainingHeight <= (baseTileHeight * LONG_IMAGE_TILE_OVERLAP_RATIO).roundToInt()
    ) {
        // A page only slightly taller than one tile is better handled as one
        // full-height crop than as two almost-identical model invocations.
        pageHeight
    } else {
        baseTileHeight
    }
}

internal fun planHighResolutionDetectionTiles(
    pageWidth: Int,
    pageHeight: Int
): List<DetectionTile> {
    if (pageWidth <= 0 || pageHeight <= 0) return emptyList()
    val tileWidth = min(pageWidth, DETECTION_TILE_EDGE_PX)
    val tileHeight = highResolutionDetectionTileHeight(pageWidth, pageHeight)
    val lefts = planDetectionAxisStarts(pageWidth, tileWidth)
    val tops = planDetectionAxisStarts(pageHeight, tileHeight)
    return tops.flatMap { tileTop ->
        lefts.map { tileLeft ->
            DetectionTile(
                left = tileLeft,
                top = tileTop,
                right = min(pageWidth, tileLeft + tileWidth),
                bottom = min(pageHeight, tileTop + tileHeight)
            )
        }
    }
}

internal fun shouldUseHighResolutionBubbleTiling(
    pageWidth: Int,
    pageHeight: Int
): Boolean {
    if (!shouldUseHighResolutionTiling(pageWidth, pageHeight)) return false
    if (shouldUseLongImageTiling(pageWidth, pageHeight)) return false
    return max(pageWidth, pageHeight) > DETECTION_MAX_EDGE + BUBBLE_TILE_OVERLAP_MIN_PX
}

internal fun planHighResolutionBubbleDetectionTiles(
    pageWidth: Int,
    pageHeight: Int
): List<DetectionTile> {
    if (pageWidth <= 0 || pageHeight <= 0) return emptyList()
    val tileEdge = min(min(pageWidth, pageHeight), BUBBLE_TILE_EDGE_PX)
    if (tileEdge <= 0) return emptyList()
    val lefts = planDetectionAxisStarts(
        length = pageWidth,
        tileSize = tileEdge,
        overlapRatio = BUBBLE_TILE_OVERLAP_RATIO,
        overlapMinPx = BUBBLE_TILE_OVERLAP_MIN_PX
    )
    val tops = planDetectionAxisStarts(
        length = pageHeight,
        tileSize = tileEdge,
        overlapRatio = BUBBLE_TILE_OVERLAP_RATIO,
        overlapMinPx = BUBBLE_TILE_OVERLAP_MIN_PX
    )
    return tops.flatMap { tileTop ->
        lefts.map { tileLeft ->
            DetectionTile(
                left = tileLeft,
                top = tileTop,
                right = min(pageWidth, tileLeft + tileEdge),
                bottom = min(pageHeight, tileTop + tileEdge)
            )
        }
    }
}

internal fun planDetectionAxisStarts(length: Int, tileSize: Int): List<Int> {
    return planDetectionAxisStarts(
        length = length,
        tileSize = tileSize,
        overlapRatio = DETECTION_TILE_OVERLAP_RATIO,
        overlapMinPx = DETECTION_TILE_OVERLAP_MIN_PX
    )
}

private fun planDetectionAxisStarts(
    length: Int,
    tileSize: Int,
    overlapRatio: Float,
    overlapMinPx: Int
): List<Int> {
    if (length <= 0 || tileSize <= 0) return emptyList()
    if (tileSize >= length) return listOf(0)
    val overlap = max(
        (tileSize * overlapRatio).roundToInt(),
        overlapMinPx
    ).coerceAtMost(tileSize - 1)
    val maxStep = max(1, tileSize - overlap)
    val lastStart = length - tileSize
    val tileCount = ((lastStart + maxStep - 1) / maxStep + 1).coerceAtLeast(2)
    if (tileCount == 2) {
        return listOf(0, lastStart)
    }
    return (0 until tileCount).map { index ->
        (lastStart * index.toFloat() / (tileCount - 1).toFloat()).roundToInt()
    }.distinct()
}

internal fun highResolutionDetectionTileHeight(pageWidth: Int, pageHeight: Int): Int {
    if (pageWidth <= 0 || pageHeight <= 0) return 0
    return min(min(pageWidth, DETECTION_TILE_EDGE_PX), pageHeight)
}

internal fun buildTileTextSuppressionRects(
    pageBubbleRects: List<RectF>,
    tile: DetectionTile,
    tileBitmapWidth: Int,
    tileBitmapHeight: Int
): List<RectF> {
    if (
        pageBubbleRects.isEmpty() ||
        tile.width <= 0 || tile.height <= 0 ||
        tileBitmapWidth <= 0 || tileBitmapHeight <= 0
    ) {
        return emptyList()
    }
    val scaleX = tileBitmapWidth / tile.width.toFloat()
    val scaleY = tileBitmapHeight / tile.height.toFloat()
    return pageBubbleRects.mapNotNull { pageRect ->
        val intersectsTile =
            pageRect.right > tile.left && pageRect.left < tile.right &&
                pageRect.bottom > tile.top && pageRect.top < tile.bottom
        if (!intersectsTile) return@mapNotNull null

        val localRect = RectF(
            (pageRect.left - tile.left) * scaleX,
            (pageRect.top - tile.top) * scaleY,
            (pageRect.right - tile.left) * scaleX,
            (pageRect.bottom - tile.top) * scaleY
        )
        val pad = max(
            TranslationCoreDefaults.PageRegionMaskExpandMin,
            max(1f, localRect.height()) * TranslationCoreDefaults.PageRegionMaskExpandRatio
        )
        RectF(
            (localRect.left - pad).coerceIn(0f, tileBitmapWidth.toFloat()),
            (localRect.top - pad).coerceIn(0f, tileBitmapHeight.toFloat()),
            (localRect.right + pad).coerceIn(0f, tileBitmapWidth.toFloat()),
            (localRect.bottom + pad).coerceIn(0f, tileBitmapHeight.toFloat())
        )
    }
}

internal fun shouldDeduplicateTileCandidates(
    firstTileIndex: Int,
    secondTileIndex: Int,
    firstRect: RectF,
    secondRect: RectF
): Boolean {
    return firstTileIndex != secondTileIndex &&
        shouldTreatRectsAsSameBubbleForDedup(firstRect, secondRect)
}

internal fun shouldUnionTileBubbleCandidates(
    candidates: List<BubblePriorityCandidate>
): Boolean {
    return candidates.size > 1 && candidates.all { it.touchesInternalTileBoundary }
}

internal fun unionDetectionRects(rects: List<RectF>): RectF? {
    val first = rects.firstOrNull() ?: return null
    var left = first.left
    var top = first.top
    var right = first.right
    var bottom = first.bottom
    for (index in 1 until rects.size) {
        val rect = rects[index]
        left = min(left, rect.left)
        top = min(top, rect.top)
        right = max(right, rect.right)
        bottom = max(bottom, rect.bottom)
    }
    return RectF(left, top, right, bottom)
}

internal fun remapTileMaskContourToPage(
    contour: FloatArray,
    tileTop: Int,
    tileHeight: Int,
    pageWidth: Int,
    pageHeight: Int,
    tileLeft: Int = 0,
    tileWidth: Int = pageWidth
): FloatArray {
    if (contour.isEmpty()) return contour
    val result = FloatArray(contour.size)
    val safePageWidth = pageWidth.coerceAtLeast(1)
    val safePageHeight = pageHeight.coerceAtLeast(1)
    val safeTileWidth = tileWidth.coerceAtLeast(1)
    val safeTileHeight = tileHeight.coerceAtLeast(1)
    var index = 0
    while (index + 1 < contour.size) {
        val x = contour[index].coerceIn(0f, 1f)
        val y = contour[index + 1].coerceIn(0f, 1f)
        result[index] = ((tileLeft + x * safeTileWidth) / safePageWidth.toFloat()).coerceIn(0f, 1f)
        result[index + 1] = ((tileTop + y * safeTileHeight) / safePageHeight.toFloat()).coerceIn(0f, 1f)
        index += 2
    }
    return result
}

/**
 * Merges page-normalized tile contours into the single polygon representation used by renderers.
 * The horizontal union envelope preserves the full extent of masks split by adjacent tile edges.
 */
internal fun mergePageMaskContours(
    contours: List<FloatArray>,
    pageHeight: Int
): FloatArray? {
    val validContours = contours.filter { it.size >= 6 && it.size % 2 == 0 }
    if (validContours.isEmpty()) return null
    if (validContours.size == 1) return validContours.first().copyOf()

    var minY = 1f
    var maxY = 0f
    for (contour in validContours) {
        var index = 1
        while (index < contour.size) {
            val y = contour[index].coerceIn(0f, 1f)
            minY = min(minY, y)
            maxY = max(maxY, y)
            index += 2
        }
    }
    if (maxY - minY <= CONTOUR_COORD_EPSILON) return null

    val estimatedPixelRows = ((maxY - minY) * pageHeight.coerceAtLeast(1)).roundToInt()
    val sampleCount = estimatedPixelRows.coerceIn(
        MERGED_CONTOUR_MIN_SAMPLE_ROWS,
        MERGED_CONTOUR_MAX_SAMPLE_ROWS
    )
    val leftEdge = ArrayList<Float>((sampleCount + 1) * 2)
    val rightEdge = ArrayList<Float>((sampleCount + 1) * 2)
    for (sample in 0..sampleCount) {
        val y = minY + (maxY - minY) * sample / sampleCount.toFloat()
        var rowLeft = Float.POSITIVE_INFINITY
        var rowRight = Float.NEGATIVE_INFINITY
        for (contour in validContours) {
            val bounds = contourHorizontalBounds(contour, y) ?: continue
            rowLeft = min(rowLeft, bounds.first)
            rowRight = max(rowRight, bounds.second)
        }
        if (!rowLeft.isFinite() || !rowRight.isFinite() || rowRight <= rowLeft) continue
        leftEdge.add(rowLeft.coerceIn(0f, 1f))
        leftEdge.add(y.coerceIn(0f, 1f))
        rightEdge.add(rowRight.coerceIn(0f, 1f))
        rightEdge.add(y.coerceIn(0f, 1f))
    }
    if (leftEdge.size < 4) return null

    val polygon = FloatArray(leftEdge.size + rightEdge.size)
    leftEdge.toFloatArray().copyInto(polygon)
    var outputIndex = leftEdge.size
    for (index in rightEdge.size - 2 downTo 0 step 2) {
        polygon[outputIndex] = rightEdge[index]
        polygon[outputIndex + 1] = rightEdge[index + 1]
        outputIndex += 2
    }
    return polygon
}

private fun contourHorizontalBounds(contour: FloatArray, y: Float): Pair<Float, Float>? {
    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    val pointCount = contour.size / 2
    for (pointIndex in 0 until pointCount) {
        val nextPointIndex = (pointIndex + 1) % pointCount
        val x1 = contour[pointIndex * 2]
        val y1 = contour[pointIndex * 2 + 1]
        val x2 = contour[nextPointIndex * 2]
        val y2 = contour[nextPointIndex * 2 + 1]
        val deltaY = y2 - y1
        if (abs(deltaY) <= CONTOUR_COORD_EPSILON) {
            if (abs(y - y1) <= CONTOUR_COORD_EPSILON) {
                minX = min(minX, min(x1, x2))
                maxX = max(maxX, max(x1, x2))
            }
            continue
        }
        val edgeMinY = min(y1, y2)
        val edgeMaxY = max(y1, y2)
        if (y < edgeMinY - CONTOUR_COORD_EPSILON || y > edgeMaxY + CONTOUR_COORD_EPSILON) {
            continue
        }
        val ratio = ((y - y1) / deltaY).coerceIn(0f, 1f)
        val x = x1 + (x2 - x1) * ratio
        minX = min(minX, x)
        maxX = max(maxX, x)
    }
    return if (minX.isFinite() && maxX.isFinite()) minX to maxX else null
}

internal fun choosePreferredBubbleCandidateIndex(
    candidates: List<BubblePriorityCandidate>
): Int {
    if (candidates.isEmpty()) return -1
    var bestIndex = 0
    for (index in 1 until candidates.size) {
        if (compareBubblePriority(candidates[index], candidates[bestIndex]) > 0) {
            bestIndex = index
        }
    }
    return bestIndex
}

internal fun shouldTreatRectsAsSameBubbleForDedup(a: RectF, b: RectF): Boolean {
    val areaA = rectAreaValue(a)
    val areaB = rectAreaValue(b)
    if (areaA <= 0f || areaB <= 0f) return false
    if (rectIou(a, b) >= BUBBLE_DEDUP_IOU_THRESHOLD) return true

    val minArea = min(areaA, areaB).coerceAtLeast(1f)
    val overlapOverMin = rectIntersectionArea(a, b) / minArea
    if (overlapOverMin >= BUBBLE_DEDUP_CONTAINMENT_THRESHOLD &&
        (rectContains(a, b) || rectContains(b, a))
    ) {
        return true
    }

    if (shouldTreatPartiallyShiftedRectsAsSameBubble(a, b, overlapOverMin)) {
        return true
    }
    // Tile seams often produce vertically stacked partial balloons with modest overlap.
    return shouldTreatVerticallySplitTileRectsAsSameBubble(a, b)
}

internal fun shouldFilterLongImageRegion(
    rect: RectF,
    pageWidth: Int,
    pageHeight: Int
): Boolean {
    if (!shouldUseLongImageTiling(pageWidth, pageHeight)) return false
    val width = rect.width().coerceAtLeast(0f)
    val height = rect.height().coerceAtLeast(0f)
    if (width <= 0f || height <= 0f) return true

    return height >= longImageMaxRegionHeight(pageWidth, pageHeight)
}

internal fun longImageMaxRegionHeight(pageWidth: Int, pageHeight: Int): Float {
    // Independent of tile height: tiles are sized for letterbox density on the fixed
    // 640 ONNX input, while this cap only rejects full-strip false positives.
    if (pageWidth <= 0) return 0f
    return pageWidth * LONG_IMAGE_MAX_REGION_HEIGHT_WIDTH_RATIO
}

internal class PageRegionDetector(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private var bubbleDetector: BubbleDetector? = null
    private var textDetector: TextDetector? = null

    suspend fun detect(
        bitmap: Bitmap,
        logTag: String = "PageRegionDetector"
    ): PageRegionDetectionResult? {
        return PipelineBitmapDecoder.openCropSource(bitmap).use { cropSource ->
            detect(cropSource, bitmap.width, bitmap.height, logTag)
        }
    }

    suspend fun detect(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String = "PageRegionDetector"
    ): PageRegionDetectionResult? {
        if (
            !shouldUseHighResolutionTiling(pageWidth, pageHeight) &&
            !shouldUseLongImageTiling(pageWidth, pageHeight)
        ) {
            return detectFullPage(cropSource, pageWidth, pageHeight, logTag)
        }
        return try {
            detectTiledPage(cropSource, pageWidth, pageHeight, logTag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(logTag, "Tiled page detection failed; trying full-page fallback", e)
            try {
                detectFullPage(cropSource, pageWidth, pageHeight, "$logTag[fallback]")
            } catch (fallbackCancellation: CancellationException) {
                throw fallbackCancellation
            } catch (fallbackError: Exception) {
                AppLogger.log(logTag, "Full-page fallback detection failed", fallbackError)
                null
            }
        }
    }

    private suspend fun detectFullPage(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String
    ): PageRegionDetectionResult? {
        val fullBitmap = cropSource.decodeRegion(
            RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()),
            maxEdge = DETECTION_MAX_EDGE
        ) ?: return null
        return try {
            detectSingleBitmap(fullBitmap, logTag)
                ?.remapToSource(pageWidth, pageHeight)
                ?.copy(detectionMode = PageRegionDetectionMode.FULL)
        } finally {
            fullBitmap.recycleSafely()
        }
    }

    private suspend fun detectTiledPage(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String
    ): PageRegionDetectionResult {
        val textTiles = planHighResolutionDetectionTiles(pageWidth, pageHeight)
        if (textTiles.isEmpty()) {
            return buildDetectionResult(
                width = pageWidth,
                height = pageHeight,
                detections = emptyList(),
                textRects = emptyList(),
                detectionMode = if (shouldUseLongImageTiling(pageWidth, pageHeight)) {
                    PageRegionDetectionMode.TILED_LONG
                } else {
                    PageRegionDetectionMode.TILED
                }
            )
        }
        val bubbleDetections = ArrayList<TiledBubbleDetection>()
        val textRects = ArrayList<RectF>()
        if (shouldUseLongImageTiling(pageWidth, pageHeight)) {
            appendLongImageBubbleCandidates(
                cropSource = cropSource,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                bubbleDetections = bubbleDetections,
                logTag = logTag
            )
        } else if (shouldCombineFullPageDetection(pageWidth, pageHeight)) {
            appendFullPageCandidates(
                cropSource = cropSource,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                bubbleDetections = bubbleDetections,
                textRects = textRects,
                logTag = logTag
            )
            if (shouldUseHighResolutionBubbleTiling(pageWidth, pageHeight)) {
                appendHighResolutionBubbleCandidates(
                    cropSource = cropSource,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    bubbleDetections = bubbleDetections,
                    logTag = logTag
                )
            }
        }
        val deduplicatedGroups = try {
            filterLongImageBubbleGroups(
                deduplicateBubbleDetections(bubbleDetections, pageHeight),
                pageWidth,
                pageHeight,
                logTag
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(logTag, "Bubble candidate merge failed; keeping raw candidates", e)
            bubbleDetections.map { tiled ->
                DeduplicatedBubbleGroup(
                    detection = tiled.detection,
                    suppressionRect = RectF(tiled.detection.rect)
                )
            }
        }
        val deduplicatedBubbles = deduplicatedGroups.map { it.detection }
        // Long-image filtering must happen before these rects are used to suppress text.
        val suppressionBubbleRects = deduplicatedGroups.map { it.suppressionRect }
        var failedTileCount = 0
        for ((index, tile) in textTiles.withIndex()) {
            currentCoroutineContext().ensureActive()
            val tileTag = "$logTag[text tile ${index + 1}/${textTiles.size}]"
            val tileBitmap = try {
                cropSource.decodeRegion(tile.toRectF(), maxEdge = DETECTION_MAX_EDGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedTileCount++
                AppLogger.log(tileTag, "Detection tile decode failed; skipping tile", e)
                continue
            }
            if (tileBitmap == null) {
                failedTileCount++
                AppLogger.log(tileTag, "Detection tile decode returned null; skipping tile")
                continue
            }
            try {
                val localTextRects = detectSupplementTextRects(
                    bitmap = tileBitmap,
                    tile = tile,
                    pageBubbleRects = suppressionBubbleRects,
                    logTag = tileTag
                )
                textRects.addAll(
                    remapTileRectsToPage(
                        rects = localTextRects,
                        tileBitmapWidth = tileBitmap.width,
                        tileBitmapHeight = tileBitmap.height,
                        tile = tile
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedTileCount++
                AppLogger.log(tileTag, "Tiled supplement text detection failed; skipping tile", e)
            } finally {
                tileBitmap.recycleSafely()
            }
        }
        if (failedTileCount > 0) {
            AppLogger.log(logTag, "Skipped $failedTileCount/${textTiles.size} text detection tiles")
        }
        val sizeFilteredTextRects = filterTinyTextRects(
            rects = textRects,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            logTag = logTag
        )
        val longFilteredTextRects = filterLongImageRects(
            sizeFilteredTextRects,
            pageWidth,
            pageHeight,
            logTag
        )
        val filteredTextRects = filterOverlapping(
            textRects = longFilteredTextRects,
            bubbleRects = deduplicatedBubbles.map { it.rect },
            threshold = TEXT_IOU_THRESHOLD
        )
        val maxMergedHeight = if (shouldUseLongImageTiling(pageWidth, pageHeight)) {
            longImageMaxRegionHeight(pageWidth, pageHeight)
        } else {
            null
        }
        val mergedTextRects = try {
            RectGeometryDeduplicator.mergeSupplementRects(
                filteredTextRects,
                pageWidth,
                pageHeight,
                maxMergedHeight = maxMergedHeight
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(logTag, "Supplement text merge failed; keeping unmerged regions", e)
            filteredTextRects
        }
        if (mergedTextRects.isNotEmpty()) {
            AppLogger.log(logTag, "Free-text regions after tile merge: ${mergedTextRects.size}")
        }
        return buildDetectionResult(
            width = pageWidth,
            height = pageHeight,
            detections = deduplicatedBubbles,
            textRects = mergedTextRects,
            detectionMode = if (shouldUseLongImageTiling(pageWidth, pageHeight)) {
                PageRegionDetectionMode.TILED_LONG
            } else {
                PageRegionDetectionMode.TILED
            }
        )
    }

    private fun detectSupplementTextRects(
        bitmap: Bitmap,
        tile: DetectionTile,
        pageBubbleRects: List<RectF>,
        logTag: String
    ): List<RectF> {
        val detector = getTextDetector(logTag) ?: return emptyList()
        val localSuppressionRects = buildTileTextSuppressionRects(
            pageBubbleRects = pageBubbleRects,
            tile = tile,
            tileBitmapWidth = bitmap.width,
            tileBitmapHeight = bitmap.height
        )
        return detector.detect(bitmap, localSuppressionRects)
    }

    private suspend fun appendLongImageBubbleCandidates(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        bubbleDetections: MutableList<TiledBubbleDetection>,
        logTag: String
    ) {
        appendBubbleCandidatesFromTiles(
            cropSource = cropSource,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            bubbleDetections = bubbleDetections,
            tiles = planLongImageBubbleDetectionTiles(pageWidth, pageHeight),
            tileLabel = "bubble tile",
            logTag = logTag
        )
    }

    private suspend fun appendHighResolutionBubbleCandidates(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        bubbleDetections: MutableList<TiledBubbleDetection>,
        logTag: String
    ) {
        appendBubbleCandidatesFromTiles(
            cropSource = cropSource,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            bubbleDetections = bubbleDetections,
            tiles = planHighResolutionBubbleDetectionTiles(pageWidth, pageHeight),
            tileLabel = "hi-res bubble tile",
            logTag = logTag
        )
    }

    private suspend fun appendBubbleCandidatesFromTiles(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        bubbleDetections: MutableList<TiledBubbleDetection>,
        tiles: List<DetectionTile>,
        tileLabel: String,
        logTag: String
    ) {
        val detector = getBubbleDetector(logTag) ?: run {
            AppLogger.log(logTag, "Bubble detector unavailable; continuing with text tiles")
            return
        }
        var failedTileCount = 0
        for ((index, tile) in tiles.withIndex()) {
            currentCoroutineContext().ensureActive()
            val tileTag = "$logTag[$tileLabel ${index + 1}/${tiles.size}]"
            val tileBitmap = try {
                cropSource.decodeRegion(tile.toRectF(), maxEdge = DETECTION_MAX_EDGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedTileCount++
                AppLogger.log(tileTag, "$tileLabel decode failed; skipping tile", e)
                continue
            }
            if (tileBitmap == null) {
                failedTileCount++
                AppLogger.log(tileTag, "$tileLabel decode returned null; skipping tile")
                continue
            }
            try {
                val detections = filterTinyBubbleDetections(
                    detector.detect(tileBitmap),
                    tileBitmap,
                    tileTag
                )
                bubbleDetections.addAll(
                    remapTileBubbleDetectionsToPage(
                        detections = detections,
                        tileBitmapWidth = tileBitmap.width,
                        tileBitmapHeight = tileBitmap.height,
                        tile = tile,
                        tileIndex = index,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedTileCount++
                AppLogger.log(tileTag, "$tileLabel detection failed; skipping tile", e)
            } finally {
                tileBitmap.recycleSafely()
            }
        }
        if (failedTileCount > 0) {
            AppLogger.log(logTag, "Skipped $failedTileCount/${tiles.size} $tileLabel(s)")
        }
    }

    private suspend fun appendFullPageCandidates(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        bubbleDetections: MutableList<TiledBubbleDetection>,
        textRects: MutableList<RectF>,
        logTag: String
    ) {
        val fullTag = "$logTag[full]"
        val fullBitmap = try {
            cropSource.decodeRegion(
                RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()),
                maxEdge = DETECTION_MAX_EDGE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(fullTag, "Full-page detection decode failed; continuing with tiles", e)
            null
        } ?: return
        try {
            val fullResult = detectSingleBitmap(fullBitmap, fullTag)
                ?.remapToSource(pageWidth, pageHeight)
                ?: return
            bubbleDetections.addAll(
                fullResult.bubbleDetections.map { detection ->
                    TiledBubbleDetection(
                        detection = detection,
                        touchesInternalTileBoundary = false,
                        tileIndex = FULL_PAGE_CANDIDATE_TILE_INDEX
                    )
                }
            )
            textRects.addAll(fullResult.textRects)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(fullTag, "Full-page detection failed; continuing with tiles", e)
        } finally {
            fullBitmap.recycleSafely()
        }
    }

    private fun detectSingleBitmap(
        bitmap: Bitmap,
        logTag: String
    ): PageRegionDetectionResult? {
        val unified = detectUnifiedRegions(bitmap, logTag) ?: return null
        val filteredTextRects = filterOverlapping(
            textRects = filterTinyTextRects(
                rects = unified.freeTextRects,
                pageWidth = bitmap.width,
                pageHeight = bitmap.height,
                logTag = logTag
            ),
            bubbleRects = unified.balloons.map { it.rect },
            threshold = TEXT_IOU_THRESHOLD
        )
        val textRects = RectGeometryDeduplicator.mergeSupplementRects(
            filteredTextRects,
            bitmap.width,
            bitmap.height,
            maxMergedHeight = null
        )
        if (textRects.isNotEmpty()) {
            AppLogger.log(logTag, "Free-text regions: ${textRects.size}")
        }
        return buildDetectionResult(
            width = bitmap.width,
            height = bitmap.height,
            detections = unified.balloons,
            textRects = textRects,
            detectionMode = PageRegionDetectionMode.FULL
        )
    }

    private fun detectUnifiedRegions(bitmap: Bitmap, logTag: String): UnifiedRegionDetection? {
        var bubbleDetectionSucceeded = false
        val balloons = getBubbleDetector(logTag)?.let { detector ->
            try {
                val raw = detector.detectRegions(bitmap)
                bubbleDetectionSucceeded = true
                filterTinyBubbleDetections(raw.balloons, bitmap, logTag)
            } catch (e: Exception) {
                AppLogger.log(logTag, "Bubble detection failed; trying supplement text detection", e)
                emptyList()
            }
        }.orEmpty()

        var textDetectionSucceeded = false
        val textRects = getTextDetector(logTag)?.let { detector ->
            try {
                val detected = detector.detect(
                    bitmap = bitmap,
                    suppressionRects = buildTextSuppressionRects(balloons, bitmap)
                )
                textDetectionSucceeded = true
                detected
            } catch (e: Exception) {
                AppLogger.log(logTag, "Supplement text detection failed; keeping bubbles", e)
                emptyList()
            }
        }.orEmpty()

        if (!bubbleDetectionSucceeded && !textDetectionSucceeded) {
            AppLogger.log(logTag, "Both page region detectors failed")
            return null
        }
        return UnifiedRegionDetection(
            balloons = balloons,
            freeTextRects = textRects
        )
    }

    private fun buildTextSuppressionRects(
        detections: List<BubbleDetection>,
        bitmap: Bitmap
    ): List<RectF> {
        return detections.map { detection ->
            val rect = detection.rect
            val pad = max(
                TranslationCoreDefaults.PageRegionMaskExpandMin,
                max(1f, rect.height()) * TranslationCoreDefaults.PageRegionMaskExpandRatio
            )
            RectF(
                (rect.left - pad).coerceIn(0f, bitmap.width.toFloat()),
                (rect.top - pad).coerceIn(0f, bitmap.height.toFloat()),
                (rect.right + pad).coerceIn(0f, bitmap.width.toFloat()),
                (rect.bottom + pad).coerceIn(0f, bitmap.height.toFloat())
            )
        }
    }

    private fun buildDetectionResult(
        width: Int,
        height: Int,
        detections: List<BubbleDetection>,
        textRects: List<RectF>,
        detectionMode: PageRegionDetectionMode
    ): PageRegionDetectionResult {
        val bubbleRects = detections.map { it.rect }
        val regions = buildRegions(detections, bubbleRects, textRects)
        return PageRegionDetectionResult(
            width = width,
            height = height,
            bubbleDetections = detections,
            textRects = textRects,
            regions = regions,
            detectionMode = detectionMode
        )
    }

    private fun remapTileBubbleDetectionsToPage(
        detections: List<BubbleDetection>,
        tileBitmapWidth: Int,
        tileBitmapHeight: Int,
        tile: DetectionTile,
        tileIndex: Int,
        pageWidth: Int,
        pageHeight: Int
    ): List<TiledBubbleDetection> {
        val scaleX = tile.width / tileBitmapWidth.toFloat().coerceAtLeast(1f)
        val scaleY = tile.height / tileBitmapHeight.toFloat().coerceAtLeast(1f)
        return detections.map { detection ->
            TiledBubbleDetection(
                detection = detection.copy(
                    rect = detection.rect.scaleBy(scaleX, scaleY)
                        .offsetBy(tile.left.toFloat(), tile.top.toFloat()),
                    maskContour = detection.maskContour?.let {
                        remapTileMaskContourToPage(
                            contour = it,
                            tileTop = tile.top,
                            tileHeight = tile.height,
                            pageWidth = pageWidth,
                            pageHeight = pageHeight,
                            tileLeft = tile.left,
                            tileWidth = tile.width
                        )
                    }
                ),
                touchesInternalTileBoundary = touchesInternalTileBoundary(
                    detection = detection,
                    tileBitmapWidth = tileBitmapWidth,
                    tileBitmapHeight = tileBitmapHeight,
                    tile = tile,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight
                ),
                tileIndex = tileIndex
            )
        }
    }

    private fun touchesInternalTileBoundary(
        detection: BubbleDetection,
        tileBitmapWidth: Int,
        tileBitmapHeight: Int,
        tile: DetectionTile,
        pageWidth: Int,
        pageHeight: Int
    ): Boolean {
        if (tileBitmapWidth <= 0 || tileBitmapHeight <= 0) return false
        val marginX = (tileBitmapWidth * TILE_BOUNDARY_MARGIN_RATIO)
            .coerceIn(TILE_BOUNDARY_MARGIN_MIN_PX, TILE_BOUNDARY_MARGIN_MAX_PX)
        val marginY = (tileBitmapHeight * TILE_BOUNDARY_MARGIN_RATIO)
            .coerceIn(TILE_BOUNDARY_MARGIN_MIN_PX, TILE_BOUNDARY_MARGIN_MAX_PX)
        val contour = detection.maskContour
        val contourMinX = contour?.let { values ->
            values.indices.asSequence().filter { it % 2 == 0 }.minOfOrNull { values[it] }
        }?.times(tileBitmapWidth)
        val contourMaxX = contour?.let { values ->
            values.indices.asSequence().filter { it % 2 == 0 }.maxOfOrNull { values[it] }
        }?.times(tileBitmapWidth)
        val contourMinY = contour?.let { values ->
            values.indices.asSequence().filter { it % 2 == 1 }.minOfOrNull { values[it] }
        }?.times(tileBitmapHeight)
        val contourMaxY = contour?.let { values ->
            values.indices.asSequence().filter { it % 2 == 1 }.maxOfOrNull { values[it] }
        }?.times(tileBitmapHeight)
        val touchesLeft = tile.left > 0 &&
            (detection.rect.left <= marginX || (contourMinX != null && contourMinX <= marginX))
        val touchesTop = tile.top > 0 &&
            (detection.rect.top <= marginY || (contourMinY != null && contourMinY <= marginY))
        val touchesRight = tile.right < pageWidth &&
            (detection.rect.right >= tileBitmapWidth - marginX ||
                (contourMaxX != null && contourMaxX >= tileBitmapWidth - marginX))
        val touchesBottom = tile.bottom < pageHeight &&
            (detection.rect.bottom >= tileBitmapHeight - marginY ||
                (contourMaxY != null && contourMaxY >= tileBitmapHeight - marginY))
        return touchesLeft || touchesTop || touchesRight || touchesBottom
    }

    private fun remapTileRectsToPage(
        rects: List<RectF>,
        tileBitmapWidth: Int,
        tileBitmapHeight: Int,
        tile: DetectionTile
    ): List<RectF> {
        val scaleX = tile.width / tileBitmapWidth.toFloat().coerceAtLeast(1f)
        val scaleY = tile.height / tileBitmapHeight.toFloat().coerceAtLeast(1f)
        return rects.map { rect ->
            rect.scaleBy(scaleX, scaleY).offsetBy(tile.left.toFloat(), tile.top.toFloat())
        }
    }

    private fun deduplicateBubbleDetections(
        detections: List<TiledBubbleDetection>,
        pageHeight: Int
    ): List<DeduplicatedBubbleGroup> {
        if (detections.size <= 1) {
            return detections.map { tiled ->
                val detection = if (tiled.touchesInternalTileBoundary) {
                    tiled.detection.copy(maskContour = null)
                } else {
                    tiled.detection
                }
                DeduplicatedBubbleGroup(detection, RectF(detection.rect))
            }
        }
        val visited = BooleanArray(detections.size)
        val result = ArrayList<DeduplicatedBubbleGroup>(detections.size)
        for (start in detections.indices) {
            if (visited[start]) continue
            val queue = ArrayDeque<Int>()
            val component = ArrayList<Int>()
            val componentTileIndices = hashSetOf(detections[start].tileIndex)
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component.add(current)
                for (next in detections.indices) {
                    if (visited[next]) continue
                    if (detections[next].tileIndex in componentTileIndices) continue
                    if (!shouldDeduplicateTileCandidates(
                            firstTileIndex = detections[current].tileIndex,
                            secondTileIndex = detections[next].tileIndex,
                            firstRect = detections[current].detection.rect,
                            secondRect = detections[next].detection.rect
                        )
                    ) continue
                    visited[next] = true
                    componentTileIndices.add(detections[next].tileIndex)
                    queue.add(next)
                }
            }
            val candidates = component.map { index ->
                BubblePriorityCandidate(
                    confidence = detections[index].detection.confidence,
                    hasMaskContour = detections[index].detection.maskContour != null,
                    area = rectAreaValue(detections[index].detection.rect),
                    touchesInternalTileBoundary = detections[index].touchesInternalTileBoundary
                )
            }
            val bestOffset = choosePreferredBubbleCandidateIndex(candidates).coerceAtLeast(0)
            val best = detections[component[bestOffset]].detection
            val useUnion = shouldUnionTileBubbleCandidates(candidates)
            val outputRect = if (useUnion) {
                requireNotNull(
                    unionDetectionRects(component.map { index -> detections[index].detection.rect })
                )
            } else {
                RectF(best.rect)
            }
            val outputContour = if (useUnion) {
                mergePageMaskContours(
                    component.mapNotNull { index -> detections[index].detection.maskContour },
                    pageHeight
                )
            } else {
                best.maskContour
            }
            result.add(
                DeduplicatedBubbleGroup(
                    detection = best.copy(rect = outputRect, maskContour = outputContour),
                    suppressionRect = RectF(outputRect)
                )
            )
        }
        return result
    }

    private fun getBubbleDetector(logTag: String): BubbleDetector? {
        if (bubbleDetector != null) return bubbleDetector
        return try {
            AppLogger.log(logTag, "Loading BubbleDetector (${BubbleDetector.DEFAULT_MODEL_ASSET})")
            bubbleDetector = BubbleDetector(appContext, settingsStore = settingsStore)
            AppLogger.log(logTag, "BubbleDetector ready")
            bubbleDetector
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init bubble detector", e)
            null
        } catch (e: Error) {
            // OutOfMemoryError / UnsatisfiedLinkError etc. — do not kill the process unlogged.
            AppLogger.logFatal(logTag, "Fatal error init bubble detector", e)
            null
        }
    }

    private fun getTextDetector(logTag: String): TextDetector? {
        if (textDetector != null) return textDetector
        return try {
            AppLogger.log(logTag, "Loading TextDetector (${TextDetector.DEFAULT_MODEL_ASSET})")
            textDetector = TextDetector(appContext, settingsStore = settingsStore)
            AppLogger.log(logTag, "TextDetector ready")
            textDetector
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init text detector", e)
            null
        } catch (e: Error) {
            AppLogger.logFatal(logTag, "Fatal error init text detector", e)
            null
        }
    }

    fun releaseLoadedDetectors() {
        val hadLoadedDetectors = bubbleDetector != null || textDetector != null
        bubbleDetector = null
        textDetector = null
        if (hadLoadedDetectors) {
            AppLogger.log("PageRegionDetector", "Released loaded detector references")
        }
    }

    private fun buildRegions(
        detections: List<BubbleDetection>,
        bubbleRects: List<RectF>,
        textRects: List<RectF>
    ): List<PageRegion> {
        data class RegionSeed(
            val rect: RectF,
            val source: BubbleSource,
            val maskContour: FloatArray?
        )
        val seeds = ArrayList<RegionSeed>(bubbleRects.size + textRects.size)
        for (i in bubbleRects.indices) {
            seeds.add(
                RegionSeed(
                    rect = bubbleRects[i],
                    source = BubbleSource.BUBBLE_DETECTOR,
                    maskContour = detections.getOrNull(i)?.maskContour
                )
            )
        }
        for (rect in textRects) {
            seeds.add(
                RegionSeed(
                    rect = rect,
                    source = BubbleSource.TEXT_DETECTOR,
                    maskContour = null
                )
            )
        }
        seeds.sortWith(compareBy({ it.rect.top }, { it.rect.left }))
        return seeds.mapIndexed { index, seed ->
            PageRegion(
                id = index,
                rect = seed.rect,
                source = seed.source,
                maskContour = seed.maskContour
            )
        }
    }

    private fun filterOverlapping(
        textRects: List<RectF>,
        bubbleRects: List<RectF>,
        threshold: Float
    ): List<RectF> {
        if (bubbleRects.isEmpty()) return textRects
        val filtered = ArrayList<RectF>(textRects.size)
        for (rect in textRects) {
            var overlapped = false
            for (bubble in bubbleRects) {
                if (shouldFilterTextRectByBubble(rect, bubble, threshold)) {
                    overlapped = true
                    break
                }
            }
            if (!overlapped) {
                filtered.add(rect)
            }
        }
        return filtered
    }

    private fun filterTinyBubbleDetections(
        detections: List<BubbleDetection>,
        bitmap: Bitmap,
        logTag: String
    ): List<BubbleDetection> {
        if (detections.isEmpty()) return detections
        val filtered = detections.filterNot {
            isTinyErrorRegion(it.rect, bitmap.width, bitmap.height)
        }
        val removedCount = detections.size - filtered.size
        if (removedCount > 0) {
            AppLogger.log(
                logTag,
                "Filtered $removedCount tiny bubble false positives, kept ${filtered.size}"
            )
        }
        return filtered
    }

    private fun filterTinyTextRects(
        rects: List<RectF>,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String
    ): List<RectF> {
        if (rects.isEmpty()) return rects
        val filtered = rects.filterNot { isTinyErrorRegion(it, pageWidth, pageHeight) }
        val removedCount = rects.size - filtered.size
        if (removedCount > 0) {
            AppLogger.log(
                logTag,
                "Filtered $removedCount tiny supplement text regions, kept ${filtered.size}"
            )
        }
        return filtered
    }

    private fun filterLongImageBubbleGroups(
        groups: List<DeduplicatedBubbleGroup>,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String
    ): List<DeduplicatedBubbleGroup> {
        if (groups.isEmpty()) return groups
        val filtered = groups.filterNot {
            shouldFilterLongImageRegion(it.detection.rect, pageWidth, pageHeight)
        }
        logLongImageRegionFilter(
            removedCount = groups.size - filtered.size,
            keptCount = filtered.size,
            label = "bubble",
            logTag = logTag
        )
        return filtered
    }

    private fun filterLongImageRects(
        rects: List<RectF>,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String
    ): List<RectF> {
        if (rects.isEmpty()) return rects
        val filtered = rects.filterNot { shouldFilterLongImageRegion(it, pageWidth, pageHeight) }
        logLongImageRegionFilter(
            removedCount = rects.size - filtered.size,
            keptCount = filtered.size,
            label = "supplement text",
            logTag = logTag
        )
        return filtered
    }

    private fun logLongImageRegionFilter(
        removedCount: Int,
        keptCount: Int,
        label: String,
        logTag: String
    ) {
        if (removedCount <= 0) return
        AppLogger.log(
            logTag,
            "Filtered $removedCount long-image $label regions, kept $keptCount"
        )
    }

    private fun isTinyErrorRegion(rect: RectF, imageWidth: Int, imageHeight: Int): Boolean {
        val width = rect.width().coerceAtLeast(0f)
        val height = rect.height().coerceAtLeast(0f)
        if (width <= 0f || height <= 0f) return true

        val shortSide = min(width, height)
        val longSide = max(width, height)
        val imageMinSide = min(imageWidth, imageHeight).toFloat().coerceAtLeast(1f)
        val imageArea = (imageWidth.toFloat() * imageHeight.toFloat()).coerceAtLeast(1f)
        val areaRatio = (width * height) / imageArea

        val maxShortSide = max(TINY_BUBBLE_SHORT_SIDE_MIN_PX, imageMinSide * TINY_BUBBLE_SHORT_SIDE_RATIO)
        val maxLongSide = max(TINY_BUBBLE_LONG_SIDE_MIN_PX, imageMinSide * TINY_BUBBLE_LONG_SIDE_RATIO)

        return shortSide <= maxShortSide &&
            longSide <= maxLongSide &&
            areaRatio <= TINY_BUBBLE_MAX_AREA_RATIO
    }

    companion object {
        private const val TEXT_IOU_THRESHOLD = TranslationCoreDefaults.PageRegionTextIouThreshold
        private const val TINY_BUBBLE_SHORT_SIDE_MIN_PX = TranslationCoreDefaults.TinyBubbleShortSideMinPx
        private const val TINY_BUBBLE_LONG_SIDE_MIN_PX = TranslationCoreDefaults.TinyBubbleLongSideMinPx
        private const val TINY_BUBBLE_SHORT_SIDE_RATIO = TranslationCoreDefaults.TinyBubbleShortSideRatio
        private const val TINY_BUBBLE_LONG_SIDE_RATIO = TranslationCoreDefaults.TinyBubbleLongSideRatio
        private const val TINY_BUBBLE_MAX_AREA_RATIO = TranslationCoreDefaults.TinyBubbleMaxAreaRatio
    }
}

private fun compareBubblePriority(
    candidate: BubblePriorityCandidate,
    currentBest: BubblePriorityCandidate
): Int {
    if (candidate.touchesInternalTileBoundary != currentBest.touchesInternalTileBoundary) {
        return if (candidate.touchesInternalTileBoundary) -1 else 1
    }
    val confidenceDiff = candidate.confidence - currentBest.confidence
    if (abs(confidenceDiff) >= 0.02f) {
        return if (confidenceDiff > 0f) 1 else -1
    }
    if (candidate.hasMaskContour != currentBest.hasMaskContour) {
        return if (candidate.hasMaskContour) 1 else -1
    }
    if (confidenceDiff != 0f) {
        return if (confidenceDiff > 0f) 1 else -1
    }
    if (candidate.area != currentBest.area) {
        return if (candidate.area > currentBest.area) 1 else -1
    }
    return 0
}

internal data class PageRegion(
    val id: Int,
    val rect: RectF,
    val source: BubbleSource,
    val maskContour: FloatArray? = null
)

internal data class PageRegionDetectionResult(
    val width: Int,
    val height: Int,
    val bubbleDetections: List<BubbleDetection>,
    val textRects: List<RectF>,
    val regions: List<PageRegion>,
    val detectionMode: PageRegionDetectionMode = PageRegionDetectionMode.FULL
)

private fun RectF.offsetBy(offsetX: Float, offsetY: Float): RectF {
    return RectF(
        left + offsetX,
        top + offsetY,
        right + offsetX,
        bottom + offsetY
    )
}

private const val LONG_IMAGE_ASPECT_THRESHOLD = 2.0f
private const val LONG_IMAGE_MIN_HEIGHT_PX = 2048
private const val LONG_IMAGE_TILE_HEIGHT_WIDTH_RATIO = 2.0f
private const val LONG_IMAGE_TILE_OVERLAP_RATIO = 0.20f
// The supplement text model uses 640px square source tiles. The 1600-input
// bubble model gets larger square fallback tiles only for very large regular pages.
private const val DETECTION_TILE_EDGE_PX = 640
private const val DETECTION_TILE_OVERLAP_RATIO = 0.30f
private const val DETECTION_TILE_OVERLAP_MIN_PX = 192
private const val BUBBLE_TILE_EDGE_PX = 1600
private const val BUBBLE_TILE_OVERLAP_RATIO = 0.20f
private const val BUBBLE_TILE_OVERLAP_MIN_PX = 320
private const val FULL_PAGE_CANDIDATE_TILE_INDEX = -1
// Reject only abnormal full-strip boxes (~1.8 page-widths tall), not normal tall balloons.
private const val LONG_IMAGE_MAX_REGION_HEIGHT_WIDTH_RATIO = 1.8f
private const val BUBBLE_DEDUP_IOU_THRESHOLD = TranslationCoreDefaults.BubbleDedupIouThreshold
private const val BUBBLE_DEDUP_CONTAINMENT_THRESHOLD = 0.9f
private const val BUBBLE_DEDUP_PARTIAL_OVERLAP_MIN_RATIO = 0.40f
private const val BUBBLE_DEDUP_AXIS_OVERLAP_MIN_RATIO = 0.45f
private const val BUBBLE_DEDUP_CENTER_DRIFT_RATIO = 0.42f
private const val BUBBLE_DEDUP_CENTER_DRIFT_PAD = 24f
private const val BUBBLE_DEDUP_VERTICAL_SPLIT_WIDTH_RATIO = 0.72f
private const val BUBBLE_DEDUP_VERTICAL_SPLIT_CENTER_X_RATIO = 0.28f
private const val BUBBLE_DEDUP_VERTICAL_SPLIT_AXIS_X_RATIO = 0.60f
private const val BUBBLE_DEDUP_VERTICAL_SPLIT_MAX_GAP_PX = 12f
private const val TILE_BOUNDARY_MARGIN_RATIO = 0.015f
private const val TILE_BOUNDARY_MARGIN_MIN_PX = 4f
private const val TILE_BOUNDARY_MARGIN_MAX_PX = 20f
private const val MERGED_CONTOUR_MIN_SAMPLE_ROWS = 8
private const val MERGED_CONTOUR_MAX_SAMPLE_ROWS = 160
private const val CONTOUR_COORD_EPSILON = 1e-5f

private fun rectIou(a: RectF, b: RectF): Float {
    val inter = rectIntersectionArea(a, b)
    val union = rectAreaValue(a) + rectAreaValue(b) - inter
    return if (union <= 0f) 0f else inter / union
}

private fun rectIntersectionArea(a: RectF, b: RectF): Float {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    return max(0f, right - left) * max(0f, bottom - top)
}

private fun rectAreaValue(rect: RectF): Float {
    return max(0f, rect.width()) * max(0f, rect.height())
}

private fun rectContains(outer: RectF, inner: RectF): Boolean {
    return outer.left <= inner.left &&
        outer.top <= inner.top &&
        outer.right >= inner.right &&
        outer.bottom >= inner.bottom
}

internal fun shouldFilterTextRectByBubble(
    textRect: RectF,
    bubbleRect: RectF,
    iouThreshold: Float
): Boolean {
    return rectIou(textRect, bubbleRect) >= iouThreshold ||
        rectContains(bubbleRect, textRect)
}

private fun shouldTreatPartiallyShiftedRectsAsSameBubble(
    a: RectF,
    b: RectF,
    overlapOverMin: Float
): Boolean {
    if (overlapOverMin < BUBBLE_DEDUP_PARTIAL_OVERLAP_MIN_RATIO) return false

    val overlapX = max(0f, min(a.right, b.right) - max(a.left, b.left))
    val overlapY = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    val minWidth = min(a.width(), b.width()).coerceAtLeast(1f)
    val minHeight = min(a.height(), b.height()).coerceAtLeast(1f)
    if (overlapX / minWidth < BUBBLE_DEDUP_AXIS_OVERLAP_MIN_RATIO) return false
    if (overlapY / minHeight < BUBBLE_DEDUP_AXIS_OVERLAP_MIN_RATIO) return false

    val maxWidth = max(a.width(), b.width()).coerceAtLeast(1f)
    val maxHeight = max(a.height(), b.height()).coerceAtLeast(1f)
    val centerAX = (a.left + a.right) * 0.5f
    val centerAY = (a.top + a.bottom) * 0.5f
    val centerBX = (b.left + b.right) * 0.5f
    val centerBY = (b.top + b.bottom) * 0.5f
    val maxCenterDx = maxWidth * BUBBLE_DEDUP_CENTER_DRIFT_RATIO + BUBBLE_DEDUP_CENTER_DRIFT_PAD
    val maxCenterDy = maxHeight * BUBBLE_DEDUP_CENTER_DRIFT_RATIO + BUBBLE_DEDUP_CENTER_DRIFT_PAD

    return abs(centerAX - centerBX) <= maxCenterDx &&
        abs(centerAY - centerBY) <= maxCenterDy
}

/**
 * Detects two partial balloons from adjacent long-image tiles: similar width, strong X overlap,
 * vertically stacked with little gap, and the union is taller than either half alone.
 */
private fun shouldTreatVerticallySplitTileRectsAsSameBubble(a: RectF, b: RectF): Boolean {
    val widthA = a.width().coerceAtLeast(1f)
    val widthB = b.width().coerceAtLeast(1f)
    val heightA = a.height().coerceAtLeast(1f)
    val heightB = b.height().coerceAtLeast(1f)
    val widthRatio = min(widthA, widthB) / max(widthA, widthB)
    if (widthRatio < BUBBLE_DEDUP_VERTICAL_SPLIT_WIDTH_RATIO) return false

    val overlapX = max(0f, min(a.right, b.right) - max(a.left, b.left))
    if (overlapX / min(widthA, widthB) < BUBBLE_DEDUP_VERTICAL_SPLIT_AXIS_X_RATIO) return false

    val centerAX = (a.left + a.right) * 0.5f
    val centerBX = (b.left + b.right) * 0.5f
    if (abs(centerAX - centerBX) > max(widthA, widthB) * BUBBLE_DEDUP_VERTICAL_SPLIT_CENTER_X_RATIO) {
        return false
    }

    val verticalGap = when {
        a.bottom <= b.top -> b.top - a.bottom
        b.bottom <= a.top -> a.top - b.bottom
        else -> 0f
    }
    if (verticalGap > BUBBLE_DEDUP_VERTICAL_SPLIT_MAX_GAP_PX) return false

    val unionTop = min(a.top, b.top)
    val unionBottom = max(a.bottom, b.bottom)
    val unionHeight = (unionBottom - unionTop).coerceAtLeast(1f)
    // Require a real vertical extension, not two nearly-identical duplicates.
    if (unionHeight <= max(heightA, heightB) * 1.08f) return false
    // Avoid gluing two full stacked bubbles that barely touch: each half should cover a
    // substantial share of the union (typical for tile-truncated pairs).
    if (heightA / unionHeight < 0.28f || heightB / unionHeight < 0.28f) return false
    return true
}

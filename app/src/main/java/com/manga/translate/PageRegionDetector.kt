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
    TILED_LONG
}

internal data class DetectionTile(
    val top: Int,
    val bottom: Int,
    val width: Int
) {
    val height: Int
        get() = bottom - top

    fun toRectF(): RectF {
        return RectF(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
    }
}

internal data class BubblePriorityCandidate(
    val confidence: Float,
    val hasMaskContour: Boolean,
    val area: Float
)

private data class DeduplicatedBubbleGroup(
    val detection: BubbleDetection,
    val suppressionRect: RectF
)

internal fun shouldUseLongImageTiling(pageWidth: Int, pageHeight: Int): Boolean {
    if (pageWidth <= 0 || pageHeight <= 0) return false
    if (pageHeight < LONG_IMAGE_MIN_HEIGHT_PX) return false
    return pageHeight / pageWidth.toFloat() >= LONG_IMAGE_ASPECT_THRESHOLD
}

internal fun planLongImageDetectionTiles(
    pageWidth: Int,
    pageHeight: Int
): List<DetectionTile> {
    if (pageWidth <= 0 || pageHeight <= 0) return emptyList()
    val tileHeight = longImageDetectionTileHeight(pageWidth, pageHeight)
    val overlapHeight = max(
        (tileHeight * LONG_IMAGE_TILE_OVERLAP_RATIO).roundToInt(),
        LONG_IMAGE_TILE_OVERLAP_MIN_PX
    ).coerceAtMost(max(0, tileHeight - 1))
    val step = max(1, tileHeight - overlapHeight)
    val tops = LinkedHashSet<Int>()
    var top = 0
    while (top < pageHeight) {
        tops.add(top)
        if (top + tileHeight >= pageHeight) break
        top += step
    }
    val lastTop = tops.maxOrNull() ?: 0
    if (lastTop + tileHeight < pageHeight) {
        tops.add(max(0, pageHeight - tileHeight))
    }
    return tops.sorted().map { tileTop ->
        DetectionTile(
            top = tileTop,
            bottom = min(pageHeight, tileTop + tileHeight),
            width = pageWidth
        )
    }
}

internal fun longImageDetectionTileHeight(pageWidth: Int, pageHeight: Int): Int {
    if (pageWidth <= 0 || pageHeight <= 0) return 0
    return (pageWidth * LONG_IMAGE_TILE_HEIGHT_WIDTH_RATIO).roundToInt()
        .coerceIn(LONG_IMAGE_TILE_MIN_HEIGHT_PX, LONG_IMAGE_TILE_MAX_HEIGHT_PX)
        .coerceAtMost(pageHeight)
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

    fun detect(bitmap: Bitmap, logTag: String = "PageRegionDetector"): PageRegionDetectionResult? {
        return detectSingleBitmap(bitmap, logTag, PageRegionDetectionMode.FULL)
    }

    suspend fun detect(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String = "PageRegionDetector"
    ): PageRegionDetectionResult? {
        if (!shouldUseLongImageTiling(pageWidth, pageHeight)) {
            return detectFullPage(cropSource, pageWidth, pageHeight, logTag)
        }
        return try {
            detectTiledLongImage(cropSource, pageWidth, pageHeight, logTag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(logTag, "Long-image tiled detection failed; returning empty result", e)
            buildDetectionResult(
                width = pageWidth,
                height = pageHeight,
                detections = emptyList(),
                textRects = emptyList(),
                detectionMode = PageRegionDetectionMode.TILED_LONG
            )
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
            detectSingleBitmap(fullBitmap, logTag, PageRegionDetectionMode.FULL)
                ?.remapToSource(pageWidth, pageHeight)
                ?.copy(detectionMode = PageRegionDetectionMode.FULL)
        } finally {
            fullBitmap.recycleSafely()
        }
    }

    private suspend fun detectTiledLongImage(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String
    ): PageRegionDetectionResult {
        val tiles = planLongImageDetectionTiles(pageWidth, pageHeight)
        if (tiles.isEmpty()) {
            return buildDetectionResult(
                width = pageWidth,
                height = pageHeight,
                detections = emptyList(),
                textRects = emptyList(),
                detectionMode = PageRegionDetectionMode.TILED_LONG
            )
        }
        val bubbleDetections = ArrayList<BubbleDetection>()
        val textRects = ArrayList<RectF>()
        var failedTileCount = 0
        for ((index, tile) in tiles.withIndex()) {
            currentCoroutineContext().ensureActive()
            val tileTag = "$logTag[tile ${index + 1}/${tiles.size}]"
            val tileBitmap = try {
                cropSource.decodeRegion(tile.toRectF(), maxEdge = DETECTION_MAX_EDGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedTileCount++
                AppLogger.log(tileTag, "Long-image tile decode failed; skipping tile", e)
                continue
            }
            if (tileBitmap == null) {
                failedTileCount++
                AppLogger.log(tileTag, "Long-image tile decode returned null; skipping tile")
                continue
            }
            try {
                val local = detectUnifiedRegions(tileBitmap, tileTag) ?: run {
                    failedTileCount++
                    AppLogger.log(tileTag, "Long-image unified detection returned null; skipping tile")
                    continue
                }
                bubbleDetections.addAll(
                    remapTileBubbleDetectionsToPage(
                        detections = local.balloons,
                        tileBitmapWidth = tileBitmap.width,
                        tileBitmapHeight = tileBitmap.height,
                        tile = tile,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight
                    )
                )
                textRects.addAll(
                    remapTileRectsToPage(
                        rects = local.freeTextRects,
                        tileBitmapWidth = tileBitmap.width,
                        tileBitmapHeight = tileBitmap.height,
                        tile = tile
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedTileCount++
                AppLogger.log(tileTag, "Long-image unified detection failed; skipping tile", e)
            } finally {
                tileBitmap.recycleSafely()
            }
        }
        if (failedTileCount > 0) {
            AppLogger.log(logTag, "Skipped $failedTileCount/${tiles.size} long-image tiles")
        }
        val deduplicatedGroups = filterLongImageBubbleGroups(
            deduplicateBubbleDetections(bubbleDetections),
            pageWidth,
            pageHeight,
            logTag
        )
        val deduplicatedBubbles = deduplicatedGroups.map { it.detection }
        val longFilteredTextRects = filterLongImageRects(textRects, pageWidth, pageHeight, logTag)
        val filteredTextRects = filterOverlapping(
            textRects = longFilteredTextRects,
            bubbleRects = deduplicatedBubbles.map { it.rect },
            threshold = TEXT_IOU_THRESHOLD,
            includeSameBubbleCheck = true
        )
        val mergedTextRects = RectGeometryDeduplicator.mergeSupplementRects(
            filteredTextRects,
            pageWidth,
            pageHeight,
            maxMergedHeight = longImageMaxRegionHeight(pageWidth, pageHeight)
        )
        if (mergedTextRects.isNotEmpty()) {
            AppLogger.log(logTag, "Free-text regions after tile merge: ${mergedTextRects.size}")
        }
        return buildDetectionResult(
            width = pageWidth,
            height = pageHeight,
            detections = deduplicatedBubbles,
            textRects = mergedTextRects,
            detectionMode = PageRegionDetectionMode.TILED_LONG
        )
    }

    private fun detectSingleBitmap(
        bitmap: Bitmap,
        logTag: String,
        detectionMode: PageRegionDetectionMode
    ): PageRegionDetectionResult? {
        val unified = detectUnifiedRegions(bitmap, logTag) ?: return null
        val maxMergedHeight = if (detectionMode == PageRegionDetectionMode.TILED_LONG) {
            bitmap.height * LONG_IMAGE_REGION_SCREEN_HEIGHT_RATIO
        } else {
            null
        }
        val filteredTextRects = filterOverlapping(
            textRects = unified.freeTextRects,
            bubbleRects = unified.balloons.map { it.rect },
            threshold = TEXT_IOU_THRESHOLD
        )
        val textRects = RectGeometryDeduplicator.mergeSupplementRects(
            filteredTextRects,
            bitmap.width,
            bitmap.height,
            maxMergedHeight = maxMergedHeight
        )
        if (textRects.isNotEmpty()) {
            AppLogger.log(logTag, "Free-text regions: ${textRects.size}")
        }
        return buildDetectionResult(
            width = bitmap.width,
            height = bitmap.height,
            detections = unified.balloons,
            textRects = textRects,
            detectionMode = detectionMode
        )
    }

    private fun detectUnifiedRegions(bitmap: Bitmap, logTag: String): UnifiedRegionDetection? {
        val detector = getBubbleDetector(logTag) ?: return null
        return try {
            val raw = detector.detectRegions(bitmap)
            UnifiedRegionDetection(
                balloons = filterTinyBubbleDetections(raw.balloons, bitmap, logTag),
                freeTextRects = raw.freeTextRects
            )
        } catch (e: Exception) {
            AppLogger.log(logTag, "Unified region detection failed", e)
            null
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
        pageWidth: Int,
        pageHeight: Int
    ): List<BubbleDetection> {
        val scaleX = tile.width / tileBitmapWidth.toFloat().coerceAtLeast(1f)
        val scaleY = tile.height / tileBitmapHeight.toFloat().coerceAtLeast(1f)
        return detections.map { detection ->
            detection.copy(
                rect = detection.rect.scaleBy(scaleX, scaleY).offsetBy(0f, tile.top.toFloat()),
                maskContour = detection.maskContour?.let {
                    remapTileMaskContourToPage(
                        contour = it,
                        tileTop = tile.top,
                        tileHeight = tile.height,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight
                    )
                }
            )
        }
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
            rect.scaleBy(scaleX, scaleY).offsetBy(0f, tile.top.toFloat())
        }
    }

    private fun deduplicateBubbleDetections(
        detections: List<BubbleDetection>
    ): List<DeduplicatedBubbleGroup> {
        if (detections.size <= 1) {
            return detections.map { DeduplicatedBubbleGroup(it, RectF(it.rect)) }
        }
        val visited = BooleanArray(detections.size)
        val result = ArrayList<DeduplicatedBubbleGroup>(detections.size)
        for (start in detections.indices) {
            if (visited[start]) continue
            val queue = ArrayDeque<Int>()
            val component = ArrayList<Int>()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component.add(current)
                for (next in detections.indices) {
                    if (visited[next]) continue
                    if (!shouldTreatAsSameBubble(detections[current].rect, detections[next].rect)) continue
                    visited[next] = true
                    queue.add(next)
                }
            }
            val candidates = component.map { index ->
                BubblePriorityCandidate(
                    confidence = detections[index].confidence,
                    hasMaskContour = detections[index].maskContour != null,
                    area = rectAreaValue(detections[index].rect)
                )
            }
            val bestOffset = choosePreferredBubbleCandidateIndex(candidates).coerceAtLeast(0)
            val best = detections[component[bestOffset]]
            val firstRect = detections[component.first()].rect
            var left = firstRect.left
            var top = firstRect.top
            var right = firstRect.right
            var bottom = firstRect.bottom
            for (offset in 1 until component.size) {
                val rect = detections[component[offset]].rect
                left = min(left, rect.left)
                top = min(top, rect.top)
                right = max(right, rect.right)
                bottom = max(bottom, rect.bottom)
            }
            // Multi-tile detections of one balloon often only cover partial heights.
            // Keep the union rect so OCR/render are not truncated to a single tile half.
            val unionRect = RectF(left, top, right, bottom)
            val rectExpanded = component.size > 1 && !rectsApproximatelyEqual(best.rect, unionRect)
            result.add(
                DeduplicatedBubbleGroup(
                    detection = best.copy(
                        rect = unionRect,
                        // Mask is relative to the tile-local detection; drop when expanded.
                        maskContour = if (rectExpanded) null else best.maskContour
                    ),
                    suppressionRect = unionRect
                )
            )
        }
        return result
    }

    private fun shouldTreatAsSameBubble(a: RectF, b: RectF): Boolean {
        return shouldTreatRectsAsSameBubbleForDedup(a, b)
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

    fun releaseLoadedDetectors() {
        val hadLoadedDetectors = bubbleDetector != null
        bubbleDetector = null
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
        threshold: Float,
        includeSameBubbleCheck: Boolean = false
    ): List<RectF> {
        if (bubbleRects.isEmpty()) return textRects
        val filtered = ArrayList<RectF>(textRects.size)
        for (rect in textRects) {
            var overlapped = false
            for (bubble in bubbleRects) {
                if (
                    iou(rect, bubble) >= threshold ||
                    contains(bubble, rect) ||
                    (includeSameBubbleCheck && shouldTreatRectsAsSameBubbleForDedup(rect, bubble))
                ) {
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
        val filtered = detections.filterNot { isTinyErrorBubble(it.rect, bitmap) }
        val removedCount = detections.size - filtered.size
        if (removedCount > 0) {
            AppLogger.log(
                logTag,
                "Filtered $removedCount tiny bubble false positives, kept ${filtered.size}"
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

    private fun isTinyErrorBubble(rect: RectF, bitmap: Bitmap): Boolean {
        val width = rect.width().coerceAtLeast(0f)
        val height = rect.height().coerceAtLeast(0f)
        if (width <= 0f || height <= 0f) return true

        val shortSide = min(width, height)
        val longSide = max(width, height)
        val imageMinSide = min(bitmap.width, bitmap.height).toFloat().coerceAtLeast(1f)
        val imageArea = (bitmap.width.toFloat() * bitmap.height.toFloat()).coerceAtLeast(1f)
        val areaRatio = (width * height) / imageArea

        val maxShortSide = max(TINY_BUBBLE_SHORT_SIDE_MIN_PX, imageMinSide * TINY_BUBBLE_SHORT_SIDE_RATIO)
        val maxLongSide = max(TINY_BUBBLE_LONG_SIDE_MIN_PX, imageMinSide * TINY_BUBBLE_LONG_SIDE_RATIO)

        return shortSide <= maxShortSide &&
            longSide <= maxLongSide &&
            areaRatio <= TINY_BUBBLE_MAX_AREA_RATIO
    }

    private fun iou(a: RectF, b: RectF): Float {
        val inter = rectIntersectionArea(a, b)
        val union = rectAreaValue(a) + rectAreaValue(b) - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun contains(outer: RectF, inner: RectF): Boolean {
        return rectContains(outer, inner)
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

private const val LONG_IMAGE_ASPECT_THRESHOLD = 3.0f
private const val LONG_IMAGE_MIN_HEIGHT_PX = 4096
// Near-square tiles: manga109-seg ONNX is fixed 640x640; tall 2.25:1 tiles letterbox
// down to ~280 effective width and miss many balloons on webtoons/long strips.
private const val LONG_IMAGE_TILE_HEIGHT_WIDTH_RATIO = 1.05f
private const val LONG_IMAGE_TILE_MIN_HEIGHT_PX = 960
private const val LONG_IMAGE_TILE_MAX_HEIGHT_PX = 1400
private const val LONG_IMAGE_TILE_OVERLAP_RATIO = 0.32f
private const val LONG_IMAGE_TILE_OVERLAP_MIN_PX = 320
// Keep for full-page TILED_LONG path (bitmap.height * ratio) if ever used.
private const val LONG_IMAGE_REGION_SCREEN_HEIGHT_RATIO = 0.85f
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
private const val BUBBLE_DEDUP_RECT_EQUAL_EPS_PX = 2f

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

private fun rectsApproximatelyEqual(a: RectF, b: RectF): Boolean {
    val eps = BUBBLE_DEDUP_RECT_EQUAL_EPS_PX
    return abs(a.left - b.left) <= eps &&
        abs(a.top - b.top) <= eps &&
        abs(a.right - b.right) <= eps &&
        abs(a.bottom - b.bottom) <= eps
}

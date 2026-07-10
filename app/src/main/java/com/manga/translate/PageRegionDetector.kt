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

internal fun buildTileBubbleSuppressionMasks(
    bubbleRects: List<RectF>,
    tile: DetectionTile,
    tileBitmapWidth: Int,
    tileBitmapHeight: Int
): List<TextSuppressionMask> {
    if (bubbleRects.isEmpty() || tileBitmapWidth <= 0 || tileBitmapHeight <= 0 || tile.height <= 0) {
        return emptyList()
    }
    val scaleX = tileBitmapWidth / tile.width.toFloat().coerceAtLeast(1f)
    val scaleY = tileBitmapHeight / tile.height.toFloat().coerceAtLeast(1f)
    val masks = ArrayList<TextSuppressionMask>(bubbleRects.size)
    for (rect in bubbleRects) {
        val intersectionLeft = max(0f, rect.left)
        val intersectionTop = max(tile.top.toFloat(), rect.top)
        val intersectionRight = min(tile.width.toFloat(), rect.right)
        val intersectionBottom = min(tile.bottom.toFloat(), rect.bottom)
        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) continue

        val localRect = RectF(
            rect.left * scaleX,
            (rect.top - tile.top) * scaleY,
            rect.right * scaleX,
            (rect.bottom - tile.top) * scaleY
        )
        val pad = max(
            TranslationCoreDefaults.PageRegionMaskExpandMin,
            max(1f, localRect.height()) * TranslationCoreDefaults.PageRegionMaskExpandRatio
        )
        masks.add(
            TextSuppressionMask.Rect(
                RectF(
                    (localRect.left - pad).coerceIn(0f, tileBitmapWidth.toFloat()),
                    (localRect.top - pad).coerceIn(0f, tileBitmapHeight.toFloat()),
                    (localRect.right + pad).coerceIn(0f, tileBitmapWidth.toFloat()),
                    (localRect.bottom + pad).coerceIn(0f, tileBitmapHeight.toFloat())
                )
            )
        )
    }
    return masks
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

    return shouldTreatPartiallyShiftedRectsAsSameBubble(a, b, overlapOverMin)
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
    return longImageDetectionTileHeight(pageWidth, pageHeight) *
        LONG_IMAGE_REGION_SCREEN_HEIGHT_RATIO
}

internal class PageRegionDetector(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private var bubbleDetector: BubbleDetector? = null
    private var textDetector: TextDetector? = null

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
        var bubbleFailedTileCount = 0
        for ((index, tile) in tiles.withIndex()) {
            currentCoroutineContext().ensureActive()
            val tileTag = "$logTag[bubble tile ${index + 1}/${tiles.size}]"
            val tileBitmap = try {
                cropSource.decodeRegion(tile.toRectF(), maxEdge = DETECTION_MAX_EDGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bubbleFailedTileCount++
                AppLogger.log(tileTag, "Long-image bubble tile decode failed; skipping tile", e)
                continue
            }
            if (tileBitmap == null) {
                bubbleFailedTileCount++
                AppLogger.log(tileTag, "Long-image tile decode returned null; skipping tile")
                continue
            }
            try {
                val localDetections = detectBubbleDetections(tileBitmap, tileTag) ?: run {
                    bubbleFailedTileCount++
                    AppLogger.log(tileTag, "Long-image bubble detection returned null; skipping tile")
                    continue
                }
                bubbleDetections.addAll(
                    remapTileBubbleDetectionsToPage(
                        detections = localDetections,
                        tileBitmapWidth = tileBitmap.width,
                        tileBitmapHeight = tileBitmap.height,
                        tile = tile,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bubbleFailedTileCount++
                AppLogger.log(tileTag, "Long-image bubble detection failed; skipping tile", e)
            } finally {
                tileBitmap.recycleSafely()
            }
        }
        if (bubbleFailedTileCount > 0) {
            AppLogger.log(
                logTag,
                "Skipped $bubbleFailedTileCount/${tiles.size} long-image bubble tiles"
            )
        }
        val deduplicatedGroups = filterLongImageBubbleGroups(
            deduplicateBubbleDetections(bubbleDetections),
            pageWidth,
            pageHeight,
            logTag
        )
        val deduplicatedBubbles = deduplicatedGroups.map { it.detection }
        val suppressionBubbleRects = deduplicatedGroups.map { it.suppressionRect }
        val textRects = detectLongImageTextRects(
            cropSource = cropSource,
            tiles = tiles,
            suppressionBubbleRects = suppressionBubbleRects,
            logTag = logTag
        )
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
            AppLogger.log(logTag, "Supplemented ${mergedTextRects.size} text boxes after tile merge")
        }
        return buildDetectionResult(
            width = pageWidth,
            height = pageHeight,
            detections = deduplicatedBubbles,
            textRects = mergedTextRects,
            detectionMode = PageRegionDetectionMode.TILED_LONG
        )
    }

    private suspend fun detectLongImageTextRects(
        cropSource: BitmapCropSource,
        tiles: List<DetectionTile>,
        suppressionBubbleRects: List<RectF>,
        logTag: String
    ): List<RectF> {
        val detector = getTextDetector(logTag) ?: return emptyList()
        val textRects = ArrayList<RectF>()
        var failedTileCount = 0
        for ((index, tile) in tiles.withIndex()) {
            currentCoroutineContext().ensureActive()
            val tileTag = "$logTag[text tile ${index + 1}/${tiles.size}]"
            val tileBitmap = try {
                cropSource.decodeRegion(tile.toRectF(), maxEdge = DETECTION_MAX_EDGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedTileCount++
                AppLogger.log(tileTag, "Long-image text tile decode failed; skipping tile", e)
                continue
            }
            if (tileBitmap == null) {
                failedTileCount++
                AppLogger.log(tileTag, "Long-image text tile decode returned null; skipping tile")
                continue
            }
            try {
                val masks = buildTileBubbleSuppressionMasks(
                    bubbleRects = suppressionBubbleRects,
                    tile = tile,
                    tileBitmapWidth = tileBitmap.width,
                    tileBitmapHeight = tileBitmap.height
                )
                val localTextRects = detector.detect(tileBitmap, masks)
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
                AppLogger.log(tileTag, "Long-image text detection failed; skipping tile", e)
            } finally {
                tileBitmap.recycleSafely()
            }
        }
        if (failedTileCount > 0) {
            AppLogger.log(logTag, "Skipped $failedTileCount/${tiles.size} long-image text tiles")
        }
        return textRects
    }

    private fun detectSingleBitmap(
        bitmap: Bitmap,
        logTag: String,
        detectionMode: PageRegionDetectionMode
    ): PageRegionDetectionResult? {
        val detections = detectBubbleDetections(bitmap, logTag) ?: return null
        val textRects = detectSupplementTextRects(bitmap, detections, detectionMode)
        if (textRects.isNotEmpty()) {
            AppLogger.log(logTag, "Supplemented ${textRects.size} text boxes")
        }
        return buildDetectionResult(
            width = bitmap.width,
            height = bitmap.height,
            detections = detections,
            textRects = textRects,
            detectionMode = detectionMode
        )
    }

    private fun detectBubbleDetections(bitmap: Bitmap, logTag: String): List<BubbleDetection>? {
        val detector = getBubbleDetector(logTag) ?: return null
        return filterTinyBubbleDetections(
            detections = detector.detect(bitmap),
            bitmap = bitmap,
            logTag = logTag
        )
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
            result.add(
                DeduplicatedBubbleGroup(
                    detection = detections[component[bestOffset]],
                    suppressionRect = RectF(left, top, right, bottom)
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
            bubbleDetector = BubbleDetector(appContext, settingsStore = settingsStore)
            bubbleDetector
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init bubble detector", e)
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

    private fun getTextDetector(logTag: String): TextDetector? {
        if (textDetector != null) return textDetector
        return try {
            textDetector = TextDetector(appContext, settingsStore = settingsStore)
            textDetector
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init text detector", e)
            null
        }
    }

    private fun detectSupplementTextRects(
        bitmap: Bitmap,
        detections: List<BubbleDetection>,
        detectionMode: PageRegionDetectionMode
    ): List<RectF> {
        val textDetector = getTextDetector("PageRegionDetector") ?: return emptyList()
        val bubbleRects = detections.map { it.rect }
        val rawTextRects = textDetector.detect(bitmap, buildSuppressionMasks(detections, bitmap))
        val filtered = filterOverlapping(rawTextRects, bubbleRects, TEXT_IOU_THRESHOLD)
        val maxMergedHeight = if (detectionMode == PageRegionDetectionMode.TILED_LONG) {
            bitmap.height * LONG_IMAGE_REGION_SCREEN_HEIGHT_RATIO
        } else {
            null
        }
        return RectGeometryDeduplicator.mergeSupplementRects(
            filtered,
            bitmap.width,
            bitmap.height,
            maxMergedHeight = maxMergedHeight
        )
    }

    private fun buildRegions(
        detections: List<BubbleDetection>,
        bubbleRects: List<RectF>,
        textRects: List<RectF>
    ): List<PageRegion> {
        val allRects = ArrayList<RectF>(bubbleRects.size + textRects.size)
        allRects.addAll(bubbleRects)
        allRects.addAll(textRects)
        val bubbleDetectorCount = bubbleRects.size
        return allRects.mapIndexed { index, rect ->
            PageRegion(
                id = index,
                rect = rect,
                source = if (index < bubbleDetectorCount) {
                    BubbleSource.BUBBLE_DETECTOR
                } else {
                    BubbleSource.TEXT_DETECTOR
                },
                maskContour = if (index < bubbleDetectorCount) {
                    detections.getOrNull(index)?.maskContour
                } else {
                    null
                }
            )
        }
    }

    private fun buildSuppressionMasks(
        detections: List<BubbleDetection>,
        bitmap: Bitmap
    ): List<TextSuppressionMask> {
        if (detections.isEmpty()) return emptyList()
        return detections.map { det ->
            val contour = det.maskContour
            if (contour != null && contour.size >= 6) {
                TextSuppressionMask.Contour(contour)
            } else {
                TextSuppressionMask.Rect(
                    padRect(det.rect, bitmap.width, bitmap.height, MASK_EXPAND_RATIO, MASK_EXPAND_MIN)
                )
            }
        }
    }

    private fun padRect(rect: RectF, width: Int, height: Int, ratio: Float, minPad: Float): RectF {
        val h = max(1f, rect.height())
        val pad = max(minPad, ratio * h)
        val left = (rect.left - pad).coerceIn(0f, width.toFloat())
        val top = (rect.top - pad).coerceIn(0f, height.toFloat())
        val right = (rect.right + pad).coerceIn(0f, width.toFloat())
        val bottom = (rect.bottom + pad).coerceIn(0f, height.toFloat())
        return RectF(left, top, right, bottom)
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
        private const val MASK_EXPAND_RATIO = TranslationCoreDefaults.PageRegionMaskExpandRatio
        private const val MASK_EXPAND_MIN = TranslationCoreDefaults.PageRegionMaskExpandMin
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
private const val LONG_IMAGE_TILE_HEIGHT_WIDTH_RATIO = 2.25f
private const val LONG_IMAGE_TILE_MIN_HEIGHT_PX = 1600
private const val LONG_IMAGE_TILE_MAX_HEIGHT_PX = 2800
private const val LONG_IMAGE_TILE_OVERLAP_RATIO = 0.18f
private const val LONG_IMAGE_TILE_OVERLAP_MIN_PX = 240
private const val LONG_IMAGE_REGION_SCREEN_HEIGHT_RATIO = 0.85f
private const val BUBBLE_DEDUP_IOU_THRESHOLD = TranslationCoreDefaults.BubbleDedupIouThreshold
private const val BUBBLE_DEDUP_CONTAINMENT_THRESHOLD = 0.9f
private const val BUBBLE_DEDUP_PARTIAL_OVERLAP_MIN_RATIO = 0.58f
private const val BUBBLE_DEDUP_AXIS_OVERLAP_MIN_RATIO = 0.55f
private const val BUBBLE_DEDUP_CENTER_DRIFT_RATIO = 0.38f
private const val BUBBLE_DEDUP_CENTER_DRIFT_PAD = 18f

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

package com.manga.translate

import android.graphics.RectF
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PageRegionDetectorTest {

    @Test
    fun `long image tiling only enables for threshold-matching vertical pages`() {
        assertFalse(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 2047))
        assertFalse(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 2700))
        assertFalse(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 2800))
        assertTrue(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 2801))
        assertTrue(shouldUseLongImageTiling(pageWidth = 1000, pageHeight = 2200))
        assertFalse(shouldUseLongImageTiling(pageWidth = 200, pageHeight = 500))
    }

    @Test
    fun `large square portrait and wide pages stay on full page detection`() {
        assertFalse(shouldUseLongImageTiling(pageWidth = 4096, pageHeight = 4096))
        assertFalse(shouldUseLongImageTiling(pageWidth = 3000, pageHeight = 4000))
        assertFalse(shouldUseLongImageTiling(pageWidth = 8192, pageHeight = 2048))
    }

    @Test
    fun `long image text tiles cover full width and only split vertically`() {
        val tiles = planLongImageTextDetectionTiles(pageWidth = 1000, pageHeight = 7000)
        val tileHeight = longImageTextDetectionTileHeight(pageWidth = 1000, pageHeight = 7000)

        assertEquals(1500, tileHeight)
        assertEquals(7, tiles.size)
        assertEquals(0, tiles.first().top)
        assertEquals(7000, tiles.last().bottom)
        assertTrue(tiles.all { it.left == 0 && it.right == 1000 })
        assertTrue(tiles.all { it.width == 1000 && it.height <= 1500 })
        assertTrue(tiles.zipWithNext().all { (a, b) -> b.top < a.bottom })
        assertTrue(tiles.zipWithNext().minOf { (a, b) -> a.bottom - b.top } >= 450)
    }

    @Test
    fun `long image just over one bubble tile uses one full-height tile`() {
        val tiles = planLongImageBubbleDetectionTiles(pageWidth = 1400, pageHeight = 2801)

        assertEquals(1, tiles.size)
        assertEquals(2801, tiles.single().height)
    }

    @Test
    fun `tile suppression rects are scaled expanded and clipped`() {
        val tile = DetectionTile(left = 440, top = 448, right = 1080, bottom = 1088)
        val suppressionRects = buildTileTextSuppressionRects(
            pageBubbleRects = listOf(
                RectF(400f, 400f, 600f, 600f),
                RectF(100f, 100f, 200f, 200f)
            ),
            tile = tile,
            tileBitmapWidth = 320,
            tileBitmapHeight = 320
        )

        assertEquals(1, suppressionRects.size)
        assertEquals(RectF(0f, 0f, 95f, 91f), suppressionRects.single())
    }

    @Test
    fun `reference webtoon text plan has no horizontal tile multiplication`() {
        val tiles = planLongImageTextDetectionTiles(pageWidth = 1080, pageHeight = 28800)

        assertEquals(25, tiles.size)
        assertTrue(tiles.all { it.left == 0 && it.right == 1080 })
        assertTrue(tiles.all { it.width == 1080 && it.height <= 1620 })
        assertEquals(28800, tiles.maxOf { it.bottom })
    }

    @Test
    fun `long image bubble plan uses full width 2x tiles and overlap`() {
        val tiles = planLongImageBubbleDetectionTiles(pageWidth = 1080, pageHeight = 28800)

        assertEquals(2160, longImageBubbleDetectionTileHeight(1080, 28800))
        assertEquals(17, tiles.size)
        assertTrue(tiles.all { it.left == 0 && it.right == 1080 && it.height <= 2160 })
        assertEquals(0, tiles.first().top)
        assertEquals(28800, tiles.last().bottom)
        assertTrue(tiles.zipWithNext().all { (a, b) -> b.top < a.bottom })
        assertTrue(tiles.zipWithNext().minOf { (a, b) -> a.bottom - b.top } >= 432)
    }

    @Test
    fun `long image region filter only removes full-strip regions`() {
        // Normal tall balloon (~1.4 page widths) must not be filtered.
        assertFalse(
            shouldFilterLongImageRegion(
                RectF(100f, 100f, 900f, 1500f),
                pageWidth = 1000,
                pageHeight = 7000
            )
        )
        // Abnormal full-strip region (~1.9 page widths) is filtered.
        assertTrue(
            shouldFilterLongImageRegion(
                RectF(100f, 100f, 900f, 2000f),
                pageWidth = 1000,
                pageHeight = 7000
            )
        )
    }

    @Test
    fun `long image region filter is disabled for regular pages`() {
        assertFalse(
            shouldFilterLongImageRegion(
                RectF(100f, 100f, 900f, 2050f),
                pageWidth = 1400,
                pageHeight = 2800
            )
        )
    }

    @Test
    fun `supplement rect merge keeps original rects when union would be screen-sized`() {
        val rects = listOf(
            RectF(100f, 100f, 220f, 1800f),
            RectF(100f, 1700f, 220f, 2020f)
        )

        val merged = RectGeometryDeduplicator.mergeSupplementRects(
            rects = rects,
            imageWidth = 1000,
            imageHeight = 7000,
            maxMergedHeight = longImageMaxRegionHeight(pageWidth = 1000, pageHeight = 7000)
        )

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.top == 100f && it.bottom == 1800f })
        assertTrue(merged.any { it.top == 1700f && it.bottom == 2020f })
    }

    @Test
    fun `short OCR text merge keeps original bubbles when union would be screen-sized`() {
        val bubbles = listOf(
            OcrBubble(
                id = 0,
                rect = RectF(100f, 100f, 220f, 220f),
                text = "あ",
                source = BubbleSource.TEXT_DETECTOR
            ),
            OcrBubble(
                id = 1,
                rect = RectF(105f, 1900f, 225f, 2020f),
                text = "い",
                source = BubbleSource.TEXT_DETECTOR
            )
        )

        val merged = RectGeometryDeduplicator.mergeShortTextDetectorOcrBubbles(
            bubbles = bubbles,
            imageWidth = 1000,
            imageHeight = 7000,
            maxMergedHeight = longImageMaxRegionHeight(pageWidth = 1000, pageHeight = 7000)
        )

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.rect.top == 100f && it.rect.bottom == 220f })
        assertTrue(merged.any { it.rect.top == 1900f && it.rect.bottom == 2020f })
    }

    @Test
    fun `tile mask contour remaps from tile normalized coordinates to page normalized coordinates`() {
        val remapped = remapTileMaskContourToPage(
            contour = floatArrayOf(0f, 0f, 1f, 1f, 0.5f, 0.5f),
            tileTop = 2000,
            tileHeight = 2500,
            pageWidth = 1000,
            pageHeight = 7000,
            tileLeft = 200,
            tileWidth = 600
        )

        assertArrayEquals(
            floatArrayOf(
                0.2f, 2000f / 7000f,
                0.8f, 4500f / 7000f,
                0.5f, 3250f / 7000f
            ),
            remapped,
            1e-4f
        )
    }

    @Test
    fun `bubble priority prefers higher confidence when gap exceeds threshold`() {
        val best = choosePreferredBubbleCandidateIndex(
            listOf(
                BubblePriorityCandidate(confidence = 0.70f, hasMaskContour = false, area = 100f),
                BubblePriorityCandidate(confidence = 0.73f, hasMaskContour = false, area = 80f)
            )
        )

        assertEquals(1, best)
    }

    @Test
    fun `bubble priority prefers contour then area when confidence gap is small`() {
        val contourPreferred = choosePreferredBubbleCandidateIndex(
            listOf(
                BubblePriorityCandidate(confidence = 0.80f, hasMaskContour = false, area = 200f),
                BubblePriorityCandidate(confidence = 0.81f, hasMaskContour = true, area = 150f)
            )
        )
        val areaPreferred = choosePreferredBubbleCandidateIndex(
            listOf(
                BubblePriorityCandidate(confidence = 0.80f, hasMaskContour = true, area = 120f),
                BubblePriorityCandidate(confidence = 0.80f, hasMaskContour = true, area = 180f)
            )
        )

        assertEquals(1, contourPreferred)
        assertEquals(1, areaPreferred)
    }

    @Test
    fun `bubble priority rejects tile boundary contour even when confidence is higher`() {
        val best = choosePreferredBubbleCandidateIndex(
            listOf(
                BubblePriorityCandidate(
                    confidence = 0.78f,
                    hasMaskContour = true,
                    area = 180f,
                    touchesInternalTileBoundary = false
                ),
                BubblePriorityCandidate(
                    confidence = 0.91f,
                    hasMaskContour = true,
                    area = 220f,
                    touchesInternalTileBoundary = true
                )
            )
        )

        assertEquals(0, best)
    }

    @Test
    fun `tile bubble union only applies when every duplicate is boundary-truncated`() {
        assertFalse(
            shouldUnionTileBubbleCandidates(
                listOf(
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = false),
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = false)
                )
            )
        )
        assertFalse(
            shouldUnionTileBubbleCandidates(
                listOf(
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = false),
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = true)
                )
            )
        )
        assertTrue(
            shouldUnionTileBubbleCandidates(
                listOf(
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = true),
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = true)
                )
            )
        )
    }

    @Test
    fun `tile contour merge covers upper and lower partial masks`() {
        val upper = floatArrayOf(
            0.20f, 0.20f,
            0.20f, 0.52f,
            0.60f, 0.52f,
            0.60f, 0.20f
        )
        val lower = floatArrayOf(
            0.22f, 0.46f,
            0.22f, 0.80f,
            0.62f, 0.80f,
            0.62f, 0.46f
        )

        val merged = mergePageMaskContours(listOf(upper, lower), pageHeight = 7000)

        requireNotNull(merged)
        val xs = merged.indices.filter { it % 2 == 0 }.map { merged[it] }
        val ys = merged.indices.filter { it % 2 == 1 }.map { merged[it] }
        assertEquals(0.20f, xs.min(), 1e-4f)
        assertEquals(0.62f, xs.max(), 1e-4f)
        assertEquals(0.20f, ys.min(), 1e-4f)
        assertEquals(0.80f, ys.max(), 1e-4f)
    }

    @Test
    fun `bubble dedup matches highly overlapping or contained rectangles`() {
        val overlappingA = RectF(0f, 0f, 100f, 100f)
        val overlappingB = RectF(5f, 5f, 95f, 95f)
        val container = RectF(0f, 0f, 100f, 100f)
        val inside = RectF(5f, 5f, 95f, 95f)
        val shiftedTileDuplicateA = RectF(100f, 1800f, 420f, 2120f)
        val shiftedTileDuplicateB = RectF(155f, 1740f, 455f, 2050f)
        val separate = RectF(150f, 0f, 250f, 100f)
        val stackedNeighborA = RectF(100f, 1000f, 420f, 1320f)
        val stackedNeighborB = RectF(120f, 1225f, 440f, 1545f)
        // Adjacent tiles often split one balloon into upper/lower halves with modest overlap.
        val tileSplitUpper = RectF(200f, 2000f, 520f, 2280f)
        val tileSplitLower = RectF(210f, 2200f, 530f, 2550f)
        // Two distinct stacked bubbles with a clear gap should stay separate.
        val distinctStackedA = RectF(100f, 1000f, 420f, 1280f)
        val distinctStackedB = RectF(110f, 1320f, 430f, 1600f)

        assertTrue(shouldTreatRectsAsSameBubbleForDedup(overlappingA, overlappingB))
        assertTrue(shouldTreatRectsAsSameBubbleForDedup(container, inside))
        assertTrue(shouldTreatRectsAsSameBubbleForDedup(shiftedTileDuplicateA, shiftedTileDuplicateB))
        assertTrue(shouldTreatRectsAsSameBubbleForDedup(tileSplitUpper, tileSplitLower))
        assertFalse(shouldTreatRectsAsSameBubbleForDedup(overlappingA, separate))
        // Partial-overlap path still merges tightly stacked neighbors with large Y overlap;
        // only clearly gapped pairs stay separate.
        assertFalse(shouldTreatRectsAsSameBubbleForDedup(distinctStackedA, distinctStackedB))
        // Keep previous tight-neighbor fixture for regression visibility of partial-overlap path.
        assertTrue(shouldTreatRectsAsSameBubbleForDedup(stackedNeighborA, stackedNeighborB))
    }

    @Test
    fun `tile dedup only merges duplicate candidates from different tiles`() {
        val first = RectF(100f, 100f, 400f, 400f)
        val intersecting = RectF(160f, 120f, 460f, 420f)

        assertFalse(
            shouldDeduplicateTileCandidates(
                firstTileIndex = 2,
                secondTileIndex = 2,
                firstRect = first,
                secondRect = intersecting
            )
        )
        assertTrue(
            shouldDeduplicateTileCandidates(
                firstTileIndex = 2,
                secondTileIndex = 3,
                firstRect = first,
                secondRect = intersecting
            )
        )
        assertEquals(
            RectF(100f, 100f, 460f, 420f),
            unionDetectionRects(listOf(first, intersecting))
        )
    }

    @Test
    fun `text suppression only removes actual bubble overlap`() {
        val bubble = RectF(100f, 100f, 400f, 400f)
        val adjacentText = RectF(110f, 400f, 410f, 700f)
        val containedText = RectF(150f, 150f, 250f, 250f)

        assertFalse(shouldFilterTextRectByBubble(adjacentText, bubble, 0.2f))
        assertTrue(shouldFilterTextRectByBubble(containedText, bubble, 0.2f))
    }

    @Test
    fun `detection strategy tag switches between full and tiled modes`() {
        assertEquals(
            "det_full_scaled_manga109seg1600_text_yolo11_v16",
            buildDetectionStrategyTag(pageWidth = 640, pageHeight = 640)
        )
        assertEquals(
            "det_full_scaled_manga109seg1600_text_yolo11_v16",
            buildDetectionStrategyTag(pageWidth = 1080, pageHeight = 1600)
        )
        assertEquals(
            "det_full_scaled_manga109seg1600_text_yolo11_v16",
            buildDetectionStrategyTag(pageWidth = 4096, pageHeight = 4096)
        )
        assertEquals(
            "det_full_scaled_manga109seg1600_text_yolo11_v16",
            buildDetectionStrategyTag(pageWidth = 8192, pageHeight = 2048)
        )
        assertEquals(
            "det_vertical_tiled_bubble_2x_text_1_5x_manga109seg_yolo11_v17",
            buildDetectionStrategyTag(pageWidth = 1000, pageHeight = 2200)
        )
    }
}

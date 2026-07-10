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
        assertFalse(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 4095))
        assertFalse(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 4199))
        assertTrue(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 4200))
        assertTrue(shouldUseLongImageTiling(pageWidth = 1000, pageHeight = 4096))
    }

    @Test
    fun `long image tile plan fully covers page with overlap and unique starts`() {
        val tiles = planLongImageDetectionTiles(pageWidth = 1000, pageHeight = 7000)

        assertEquals(listOf(0, 1845, 3690, 5535), tiles.map { it.top })
        assertEquals(7000, tiles.last().bottom)
        assertEquals(7000, tiles.maxOf { it.bottom })
        assertEquals(tiles.map { it.top }.distinct().size, tiles.size)
        assertTrue(tiles.zipWithNext().all { (a, b) -> b.top < a.bottom })
    }

    @Test
    fun `long image region filter only removes screen-sized regions`() {
        assertFalse(
            shouldFilterLongImageRegion(
                RectF(100f, 100f, 900f, 1500f),
                pageWidth = 1000,
                pageHeight = 7000
            )
        )
        assertTrue(
            shouldFilterLongImageRegion(
                RectF(100f, 100f, 900f, 2050f),
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
                pageWidth = 1000,
                pageHeight = 3000
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
            pageHeight = 7000
        )

        assertArrayEquals(
            floatArrayOf(
                0f, 2000f / 7000f,
                1f, 4500f / 7000f,
                0.5f, 3250f / 7000f
            ),
            remapped,
            1e-4f
        )
    }

    @Test
    fun `global bubble creates suppression masks in every overlapping tile`() {
        val tiles = planLongImageDetectionTiles(pageWidth = 1000, pageHeight = 7000)
        val bubble = RectF(200f, 1900f, 700f, 2100f)

        val firstMasks = buildTileBubbleSuppressionMasks(
            bubbleRects = listOf(bubble),
            tile = tiles[0],
            tileBitmapWidth = 1000,
            tileBitmapHeight = tiles[0].height
        )
        val secondMasks = buildTileBubbleSuppressionMasks(
            bubbleRects = listOf(bubble),
            tile = tiles[1],
            tileBitmapWidth = 500,
            tileBitmapHeight = tiles[1].height / 2
        )

        assertEquals(1, firstMasks.size)
        assertEquals(1, secondMasks.size)
        val firstRect = (firstMasks.single() as TextSuppressionMask.Rect).rect
        val secondRect = (secondMasks.single() as TextSuppressionMask.Rect).rect
        assertTrue(firstRect.top < 1900f)
        assertTrue(firstRect.bottom > 2100f)
        assertTrue(secondRect.top < (1900f - tiles[1].top) / 2f)
        assertTrue(secondRect.bottom > (2100f - tiles[1].top) / 2f)
    }

    @Test
    fun `global bubble outside tile does not create suppression mask`() {
        val tile = planLongImageDetectionTiles(pageWidth = 1000, pageHeight = 7000).last()

        val masks = buildTileBubbleSuppressionMasks(
            bubbleRects = listOf(RectF(200f, 100f, 700f, 300f)),
            tile = tile,
            tileBitmapWidth = 1000,
            tileBitmapHeight = tile.height
        )

        assertTrue(masks.isEmpty())
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

        assertTrue(shouldTreatRectsAsSameBubbleForDedup(overlappingA, overlappingB))
        assertTrue(shouldTreatRectsAsSameBubbleForDedup(container, inside))
        assertTrue(shouldTreatRectsAsSameBubbleForDedup(shiftedTileDuplicateA, shiftedTileDuplicateB))
        assertFalse(shouldTreatRectsAsSameBubbleForDedup(overlappingA, separate))
        assertFalse(shouldTreatRectsAsSameBubbleForDedup(stackedNeighborA, stackedNeighborB))
    }

    @Test
    fun `detection strategy tag switches between full and tiled modes`() {
        assertEquals("det_full_v1", buildDetectionStrategyTag(pageWidth = 1600, pageHeight = 3000))
        assertEquals("det_tiled_long_v3", buildDetectionStrategyTag(pageWidth = 1000, pageHeight = 4096))
    }
}

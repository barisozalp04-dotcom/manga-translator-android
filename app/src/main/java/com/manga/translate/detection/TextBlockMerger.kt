package com.manga.translate.detection

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class TextLineOrientation {
    HORIZONTAL,
    VERTICAL,
    AMBIGUOUS
}

internal data class TextBlock(
    val rect: RectF,
    val lines: List<RectF>,
    val orientation: TextLineOrientation,
    val maskContour: FloatArray
)

/**
 * Groups Paddle OCR line boxes into text blocks before recognition.
 *
 * Merges are evaluated against the entire prospective group so a chain of
 * individually close diagonal boxes cannot grow into one block.
 */
internal object TextBlockMerger {
    fun merge(
        lineRects: List<RectF>,
        imageWidth: Int,
        imageHeight: Int
    ): List<TextBlock> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        val lines = deduplicateLines(lineRects, imageWidth, imageHeight)
        if (lines.isEmpty()) return emptyList()

        val horizontal = ArrayList<LineGroup>()
        val vertical = ArrayList<LineGroup>()
        val ambiguous = ArrayList<LineGroup>()
        lines.forEach { rect ->
            val group = LineGroup(mutableListOf(rect), orientationOf(rect))
            when (group.orientation) {
                TextLineOrientation.HORIZONTAL -> horizontal.add(group)
                TextLineOrientation.VERTICAL -> vertical.add(group)
                TextLineOrientation.AMBIGUOUS -> ambiguous.add(group)
            }
        }

        val groups = ArrayList<LineGroup>()
        groups.addAll(mergeOriented(horizontal))
        groups.addAll(mergeOriented(vertical))
        groups.addAll(ambiguous)
        absorbContainedSingletons(groups)

        return groups
            .map { group ->
                val sortedLines = group.sortedLines()
                TextBlock(
                    rect = group.bounds(),
                    lines = sortedLines,
                    orientation = group.orientation,
                    maskContour = buildMaskContour(
                        lines = sortedLines,
                        orientation = group.orientation,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )
                )
            }
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    fun deduplicateLines(
        lineRects: List<RectF>,
        imageWidth: Int,
        imageHeight: Int
    ): List<RectF> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        return deduplicateClampedLines(
            lineRects.mapNotNull { it.clampTo(imageWidth, imageHeight) }
        ).sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun mergeOriented(initial: List<LineGroup>): List<LineGroup> {
        val groups = initial.toMutableList()
        while (groups.size > 1) {
            var bestFirst = -1
            var bestSecond = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (first in 0 until groups.lastIndex) {
                for (second in first + 1 until groups.size) {
                    val score = mergeScore(groups[first], groups[second]) ?: continue
                    if (score > bestScore) {
                        bestScore = score
                        bestFirst = first
                        bestSecond = second
                    }
                }
            }
            if (bestFirst < 0 || bestScore < MIN_MERGE_SCORE) break
            groups[bestFirst].lines.addAll(groups[bestSecond].lines)
            groups.removeAt(bestSecond)
        }
        return groups
    }

    private fun mergeScore(first: LineGroup, second: LineGroup): Float? {
        if (first.orientation != second.orientation) return null
        val orientation = first.orientation
        if (orientation == TextLineOrientation.AMBIGUOUS) return null
        val lines = (first.lines + second.lines).sortedBy { it.primaryCenter(orientation) }
        val thickness = median(lines.map { it.primaryThickness(orientation) }).coerceAtLeast(1f)

        val leadingSpread = spread(lines.map { it.leadingEdge(orientation) }) / thickness
        val trailingSpread = spread(lines.map { it.trailingEdge(orientation) }) / thickness
        val edgeSpread = min(leadingSpread, trailingSpread)
        if (edgeSpread > MAX_EDGE_SPREAD_RATIO) return null

        val neighborStats = lines.zipWithNext().map { (current, next) ->
            val gap = max(0f, next.primaryStart(orientation) - current.primaryEnd(orientation))
            val overlap = overlapLength(
                current.crossStart(orientation),
                current.crossEnd(orientation),
                next.crossStart(orientation),
                next.crossEnd(orientation)
            )
            val minCrossSize = min(
                current.crossSize(orientation),
                next.crossSize(orientation)
            ).coerceAtLeast(1f)
            NeighborStats(
                gapRatio = gap / thickness,
                crossOverlapRatio = overlap / minCrossSize,
                centerDistance = next.primaryCenter(orientation) - current.primaryCenter(orientation),
                thicknessRatio = min(
                    current.primaryThickness(orientation),
                    next.primaryThickness(orientation)
                ) / max(
                    current.primaryThickness(orientation),
                    next.primaryThickness(orientation)
                ).coerceAtLeast(1f)
            )
        }
        if (neighborStats.any { it.gapRatio > MAX_GAP_RATIO }) return null
        if (neighborStats.any { it.crossOverlapRatio < MIN_CROSS_OVERLAP_RATIO }) return null

        val alignmentScore = 1f - edgeSpread / MAX_EDGE_SPREAD_RATIO
        val connectionScore = 1f - neighborStats.maxOf { it.gapRatio } / MAX_GAP_RATIO
        val overlapScore = neighborStats.minOf { it.crossOverlapRatio }.coerceIn(0f, 1f)
        val thicknessScore = neighborStats.minOf { it.thicknessRatio }.coerceIn(0f, 1f)
        val spacingScore = if (neighborStats.size >= 2) {
            spacingConsistency(neighborStats.map { it.centerDistance }, thickness)
        } else {
            0f
        }
        return ALIGNMENT_WEIGHT * alignmentScore +
            CONNECTION_WEIGHT * connectionScore +
            OVERLAP_WEIGHT * overlapScore +
            THICKNESS_WEIGHT * thicknessScore +
            SPACING_WEIGHT * spacingScore
    }

    private fun spacingConsistency(distances: List<Float>, thickness: Float): Float {
        val typical = median(distances).coerceAtLeast(1f)
        val maxDeviation = distances.maxOf { abs(it - typical) }
        val allowedDeviation = max(thickness * SPACING_TOLERANCE_THICKNESS, typical * SPACING_TOLERANCE_RATIO)
        return (1f - maxDeviation / allowedDeviation.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    private fun absorbContainedSingletons(groups: MutableList<LineGroup>) {
        var changed = true
        while (changed) {
            changed = false
            val blocks = groups.filter { it.lines.size >= 2 }
            val singletons = groups.filter { it.lines.size == 1 }
            for (singleton in singletons) {
                val line = singleton.lines.single()
                val owner = blocks
                    .filter { containsRect(it.bounds(), line) }
                    .minByOrNull { it.bounds().width() * it.bounds().height() }
                    ?: continue
                owner.lines.add(line)
                groups.remove(singleton)
                changed = true
                break
            }
        }
    }

    private fun deduplicateClampedLines(lines: List<RectF>): List<RectF> {
        val kept = ArrayList<RectF>()
        for (candidate in lines.sortedByDescending { it.width() * it.height() }) {
            val duplicateIndex = kept.indexOfFirst { existing ->
                intersectionOverSmaller(existing, candidate) >= DUPLICATE_OVERLAP_THRESHOLD
            }
            if (duplicateIndex < 0) {
                kept.add(RectF(candidate))
            } else {
                val existing = kept[duplicateIndex]
                if (candidate.width() * candidate.height() > existing.width() * existing.height()) {
                    kept[duplicateIndex] = RectF(candidate)
                }
            }
        }
        return kept
    }

    private fun orientationOf(rect: RectF): TextLineOrientation {
        val ratio = rect.width() / rect.height().coerceAtLeast(1f)
        return when {
            ratio >= ORIENTATION_ASPECT_RATIO -> TextLineOrientation.HORIZONTAL
            ratio <= 1f / ORIENTATION_ASPECT_RATIO -> TextLineOrientation.VERTICAL
            else -> TextLineOrientation.AMBIGUOUS
        }
    }

    /**
     * Builds a page-normalized polygon by connecting the original corners of
     * every detected text line. Horizontal blocks trace their left and right
     * edges; vertical blocks trace their top and bottom edges.
     */
    private fun buildMaskContour(
        lines: List<RectF>,
        orientation: TextLineOrientation,
        imageWidth: Int,
        imageHeight: Int
    ): FloatArray {
        val points = ArrayList<Float>(lines.size * 8)
        fun addPoint(x: Float, y: Float) {
            points.add((x / imageWidth).coerceIn(0f, 1f))
            points.add((y / imageHeight).coerceIn(0f, 1f))
        }

        if (orientation == TextLineOrientation.VERTICAL) {
            lines.forEach { line ->
                addPoint(line.left, line.top)
                addPoint(line.right, line.top)
            }
            lines.asReversed().forEach { line ->
                addPoint(line.right, line.bottom)
                addPoint(line.left, line.bottom)
            }
        } else {
            lines.forEach { line ->
                addPoint(line.left, line.top)
                addPoint(line.left, line.bottom)
            }
            lines.asReversed().forEach { line ->
                addPoint(line.right, line.bottom)
                addPoint(line.right, line.top)
            }
        }
        return points.toFloatArray()
    }

    private fun RectF.clampTo(imageWidth: Int, imageHeight: Int): RectF? {
        val clamped = RectF(
            left.coerceIn(0f, imageWidth.toFloat()),
            top.coerceIn(0f, imageHeight.toFloat()),
            right.coerceIn(0f, imageWidth.toFloat()),
            bottom.coerceIn(0f, imageHeight.toFloat())
        )
        return clamped.takeIf { it.width() >= MIN_LINE_SIZE && it.height() >= MIN_LINE_SIZE }
    }

    private fun RectF.primaryStart(orientation: TextLineOrientation): Float =
        if (orientation == TextLineOrientation.HORIZONTAL) top else left

    private fun RectF.primaryEnd(orientation: TextLineOrientation): Float =
        if (orientation == TextLineOrientation.HORIZONTAL) bottom else right

    private fun RectF.primaryCenter(orientation: TextLineOrientation): Float =
        (primaryStart(orientation) + primaryEnd(orientation)) / 2f

    private fun RectF.primaryThickness(orientation: TextLineOrientation): Float =
        if (orientation == TextLineOrientation.HORIZONTAL) height() else width()

    private fun RectF.crossStart(orientation: TextLineOrientation): Float =
        if (orientation == TextLineOrientation.HORIZONTAL) left else top

    private fun RectF.crossEnd(orientation: TextLineOrientation): Float =
        if (orientation == TextLineOrientation.HORIZONTAL) right else bottom

    private fun RectF.crossSize(orientation: TextLineOrientation): Float =
        crossEnd(orientation) - crossStart(orientation)

    private fun RectF.leadingEdge(orientation: TextLineOrientation): Float = crossStart(orientation)

    private fun RectF.trailingEdge(orientation: TextLineOrientation): Float = crossEnd(orientation)

    private fun intersectionOverSmaller(first: RectF, second: RectF): Float {
        val intersectionWidth = max(0f, min(first.right, second.right) - max(first.left, second.left))
        val intersectionHeight = max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
        val intersection = intersectionWidth * intersectionHeight
        val smaller = min(first.width() * first.height(), second.width() * second.height())
        return if (smaller <= 0f) 0f else intersection / smaller
    }

    private fun overlapLength(firstStart: Float, firstEnd: Float, secondStart: Float, secondEnd: Float): Float =
        max(0f, min(firstEnd, secondEnd) - max(firstStart, secondStart))

    private fun containsRect(container: RectF, candidate: RectF): Boolean =
        candidate.left >= container.left && candidate.top >= container.top &&
            candidate.right <= container.right && candidate.bottom <= container.bottom

    private fun spread(values: List<Float>): Float =
        (values.maxOrNull() ?: 0f) - (values.minOrNull() ?: 0f)

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    private data class LineGroup(
        val lines: MutableList<RectF>,
        val orientation: TextLineOrientation
    ) {
        fun bounds(): RectF {
            return RectF(
                lines.minOf { it.left },
                lines.minOf { it.top },
                lines.maxOf { it.right },
                lines.maxOf { it.bottom }
            )
        }

        fun sortedLines(): List<RectF> = lines
            .sortedBy { it.primaryCenter(orientation) }
            .map(::RectF)
    }

    private data class NeighborStats(
        val gapRatio: Float,
        val crossOverlapRatio: Float,
        val centerDistance: Float,
        val thicknessRatio: Float
    )

    private const val ORIENTATION_ASPECT_RATIO = 1.35f
    private const val MIN_LINE_SIZE = 2f
    private const val DUPLICATE_OVERLAP_THRESHOLD = 0.72f
    private const val MAX_EDGE_SPREAD_RATIO = 1.15f
    private const val MAX_GAP_RATIO = 1.8f
    private const val MIN_CROSS_OVERLAP_RATIO = 0.30f
    private const val SPACING_TOLERANCE_RATIO = 0.22f
    private const val SPACING_TOLERANCE_THICKNESS = 0.55f
    private const val ALIGNMENT_WEIGHT = 3.5f
    private const val CONNECTION_WEIGHT = 3f
    private const val OVERLAP_WEIGHT = 1.5f
    private const val THICKNESS_WEIGHT = 1f
    private const val SPACING_WEIGHT = 5f
    private const val MIN_MERGE_SCORE = 5.4f
}

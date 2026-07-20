package com.manga.translate.detection

import android.graphics.RectF

internal fun PageRegionDetectionResult.remapToSource(
    sourceWidth: Int,
    sourceHeight: Int
): PageRegionDetectionResult {
    if (width <= 0 || height <= 0) return this
    if (width == sourceWidth && height == sourceHeight) return this
    val scaleX = sourceWidth / width.toFloat()
    val scaleY = sourceHeight / height.toFloat()
    return PageRegionDetectionResult(
        width = sourceWidth,
        height = sourceHeight,
        bubbleDetections = bubbleDetections.map { detection ->
            detection.copy(rect = detection.rect.scaleBy(scaleX, scaleY))
        },
        textRects = textRects.map { rect -> rect.scaleBy(scaleX, scaleY) },
        regions = regions.map { region ->
            region.copy(rect = region.rect.scaleBy(scaleX, scaleY))
        },
        detectionMode = detectionMode
    )
}

internal fun RectF.scaleBy(scaleX: Float, scaleY: Float): RectF {
    return RectF(
        left * scaleX,
        top * scaleY,
        right * scaleX,
        bottom * scaleY
    )
}

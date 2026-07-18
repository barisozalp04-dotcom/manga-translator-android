package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.graphics.scale
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

data class BubbleDetection(
    val rect: RectF,
    val confidence: Float,
    val classId: Int,
    val maskContour: FloatArray? = null
)

data class UnifiedRegionDetection(
    val balloons: List<BubbleDetection>,
    val freeTextRects: List<RectF>
)

internal data class YoloClassScore(
    val classId: Int,
    val confidence: Float
)

/**
 * Comic speech-bubble YOLOv8m detector.
 * Classes: 0=text_bubble, 1=text_free.
 */
class BubbleDetector(
    private val context: Context,
    private val modelAssetName: String = DEFAULT_MODEL_ASSET,
    private val threadProfile: OnnxThreadProfile = OnnxThreadProfile.LIGHT,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    private val env = OnnxRuntimeSupport.environment()
    private val session: OrtSession = createSession()
    private val inputName: String
    private val inputShape: LongArray

    init {
        val input = session.inputInfo.entries.first()
        inputName = input.key
        inputShape = (input.value.info as TensorInfo).shape
    }

    @Synchronized
    fun detectRegions(bitmap: Bitmap): UnifiedRegionDetection {
        val inputHeight = inputShape.getOrNull(2)?.takeIf { it > 0 }?.toInt()
            ?: TranslationCoreDefaults.DefaultDetectionInputSize
        val inputWidth = inputShape.getOrNull(3)?.takeIf { it > 0 }?.toInt()
            ?: TranslationCoreDefaults.DefaultDetectionInputSize
        val resized = bitmap.scale(inputWidth, inputHeight)
        val inputBuffer = OnnxImagePreprocessor.bitmapToRgbChwFloat(resized)
        if (resized !== bitmap) {
            resized.recycle()
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputBuffer),
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        )
        tensor.use {
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[0] as OnnxTensor
                val outputShape = (output.info as TensorInfo).shape
                val rawDetections = parseDetections(output.value, outputShape)
                val configuredThreshold = settingsStore.loadBubbleConfThresholdPercent() / 100f

                if (settingsStore.loadModelIoLogging()) {
                    val maxByClass = rawDetections.groupBy { it.classId }
                        .mapValues { (_, detections) -> detections.maxOf { it.confidence } }
                    AppLogger.log(
                        "BubbleDetector",
                        "Raw detections=${rawDetections.size}, configured=$configuredThreshold, " +
                            "max text_bubble=${formatConfidence(maxByClass[CLASS_BALLOON])}, " +
                            "max text_free=${formatConfidence(maxByClass[CLASS_TEXT])}"
                    )
                }

                val filtered = filterByNms(
                    detections = rawDetections,
                    configuredThreshold = configuredThreshold,
                    iouThreshold = TranslationCoreDefaults.BubbleDetectorNmsIouThreshold,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    originalWidth = bitmap.width,
                    originalHeight = bitmap.height
                )
                val balloons = ArrayList<BubbleDetection>()
                for (raw in filtered) {
                    val rect = raw.toRect(
                        inputWidth = inputWidth,
                        inputHeight = inputHeight,
                        originalWidth = bitmap.width,
                        originalHeight = bitmap.height
                    )
                    if (rect.width() <= 1f || rect.height() <= 1f) continue
                    when (raw.classId) {
                        CLASS_BALLOON -> balloons.add(
                            BubbleDetection(
                                rect = rect,
                                confidence = raw.confidence,
                                classId = raw.classId
                            )
                        )

                        CLASS_TEXT -> Unit
                    }
                }
                if (settingsStore.loadModelIoLogging()) {
                    AppLogger.log(
                        "BubbleDetector",
                        "Input ${bitmap.width}x${bitmap.height} resized to ${inputWidth}x$inputHeight, " +
                            "text_bubble=${balloons.size}; text_free output ignored"
                    )
                }
                return UnifiedRegionDetection(
                    balloons = balloons,
                    freeTextRects = emptyList()
                )
            }
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap): List<BubbleDetection> = detectRegions(bitmap).balloons

    private fun parseDetections(raw: Any, shape: LongArray): List<RawDetection> {
        if (shape.size != 3) return emptyList()
        val batch = raw as? Array<*> ?: return emptyList()
        val first = batch.firstOrNull() as? Array<*> ?: return emptyList()
        val rows = first.mapNotNull { it as? FloatArray }
        if (rows.size != first.size) return emptyList()

        val dim1 = shape[1].toInt()
        val dim2 = shape[2].toInt()
        if (dim1 <= 0 || dim2 <= 0) return emptyList()
        val channelsFirst = dim1 <= dim2
        val featureCount = if (channelsFirst) dim1 else dim2
        val detectionCount = if (channelsFirst) dim2 else dim1
        if (featureCount < 5) return emptyList()

        val result = ArrayList<RawDetection>(detectionCount)
        if (channelsFirst) {
            if (rows.size < featureCount || rows.take(featureCount).any { it.size < detectionCount }) {
                return emptyList()
            }
            for (index in 0 until detectionCount) {
                val featureRow = FloatArray(featureCount) { channel -> rows[channel][index] }
                parseFeatureRow(featureRow)?.let(result::add)
            }
        } else {
            if (rows.size < detectionCount) return emptyList()
            for (index in 0 until detectionCount) {
                parseFeatureRow(rows[index])?.let(result::add)
            }
        }
        return result
    }

    private fun parseFeatureRow(row: FloatArray): RawDetection? {
        if (row.size < 5) return null
        val classScore = bestYoloClassScore(row) ?: return null
        val cx = row[0]
        val cy = row[1]
        val width = row[2]
        val height = row[3]
        if (
            !cx.isFinite() || !cy.isFinite() ||
            !width.isFinite() || !height.isFinite() ||
            width <= 0f || height <= 0f
        ) {
            return null
        }
        return RawDetection(
            cx = cx,
            cy = cy,
            width = width,
            height = height,
            confidence = classScore.confidence,
            classId = classScore.classId
        )
    }

    private fun filterByNms(
        detections: List<RawDetection>,
        configuredThreshold: Float,
        iouThreshold: Float,
        inputWidth: Int,
        inputHeight: Int,
        originalWidth: Int,
        originalHeight: Int
    ): List<RawDetection> {
        val filtered = detections.filter {
            it.classId == CLASS_BALLOON || it.classId == CLASS_TEXT
        }.filter {
            it.confidence >= effectiveDetectionConfidenceThreshold(
                classId = it.classId,
                configuredThreshold = configuredThreshold
            )
        }.sortedByDescending { it.confidence }
        val selected = ArrayList<RawDetection>()
        val suppressed = BooleanArray(filtered.size)

        for (index in filtered.indices) {
            if (suppressed[index]) continue
            val detection = filtered[index]
            val rect = detection.toRect(
                inputWidth,
                inputHeight,
                originalWidth,
                originalHeight
            )
            selected.add(detection)
            for (otherIndex in index + 1 until filtered.size) {
                if (suppressed[otherIndex]) continue
                val other = filtered[otherIndex]
                if (other.classId != detection.classId) continue
                val overlap = iou(
                    rect,
                    other.toRect(inputWidth, inputHeight, originalWidth, originalHeight)
                )
                if (overlap > iouThreshold) {
                    suppressed[otherIndex] = true
                }
            }
        }
        return selected
    }

    private fun iou(first: RectF, second: RectF): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val firstArea = max(0f, first.width()) * max(0f, first.height())
        val secondArea = max(0f, second.width()) * max(0f, second.height())
        val union = firstArea + secondArea - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun createSession(): OrtSession {
        return OnnxRuntimeSupport.getOrCreateSession(
            cacheDir = context.cacheDir,
            assetProvider = context.assets::open,
            assetName = modelAssetName,
            threadProfile = threadProfile,
            useXnnpack = false
        )
    }

    private fun formatConfidence(value: Float?): String {
        return value?.let { "%.3f".format(it) } ?: "n/a"
    }

    companion object {
        const val DEFAULT_MODEL_ASSET = "models/detection/comic-speech-bubble-detector.onnx"
        const val CLASS_BALLOON = 0
        const val CLASS_TEXT = 1
    }
}

internal fun bestYoloClassScore(
    featureRow: FloatArray,
    firstClassIndex: Int = 4
): YoloClassScore? {
    if (firstClassIndex !in featureRow.indices) return null
    var bestClassId = -1
    var bestConfidence = Float.NEGATIVE_INFINITY
    for (index in firstClassIndex until featureRow.size) {
        val confidence = featureRow[index]
        if (!confidence.isFinite()) continue
        if (confidence > bestConfidence) {
            bestConfidence = confidence
            bestClassId = index - firstClassIndex
        }
    }
    if (bestClassId < 0) return null
    return YoloClassScore(bestClassId, bestConfidence)
}

internal fun effectiveDetectionConfidenceThreshold(
    classId: Int,
    configuredThreshold: Float
): Float {
    val normalized = configuredThreshold.coerceIn(0f, 1f)
    return if (classId == BubbleDetector.CLASS_BALLOON) {
        max(normalized, TranslationCoreDefaults.MinBalloonConfidence)
    } else {
        normalized
    }
}

private data class RawDetection(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val classId: Int
) {
    fun toRect(
        inputWidth: Int,
        inputHeight: Int,
        originalWidth: Int,
        originalHeight: Int
    ): RectF {
        val normalized = width <= 1.5f && height <= 1.5f && cx <= 1.5f && cy <= 1.5f
        val inputCenterX = if (normalized) cx * inputWidth else cx
        val inputCenterY = if (normalized) cy * inputHeight else cy
        val inputBoxWidth = if (normalized) width * inputWidth else width
        val inputBoxHeight = if (normalized) height * inputHeight else height
        val scaleX = originalWidth / inputWidth.toFloat().coerceAtLeast(1f)
        val scaleY = originalHeight / inputHeight.toFloat().coerceAtLeast(1f)
        return RectF(
            ((inputCenterX - inputBoxWidth / 2f) * scaleX)
                .coerceIn(0f, max(0f, originalWidth - 1f)),
            ((inputCenterY - inputBoxHeight / 2f) * scaleY)
                .coerceIn(0f, max(0f, originalHeight - 1f)),
            ((inputCenterX + inputBoxWidth / 2f) * scaleX)
                .coerceIn(0f, max(0f, originalWidth - 1f)),
            ((inputCenterY + inputBoxHeight / 2f) * scaleY)
                .coerceIn(0f, max(0f, originalHeight - 1f))
        )
    }
}

package com.manga.translate.platform

import android.app.ActivityManager
import android.content.Context
import kotlin.math.sqrt

internal data class DeviceResourceSnapshot(
    val systemAvailableBytes: Long?,
    val appHeapHeadroomBytes: Long,
    val systemReportsLowMemory: Boolean,
    val cpuCoreCount: Int = DeviceResourcePolicy.readCpuCoreCount()
) {
    val displayAvailableBytes: Long
        get() = systemAvailableBytes ?: appHeapHeadroomBytes

    val effectiveAvailableBytes: Long
        get() = systemAvailableBytes
            ?.let { minOf(it, appHeapHeadroomBytes) }
            ?: appHeapHeadroomBytes
}

internal data class ResourceAssessment(
    val snapshot: DeviceResourceSnapshot,
    val estimatedPeakBytes: Long?,
    val shouldWarn: Boolean,
    val requestedConcurrency: Int? = null,
    val recommendedConcurrency: Int? = null
)

/** Pure CPU and remaining-memory calculations shared by resource-intensive operations. */
internal object DeviceResourcePolicy {
    private const val MIN_COMFORTABLE_AVAILABLE_BYTES = 128L * 1024L * 1024L
    private const val WARNING_BUDGET_NUMERATOR = 3L
    private const val WARNING_BUDGET_DENOMINATOR = 5L
    private const val MIN_IMPORT_AVAILABLE_BYTES = 64L * 1024L * 1024L
    private const val IMPORT_WARNING_BUDGET_NUMERATOR = 4L
    private const val IMPORT_WARNING_BUDGET_DENOMINATOR = 5L
    private const val BITMAP_BUDGET_NUMERATOR = 1L
    private const val BITMAP_BUDGET_DENOMINATOR = 2L
    private const val BYTES_PER_BITMAP_PIXEL = 4L
    const val OCR_INSTANCE_BYTES = 45L * 1024L * 1024L
    private const val EXPORT_RENDER_COPIES = 2L
    private const val EXPORT_OVERHEAD_BYTES = 16L * 1024L * 1024L

    fun readCpuCoreCount(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun readSnapshot(context: Context? = null): DeviceResourceSnapshot {
        val runtime = Runtime.getRuntime()
        val heapHeadroom = (
            runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        ).coerceAtLeast(0L)
        val memoryInfo = context?.let { sourceContext ->
            runCatching {
                val manager = sourceContext.applicationContext
                    .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.let {
                    ActivityManager.MemoryInfo().also(it::getMemoryInfo)
                }
            }.getOrNull()
        }
        return DeviceResourceSnapshot(
            systemAvailableBytes = memoryInfo?.availMem?.takeIf { it > 0L },
            appHeapHeadroomBytes = heapHeadroom,
            systemReportsLowMemory = memoryInfo?.lowMemory == true,
            cpuCoreCount = readCpuCoreCount()
        )
    }

    /** Keep at least one core, and roughly one quarter on larger CPUs, for Android and the UI. */
    fun usableWorkerCpuCores(cpuCoreCount: Int): Int {
        val cores = cpuCoreCount.coerceAtLeast(1)
        val reserved = maxOf(1, cores / 4)
        return (cores - reserved).coerceAtLeast(1)
    }

    fun assess(
        snapshot: DeviceResourceSnapshot,
        estimatedPeakBytes: Long?
    ): ResourceAssessment {
        val available = snapshot.effectiveAvailableBytes.coerceAtLeast(0L)
        val estimatedExceedsComfortableBudget = estimatedPeakBytes?.let { estimated ->
            estimated > comfortableMemoryBudget(snapshot)
        } ?: false
        return ResourceAssessment(
            snapshot = snapshot,
            estimatedPeakBytes = estimatedPeakBytes,
            shouldWarn = snapshot.systemReportsLowMemory ||
                available < MIN_COMFORTABLE_AVAILABLE_BYTES ||
                estimatedExceedsComfortableBudget
        )
    }

    fun assessImport(
        snapshot: DeviceResourceSnapshot,
        estimatedPeakBytes: Long?
    ): ResourceAssessment {
        val available = snapshot.effectiveAvailableBytes.coerceAtLeast(0L)
        val importBudget = safeMultiplyDivide(
            available,
            IMPORT_WARNING_BUDGET_NUMERATOR,
            IMPORT_WARNING_BUDGET_DENOMINATOR
        )
        return ResourceAssessment(
            snapshot = snapshot,
            estimatedPeakBytes = estimatedPeakBytes,
            shouldWarn = snapshot.systemReportsLowMemory ||
                available < MIN_IMPORT_AVAILABLE_BYTES ||
                (estimatedPeakBytes != null && estimatedPeakBytes > importBudget)
        )
    }

    fun assessConcurrency(
        snapshot: DeviceResourceSnapshot,
        perWorkerBytes: Long,
        requestedConcurrency: Int,
        hardCap: Int
    ): ResourceAssessment {
        val requested = requestedConcurrency.coerceAtLeast(1)
        val recommended = recommendConcurrency(snapshot, perWorkerBytes, hardCap)
        val base = assess(snapshot, estimateParallelPeakBytes(perWorkerBytes, requested))
        return base.copy(
            shouldWarn = base.shouldWarn || requested > recommended,
            requestedConcurrency = requested,
            recommendedConcurrency = recommended
        )
    }

    fun recommendConcurrency(
        snapshot: DeviceResourceSnapshot,
        perWorkerBytes: Long,
        hardCap: Int
    ): Int {
        val memoryBudget = comfortableMemoryBudget(snapshot)
        val byMemoryLong = if (perWorkerBytes > 0L) memoryBudget / perWorkerBytes else 1L
        val byMemory = byMemoryLong.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return minOf(
            usableWorkerCpuCores(snapshot.cpuCoreCount),
            hardCap.coerceAtLeast(1),
            byMemory
        ).coerceAtLeast(1)
    }

    /** Keep half of the current allocation headroom for the renderer and encoder. */
    fun safeBitmapPixelBudget(snapshot: DeviceResourceSnapshot): Long {
        val bytes = safeMultiplyDivide(
            snapshot.effectiveAvailableBytes.coerceAtLeast(0L),
            BITMAP_BUDGET_NUMERATOR,
            BITMAP_BUDGET_DENOMINATOR
        )
        return (bytes / BYTES_PER_BITMAP_PIXEL).coerceAtLeast(1L)
    }

    fun fitScaleToPixelBudget(
        width: Int,
        height: Int,
        preferredScale: Float,
        maxPixels: Long
    ): Float {
        require(width > 0 && height > 0)
        require(preferredScale > 0f)
        val sourcePixels = width.toDouble() * height.toDouble()
        val preferredPixels = sourcePixels * preferredScale.toDouble() * preferredScale.toDouble()
        if (preferredPixels <= maxPixels.coerceAtLeast(1L).toDouble()) return preferredScale
        val fitted = sqrt(maxPixels.coerceAtLeast(1L).toDouble() / sourcePixels)
        return minOf(preferredScale.toDouble(), fitted).toFloat().coerceAtLeast(Float.MIN_VALUE)
    }

    fun estimateBitmapBytes(width: Int, height: Int, scale: Float = 1f): Long {
        require(width > 0 && height > 0)
        require(scale > 0f)
        val estimated = width.toDouble() * height.toDouble() *
            scale.toDouble() * scale.toDouble() * BYTES_PER_BITMAP_PIXEL.toDouble()
        return estimated.coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
    }

    fun estimateParallelPeakBytes(perWorkerBytes: Long, workers: Int): Long {
        if (perWorkerBytes <= 0L || workers <= 0) return 0L
        return saturatingMultiply(perWorkerBytes, workers.toLong())
    }

    fun estimateExportWorkerBytes(width: Int, height: Int): Long {
        val bitmapBytes = estimateBitmapBytes(width, height)
        return saturatingAdd(
            saturatingMultiply(bitmapBytes, EXPORT_RENDER_COPIES),
            EXPORT_OVERHEAD_BYTES
        )
    }

    fun saturatingAdd(first: Long, second: Long): Long {
        val safeFirst = first.coerceAtLeast(0L)
        val safeSecond = second.coerceAtLeast(0L)
        return if (safeFirst > Long.MAX_VALUE - safeSecond) Long.MAX_VALUE else safeFirst + safeSecond
    }

    fun saturatingMultiply(first: Long, second: Long): Long {
        val safeFirst = first.coerceAtLeast(0L)
        val safeSecond = second.coerceAtLeast(0L)
        if (safeFirst == 0L || safeSecond == 0L) return 0L
        return if (safeFirst > Long.MAX_VALUE / safeSecond) Long.MAX_VALUE else safeFirst * safeSecond
    }

    private fun comfortableMemoryBudget(snapshot: DeviceResourceSnapshot): Long =
        safeMultiplyDivide(
            snapshot.effectiveAvailableBytes.coerceAtLeast(0L),
            WARNING_BUDGET_NUMERATOR,
            WARNING_BUDGET_DENOMINATOR
        )

    private fun safeMultiplyDivide(value: Long, numerator: Long, denominator: Long): Long {
        if (value <= 0L) return 0L
        return if (value <= Long.MAX_VALUE / numerator) {
            value * numerator / denominator
        } else {
            (value / denominator) * numerator
        }
    }
}

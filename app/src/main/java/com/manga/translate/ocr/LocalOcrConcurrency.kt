package com.manga.translate.ocr

import android.content.Context
import com.manga.translate.platform.DeviceResourcePolicy
import com.manga.translate.platform.DeviceResourceSnapshot

internal object LocalOcrConcurrency {
    private const val HARD_CAP = 8

    fun compute(snapshot: DeviceResourceSnapshot): Int =
        DeviceResourcePolicy.recommendConcurrency(
            snapshot = snapshot,
            perWorkerBytes = DeviceResourcePolicy.OCR_INSTANCE_BYTES,
            hardCap = HARD_CAP
        )

    fun compute(context: Context): Int =
        compute(DeviceResourcePolicy.readSnapshot(context))

    // 0 means auto; positive values override the computed result
    fun resolve(override: Int, context: Context): Int =
        if (override > 0) override.coerceAtMost(HARD_CAP) else compute(context)

    fun assess(context: Context, requestedConcurrency: Int) =
        DeviceResourcePolicy.assessConcurrency(
            snapshot = DeviceResourcePolicy.readSnapshot(context),
            perWorkerBytes = DeviceResourcePolicy.OCR_INSTANCE_BYTES,
            requestedConcurrency = requestedConcurrency,
            hardCap = HARD_CAP
        )
}

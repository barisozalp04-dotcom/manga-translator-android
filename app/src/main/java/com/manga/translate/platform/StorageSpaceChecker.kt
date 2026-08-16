package com.manga.translate.platform

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import java.io.File

/**
 * Checks whether a write can keep [reserveBytes] free on the target volume.
 * On Android 8.0+, StorageManager may reclaim cache space before the write.
 */
internal object StorageSpaceChecker {
    fun hasSpaceFor(
        context: Context,
        directory: File,
        requiredBytes: Long,
        reserveBytes: Long
    ): Boolean {
        if (requiredBytes < 0L || reserveBytes < 0L || requiredBytes > Long.MAX_VALUE - reserveBytes) {
            return false
        }
        val requiredWithReserve = requiredBytes + reserveBytes
        if (directory.usableSpace >= requiredWithReserve) {
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        val storageManager = context.getSystemService(StorageManager::class.java) ?: return false
        return try {
            val storageUuid = storageManager.getUuidForPath(directory)
            if (storageManager.getAllocatableBytes(storageUuid) < requiredWithReserve) {
                return false
            }
            storageManager.allocateBytes(storageUuid, requiredWithReserve)
            true
        } catch (error: Exception) {
            AppLogger.log("StorageSpaceChecker", "Unable to allocate storage for ${directory.path}", error)
            false
        }
    }
}

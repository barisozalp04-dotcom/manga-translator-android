package com.manga.translate.platform

import android.graphics.Bitmap
import android.util.Size
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import java.io.File
import java.io.FileOutputStream

object AvifBitmapDecoder {
    private val coder = HeifCoder()

    suspend fun decode(file: File): Bitmap? {
        val size = getSize(file) ?: return null
        return ImageProcessingGuards.withDecodePermit(
            width = size.width,
            height = size.height,
            tag = "AvifDecoder"
        ) {
            runCatching {
                val bytes = file.readBytes()
                coder.decode(bytes)
            }.getOrNull()
        }
    }

    fun getSize(file: File): Size? =
        runCatching {
            val bytes = file.readBytes()
            coder.getSize(bytes)
        }.getOrNull()

    suspend fun decodeSampled(file: File, targetWidth: Int, targetHeight: Int): Pair<Bitmap?, Size?> {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null to null
        val size = runCatching { coder.getSize(bytes) }.getOrNull()
        val guardWidth = size?.width ?: targetWidth
        val guardHeight = size?.height ?: targetHeight
        val bitmap = ImageProcessingGuards.withDecodePermit(
            width = guardWidth,
            height = guardHeight,
            tag = "AvifDecoder"
        ) {
            runCatching {
                coder.decodeSampled(
                    bytes,
                    targetWidth.coerceAtLeast(1),
                    targetHeight.coerceAtLeast(1)
                )
            }.getOrNull()
        }
        return bitmap to size
    }

    suspend fun convertToPng(
        source: File,
        destination: File,
        propagateOutOfMemory: Boolean = false,
        beforeDecode: suspend (Size) -> Unit = {}
    ): Boolean {
        val bytes = try {
            source.readBytes()
        } catch (error: OutOfMemoryError) {
            if (propagateOutOfMemory) throw error
            return false
        } catch (_: Exception) {
            return false
        }
        val size = try {
            coder.getSize(bytes)
        } catch (error: OutOfMemoryError) {
            if (propagateOutOfMemory) throw error
            return false
        } catch (_: Exception) {
            return false
        } ?: return false
        beforeDecode(size)
        val bitmap = ImageProcessingGuards.withDecodePermit(
            width = size.width,
            height = size.height,
            tag = "AvifDecoder"
        ) {
            try {
                coder.decode(bytes)
            } catch (error: OutOfMemoryError) {
                if (propagateOutOfMemory) throw error
                null
            } catch (_: Exception) {
                null
            }
        } ?: return false
        return try {
            FileOutputStream(destination).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } catch (_: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
    }
}

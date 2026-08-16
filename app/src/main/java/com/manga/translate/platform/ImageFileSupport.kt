package com.manga.translate.platform

import java.io.File
import java.util.Locale

object ImageFileSupport {
    private val BASE_SOURCE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private const val AVIF_EXTENSION = "avif"
    private const val PNG_EXTENSION = "png"

    fun isAvifFile(name: String): Boolean = extensionOf(name) == AVIF_EXTENSION

    fun isAvifMimeType(mimeType: String?): Boolean =
        mimeType.equals("image/avif", ignoreCase = true)

    fun isSupportedSourceImageFileName(name: String): Boolean {
        val extension = extensionOf(name) ?: return false
        return extension in BASE_SOURCE_EXTENSIONS || extension == AVIF_EXTENSION
    }

    fun isSupportedImportImageFileName(name: String): Boolean =
        isSupportedSourceImageFileName(name)

    fun isSupportedRenderedImageFileName(name: String): Boolean {
        return isSupportedSourceImageFileName(name) || extensionOf(name) == PNG_EXTENSION
    }

    fun resolveRenderedOutputName(sourceName: String): String {
        return if (extensionOf(sourceName) == AVIF_EXTENSION) {
            "${sourceName.substringBeforeLast('.', sourceName)}.$PNG_EXTENSION"
        } else {
            sourceName
        }
    }

    fun resolveImportedAvifOutputName(sourceName: String): String =
        "${sourceName.substringBeforeLast('.', sourceName)}.$PNG_EXTENSION"

    fun buildNameLookup(files: List<File>): Map<String, File> {
        return files.associateBy { normalizeName(it.name) }
    }

    fun findRenderedImageForSource(
        sourceName: String,
        renderedByName: Map<String, File>
    ): File? {
        val expectedName = resolveRenderedOutputName(sourceName)
        return renderedByName[normalizeName(expectedName)]
    }

    private fun extensionOf(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        return extension.takeIf { it.isNotEmpty() }
    }

    private fun normalizeName(name: String): String {
        return name.lowercase(Locale.US)
    }
}

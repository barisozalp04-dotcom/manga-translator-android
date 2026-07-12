package com.manga.translate

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal class LibraryPreferencesGateway(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val repository: LibraryRepository
) {
    fun isFullTranslateEnabled(folder: File): Boolean {
        return prefs.getBoolean(
            fullTranslateKeyPrefix + settingsFolder(folder).absolutePath,
            true
        )
    }

    fun isGlossaryProcessingEnabled(folder: File): Boolean {
        return prefs.getBoolean(
            glossaryProcessingKeyPrefix + settingsFolder(folder).absolutePath,
            true
        )
    }

    fun setFullTranslateEnabled(folder: File, enabled: Boolean) {
        prefs.edit() {
            putBoolean(fullTranslateKeyPrefix + settingsFolder(folder).absolutePath, enabled)
        }
    }

    fun setGlossaryProcessingEnabled(folder: File, enabled: Boolean) {
        prefs.edit() {
            putBoolean(glossaryProcessingKeyPrefix + settingsFolder(folder).absolutePath, enabled)
        }
    }

    fun getTranslationLanguage(folder: File): TranslationLanguage {
        val value = prefs.getString(languageKeyPrefix + settingsFolder(folder).absolutePath, null)
        return TranslationLanguage.fromPref(value)
    }

    fun isVlDirectTranslateEnabled(folder: File): Boolean {
        return prefs.getBoolean(
            vlDirectTranslateKeyPrefix + settingsFolder(folder).absolutePath,
            false
        )
    }

    fun getReadingMode(folder: File): FolderReadingMode {
        val value = prefs.getString(
            readingModeKeyPrefix + settingsFolder(folder).absolutePath,
            null
        )
        return FolderReadingMode.fromPref(value)
    }

    fun hasStoredReadingMode(folder: File): Boolean {
        return prefs.contains(readingModeKeyPrefix + settingsFolder(folder).absolutePath)
    }

    fun setTranslationLanguage(folder: File, language: TranslationLanguage) {
        prefs.edit() {
            putString(languageKeyPrefix + settingsFolder(folder).absolutePath, language.prefValue)
        }
    }

    fun setVlDirectTranslateEnabled(folder: File, enabled: Boolean) {
        prefs.edit() {
            putBoolean(vlDirectTranslateKeyPrefix + settingsFolder(folder).absolutePath, enabled)
        }
    }

    fun setReadingMode(folder: File, mode: FolderReadingMode) {
        prefs.edit() {
            putString(readingModeKeyPrefix + settingsFolder(folder).absolutePath, mode.prefValue)
        }
    }

    fun getFolderTags(folder: File): Set<String> {
        return prefs.getStringSet(folderTagsKeyPrefix + folder.absolutePath, emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun setFolderTags(folder: File, tags: Set<String>) {
        prefs.edit {
            val key = folderTagsKeyPrefix + folder.absolutePath
            if (tags.isEmpty()) {
                remove(key)
            } else {
                putStringSet(key, tags.toSet())
            }
        }
    }

    fun autoDetectAndSetReadingMode(folder: File, importedImages: List<File>): FolderReadingMode? {
        if (importedImages.isEmpty()) return null
        if (hasStoredReadingMode(folder)) return null
        val detectedMode = detectReadingMode(importedImages)
        setReadingMode(folder, detectedMode)
        AppLogger.log(
            "Library",
            "Auto-detected reading mode for ${settingsFolder(folder).name}: ${detectedMode.prefValue}"
        )
        return detectedMode
    }

    fun migrateFolderSettings(from: File, to: File) {
        val oldPath = settingsFolder(from).absolutePath
        val newPath = settingsFolder(to).absolutePath
        if (oldPath == newPath) return

        prefs.edit {
            settingsKeyPrefixes.forEach { prefix ->
                val oldKey = prefix + oldPath
                if (!prefs.contains(oldKey)) return@forEach
                val newKey = prefix + newPath
                when (val value = prefs.all[oldKey]) {
                    is Boolean -> putBoolean(newKey, value)
                    is String -> putString(newKey, value)
                    is Int -> putInt(newKey, value)
                    is Long -> putLong(newKey, value)
                    is Float -> putFloat(newKey, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(newKey, value as Set<String>)
                    }
                }
                remove(oldKey)
            }
            val oldTagsKey = folderTagsKeyPrefix + from.absolutePath
            val oldTags = prefs.getStringSet(oldTagsKey, null)
            if (oldTags != null) {
                putStringSet(folderTagsKeyPrefix + to.absolutePath, oldTags.toSet())
                remove(oldTagsKey)
            }
        }
    }

    fun clearFolderSettings(folder: File) {
        val resolved = settingsFolder(folder)
        if (resolved.absolutePath != folder.absolutePath) return
        prefs.edit {
            settingsKeyPrefixes.forEach { prefix ->
                remove(prefix + resolved.absolutePath)
            }
            remove(folderTagsKeyPrefix + folder.absolutePath)
        }
    }

    fun getLibrarySortField(): LibrarySortField {
        return LibrarySortField.fromPref(prefs.getString(librarySortFieldKey, null))
    }

    fun setLibrarySortField(field: LibrarySortField) {
        prefs.edit { putString(librarySortFieldKey, field.prefValue) }
    }

    fun isLibrarySortAscending(): Boolean {
        return prefs.getBoolean(librarySortAscendingKey, false)
    }

    fun setLibrarySortAscending(ascending: Boolean) {
        prefs.edit { putBoolean(librarySortAscendingKey, ascending) }
    }

    fun getImportTreeUri(): Uri? {
        return prefs.getString(importTreeKey, null)?.let(Uri::parse)
    }

    fun setImportTreeUri(uri: Uri) {
        prefs.edit() {putString(importTreeKey, uri.toString())}
    }

    fun getExportTreeUri(): Uri? {
        return prefs.getString(exportTreeKey, null)?.let(Uri::parse)
    }

    fun setExportTreeUri(uri: Uri) {
        prefs.edit() {putString(exportTreeKey, uri.toString())}
    }

    fun hasImportPermission(uri: Uri): Boolean {
        val persisted = context
            .contentResolver
            .persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
        val root = DocumentFile.fromTreeUri(context, uri)
        return persisted && root?.canRead() == true
    }

    fun hasExportPermission(uri: Uri): Boolean {
        val persisted = context
            .contentResolver
            .persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        val root = DocumentFile.fromTreeUri(context, uri)
        return persisted && root?.canWrite() == true
    }

    fun buildImportInitialUri(): Uri? {
        return getImportTreeUri()?.takeIf(::hasImportPermission)
    }

    fun buildExportInitialUri(): Uri? {
        return try {
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Documents/manga-translator"
            )
        } catch (_: Exception) {
            try {
                DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Documents"
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun settingsFolder(folder: File): File = repository.resolveSettingsFolder(folder)

    private fun detectReadingMode(images: List<File>): FolderReadingMode {
        val sampledImages = images.asSequence()
            .filter { it.exists() && it.isFile }
            .take(READING_MODE_SAMPLE_COUNT)
            .toList()
        if (sampledImages.isEmpty()) return FolderReadingMode.STANDARD

        val webtoonLikeCount = sampledImages.count(::isWebtoonLikeImage)
        val requiredMatches = if (sampledImages.size == 1) 1 else (sampledImages.size + 1) / 2
        return if (webtoonLikeCount >= requiredMatches) {
            FolderReadingMode.WEBTOON_SCROLL
        } else {
            FolderReadingMode.STANDARD
        }
    }

    private fun isWebtoonLikeImage(imageFile: File): Boolean {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return false
        return height > width && height.toFloat() / width.toFloat() >= WEBTOON_ASPECT_RATIO_THRESHOLD
    }

    private companion object {
        private const val importTreeKey = "ehviewer_tree_uri"
        private const val exportTreeKey = "export_tree_uri"
        private const val librarySortFieldKey = "library_sort_field"
        private const val librarySortAscendingKey = "library_sort_ascending"
        private const val fullTranslateKeyPrefix = "full_translate_enabled_"
        private const val glossaryProcessingKeyPrefix = "glossary_processing_enabled_"
        private const val languageKeyPrefix = "translation_language_"
        private const val vlDirectTranslateKeyPrefix = "vl_direct_translate_enabled_"
        private const val readingModeKeyPrefix = "reading_mode_"
        private const val folderTagsKeyPrefix = "folder_tags_"
        private val settingsKeyPrefixes = listOf(
            fullTranslateKeyPrefix,
            glossaryProcessingKeyPrefix,
            languageKeyPrefix,
            vlDirectTranslateKeyPrefix,
            readingModeKeyPrefix
        )
        private const val READING_MODE_SAMPLE_COUNT = 6
        private const val WEBTOON_ASPECT_RATIO_THRESHOLD = 2.4f
    }
}

enum class LibrarySortField(val prefValue: String) {
    NAME("name"),
    TIME("time");

    companion object {
        fun fromPref(value: String?): LibrarySortField {
            return entries.firstOrNull { it.prefValue == value } ?: TIME
        }
    }
}

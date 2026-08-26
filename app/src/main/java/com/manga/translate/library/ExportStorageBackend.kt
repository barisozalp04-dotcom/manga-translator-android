package com.manga.translate.library

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.ImageFileSupport
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Export storage layer: writes rendered/translated images to the three
 * supported destinations (SAF DocumentFile tree, MediaStore on Q+, and legacy
 * external storage), including the temporary-file/rename transaction pattern,
 * ".nomedia" bookkeeping and destination name resolution.
 *
 * Extracted verbatim from [LibraryExporter]; callers always passed the
 * application context, so the backend holds it internally.
 */
internal class ExportStorageBackend(context: Context) {
    private val appContext = context.applicationContext

    fun resolveExportDirectory(
        treeUri: Uri,
        folderName: String
    ): DocumentFile? {
        val root = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
        if (!root.canWrite()) {
            return null
        }
        val existing = root.findFile(folderName)
        return when {
            existing == null -> root.createDirectory(folderName)
            existing.isDirectory -> existing
            else -> null
        }
    }

    fun buildExportRootPathHint(treeUri: Uri): String {
        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            null
        }
        val base = docId?.let { id ->
            if (id.startsWith("primary:")) {
                "/storage/emulated/0/${id.removePrefix("primary:")}"
            } else {
                id
            }
        } ?: "所选目录"
        return base
    }

    fun ensureNoMediaFile(folderName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val relativePath = "Documents/manga-translate/$folderName/"
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            val selectionArgs = arrayOf(relativePath, ".nomedia")
            val exists = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                selectionArgs,
                null
            )?.use { it.moveToFirst() } == true
            if (exists) {
                return
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ".nomedia")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return
            try {
                resolver.openOutputStream(uri)?.use { }
            } catch (e: Exception) {
                AppLogger.log("Library", "Create .nomedia failed: $relativePath", e)
                resolver.delete(uri, null, null)
                return
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val exportDir = File(root, "manga-translate/$folderName")
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                return
            }
            val noMedia = File(exportDir, ".nomedia")
            if (!noMedia.exists()) {
                try {
                    noMedia.createNewFile()
                } catch (e: Exception) {
                    AppLogger.log("Library", "Create .nomedia failed: ${noMedia.absolutePath}", e)
                }
            }
        }
    }

    fun ensureNoMediaFile(exportDir: DocumentFile) {
        if (exportDir.findFile(".nomedia") != null) return
        runCatching {
            exportDir.createFile("application/octet-stream", ".nomedia")
        }.onFailure { e ->
            AppLogger.log("Library", "Create .nomedia failed: ${exportDir.uri}", e)
        }
    }

    fun resolveExportSpec(fileName: String): ExportSpec {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val format = when (ext) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            "avif" -> Bitmap.CompressFormat.PNG
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.JPEG
        }
        val mimeType = when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "avif" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "image/jpeg"
        }
        val displayName = when (ext) {
            "png", "webp", "jpg", "jpeg" -> fileName
            "avif" -> ImageFileSupport.resolveRenderedOutputName(fileName)
            else -> {
                val baseName = fileName.substringBeforeLast('.', fileName)
                "$baseName.jpg"
            }
        }
        val quality = 100
        return ExportSpec(displayName, mimeType, format, quality)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveBitmapToMediaStore(
        bitmap: Bitmap,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        return writeMediaStoreImage(spec, folderName) { output ->
            bitmap.compress(spec.format, spec.quality, output)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun copyFileToMediaStore(
        source: File,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        return writeMediaStoreImage(spec, folderName) { output ->
            copySourceToStream(source, output)
        }
    }

    suspend fun saveBitmapToDocumentFile(
        bitmap: Bitmap,
        spec: ExportSpec,
        exportDir: DocumentFile
    ): Boolean {
        return writeDocumentFileTransaction(
            exportDir = exportDir,
            finalName = spec.displayName,
            mimeType = spec.mimeType,
            replaceExisting = true
        ) { output ->
            bitmap.compress(spec.format, spec.quality, output)
        }
    }

    suspend fun copyFileToDocumentFile(
        source: File,
        spec: ExportSpec,
        exportDir: DocumentFile
    ): Boolean {
        return writeDocumentFileTransaction(
            exportDir = exportDir,
            finalName = spec.displayName,
            mimeType = spec.mimeType,
            replaceExisting = true
        ) { output ->
            copySourceToStream(source, output)
        }
    }

    suspend fun writeDocumentFileTransaction(
        exportDir: DocumentFile,
        finalName: String,
        mimeType: String,
        replaceExisting: Boolean,
        writer: (OutputStream) -> Boolean
    ): Boolean {
        val temp = exportDir.createFile(mimeType, buildExportTempName(finalName)) ?: return false
        var published = false
        var replacedBackup: DocumentFile? = null
        try {
            val success = withContext(Dispatchers.IO) {
                appContext.contentResolver.openOutputStream(temp.uri, "wt")?.use { output ->
                    writer(output)
                } ?: false
            }
            if (!success) return false

            val existing = exportDir.findFile(finalName)
            if (existing != null) {
                if (!replaceExisting || !existing.isFile) return false
                val backupName = buildExportBackupName(finalName)
                if (!existing.renameTo(backupName)) return false
                val backup = exportDir.findFile(backupName) ?: existing
                replacedBackup = backup
                if (!temp.renameTo(finalName)) return false
                published = true
                if (!backup.delete()) {
                    AppLogger.log("Library", "Delete replaced export failed: $finalName")
                }
                return true
            }

            if (!temp.renameTo(finalName)) return false
            published = true
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Document export write failed: $finalName", e)
            return false
        } finally {
            if (!published) {
                runCatching { temp.delete() }
                replacedBackup?.let { backup ->
                    if (!backup.renameTo(finalName)) {
                        AppLogger.log("Library", "Restore replaced export failed: $finalName")
                    }
                }
            }
        }
    }

    fun resolveUniqueCbzName(root: DocumentFile, folderName: String): String {
        var index = 0
        while (true) {
            val fileName = if (index == 0) "$folderName.cbz" else "${folderName}_$index.cbz"
            val existing = root.findFile(fileName)
            if (existing == null) {
                return fileName
            }
            index += 1
        }
    }

    fun resolveUniquePdfName(root: DocumentFile, folderName: String): String {
        var index = 0
        while (true) {
            val fileName = if (index == 0) "$folderName.pdf" else "${folderName}_$index.pdf"
            val existing = root.findFile(fileName)
            if (existing == null) {
                return fileName
            }
            index += 1
        }
    }

    suspend fun writeLegacyExportTransaction(
        exportDir: File,
        finalName: String,
        writer: (OutputStream) -> Boolean
    ): File? {
        val temp = try {
            File.createTempFile(".manga_translate_export_", ".tmp", exportDir)
        } catch (e: Exception) {
            AppLogger.log("Library", "Create export temp file failed: $finalName", e)
            return null
        }
        var published = false
        try {
            val success = withContext(Dispatchers.IO) {
                FileOutputStream(temp).use { output ->
                    writer(output)
                }
            }
            if (!success) return null

            val target = resolveUniqueFile(exportDir, finalName)
            if (!temp.renameTo(target)) {
                AppLogger.log("Library", "Publish export failed: ${target.absolutePath}")
                return null
            }
            published = true
            return target
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export write failed: $finalName", e)
            return null
        } finally {
            if (!published) {
                runCatching { temp.delete() }
            }
        }
    }

    suspend fun saveBitmapToLegacyStorage(
        bitmap: Bitmap,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        val exportDir = resolveLegacyExportDirectory(folderName) ?: return false
        return writeLegacyExportTransaction(exportDir, spec.displayName) { output ->
            bitmap.compress(spec.format, spec.quality, output)
        } != null
    }

    suspend fun copyFileToLegacyStorage(
        source: File,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        val exportDir = resolveLegacyExportDirectory(folderName) ?: return false
        return writeLegacyExportTransaction(exportDir, spec.displayName) { output ->
            copySourceToStream(source, output)
        } != null
    }

    private fun resolveLegacyExportDirectory(folderName: String): File? {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val exportDir = File(root, "manga-translate/$folderName")
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            AppLogger.log("Library", "Export directory create failed: ${exportDir.absolutePath}")
            return null
        }
        return exportDir
    }

    private fun buildExportTempName(finalName: String): String {
        return ".manga_translate_tmp_${UUID.randomUUID()}_$finalName"
    }

    private fun buildExportBackupName(finalName: String): String {
        return ".manga_translate_backup_${UUID.randomUUID()}_$finalName"
    }

    private fun copySourceToStream(source: File, output: OutputStream): Boolean {
        return try {
            FileInputStream(source).use { input ->
                input.copyTo(output)
            }
            true
        } catch (e: Exception) {
            AppLogger.log("Library", "Export copy failed: ${source.name}", e)
            false
        }
    }

    private fun resolveUniqueFile(folder: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var candidate = File(folder, fileName)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(folder, "${base}_$index$suffix")
            index += 1
        }
        return candidate
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMediaStoreTempUri(
        spec: ExportSpec,
        folderName: String
    ): Uri? {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val relativePathWithSlash = "Documents/manga-translate/$folderName/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, buildExportTempName(spec.displayName))
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathWithSlash)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findMediaStoreFile(
        spec: ExportSpec,
        folderName: String
    ): Uri? {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val relativePathWithSlash = "Documents/manga-translate/$folderName/"
        val relativePathNoSlash = "Documents/manga-translate/$folderName"
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val selectionArgs = arrayOf(relativePathWithSlash, relativePathNoSlash, spec.displayName)
        return resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeMediaStoreImage(
        spec: ExportSpec,
        folderName: String,
        writer: (OutputStream) -> Boolean
    ): Boolean {
        val resolver = appContext.contentResolver
        val tempUri = createMediaStoreTempUri(spec, folderName) ?: return false
        var published = false
        var replacedUri: Uri? = null
        val backupName = buildExportBackupName(spec.displayName)
        try {
            val success = resolver.openOutputStream(tempUri, "wt")?.use { output ->
                writer(output)
            } ?: false
            if (!success) return false

            val existingUri = findMediaStoreFile(spec, folderName)
            if (existingUri != null) {
                val backupValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, backupName)
                }
                if (resolver.update(existingUri, backupValues, null, null) <= 0) {
                    return false
                }
                replacedUri = existingUri
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, spec.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(tempUri, publishValues, null, null) <= 0) {
                return false
            }
            published = true
            if (replacedUri != null) {
                runCatching { resolver.delete(replacedUri, null, null) }
                    .onFailure { e ->
                        AppLogger.log("Library", "Delete replaced export failed: ${spec.displayName}", e)
                    }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export write failed: ${spec.displayName}", e)
            return false
        } finally {
            if (!published) {
                runCatching { resolver.delete(tempUri, null, null) }
                replacedUri?.let { uri ->
                    restoreMediaStoreFileName(resolver, uri, spec.displayName, backupName)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun restoreMediaStoreFileName(
        resolver: android.content.ContentResolver,
        uri: Uri,
        finalName: String,
        backupName: String
    ) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
        }
        if (resolver.update(uri, values, null, null) <= 0) {
            AppLogger.log("Library", "Restore replaced export failed: $backupName")
        }
    }
}

internal data class ExportSpec(
    val displayName: String,
    val mimeType: String,
    val format: Bitmap.CompressFormat,
    val quality: Int
)

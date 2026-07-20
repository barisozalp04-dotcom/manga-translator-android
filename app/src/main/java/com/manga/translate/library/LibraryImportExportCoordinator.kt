package com.manga.translate.library

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.manga.translate.R
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.TranslationStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class LibraryImportExportCoordinator(
    context: Context,
    private val repository: LibraryRepository,
    private val translationStore: TranslationStore,
    private val settingsStore: SettingsStore,
    prefs: SharedPreferences,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val dialogs: LibraryDialogs,
    private val ui: LibraryUiCallbacks
) {
    private val appContext = context.applicationContext
    private val prefsRef = prefs
    private val exporter = LibraryExporter(
        context, repository, translationStore, settingsStore,
        prefs, preferencesGateway, dialogs, ui
    )

    fun isPendingExportCollection(): Boolean = exporter.pendingExportIsCollection

    fun getExportThreadCount(): Int = exporter.getExportThreads()
    fun getExportFormatDefault(): ExportFormat = exporter.getExportFormat()
    fun buildExportRootPathPreview(): String = exporter.buildExportRootPreview()

    fun requestImportDirectory(
        requestImportPermission: (Uri?) -> Unit
    ) {
        requestImportPermission(preferencesGateway.buildImportInitialUri())
    }

    fun importFromArchiveOrPdf(
        uiContext: Context,
        uri: Uri,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val displayName = runCatching {
                uiContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            }.getOrNull().orEmpty()
            val isPdf = displayName.substringAfterLast('.', "").lowercase() == "pdf"
            val result = if (isPdf) repository.importPdf(uri) else repository.importCbz(uri)
            result?.folder?.let { importedFolder ->
                if (result.importedCount > 0) {
                    preferencesGateway.autoDetectAndSetReadingMode(
                        importedFolder,
                        repository.listImages(importedFolder)
                    )
                }
            }
            withContext(Dispatchers.Main) {
                when {
                    result == null -> ui.showToast(
                        if (isPdf) R.string.pdf_import_failed else R.string.cbz_import_failed
                    )
                    result.importedCount <= 0 -> ui.showToast(
                        if (isPdf) R.string.pdf_import_no_images else R.string.cbz_import_no_images
                    )
                    else -> ui.showToastMessage(
                        uiContext.getString(
                            if (isPdf) R.string.pdf_import_done else R.string.cbz_import_done,
                            result.importedCount
                        )
                    )
                }
                ui.refreshFolders()
                onShowFolderList()
            }
        }
    }

    fun handleImportTreeSelection(
        uiContext: Context,
        uri: Uri,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            uiContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            AppLogger.log("Library", "Persist import permission failed", e)
        }
        preferencesGateway.setImportTreeUri(uri)
        showImportFolderPicker(uiContext, uri, scope, onShowFolderList)
    }

    fun handleChapterImportTreeSelection(
        uiContext: Context,
        parentFolder: File,
        uri: Uri,
        scope: CoroutineScope
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            uiContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            AppLogger.log("Library", "Persist chapter import permission failed", e)
        }
        preferencesGateway.setImportTreeUri(uri)
        showChapterImportFolderPicker(uiContext, parentFolder, uri, scope)
    }

    private fun showImportFolderPicker(
        uiContext: Context,
        treeUri: Uri,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        val root = DocumentFile.fromTreeUri(uiContext, treeUri)
        if (root == null || !root.canRead()) {
            ui.showToast(R.string.import_permission_required)
            return
        }
        val files = root.listFiles()
        val rootHasImages = files.any { it.isFile && isImageDocument(it) }
        val folders = files.filter { file ->
            file.isDirectory && file.listFiles().any { child -> child.isFile && isImageDocument(child) }
        }
        if (folders.isNotEmpty()) {
            val defaultName = root.name ?: ""
            dialogs.showEhViewerImportNameDialog(uiContext, defaultName) { importName ->
                importEhViewerCollection(uiContext, folders, importName, scope, onShowFolderList)
            }
            return
        }
        if (rootHasImages) {
            val defaultName = root.name ?: ""
            dialogs.showEhViewerImportNameDialog(uiContext, defaultName) { importName ->
                importEhViewerFolder(uiContext, root, importName, scope, onShowFolderList)
            }
            return
        }
        if (folders.isEmpty()) {
            ui.showToast(R.string.import_no_folders)
            return
        }
        dialogs.showEhViewerSubfolderPicker(uiContext, folders) { folder ->
            val defaultName = folder.name ?: ""
            dialogs.showEhViewerImportNameDialog(uiContext, defaultName) { importName ->
                importEhViewerFolder(uiContext, folder, importName, scope, onShowFolderList)
            }
        }
    }

    private fun importEhViewerFolder(
        uiContext: Context,
        source: DocumentFile,
        importName: String,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        val folder = repository.createFolder(importName)
        if (folder == null) {
            ui.showToast(R.string.import_folder_exists)
            return
        }
        val images = source.listFiles().filter { it.isFile && isImageDocument(it) }
        if (images.isEmpty()) {
            folder.deleteRecursively()
            ui.showToast(R.string.import_no_images)
            return
        }
        scope.launch(Dispatchers.IO) {
            val added = repository.addImages(folder, images.map { it.uri })
            if (added.isNotEmpty()) {
                preferencesGateway.autoDetectAndSetReadingMode(folder, added)
            }
            withContext(Dispatchers.Main) {
                if (added.isEmpty()) {
                    folder.deleteRecursively()
                    ui.showToast(R.string.import_failed)
                } else {
                    ui.showToastMessage(uiContext.getString(R.string.import_done, added.size))
                }
                ui.refreshFolders()
                onShowFolderList()
            }
        }
    }

    private fun importEhViewerCollection(
        uiContext: Context,
        sources: List<DocumentFile>,
        importName: String,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        val collection = repository.createCollection(importName)
        if (collection == null) {
            ui.showToast(R.string.import_folder_exists)
            return
        }
        scope.launch(Dispatchers.IO) {
            var importedChapters = 0
            var importedImages = 0
            var skippedChapters = 0
            val detectionSamples = ArrayList<File>()

            for (source in sources) {
                val sourceName = source.name?.trim().orEmpty()
                if (sourceName.isEmpty()) {
                    skippedChapters += 1
                    continue
                }
                val chapterFolder = repository.createChildFolder(collection, sourceName)
                if (chapterFolder == null) {
                    skippedChapters += 1
                    continue
                }
                val images = source.listFiles().filter { it.isFile && isImageDocument(it) }
                if (images.isEmpty()) {
                    chapterFolder.deleteRecursively()
                    skippedChapters += 1
                    continue
                }
                val added = repository.addImages(chapterFolder, images.map { it.uri })
                if (added.isEmpty()) {
                    chapterFolder.deleteRecursively()
                    skippedChapters += 1
                    continue
                }
                appendDetectionSamples(detectionSamples, added)
                importedChapters += 1
                importedImages += added.size
            }

            if (importedChapters > 0) {
                preferencesGateway.autoDetectAndSetReadingMode(collection, detectionSamples)
            }

            withContext(Dispatchers.Main) {
                when {
                    importedChapters <= 0 -> {
                        collection.deleteRecursively()
                        ui.showToast(R.string.import_failed)
                    }
                    skippedChapters > 0 -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done_with_skipped,
                            importedChapters,
                            importedImages,
                            skippedChapters
                        )
                    )
                    else -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done,
                            importedChapters,
                            importedImages
                        )
                    )
                }
                ui.refreshFolders()
                onShowFolderList()
            }
        }
    }

    private fun showChapterImportFolderPicker(
        uiContext: Context,
        parentFolder: File,
        treeUri: Uri,
        scope: CoroutineScope
    ) {
        val root = DocumentFile.fromTreeUri(uiContext, treeUri)
        if (root == null || !root.canRead()) {
            ui.showToast(R.string.import_permission_required)
            return
        }
        val folders = root.listFiles().filter { folder ->
            folder.isDirectory && folder.listFiles().any { child -> child.isFile && isImageDocument(child) }
        }
        if (folders.isNotEmpty()) {
            dialogs.showDocumentFolderMultiPicker(
                context = uiContext,
                titleRes = R.string.chapter_import_select_folders,
                folders = folders
            ) { selected ->
                if (selected.isEmpty()) {
                    ui.showToast(R.string.chapter_import_no_folders)
                    return@showDocumentFolderMultiPicker
                }
                importChildChapters(uiContext, parentFolder, selected, scope)
            }
            return
        }
        val rootImages = root.listFiles().filter { it.isFile && isImageDocument(it) }
        if (rootImages.isEmpty()) {
            ui.showToast(R.string.chapter_import_no_folders)
            return
        }
        importChildChapters(uiContext, parentFolder, listOf(root), scope)
    }

    private fun importChildChapters(
        uiContext: Context,
        parentFolder: File,
        sources: List<DocumentFile>,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            val collectionWasEmpty = !collectionHasAnyImages(parentFolder)
            var importedChapters = 0
            var importedImages = 0
            var skippedChapters = 0
            val detectionSamples = ArrayList<File>()

            for (source in sources) {
                val sourceName = source.name?.trim().orEmpty()
                if (sourceName.isEmpty()) {
                    skippedChapters += 1
                    continue
                }
                val chapterFolder = repository.createChildFolder(parentFolder, sourceName)
                if (chapterFolder == null) {
                    skippedChapters += 1
                    continue
                }
                val images = source.listFiles().filter { it.isFile && isImageDocument(it) }
                if (images.isEmpty()) {
                    chapterFolder.deleteRecursively()
                    skippedChapters += 1
                    continue
                }
                val added = repository.addImages(chapterFolder, images.map { it.uri })
                if (added.isEmpty()) {
                    chapterFolder.deleteRecursively()
                    skippedChapters += 1
                    continue
                }
                appendDetectionSamples(detectionSamples, added)
                importedChapters += 1
                importedImages += added.size
            }

            if (collectionWasEmpty && importedChapters > 0) {
                preferencesGateway.autoDetectAndSetReadingMode(parentFolder, detectionSamples)
            }

            withContext(Dispatchers.Main) {
                when {
                    importedChapters <= 0 -> ui.showToast(R.string.import_failed)
                    skippedChapters > 0 -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done_with_skipped,
                            importedChapters,
                            importedImages,
                            skippedChapters
                        )
                    )
                    else -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done,
                            importedChapters,
                            importedImages
                        )
                    )
                }
                ui.refreshImages(parentFolder)
                ui.refreshFolders()
            }
        }
    }

    private fun collectionHasAnyImages(collectionFolder: File): Boolean {
        return repository.listChildFolders(collectionFolder).any { chapter ->
            repository.listImages(chapter).isNotEmpty()
        }
    }

    private fun appendDetectionSamples(target: MutableList<File>, added: List<File>) {
        if (target.size >= READING_MODE_SAMPLE_LIMIT) return
        val remaining = READING_MODE_SAMPLE_LIMIT - target.size
        target.addAll(added.take(remaining))
    }


    fun handleStoragePermissionResult(granted: Boolean, onGranted: () -> Unit) =
        exporter.handleStoragePermissionResult(granted, onGranted)

    fun handleExportTreeSelection(uri: Uri, onReady: () -> Unit) =
        exporter.handleExportTreeSelection(uri, onReady)

    fun handleExportTreeCanceled() = exporter.handleExportTreeCanceled()

    fun exportFolder(
        uiContext: Context, folder: File?, images: List<File>,
        exportThreads: Int, exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit, onSetExportEnabled: (Boolean) -> Unit
    ) = exporter.exportFolder(
        uiContext, folder, images, exportThreads, exportFormat,
        requestExportDirectoryPermission, requestLegacyPermission,
        onExitSelectionMode, onSetExportEnabled
    )

    fun exportFolderAfterPermission(
        uiContext: Context, folder: File?, images: List<File>,
        onExitSelectionMode: () -> Unit, onSetExportEnabled: (Boolean) -> Unit
    ) = exporter.exportFolderAfterPermission(
        uiContext, folder, images, onExitSelectionMode, onSetExportEnabled
    )

    fun exportCollection(
        uiContext: Context, collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        exportThreads: Int, exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit, onSetExportEnabled: (Boolean) -> Unit
    ) = exporter.exportCollection(
        uiContext, collectionFolder, chapterImages, exportThreads, exportFormat,
        requestExportDirectoryPermission, requestLegacyPermission,
        onExitSelectionMode, onSetExportEnabled
    )

    private companion object {
        private const val READING_MODE_SAMPLE_LIMIT = 6
    }

    fun exportCollectionAfterPermission(
        uiContext: Context, collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        onExitSelectionMode: () -> Unit, onSetExportEnabled: (Boolean) -> Unit
    ) = exporter.exportCollectionAfterPermission(
        uiContext, collectionFolder, chapterImages, onExitSelectionMode, onSetExportEnabled
    )

    enum class ExportFormat {
        IMAGE_DIR,
        CBZ,
        PDF
    }

    private fun isImageDocument(file: DocumentFile): Boolean {
        val name = file.name ?: return false
        val lowerName = name.lowercase()
        return lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") ||
            lowerName.endsWith(".bmp") || lowerName.endsWith(".gif")
    }
}

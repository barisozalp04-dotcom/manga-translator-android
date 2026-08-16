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
import com.manga.translate.background.TranslationKeepAliveService
import com.manga.translate.model.TranslationResult
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.DeviceResourcePolicy
import com.manga.translate.platform.ImageFileSupport
import com.manga.translate.platform.PdfImageCodec
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.platform.ResourceAssessment
import com.manga.translate.rendering.BubbleRenderer
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.TranslationStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private typealias ExportFormat = LibraryImportExportCoordinator.ExportFormat

internal class LibraryExporter(
    context: Context,
    private val repository: LibraryRepository,
    private val translationStore: TranslationStore,
    private val settingsStore: SettingsStore,
    prefs: SharedPreferences,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val ui: LibraryUiCallbacks,
    private val exportTaskHost: ExportTaskHost
) {
    private val appContext = context.applicationContext
    private val prefsRef = prefs
    private var pendingExportAfterPermission = false
    private var pendingExportAfterExportTreeSelection = false
    var pendingExportIsCollection = false
        private set
    private var pendingExportThreads = loadExportThreads()
    private var pendingExportFormat: ExportFormat = loadExportFormatDefault()

    fun getExportFormat(): ExportFormat = loadExportFormatDefault()
    fun isExportActiveFor(folder: File): Boolean = exportTaskHost.isExportActiveFor(folder)
    fun buildExportRootPreview(): String {
        val treeUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preferencesGateway.getExportTreeUri()?.takeIf { preferencesGateway.hasExportPermission(it) }
        } else {
            null
        }
        return treeUri?.let(::buildExportRootPathHint) ?: "/Documents/manga-translate"
    }

    fun assessExportResources(images: List<File>, requestedThreads: Int): ResourceAssessment {
        val perWorkerBytes = images.asSequence()
            .mapNotNull(PipelineBitmapDecoder::readImageSize)
            .map { size -> DeviceResourcePolicy.estimateExportWorkerBytes(size.width, size.height) }
            .maxOrNull()
            ?: DEFAULT_EXPORT_WORKER_BYTES
        return DeviceResourcePolicy.assessConcurrency(
            snapshot = DeviceResourcePolicy.readSnapshot(appContext),
            perWorkerBytes = perWorkerBytes,
            requestedConcurrency = normalizeExportThreads(requestedThreads),
            hardCap = MAX_EXPORT_THREADS
        )
    }

    fun suggestExportThreads(images: List<File>): Int {
        val saved = loadExportThreads()
        val recommended = assessExportResources(images, saved).recommendedConcurrency ?: saved
        return minOf(saved, recommended).coerceIn(MIN_EXPORT_THREADS, MAX_EXPORT_THREADS)
    }

    fun handleStoragePermissionResult(
        granted: Boolean,
        onGranted: () -> Unit
    ) {
        if (pendingExportAfterPermission && granted) {
            pendingExportAfterPermission = false
            onGranted()
            return
        }
        pendingExportAfterPermission = false
        pendingExportIsCollection = false
        if (!granted) {
            ui.showToast(R.string.export_permission_denied)
            ui.setFolderStatus(appContext.getString(R.string.export_permission_denied))
        }
    }

    fun handleExportTreeSelection(uri: Uri, onReady: () -> Unit) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            AppLogger.log("Library", "Persist export permission failed", e)
        }
        preferencesGateway.setExportTreeUri(uri)
        if (pendingExportAfterExportTreeSelection) {
            pendingExportAfterExportTreeSelection = false
            onReady()
        }
    }

    fun handleExportTreeCanceled() {
        pendingExportAfterExportTreeSelection = false
        pendingExportIsCollection = false
    }

    fun exportFolder(
        uiContext: Context,
        folder: File?,
        images: List<File>,
        exportThreads: Int,
        exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        if (folder == null) return
        pendingExportIsCollection = false
        pendingExportThreads = normalizeExportThreads(exportThreads)
        pendingExportFormat = exportFormat
        prefsRef.edit() {
            putInt(KEY_EXPORT_THREADS, pendingExportThreads)
            putString(KEY_EXPORT_FORMAT, pendingExportFormat.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val treeUri = preferencesGateway.getExportTreeUri()
            if (treeUri == null || !preferencesGateway.hasExportPermission(treeUri)) {
                pendingExportAfterExportTreeSelection = true
                ui.showToast(R.string.export_directory_required)
                requestExportDirectoryPermission(preferencesGateway.buildExportInitialUri())
                return
            }
        } else {
            val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(
                uiContext,
                permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingExportAfterPermission = true
                requestLegacyPermission()
                return
            }
        }
        exportFolderInternal(
            folder = folder,
            images = images,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    fun exportFolderAfterPermission(
        uiContext: Context,
        folder: File?,
        images: List<File>,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        if (folder == null) return
        exportFolderInternal(
            folder = folder,
            images = images,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    fun exportCollection(
        uiContext: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        exportThreads: Int,
        exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        pendingExportIsCollection = true
        pendingExportThreads = normalizeExportThreads(exportThreads)
        pendingExportFormat = exportFormat
        prefsRef.edit() {
            putInt(KEY_EXPORT_THREADS, pendingExportThreads)
            putString(KEY_EXPORT_FORMAT, pendingExportFormat.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val treeUri = preferencesGateway.getExportTreeUri()
            if (treeUri == null || !preferencesGateway.hasExportPermission(treeUri)) {
                pendingExportAfterExportTreeSelection = true
                ui.showToast(R.string.export_directory_required)
                requestExportDirectoryPermission(preferencesGateway.buildExportInitialUri())
                return
            }
        } else {
            val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(
                uiContext,
                permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingExportAfterPermission = true
                requestLegacyPermission()
                return
            }
        }
        exportCollectionInternal(
            collectionFolder = collectionFolder,
            chapterImages = chapterImages,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    fun exportCollectionAfterPermission(
        uiContext: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        exportCollectionInternal(
            collectionFolder = collectionFolder,
            chapterImages = chapterImages,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    private fun exportFolderInternal(
        folder: File,
        images: List<File>,
        exportThreads: Int,
        exportFormat: ExportFormat,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        val exportImages = images
        if (exportImages.isEmpty()) {
            LibraryUiBridge.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return
        }
        val exportTreeUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preferencesGateway.getExportTreeUri()?.takeIf { preferencesGateway.hasExportPermission(it) }
        } else {
            null
        }
        val verticalLayoutEnabled = !settingsStore.loadNormalBubbleRenderSettings().useHorizontalText

        val target = exportTarget(folder, exportFormat, exportTreeUri)
        if (!launchExportTask(target) exportTask@{
            val exportTaskId = TranslationKeepAliveService.startExportTask(
                appContext,
                appContext.getString(R.string.export_keepalive_title),
                appContext.getString(R.string.translation_keepalive_message),
                appContext.getString(R.string.exporting_progress, 0, exportImages.size)
            )
            var failed = false
            var exportDir: DocumentFile? = null
            var exportDirReady = true
            try {
                withContext(Dispatchers.IO) {
                    if (exportTreeUri != null && exportFormat == ExportFormat.IMAGE_DIR) {
                        exportDir = resolveExportDirectory(appContext, exportTreeUri, folder.name)
                        if (exportDir == null) {
                            exportDirReady = false
                        } else {
                            ensureNoMediaFile(exportDir)
                        }
                    } else if (exportFormat == ExportFormat.IMAGE_DIR) {
                        ensureNoMediaFile(appContext, folder.name)
                    }
                }
                if (!exportDirReady) {
                    failed = true
                    LibraryUiBridge.setFolderStatus(appContext.getString(R.string.export_failed))
                    return@exportTask
                }
                LibraryUiBridge.setFolderStatus(appContext.getString(R.string.exporting_progress, 0, exportImages.size))
                var successPathHint: String? = null

                when (exportFormat) {
                    ExportFormat.CBZ -> {
                        val result = exportCbzWithBubbles(
                            context = appContext,
                            folder = folder,
                            images = exportImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri
                        ) { count ->
                            withContext(Dispatchers.Main) {
                                LibraryUiBridge.setFolderStatus(
                                    appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                )
                                TranslationKeepAliveService.updateExportProgress(
                                    appContext,
                                    exportTaskId,
                                    count,
                                    exportImages.size,
                                    appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                )
                            }
                        }
                        failed = !result.success
                        successPathHint = result.pathHint
                    }
                    ExportFormat.PDF -> {
                        val result = exportPdfWithBubbles(
                            context = appContext,
                            folder = folder,
                            images = exportImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri
                        ) { count ->
                            withContext(Dispatchers.Main) {
                                LibraryUiBridge.setFolderStatus(
                                    appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                )
                                TranslationKeepAliveService.updateExportProgress(
                                    appContext,
                                    exportTaskId,
                                    count,
                                    exportImages.size,
                                    appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                )
                            }
                        }
                        failed = !result.success
                        successPathHint = result.pathHint
                    }
                    ExportFormat.IMAGE_DIR -> {
                        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
                        val exportedCount = AtomicInteger(0)
                        val hasFailures = AtomicBoolean(false)

                        coroutineScope {
                            val tasks = exportImages.map { image ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        val renderer = BubbleRenderer(appContext)
                                        val success = exportImageWithBubbles(
                                            appContext,
                                            renderer,
                                            image,
                                            folder.name,
                                            verticalLayoutEnabled,
                                            exportDir
                                        )
                                        if (!success) {
                                            hasFailures.set(true)
                                        }
                                        val count = exportedCount.incrementAndGet()
                                        withContext(Dispatchers.Main) {
                                            LibraryUiBridge.setFolderStatus(
                                                appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                            )
                                            TranslationKeepAliveService.updateExportProgress(
                                                appContext,
                                                exportTaskId,
                                                count,
                                                exportImages.size,
                                                appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                            )
                                        }
                                    }
                                }
                            }
                            tasks.awaitAll()
                        }
                        failed = hasFailures.get()
                    }
                }

                LibraryUiBridge.setFolderStatus(
                    if (failed) appContext.getString(R.string.export_failed) else appContext.getString(R.string.export_done)
                )
                if (!failed) {
                    val path = successPathHint ?: if (exportTreeUri != null) {
                        buildExportPathHint(exportTreeUri, folder.name)
                    } else {
                        "/Documents/manga-translate/${folder.name}"
                    }
                    LibraryUiBridge.showExportSuccess(path)
                }
                AppLogger.log(
                    "Library",
                    "Export ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
            } catch (e: CancellationException) {
                failed = true
                throw e
            } catch (e: Exception) {
                failed = true
                AppLogger.log("Library", "Export failed: ${folder.name}", e)
                LibraryUiBridge.setFolderStatus(appContext.getString(R.string.export_failed))
            } finally {
                LibraryUiBridge.setFolderExportEnabled(folder, true)
                TranslationKeepAliveService.finishExportTask(
                    context = appContext,
                    taskId = exportTaskId,
                    failed = failed,
                    content = appContext.getString(
                        if (failed) R.string.export_failed else R.string.export_done
                    )
                )
            }
        }) {
            LibraryUiBridge.setFolderStatus(appContext.getString(R.string.exporting_progress, 0, exportImages.size))
            return
        }
        onExitSelectionMode()
        onSetExportEnabled(false)
        LibraryUiBridge.setFolderExportEnabled(folder, false)
    }

    private fun exportCollectionInternal(
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        exportThreads: Int,
        exportFormat: ExportFormat,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        val allImages = chapterImages.flatMap { it.second }
        if (allImages.isEmpty()) {
            LibraryUiBridge.setFolderStatus(appContext.getString(R.string.folder_chapters_empty))
            return
        }
        val exportTreeUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preferencesGateway.getExportTreeUri()?.takeIf { preferencesGateway.hasExportPermission(it) }
        } else {
            null
        }
        val verticalLayoutEnabled = !settingsStore.loadNormalBubbleRenderSettings().useHorizontalText

        val target = exportTarget(collectionFolder, exportFormat, exportTreeUri)
        if (!launchExportTask(target) {
            val exportTaskId = TranslationKeepAliveService.startExportTask(
                appContext,
                appContext.getString(R.string.export_keepalive_title),
                appContext.getString(R.string.translation_keepalive_message),
                appContext.getString(R.string.exporting_progress, 0, allImages.size)
            )
            var failed = false
            try {
                when (exportFormat) {
                    ExportFormat.IMAGE_DIR -> {
                        failed = exportCollectionAsImageDir(
                            context = appContext,
                            collectionName = collectionFolder.name,
                            chapterImages = chapterImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri,
                            totalImages = allImages.size,
                            exportTaskId = exportTaskId
                        )
                    }
                    ExportFormat.CBZ -> {
                        val result = exportCollectionAsCbz(
                            context = appContext,
                            collectionFolder = collectionFolder,
                            chapterImages = chapterImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri,
                            exportTaskId = exportTaskId
                        )
                        failed = !result.success
                        if (!failed) {
                            val path = result.pathHint ?: if (exportTreeUri != null) {
                                "${buildExportRootPathHint(exportTreeUri)}/${collectionFolder.name}.cbz"
                            } else {
                                "/Documents/manga-translate/${collectionFolder.name}.cbz"
                            }
                            LibraryUiBridge.showExportSuccess(path)
                        }
                    }
                    ExportFormat.PDF -> {
                        val result = exportCollectionAsPdfWithOutlines(
                            context = appContext,
                            collectionFolder = collectionFolder,
                            chapterImages = chapterImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri,
                            exportTaskId = exportTaskId
                        )
                        failed = !result.success
                        if (!failed) {
                            val path = result.pathHint ?: if (exportTreeUri != null) {
                                "${buildExportRootPathHint(exportTreeUri)}/${collectionFolder.name}.pdf"
                            } else {
                                "/Documents/manga-translate/${collectionFolder.name}.pdf"
                            }
                            LibraryUiBridge.showExportSuccess(path)
                        }
                    }
                }

                LibraryUiBridge.setFolderStatus(
                    if (failed) appContext.getString(R.string.export_failed) else appContext.getString(R.string.export_done)
                )
                AppLogger.log(
                    "Library",
                    "Collection export ${if (failed) "completed with failures" else "completed"}: ${collectionFolder.name}"
                )
            } catch (e: CancellationException) {
                failed = true
                throw e
            } catch (e: Exception) {
                failed = true
                AppLogger.log("Library", "Collection export failed: ${collectionFolder.name}", e)
                LibraryUiBridge.setFolderStatus(appContext.getString(R.string.export_failed))
            } finally {
                LibraryUiBridge.setFolderExportEnabled(collectionFolder, true)
                TranslationKeepAliveService.finishExportTask(
                    context = appContext,
                    taskId = exportTaskId,
                    failed = failed,
                    content = appContext.getString(
                        if (failed) R.string.export_failed else R.string.export_done
                    )
                )
            }
        }) {
            LibraryUiBridge.setFolderStatus(appContext.getString(R.string.exporting_progress, 0, allImages.size))
            return
        }
        onExitSelectionMode()
        onSetExportEnabled(false)
        LibraryUiBridge.setFolderExportEnabled(collectionFolder, false)
    }

    private fun launchExportTask(target: ExportTaskTarget, block: suspend () -> Unit): Boolean {
        return exportTaskHost.launch(target, block)
    }

    private fun exportTarget(folder: File, format: ExportFormat, exportTreeUri: Uri?): ExportTaskTarget {
        val extension = when (format) {
            ExportFormat.IMAGE_DIR -> "directory"
            ExportFormat.CBZ -> "cbz"
            ExportFormat.PDF -> "pdf"
        }
        return ExportTaskTarget(
            folderPath = folder.absolutePath,
            destination = "${exportTreeUri ?: "legacy"}|${folder.name}|$extension"
        )
    }

    private suspend fun exportCollectionAsImageDir(
        context: Context,
        collectionName: String,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        totalImages: Int,
        exportTaskId: String
    ): Boolean {
        var collectionDir: DocumentFile? = null
        var collectionDirReady = true

        if (exportTreeUri != null) {
            collectionDir = resolveExportDirectory(context, exportTreeUri, collectionName)
            if (collectionDir == null) {
                collectionDirReady = false
            } else {
                ensureNoMediaFile(collectionDir)
            }
        } else {
            ensureNoMediaFile(context, collectionName)
        }

        if (!collectionDirReady) {
            return true
        }

        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
        val exportedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)

        coroutineScope {
            val tasks = chapterImages.flatMap { (chapter, images) ->
                images.map { image ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val renderer = BubbleRenderer(context)
                            val chapterExportDir = if (collectionDir != null) {
                                val existing = collectionDir.findFile(chapter.name)
                                when {
                                    existing == null -> collectionDir.createDirectory(chapter.name)
                                    existing.isDirectory -> existing
                                    else -> null
                                }
                            } else {
                                null
                            }
                            val success = exportImageWithBubbles(
                                context,
                                renderer,
                                image,
                                if (collectionDir != null) "" else "$collectionName/${chapter.name}",
                                verticalLayoutEnabled,
                                chapterExportDir
                            )
                            if (!success) {
                                hasFailures.set(true)
                            }
                            val count = exportedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                LibraryUiBridge.setFolderStatus(
                                    appContext.getString(R.string.exporting_progress, count, totalImages)
                                )
                                TranslationKeepAliveService.updateExportProgress(
                                    appContext,
                                    exportTaskId,
                                    count,
                                    totalImages,
                                    appContext.getString(R.string.exporting_progress, count, totalImages)
                                )
                            }
                        }
                    }
                }
            }
            tasks.awaitAll()
        }

        if (!hasFailures.get()) {
            val path = if (exportTreeUri != null) {
                buildExportPathHint(exportTreeUri, collectionName)
            } else {
                "/Documents/manga-translate/$collectionName"
            }
            withContext(Dispatchers.Main) {
                LibraryUiBridge.showExportSuccess(path)
            }
        }
        return hasFailures.get()
    }

    private suspend fun exportCollectionAsCbz(
        context: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        exportTaskId: String
    ): CbzExportResult {
        val preparedEntries = prepareCollectionArchiveEntries(
            context = context,
            chapterImages = chapterImages,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads,
            exportTaskId = exportTaskId
        ) ?: return CbzExportResult(success = false, pathHint = null)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            exportCbzToDocumentTree(
                context = context,
                folder = collectionFolder,
                exportTreeUri = exportTreeUri,
                preparedEntries = preparedEntries
            )
        } else {
            exportCbzToLegacyStorage(
                folder = collectionFolder,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun exportCollectionAsPdfWithOutlines(
        context: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        exportTaskId: String
    ): CbzExportResult {
        val preparedEntries = prepareCollectionArchiveEntries(
            context = context,
            chapterImages = chapterImages,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads,
            exportTaskId = exportTaskId
        ) ?: return CbzExportResult(success = false, pathHint = null)

        val sortedEntries = preparedEntries.sortedBy { it.index }
        val orderedImages = sortedEntries.map { it.tempFile }

        var pageIndex = 0
        val chapterOutlines = chapterImages.map { (chapter, images) ->
            val outline = chapter.name to pageIndex
            pageIndex += images.size
            outline
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            writeCollectionPdfToDocumentTree(
                context = context,
                folder = collectionFolder,
                exportTreeUri = exportTreeUri,
                chapterOutlines = chapterOutlines,
                orderedImages = orderedImages,
                preparedEntries = preparedEntries
            )
        } else {
            writeCollectionPdfToLegacyStorage(
                folder = collectionFolder,
                chapterOutlines = chapterOutlines,
                orderedImages = orderedImages,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun writeCollectionPdfToDocumentTree(
        context: Context,
        folder: File,
        exportTreeUri: Uri,
        chapterOutlines: List<Pair<String, Int>>,
        orderedImages: List<File>,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = DocumentFile.fromTreeUri(context, exportTreeUri)
        if (root == null || !root.canWrite()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val pdfName = resolveUniquePdfName(root, folder.name)
        val pathHint = "${buildExportRootPathHint(exportTreeUri)}/$pdfName"

        return try {
            val success = writeDocumentFileTransaction(
                context = context,
                exportDir = root,
                finalName = pdfName,
                mimeType = "application/pdf",
                replaceExisting = false
            ) { stream ->
                PdfImageCodec.writeImagesToPdfWithOutlines(chapterOutlines, orderedImages, stream)
            }
            CbzExportResult(success = success, pathHint = pathHint.takeIf { success })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun writeCollectionPdfToLegacyStorage(
        folder: File,
        chapterOutlines: List<Pair<String, Int>>,
        orderedImages: List<File>,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "manga-translate"
        )
        if (!root.exists() && !root.mkdirs()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }

        return try {
            val target = writeLegacyExportTransaction(root, "${folder.name}.pdf") { stream ->
                PdfImageCodec.writeImagesToPdfWithOutlines(chapterOutlines, orderedImages, stream)
            }
            CbzExportResult(
                success = target != null,
                pathHint = target?.let { "/Documents/manga-translate/${it.name}" }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun prepareCollectionArchiveEntries(
        context: Context,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTaskId: String
    ): List<PreparedCbzEntry>? {
        val allImages = chapterImages.flatMap { (chapter, images) ->
            images.map { chapter to it }
        }
        if (allImages.isEmpty()) return null

        val tempDir = File(context.cacheDir, "cbz_export_${System.currentTimeMillis()}_collection")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            AppLogger.log("Library", "Create CBZ temp directory failed: ${tempDir.absolutePath}")
            return null
        }

        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
        val renderedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)
        val totalImages = allImages.size
        val entries = MutableList<PreparedCbzEntry?>(totalImages) { null }

        return try {
            coroutineScope {
                val tasks = allImages.mapIndexed { index, (chapter, image) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val prepared = renderImageToTempFileWithPrefix(
                                context = context,
                                imageFile = image,
                                prefix = chapter.name,
                                verticalLayoutEnabled = verticalLayoutEnabled,
                                tempDir = tempDir,
                                index = index
                            )
                            if (prepared == null) {
                                hasFailures.set(true)
                            } else {
                                entries[index] = prepared
                            }
                            val count = renderedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                LibraryUiBridge.setFolderStatus(
                                    appContext.getString(R.string.exporting_progress, count, totalImages)
                                )
                                TranslationKeepAliveService.updateExportProgress(
                                    appContext,
                                    exportTaskId,
                                    count,
                                    totalImages,
                                    appContext.getString(R.string.exporting_progress, count, totalImages)
                                )
                            }
                        }
                    }
                }
                tasks.awaitAll()
            }
            if (hasFailures.get() || entries.any { it == null }) {
                null
            } else {
                entries.filterNotNull()
            }
        } finally {
            if (hasFailures.get() || entries.any { it == null }) {
                runCatching { tempDir.deleteRecursively() }
            }
        }
    }

    private suspend fun renderImageToTempFileWithPrefix(
        context: Context,
        imageFile: File,
        prefix: String,
        verticalLayoutEnabled: Boolean,
        tempDir: File,
        index: Int
    ): PreparedCbzEntry? {
        val translation = translationStore.load(imageFile)
        val hasText = hasExportableTranslation(translation)
        val spec = resolveExportSpec(imageFile.name)
        val entryName = "$prefix/${spec.displayName}"
        val tempFile = File(tempDir, "entry_$index")
        if (!hasText && canPassthroughOriginal(imageFile, spec)) {
            val success = try {
                imageFile.copyTo(tempFile, overwrite = true)
                true
            } catch (e: Exception) {
                AppLogger.log("Library", "Copy CBZ entry failed: ${imageFile.name}", e)
                false
            }
            if (!success) return null
            return PreparedCbzEntry(index = index, entryName = entryName, tempFile = tempFile)
        }
        val renderer = BubbleRenderer(context)
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        var output: Bitmap? = null
        try {
            output = if (hasText && translation != null) {
                renderer.render(bitmap, translation, verticalLayoutEnabled)
            } else {
                bitmap
            }
            val success = try {
                FileOutputStream(tempFile).use { outputStream ->
                    output.compress(spec.format, spec.quality, outputStream)
                }
            } catch (e: Exception) {
                AppLogger.log("Library", "Write CBZ entry failed: ${imageFile.name}", e)
                false
            }
            if (!success) return null
            return PreparedCbzEntry(index = index, entryName = entryName, tempFile = tempFile)
        } finally {
            if (output != null && output !== bitmap) {
                output.recycle()
            }
            bitmap.recycle()
        }
    }

    private fun resolveExportDirectory(
        context: Context,
        treeUri: Uri,
        folderName: String
    ): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
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

    private fun buildExportPathHint(treeUri: Uri, folderName: String): String {
        val base = buildExportRootPathHint(treeUri)
        return "$base/$folderName"
    }

    private fun buildExportRootPathHint(treeUri: Uri): String {
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

    private fun isImageDocument(file: DocumentFile): Boolean {
        val name = file.name.orEmpty()
        return ImageFileSupport.isSupportedSourceImageFileName(name)
    }

    private fun ensureNoMediaFile(context: Context, folderName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
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

    private fun ensureNoMediaFile(exportDir: DocumentFile) {
        if (exportDir.findFile(".nomedia") != null) return
        runCatching {
            exportDir.createFile("application/octet-stream", ".nomedia")
        }.onFailure { e ->
            AppLogger.log("Library", "Create .nomedia failed: ${exportDir.uri}", e)
        }
    }

    private suspend fun exportImageWithBubbles(
        context: Context,
        renderer: BubbleRenderer,
        imageFile: File,
        folderName: String,
        verticalLayoutEnabled: Boolean,
        exportDir: DocumentFile?
    ): Boolean {
        val translation = translationStore.load(imageFile)
        val hasText = hasExportableTranslation(translation)
        val spec = resolveExportSpec(imageFile.name)
        val success = if (!hasText && canPassthroughOriginal(imageFile, spec)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportDir != null) {
                copyFileToDocumentFile(context, imageFile, spec, exportDir)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyFileToMediaStore(context, imageFile, spec, folderName)
            } else {
                copyFileToLegacyStorage(imageFile, spec, folderName)
            }
        } else {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
            val output = if (hasText && translation != null) {
                renderer.render(bitmap, translation, verticalLayoutEnabled)
            } else {
                bitmap
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportDir != null) {
                    saveBitmapToDocumentFile(context, output, spec, exportDir)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveBitmapToMediaStore(context, output, spec, folderName)
                } else {
                    saveBitmapToLegacyStorage(output, spec, folderName)
                }
            } finally {
                if (output !== bitmap) {
                    output.recycle()
                }
                bitmap.recycle()
            }
        }
        if (!success) {
            AppLogger.log("Library", "Export failed for ${imageFile.name}")
        }
        return success
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMediaStoreTempUri(
        context: Context,
        spec: ExportSpec,
        folderName: String
    ): Uri? {
        val resolver = context.contentResolver
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
        context: Context,
        spec: ExportSpec,
        folderName: String
    ): Uri? {
        val resolver = context.contentResolver
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
        context: Context,
        spec: ExportSpec,
        folderName: String,
        writer: (OutputStream) -> Boolean
    ): Boolean {
        val resolver = context.contentResolver
        val tempUri = createMediaStoreTempUri(context, spec, folderName) ?: return false
        var published = false
        var replacedUri: Uri? = null
        val backupName = buildExportBackupName(spec.displayName)
        try {
            val success = resolver.openOutputStream(tempUri, "wt")?.use { output ->
                writer(output)
            } ?: false
            if (!success) return false

            val existingUri = findMediaStoreFile(context, spec, folderName)
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveBitmapToMediaStore(
        context: Context,
        bitmap: Bitmap,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        return writeMediaStoreImage(context, spec, folderName) { output ->
            bitmap.compress(spec.format, spec.quality, output)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copyFileToMediaStore(
        context: Context,
        source: File,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        return writeMediaStoreImage(context, spec, folderName) { output ->
            copySourceToStream(source, output)
        }
    }

    private suspend fun writeDocumentFileTransaction(
        context: Context,
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
                context.contentResolver.openOutputStream(temp.uri, "wt")?.use { output ->
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

    private suspend fun saveBitmapToDocumentFile(
        context: Context,
        bitmap: Bitmap,
        spec: ExportSpec,
        exportDir: DocumentFile
    ): Boolean {
        return writeDocumentFileTransaction(
            context = context,
            exportDir = exportDir,
            finalName = spec.displayName,
            mimeType = spec.mimeType,
            replaceExisting = true
        ) { output ->
            bitmap.compress(spec.format, spec.quality, output)
        }
    }

    private suspend fun copyFileToDocumentFile(
        context: Context,
        source: File,
        spec: ExportSpec,
        exportDir: DocumentFile
    ): Boolean {
        return writeDocumentFileTransaction(
            context = context,
            exportDir = exportDir,
            finalName = spec.displayName,
            mimeType = spec.mimeType,
            replaceExisting = true
        ) { output ->
            copySourceToStream(source, output)
        }
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

    private suspend fun writeLegacyExportTransaction(
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

    private suspend fun saveBitmapToLegacyStorage(
        bitmap: Bitmap,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        val exportDir = resolveLegacyExportDirectory(folderName) ?: return false
        return writeLegacyExportTransaction(exportDir, spec.displayName) { output ->
            bitmap.compress(spec.format, spec.quality, output)
        } != null
    }

    private suspend fun copyFileToLegacyStorage(
        source: File,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        val exportDir = resolveLegacyExportDirectory(folderName) ?: return false
        return writeLegacyExportTransaction(exportDir, spec.displayName) { output ->
            copySourceToStream(source, output)
        } != null
    }

    private fun buildExportTempName(finalName: String): String {
        return ".manga_translate_tmp_${UUID.randomUUID()}_$finalName"
    }

    private fun buildExportBackupName(finalName: String): String {
        return ".manga_translate_backup_${UUID.randomUUID()}_$finalName"
    }

    private fun resolveExportSpec(fileName: String): ExportSpec {
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

    private fun hasExportableTranslation(translation: TranslationResult?): Boolean {
        return translation != null && translation.bubbles.any { it.text.isNotBlank() }
    }

    private fun canPassthroughOriginal(imageFile: File, spec: ExportSpec): Boolean {
        if (!imageFile.isFile || !imageFile.exists()) return false
        if (ImageFileSupport.isAvifFile(imageFile.name)) return false
        val sourceName = imageFile.name.lowercase()
        val targetName = spec.displayName.lowercase()
        if (sourceName != targetName) return false
        val ext = sourceName.substringAfterLast('.', "")
        return ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "webp"
    }

    private fun copySourceToStream(source: File, output: java.io.OutputStream): Boolean {
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

    private data class ExportSpec(
        val displayName: String,
        val mimeType: String,
        val format: Bitmap.CompressFormat,
        val quality: Int
    )

    private data class CbzExportResult(
        val success: Boolean,
        val pathHint: String?
    )

    private data class PreparedCbzEntry(
        val index: Int,
        val entryName: String,
        val tempFile: File
    )

    private suspend fun exportCbzWithBubbles(
        context: Context,
        folder: File,
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        onProgress: suspend (Int) -> Unit
    ): CbzExportResult {
        val preparedEntries = prepareCbzEntries(
            context = context,
            folderName = folder.name,
            images = images,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads,
            onProgress = onProgress
        ) ?: return CbzExportResult(success = false, pathHint = null)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            exportCbzToDocumentTree(
                context = context,
                folder = folder,
                exportTreeUri = exportTreeUri,
                preparedEntries = preparedEntries
            )
        } else {
            exportCbzToLegacyStorage(
                folder = folder,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun exportPdfWithBubbles(
        context: Context,
        folder: File,
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        onProgress: suspend (Int) -> Unit
    ): CbzExportResult {
        val preparedEntries = prepareCbzEntries(
            context = context,
            folderName = folder.name,
            images = images,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads,
            onProgress = onProgress
        ) ?: return CbzExportResult(success = false, pathHint = null)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            exportPdfToDocumentTree(
                context = context,
                folder = folder,
                exportTreeUri = exportTreeUri,
                preparedEntries = preparedEntries
            )
        } else {
            exportPdfToLegacyStorage(
                folder = folder,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun exportCbzToDocumentTree(
        context: Context,
        folder: File,
        exportTreeUri: Uri,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = DocumentFile.fromTreeUri(context, exportTreeUri)
        if (root == null || !root.canWrite()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val cbzName = resolveUniqueCbzName(root, folder.name)
        val pathHint = "${buildExportRootPathHint(exportTreeUri)}/$cbzName"

        return try {
            val success = writeDocumentFileTransaction(
                context = context,
                exportDir = root,
                finalName = cbzName,
                mimeType = "application/vnd.comicbook+zip",
                replaceExisting = false
            ) { stream ->
                ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                    for (entry in preparedEntries.sortedBy { it.index }) {
                        zip.putNextEntry(ZipEntry(entry.entryName))
                        FileInputStream(entry.tempFile).use { input ->
                            input.copyTo(zip)
                        }
                        zip.closeEntry()
                    }
                }
                true
            }
            CbzExportResult(success = success, pathHint = pathHint.takeIf { success })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export CBZ failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun exportCbzToLegacyStorage(
        folder: File,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "manga-translate"
        )
        if (!root.exists() && !root.mkdirs()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }

        return try {
            val target = writeLegacyExportTransaction(root, "${folder.name}.cbz") { stream ->
                ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                    for (entry in preparedEntries.sortedBy { it.index }) {
                        zip.putNextEntry(ZipEntry(entry.entryName))
                        FileInputStream(entry.tempFile).use { input ->
                            input.copyTo(zip)
                        }
                        zip.closeEntry()
                    }
                }
                true
            }
            CbzExportResult(
                success = target != null,
                pathHint = target?.let { "/Documents/manga-translate/${it.name}" }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export CBZ failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun exportPdfToDocumentTree(
        context: Context,
        folder: File,
        exportTreeUri: Uri,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = DocumentFile.fromTreeUri(context, exportTreeUri)
        if (root == null || !root.canWrite()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val pdfName = resolveUniquePdfName(root, folder.name)
        val pathHint = "${buildExportRootPathHint(exportTreeUri)}/$pdfName"

        return try {
            val success = writeDocumentFileTransaction(
                context = context,
                exportDir = root,
                finalName = pdfName,
                mimeType = "application/pdf",
                replaceExisting = false
            ) { stream ->
                val orderedImages = preparedEntries.sortedBy { it.index }.map { it.tempFile }
                PdfImageCodec.writeImagesToPdf(orderedImages, stream)
            }
            CbzExportResult(success = success, pathHint = pathHint.takeIf { success })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun exportPdfToLegacyStorage(
        folder: File,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "manga-translate"
        )
        if (!root.exists() && !root.mkdirs()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }

        return try {
            val target = writeLegacyExportTransaction(root, "${folder.name}.pdf") { stream ->
                val orderedImages = preparedEntries.sortedBy { it.index }.map { it.tempFile }
                PdfImageCodec.writeImagesToPdf(orderedImages, stream)
            }
            CbzExportResult(
                success = target != null,
                pathHint = target?.let { "/Documents/manga-translate/${it.name}" }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun prepareCbzEntries(
        context: Context,
        folderName: String,
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        onProgress: suspend (Int) -> Unit
    ): List<PreparedCbzEntry>? {
        val tempDir = File(context.cacheDir, "cbz_export_${System.currentTimeMillis()}_$folderName")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            AppLogger.log("Library", "Create CBZ temp directory failed: ${tempDir.absolutePath}")
            return null
        }

        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
        val renderedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)
        val entries = MutableList<PreparedCbzEntry?>(images.size) { null }

        return try {
            coroutineScope {
                val tasks = images.mapIndexed { index, image ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val prepared = renderImageToTempFile(
                                context = context,
                                imageFile = image,
                                verticalLayoutEnabled = verticalLayoutEnabled,
                                tempDir = tempDir,
                                index = index
                            )
                            if (prepared == null) {
                                hasFailures.set(true)
                            } else {
                                entries[index] = prepared
                            }
                            onProgress(renderedCount.incrementAndGet())
                        }
                    }
                }
                tasks.awaitAll()
            }
            if (hasFailures.get() || entries.any { it == null }) {
                null
            } else {
                entries.filterNotNull()
            }
        } finally {
            if (hasFailures.get() || entries.any { it == null }) {
                runCatching { tempDir.deleteRecursively() }
            }
        }
    }

    private suspend fun renderImageToTempFile(
        context: Context,
        imageFile: File,
        verticalLayoutEnabled: Boolean,
        tempDir: File,
        index: Int
    ): PreparedCbzEntry? {
        val translation = translationStore.load(imageFile)
        val hasText = hasExportableTranslation(translation)
        val spec = resolveExportSpec(imageFile.name)
        val tempFile = File(tempDir, "entry_$index")
        if (!hasText && canPassthroughOriginal(imageFile, spec)) {
            val success = try {
                imageFile.copyTo(tempFile, overwrite = true)
                true
            } catch (e: Exception) {
                AppLogger.log("Library", "Copy CBZ entry failed: ${imageFile.name}", e)
                false
            }
            if (!success) return null
            return PreparedCbzEntry(index = index, entryName = spec.displayName, tempFile = tempFile)
        }
        val renderer = BubbleRenderer(context)
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        var output: Bitmap? = null
        try {
            output = if (hasText && translation != null) {
                renderer.render(bitmap, translation, verticalLayoutEnabled)
            } else {
                bitmap
            }
            val success = try {
                FileOutputStream(tempFile).use { outputStream ->
                    output.compress(spec.format, spec.quality, outputStream)
                }
            } catch (e: Exception) {
                AppLogger.log("Library", "Write CBZ entry failed: ${imageFile.name}", e)
                false
            }
            if (!success) return null
            return PreparedCbzEntry(index = index, entryName = spec.displayName, tempFile = tempFile)
        } finally {
            if (output != null && output !== bitmap) {
                output.recycle()
            }
            bitmap.recycle()
        }
    }

    private fun cleanupPreparedCbzEntries(entries: List<PreparedCbzEntry>) {
        val tempDir = entries.firstOrNull()?.tempFile?.parentFile ?: return
        runCatching { tempDir.deleteRecursively() }
    }

    private fun resolveUniqueCbzName(root: DocumentFile, folderName: String): String {
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

    private fun resolveUniquePdfName(root: DocumentFile, folderName: String): String {
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

    private fun loadExportThreads(): Int {
        val saved = prefsRef.getInt(KEY_EXPORT_THREADS, DEFAULT_EXPORT_THREADS)
        return normalizeExportThreads(saved)
    }

    private fun loadExportFormatDefault(): ExportFormat {
        val saved = prefsRef.getString(KEY_EXPORT_FORMAT, null)
        if (!saved.isNullOrBlank()) {
            return runCatching { ExportFormat.valueOf(saved) }.getOrDefault(ExportFormat.IMAGE_DIR)
        }
        return if (prefsRef.getBoolean(KEY_EXPORT_AS_CBZ, false)) {
            ExportFormat.CBZ
        } else {
            ExportFormat.IMAGE_DIR
        }
    }

    private fun normalizeExportThreads(value: Int): Int {
        return value.coerceIn(MIN_EXPORT_THREADS, MAX_EXPORT_THREADS)
    }

    companion object {
        private const val KEY_EXPORT_THREADS = "export_threads"
        private const val KEY_EXPORT_AS_CBZ = "export_as_cbz"
        private const val KEY_EXPORT_FORMAT = "export_format"
        private const val DEFAULT_EXPORT_THREADS = 2
        private const val DEFAULT_EXPORT_WORKER_BYTES = 48L * 1024L * 1024L
        private const val MIN_EXPORT_THREADS = 1
        private const val MAX_EXPORT_THREADS = 16
    }
}

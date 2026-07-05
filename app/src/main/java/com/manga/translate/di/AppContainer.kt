package com.manga.translate.di

import android.content.Context
import com.manga.translate.BaiduAccessTokenManager
import com.manga.translate.CrashStateStore
import com.manga.translate.ExtractStateStore
import com.manga.translate.FloatingBubbleTranslationCoordinator
import com.manga.translate.FloatingEmptyBubbleCoordinator
import com.manga.translate.FloatingTranslationCacheStore
import com.manga.translate.FolderTranslationCoordinator
import com.manga.translate.GlossaryStore
import com.manga.translate.LibraryPreferencesGateway
import com.manga.translate.LibraryRepository
import com.manga.translate.LibraryUiCallbacks
import com.manga.translate.LocalModelMemoryManager
import com.manga.translate.LlmClient
import com.manga.translate.MangaTranslateApp
import com.manga.translate.OnnxRuntimeSupport
import com.manga.translate.OcrStore
import com.manga.translate.PendingBubbleRetranslator
import com.manga.translate.ReadingEmptyBubbleCoordinator
import com.manga.translate.ReadingProgressStore
import com.manga.translate.SettingsStore
import com.manga.translate.TextBubbleTranslationCoordinator
import com.manga.translate.TranslationPipeline
import com.manga.translate.TranslationProgressStore
import com.manga.translate.TranslationStore
import com.manga.translate.UpdateIgnoreStore
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

internal class AppContainer(private val appContext: Context) {
    private val translationPipelines = CopyOnWriteArrayList<WeakReference<TranslationPipeline>>()
    val settingsStore = SettingsStore(appContext)
    val baiduAccessTokenManager = BaiduAccessTokenManager(appContext)
    val crashStateStore = CrashStateStore(appContext)
    val updateIgnoreStore = UpdateIgnoreStore(appContext)
    val readingProgressStore = ReadingProgressStore(appContext)
    val libraryRepository = LibraryRepository(appContext)
    val llmClient = LlmClient(appContext, settingsStore, baiduAccessTokenManager)
    val ocrEngineRegistry = com.manga.translate.OcrEngineRegistry(appContext, settingsStore)
    val localModelMemoryManager = LocalModelMemoryManager {
        releasePipelineModels()
        ocrEngineRegistry.releaseLoadedEngines()
        OnnxRuntimeSupport.closeCachedSessions()
    }
    val bubbleTextRecognizer =
        com.manga.translate.BubbleTextRecognizer(llmClient, ocrEngineRegistry, settingsStore)
    val translationStore = TranslationStore()
    val ocrStore = OcrStore()
    val glossaryStore = GlossaryStore()
    val extractStateStore = ExtractStateStore()
    val translationProgressStore = TranslationProgressStore()
    val floatingTranslationCacheStore = FloatingTranslationCacheStore(appContext)
    val textBubbleTranslationCoordinator = TextBubbleTranslationCoordinator(llmClient = llmClient)
    val libraryPrefs = appContext.getSharedPreferences(LIBRARY_PREFS_NAME, Context.MODE_PRIVATE)

    fun createTranslationPipeline(): TranslationPipeline {
        return TranslationPipeline(
            context = appContext,
            llmClient = llmClient,
            settingsStore = settingsStore,
            store = translationStore,
            ocrStore = ocrStore,
            ocrEngineRegistry = ocrEngineRegistry,
            bubbleTextRecognizer = bubbleTextRecognizer,
            textBubbleTranslationCoordinator = textBubbleTranslationCoordinator,
            floatingBubbleTranslationCoordinator = createFloatingBubbleTranslationCoordinator()
        ).also { pipeline ->
            translationPipelines.add(WeakReference(pipeline))
        }
    }

    private fun releasePipelineModels() {
        translationPipelines.removeAll { reference ->
            val pipeline = reference.get()
            if (pipeline == null) {
                true
            } else {
                pipeline.releaseLoadedModels()
                false
            }
        }
    }

    fun createFolderTranslationCoordinator(
        translationPipeline: TranslationPipeline,
        ui: LibraryUiCallbacks
    ): FolderTranslationCoordinator {
        return FolderTranslationCoordinator(
            context = appContext,
            translationPipeline = translationPipeline,
            glossaryStore = glossaryStore,
            extractStateStore = extractStateStore,
            translationStore = translationStore,
            settingsStore = settingsStore,
            preferencesGateway = LibraryPreferencesGateway(
                context = appContext,
                prefs = libraryPrefs,
                repository = libraryRepository
            ),
            llmClient = llmClient,
            ui = ui,
            progressStore = translationProgressStore,
            pendingBubbleRetranslator = createPendingBubbleRetranslator()
        )
    }

    fun createPendingBubbleRetranslator(): PendingBubbleRetranslator {
        return PendingBubbleRetranslator(
            context = appContext,
            settingsStore = settingsStore,
            bubbleTextRecognizer = bubbleTextRecognizer,
            textBubbleTranslationCoordinator = textBubbleTranslationCoordinator
        )
    }

    fun createReadingEmptyBubbleCoordinator(): ReadingEmptyBubbleCoordinator {
        return ReadingEmptyBubbleCoordinator(
            context = appContext,
            translationStore = translationStore,
            glossaryStore = glossaryStore,
            repository = libraryRepository,
            libraryPrefs = libraryPrefs,
            settingsStore = settingsStore,
            bubbleTextRecognizer = bubbleTextRecognizer,
            textBubbleTranslationCoordinator = textBubbleTranslationCoordinator,
            localModelMemoryManager = localModelMemoryManager
        )
    }

    fun createFloatingEmptyBubbleCoordinator(): FloatingEmptyBubbleCoordinator {
        return FloatingEmptyBubbleCoordinator(
            context = appContext,
            llmClient = llmClient,
            floatingTranslationCacheStore = floatingTranslationCacheStore,
            settingsStore = settingsStore,
            bubbleTextRecognizer = bubbleTextRecognizer
        )
    }

    fun createFloatingBubbleTranslationCoordinator(): FloatingBubbleTranslationCoordinator {
        return FloatingBubbleTranslationCoordinator(
            llmClient = llmClient,
            floatingTranslationCacheStore = floatingTranslationCacheStore,
            settingsStore = settingsStore
        )
    }

    companion object {
        private const val LIBRARY_PREFS_NAME = "library_prefs"
    }
}

internal val Context.appContainer: AppContainer
    get() = (applicationContext as MangaTranslateApp).appContainer

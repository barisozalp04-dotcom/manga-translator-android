package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.library.LibraryRepository
import com.manga.translate.model.OcrApiFormat
import com.manga.translate.model.FolderStatus
import com.manga.translate.model.ThemeMode
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.settings.AiProviderProfilesFileWriter
import com.manga.translate.settings.ApiSettingsStore
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.AdditionalTranslationProvider
import com.manga.translate.settings.CustomThemeColors
import com.manga.translate.settings.LlmParameterStore
import com.manga.translate.settings.OcrSettingsStore
import com.manga.translate.settings.ProviderProfileStore
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.SettingsStoreStorage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val profileFile = context.filesDir.resolve("ai_provider_profiles.json")
        profileFile.delete()
        context.filesDir.resolve("ai_provider_profiles.json.bak").delete()
        context.filesDir.resolve("ai_provider_profiles.json.new").delete()
        context.getSharedPreferences("library_prefs_test", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `translation language availability follows ocr mode`() {
        val localLanguages = TranslationLanguage.supportedForOcr(useLocalOcr = true)
        assertTrue(localLanguages.contains(TranslationLanguage.JA_TO_ZH))
        assertTrue(localLanguages.contains(TranslationLanguage.EN_TO_ZH))
        assertTrue(localLanguages.contains(TranslationLanguage.KO_TO_ZH))
        assertFalse(localLanguages.contains(TranslationLanguage.RU_TO_ZH))

        assertTrue(
            TranslationLanguage.supportedForOcr(useLocalOcr = false)
                .contains(TranslationLanguage.RU_TO_ZH)
        )
    }

    @Test
    fun `settings store exposes observable change flows`() = runBlocking {
        val store = SettingsStore(context)
        val initialVersion = store.settingsVersion.value
        val changeDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) {
                store.settingChanges.first()
            }
        }

        store.saveThemeMode(ThemeMode.PASTEL)

        val changedKeys = changeDeferred.await()
        assertTrue(changedKeys.isNotEmpty())
        assertTrue(store.settingsVersion.value > initialVersion)
    }

    @Test
    fun `xnnpack setting defaults off and persists`() {
        val store = SettingsStore(context)

        assertFalse(store.loadUseXnnpack())

        store.saveUseXnnpack(true)

        assertTrue(SettingsStore(context).loadUseXnnpack())
    }

    @Test
    fun `unsupported legacy ocr settings fall back to local ocr`() {
        val prefs = context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("ocr_use_local", false)
            .putString("ocr_api_format", "baidu_ai")
            .putString("ocr_api_url", "https://api.siliconflow.cn/v1")
            .putString("ocr_api_key", "old-baidu-key")
            .putString("ocr_model_name", "Qwen/Qwen3-VL-8B-Instruct")
            .putString("ocr_secret_key", "old-baidu-secret")
            .commit()

        val settings = SettingsStore(context).loadOcrApiSettings()

        assertTrue(settings.useLocalOcr)
        assertEquals(OcrApiFormat.OPENAI_COMPATIBLE, settings.ocrApiFormat)
        assertTrue(settings.isValid())
        assertEquals("", settings.apiUrl)
        assertEquals("", settings.apiKey)
        assertEquals("", settings.modelName)
        assertEquals(false, prefs.getBoolean("ocr_use_local", true))
        assertEquals("baidu_ai", prefs.getString("ocr_api_format", null))
        assertEquals("old-baidu-key", prefs.getString("ocr_api_key", null))
        assertEquals("old-baidu-secret", prefs.getString("ocr_secret_key", null))
    }

    @Test
    fun `unsupported future ocr format falls back to local ocr`() {
        val prefs = context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("ocr_use_local", false)
            .putString("ocr_api_format", "future_ocr_provider")
            .putString("ocr_api_key", "future-provider-key")
            .commit()

        val settings = SettingsStore(context).loadOcrApiSettings()

        assertTrue(settings.useLocalOcr)
        assertEquals(OcrApiFormat.OPENAI_COMPATIBLE, settings.ocrApiFormat)
        assertEquals("", settings.apiKey)
        assertEquals("future_ocr_provider", prefs.getString("ocr_api_format", null))
        assertEquals("future-provider-key", prefs.getString("ocr_api_key", null))
    }

    @Test
    fun `custom theme colors persist as opaque rgb values`() {
        val store = SettingsStore(context)
        val colors = CustomThemeColors.DEFAULT.copy(
            background = 0xFF123456.toInt(),
            surface = 0xFFABCDEF.toInt(),
            surfaceAlt = 0xFF223344.toInt(),
            accent = 0xFFCC5500.toInt(),
            accentContent = 0xFF112233.toInt(),
            foreground = 0xFF334455.toInt(),
            mutedForeground = 0xFF556677.toInt(),
            outline = 0xFF778899.toInt(),
            buttonFill = 0xFF99AABB.toInt(),
            buttonPressed = 0xFFBBAA99.toInt(),
            buttonText = 0xFF102030.toInt(),
            heroStart = 0xFF405060.toInt(),
            heroEnd = 0xFF607080.toInt()
        )

        store.saveCustomThemeColors(colors)

        assertEquals(colors, SettingsStore(context).loadCustomThemeColors())
    }

    @Test
    fun `legacy additional provider parameter targets migrate to stable ids`() {
        val prefs = context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(
                "additional_translation_providers",
                JSONObject()
                    .put("version", 2)
                    .put(
                        "providers",
                        JSONArray()
                            .put(legacyProvider("First"))
                            .put(legacyProvider("Second"))
                    )
                    .toString()
            )
            .putString(
                "custom_request_parameters",
                JSONObject()
                    .put("version", 2)
                    .put(
                        "items",
                        JSONArray()
                            .put(customParameter("first_only", "additional_1"))
                            .put(customParameter("second_only", "additional_2"))
                    )
                    .toString()
            )
            .commit()

        val store = SettingsStore(context)
        val providers = store.loadAdditionalTranslationProviders()
        val firstId = providers[0].providerId
        val secondId = providers[1].providerId

        assertNotEquals("additional_1", firstId)
        assertNotEquals("additional_2", secondId)
        assertEquals(
            setOf(firstId, secondId),
            store.loadCustomRequestParameters().map { it.targetProviderId }.toSet()
        )

        assertTrue(store.saveCurrentAsAiProviderProfile("stable_ids_${System.nanoTime()}"))
        val profileName = store.loadAiProviderProfilesState().activeProfileName!!

        store.saveAdditionalTranslationProviders(listOf(providers[1], providers[0]))
        assertEquals(
            setOf(firstId, secondId),
            store.loadCustomRequestParameters().map { it.targetProviderId }.toSet()
        )
        store.saveAdditionalTranslationProviders(listOf(providers[1]))
        assertEquals(
            setOf(firstId, secondId),
            store.loadCustomRequestParameters().map { it.targetProviderId }.toSet()
        )

        assertTrue(store.applyAiProviderProfile(profileName))
        assertEquals(
            listOf(firstId, secondId),
            store.loadAdditionalTranslationProviders().map { it.providerId }
        )
        assertEquals(
            setOf(firstId, secondId),
            store.loadCustomRequestParameters().map { it.targetProviderId }.toSet()
        )
    }

    @Test
    fun `provider profile restores retry and concurrency settings`() {
        val store = SettingsStore(context)
        store.saveApiRetryCount(9)
        store.saveMaxConcurrency(7)
        val profileName = "request_controls_${System.nanoTime()}"

        assertTrue(store.saveCurrentAsAiProviderProfile(profileName))
        val profile = store.loadAiProviderProfilesState().profiles.single()
        assertEquals(9, profile.apiRetryCount)
        assertEquals(7, profile.maxConcurrency)

        store.saveApiRetryCount(2)
        store.saveMaxConcurrency(3)

        assertTrue(store.applyAiProviderProfile(profileName))
        assertEquals(9, store.loadApiRetryCount())
        assertEquals(7, store.loadMaxConcurrency())
    }

    @Test
    fun `profile write failure preserves the previous json`() {
        val storage = SettingsStoreStorage(context)
        val oldJson = """{"version":2,"activeProfileName":"old","profiles":[]}"""
        storage.aiProviderProfilesFile.writeText(oldJson)
        val failingWriter = AiProviderProfilesFileWriter { _, _ -> false }
        val profileStore = ProviderProfileStore(
            storage = storage,
            apiSettingsStore = ApiSettingsStore(storage),
            ocrSettingsStore = OcrSettingsStore(storage),
            llmParameterStore = LlmParameterStore(storage),
            profileFileWriter = failingWriter
        )

        assertFalse(profileStore.saveCurrentAsAiProviderProfile("new"))
        assertEquals(oldJson, storage.aiProviderProfilesFile.readText())
    }

    @Test
    fun `floating opacity migrates once and then stays independent`() {
        val prefs = context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("translation_bubble_opacity_percent", 64).commit()
        val store = SettingsStore(context)

        assertEquals(64, store.loadFloatingBubbleRenderSettings().opacityPercent)
        assertEquals(64, prefs.getInt("floating_bubble_opacity_percent", -1))

        prefs.edit().putInt("translation_bubble_opacity_percent", 21).commit()
        assertEquals(21, store.loadNormalBubbleRenderSettings().opacityPercent)
        assertEquals(64, store.loadFloatingBubbleRenderSettings().opacityPercent)
    }

    @Test
    fun `profile application rejects concurrency below enabled provider count`() {
        val store = SettingsStore(context)
        store.save(ApiSettings("https://primary.example", "primary-key", "primary-model"))
        store.saveAdditionalTranslationProviders(
            listOf(
                AdditionalTranslationProvider(
                    name = "Secondary",
                    apiUrl = "https://secondary.example",
                    apiKey = "secondary-key",
                    modelName = "secondary-model",
                    weight = 1
                )
            )
        )
        store.saveMaxConcurrency(1)
        val profileName = "invalid_concurrency_${System.nanoTime()}"
        assertTrue(store.saveCurrentAsAiProviderProfile(profileName))

        store.saveMaxConcurrency(4)

        assertFalse(store.canApplyAiProviderProfile(profileName))
        assertFalse(store.applyAiProviderProfile(profileName))
        assertEquals(4, store.loadMaxConcurrency())
    }

    private fun legacyProvider(name: String): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("apiUrl", "https://$name.example")
            .put("apiKey", "$name-key")
            .put("modelName", "$name-model")
            .put("weight", 1)
            .put("enabled", true)
    }

    private fun customParameter(key: String, targetProviderId: String): JSONObject {
        return JSONObject()
            .put("key", key)
            .put("value", "true")
            .put("enabled", true)
            .put("targetProviderId", targetProviderId)
    }

    @Test
    fun `clear folder settings removes standalone folder preferences`() {
        val repository = LibraryRepository(context)
        val prefs = context.getSharedPreferences("library_prefs_test", Context.MODE_PRIVATE)
        val gateway = LibraryPreferencesGateway(context, prefs, repository)
        val folder = repository.createFolder("standalone_${System.nanoTime()}")!!

        try {
            gateway.setTranslationLanguage(folder, TranslationLanguage.KO_TO_ZH)
            gateway.setFullTranslateEnabled(folder, false)

            gateway.clearFolderSettings(folder)

            assertEquals(TranslationLanguage.JA_TO_ZH, gateway.getTranslationLanguage(folder))
            assertTrue(gateway.isFullTranslateEnabled(folder))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `clear folder settings preserves collection-scoped preferences for member folders`() {
        val repository = LibraryRepository(context)
        val prefs = context.getSharedPreferences("library_prefs_test", Context.MODE_PRIVATE)
        val gateway = LibraryPreferencesGateway(context, prefs, repository)
        val collection = repository.createCollection("collection_${System.nanoTime()}")!!
        val child = repository.createChildFolder(collection, "child_${System.nanoTime()}")!!

        try {
            gateway.setTranslationLanguage(child, TranslationLanguage.EN_TO_ZH)
            gateway.setFullTranslateEnabled(child, false)

            gateway.clearFolderSettings(child)

            assertEquals(TranslationLanguage.EN_TO_ZH, gateway.getTranslationLanguage(child))
            assertFalse(gateway.isFullTranslateEnabled(child))
        } finally {
            collection.deleteRecursively()
        }
    }

    @Test
    fun `folder tags persist migrate and clear with root folder`() {
        val repository = LibraryRepository(context)
        val prefs = context.getSharedPreferences("library_prefs_test", Context.MODE_PRIVATE)
        val gateway = LibraryPreferencesGateway(context, prefs, repository)
        val folder = repository.createFolder("tagged_${System.nanoTime()}")!!
        var renamed: java.io.File? = null

        try {
            gateway.setFolderTags(folder, setOf("Favorite", "Unread"))
            assertEquals(setOf("Favorite", "Unread"), gateway.getFolderTags(folder))

            renamed = repository.renameFolder(folder, "renamed_${System.nanoTime()}")!!
            gateway.migrateFolderSettings(folder, renamed)
            assertTrue(gateway.getFolderTags(folder).isEmpty())
            assertEquals(setOf("Favorite", "Unread"), gateway.getFolderTags(renamed))

            gateway.clearFolderSettings(renamed)
            assertTrue(gateway.getFolderTags(renamed).isEmpty())
        } finally {
            (renamed ?: folder).deleteRecursively()
        }
    }

    @Test
    fun `cached folder status persists migrates and clears with root folder`() {
        val repository = LibraryRepository(context)
        val prefs = context.getSharedPreferences("library_prefs_test", Context.MODE_PRIVATE)
        val gateway = LibraryPreferencesGateway(context, prefs, repository)
        val folder = repository.createFolder("status_${System.nanoTime()}")!!
        var renamed: java.io.File? = null

        try {
            gateway.setCachedFolderStatus(folder, FolderStatus.TRANSLATED)
            assertEquals(FolderStatus.TRANSLATED, gateway.getCachedFolderStatus(folder))

            renamed = repository.renameFolder(folder, "status_renamed_${System.nanoTime()}")!!
            gateway.migrateFolderSettings(folder, renamed)
            assertEquals(FolderStatus.TRANSLATED, gateway.getCachedFolderStatus(renamed))

            gateway.clearFolderSettings(renamed)
            assertEquals(null, gateway.getCachedFolderStatus(renamed))
        } finally {
            (renamed ?: folder).deleteRecursively()
        }
    }
}

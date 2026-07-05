package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        context.getSharedPreferences("library_prefs_test", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `translation language availability follows ocr mode`() {
        assertEquals(
            listOf(
                TranslationLanguage.JA_TO_ZH,
                TranslationLanguage.EN_TO_ZH,
                TranslationLanguage.KO_TO_ZH
            ),
            TranslationLanguage.supportedForOcr(useLocalOcr = true)
        )
        assertTrue(TranslationLanguage.supportedForOcr(useLocalOcr = false).contains(TranslationLanguage.RU_TO_ZH))
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
}

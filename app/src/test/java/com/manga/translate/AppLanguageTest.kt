package com.manga.translate

import androidx.core.os.LocaleListCompat
import com.manga.translate.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `follow system keeps system locales when a supported language exists`() {
        val resolved = AppLanguage.FOLLOW_SYSTEM.resolveApplicationLocales(
            LocaleListCompat.forLanguageTags("ru-RU,en-US")
        )

        assertTrue(resolved.isEmpty)
    }

    @Test
    fun `follow system keeps system locales for traditional chinese`() {
        val resolved = AppLanguage.FOLLOW_SYSTEM.resolveApplicationLocales(
            LocaleListCompat.forLanguageTags("zh-Hant-TW")
        )

        assertTrue(resolved.isEmpty)
    }

    @Test
    fun `follow system falls back to english when all system languages are unsupported`() {
        val resolved = AppLanguage.FOLLOW_SYSTEM.resolveApplicationLocales(
            LocaleListCompat.forLanguageTags("fr-FR,de-DE")
        )

        assertEquals("en", resolved.toLanguageTags())
    }

    @Test
    fun `supported language detection matches chinese english and russian only`() {
        assertTrue(AppLanguage.hasSupportedSystemLanguage(LocaleListCompat.forLanguageTags("zh-CN")))
        assertTrue(AppLanguage.hasSupportedSystemLanguage(LocaleListCompat.forLanguageTags("en-US")))
        assertTrue(AppLanguage.hasSupportedSystemLanguage(LocaleListCompat.forLanguageTags("ru-RU")))
        assertFalse(AppLanguage.hasSupportedSystemLanguage(LocaleListCompat.forLanguageTags("fr-FR")))
    }
}

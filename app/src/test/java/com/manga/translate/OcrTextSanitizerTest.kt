package com.manga.translate

import com.manga.translate.model.TranslationLanguage
import com.manga.translate.ocr.OcrTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextSanitizerTest {

    @Test
    fun `drops symbol-only bubbles regardless of symbol count`() {
        assertEquals("", OcrTextSanitizer.sanitize("!", TranslationLanguage.JA_TO_ZH))
        assertEquals("", OcrTextSanitizer.sanitize("!!!???...", TranslationLanguage.EN_TO_ZH))
        assertEquals("", OcrTextSanitizer.sanitize("♪ ♡ ☆", TranslationLanguage.KO_TO_ZH))
    }

    @Test
    fun `drops number-only bubbles because they contain no text`() {
        assertEquals("", OcrTextSanitizer.sanitize("12345", TranslationLanguage.EN_TO_ZH))
        assertEquals("", OcrTextSanitizer.sanitize("100%", TranslationLanguage.JA_TO_ZH))
    }

    @Test
    fun `keeps unicode text for supported and future languages`() {
        assertEquals("なに?!", OcrTextSanitizer.sanitize("なに?!", TranslationLanguage.JA_TO_ZH))
        assertEquals("Hello!", OcrTextSanitizer.sanitize("Hello!", TranslationLanguage.EN_TO_ZH))
        assertEquals("안녕!", OcrTextSanitizer.sanitize("안녕!", TranslationLanguage.KO_TO_ZH))
        assertEquals("Привет!", OcrTextSanitizer.sanitize("Привет!", TranslationLanguage.EN_TO_ZH))
    }

    @Test
    fun `normalizes invisible noise without dropping text`() {
        assertEquals("HELLO", OcrTextSanitizer.sanitize("ＨＥＬＬＯ\u200B", TranslationLanguage.EN_TO_ZH))
        assertEquals("あ\nい", OcrTextSanitizer.sanitize("あ\r\nい", TranslationLanguage.JA_TO_ZH))
    }
}

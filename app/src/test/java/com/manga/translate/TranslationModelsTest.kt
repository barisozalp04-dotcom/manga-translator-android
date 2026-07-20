package com.manga.translate

import android.graphics.RectF
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.model.TranslationResult
import com.manga.translate.model.deriveStatus
import com.manga.translate.translation.CrossPageBubbleMerger
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.PageOcrResult
import com.manga.translate.translation.withRecognizedTextBubblesOnly
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationModelsTest {
    @Test
    fun `ocr page drops bubbles without recognized text`() {
        val page = PageOcrResult(
            imageFile = File("page.jpg"),
            width = 1000,
            height = 7000,
            bubbles = listOf(
                OcrBubble(0, rect(0), "あ", BubbleSource.BUBBLE_DETECTOR),
                OcrBubble(1, rect(1), "", BubbleSource.BUBBLE_DETECTOR),
                OcrBubble(2, rect(2), "  ", BubbleSource.TEXT_DETECTOR)
            )
        )

        val filtered = page.withRecognizedTextBubblesOnly()

        assertEquals(listOf(0), filtered.bubbles.map { it.id })
    }

    @Test
    fun `standard status ignores bubbles without ocr text`() {
        val result = TranslationResult(
            imageName = "page.jpg",
            width = 1000,
            height = 1600,
            bubbles = buildList {
                repeat(43) { index ->
                    add(
                        BubbleTranslation.translated(
                            id = index,
                            rect = rect(index),
                            originalText = "source $index",
                            translatedText = "translated $index"
                        )
                    )
                }
                add(BubbleTranslation.pending(43, rect(43), originalText = ""))
                add(BubbleTranslation.pending(44, rect(44), originalText = ""))
            },
            metadata = TranslationMetadata(mode = TranslationMetadata.MODE_STANDARD)
        )

        assertEquals(PageTranslationStatus.SUCCESS, result.deriveStatus())
    }

    @Test
    fun `vl status still requires every detected bubble to translate`() {
        val result = TranslationResult(
            imageName = "page.jpg",
            width = 1000,
            height = 1600,
            bubbles = listOf(
                BubbleTranslation.translated(0, rect(0), translatedText = "translated"),
                BubbleTranslation.pending(1, rect(1), originalText = "")
            ),
            metadata = TranslationMetadata(mode = TranslationMetadata.MODE_VL_DIRECT)
        )

        assertEquals(PageTranslationStatus.PARTIAL, result.deriveStatus())
    }

    @Test
    fun `translation language exposes baidu ocr types`() {
        assertEquals("CHN_ENG", TranslationLanguage.CHN_ENG_TO_ZH.baiduLanguageType)
        assertEquals("RUS", TranslationLanguage.RU_TO_ZH.baiduLanguageType)
    }

    @Test
    fun `cross page merge returns detached list for single page input`() {
        val pages = mutableListOf(
            PageOcrResult(
                imageFile = File("page.jpg"),
                width = 1000,
                height = 1600,
                bubbles = listOf(
                    OcrBubble(0, rect(0), "hello", BubbleSource.BUBBLE_DETECTOR)
                )
            )
        )

        val merged = CrossPageBubbleMerger.merge(pages)

        assertNotSame(pages, merged)
        pages.clear()
        pages.addAll(merged)

        assertEquals(1, pages.size)
        assertEquals(1, pages.first().bubbles.size)
        assertEquals("hello", pages.first().bubbles.first().text)
    }

    private fun rect(index: Int): RectF {
        val top = index * 10f
        return RectF(0f, top, 100f, top + 8f)
    }
}

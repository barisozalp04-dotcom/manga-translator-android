package com.manga.translate

import android.graphics.RectF
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.PageOcrResult
import com.manga.translate.translation.withRecognizedTextBubblesOnly
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationPipelineCoreTest {
    @Test
    fun `page ocr result drops empty bubbles`() {
        val page = PageOcrResult(
            imageFile = File("page.jpg"),
            width = 1000,
            height = 1600,
            bubbles = listOf(
                OcrBubble(1, RectF(0f, 0f, 10f, 10f), "hello"),
                OcrBubble(2, RectF(0f, 0f, 10f, 10f), "")
            )
        )

        val filtered = page.withRecognizedTextBubblesOnly()

        assertEquals(1, filtered.bubbles.size)
    }

}

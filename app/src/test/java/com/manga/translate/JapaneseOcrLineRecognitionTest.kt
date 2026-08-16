package com.manga.translate

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.manga.translate.ocr.OcrEngine
import com.manga.translate.ocr.recognizeJapaneseLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JapaneseOcrLineRecognitionTest {

    @Test
    fun `vertical line is rotated counterclockwise before recognition`() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(5, 1, Color.RED)
        }
        val rect = RectF(2f, 1f, 6f, 7f)
        val engine = RecordingOcrEngine(
            ArrayDeque(listOf(OcrEngine.OcrEngineResult("縦", 0.9f)))
        )

        try {
            val lines = recognizeJapaneseLines(source, listOf(rect), engine)

            assertEquals(listOf("縦"), lines.map { it.text })
            assertEquals(1, engine.calls.size)
            assertEquals(6, engine.calls.single().width)
            assertEquals(4, engine.calls.single().height)
            assertEquals(Color.RED, engine.calls.single().topLeftPixel)
            assertNull(engine.calls.single().rect)
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `vertical Japanese columns are recognized from right to left`() {
        val source = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        val left = RectF(2f, 1f, 6f, 7f)
        val right = RectF(12f, 1f, 16f, 7f)
        val engine = RecordingOcrEngine(
            ArrayDeque(
                listOf(
                    OcrEngine.OcrEngineResult("右", 0.9f),
                    OcrEngine.OcrEngineResult("左", 0.9f)
                )
            )
        )

        try {
            val lines = recognizeJapaneseLines(source, listOf(left, right), engine)

            assertEquals(listOf(right, left), lines.map { it.rect })
            assertEquals(listOf("右", "左"), lines.map { it.text })
            assertTrue(engine.calls.all { it.rect == null })
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `rotated line still respects the recognition score threshold`() {
        val source = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        val engine = RecordingOcrEngine(
            ArrayDeque(listOf(OcrEngine.OcrEngineResult("誤", 0.49f)))
        )

        try {
            val lines = recognizeJapaneseLines(
                source,
                listOf(RectF(0f, 0f, 4f, 6f)),
                engine
            )

            assertTrue(lines.isEmpty())
        } finally {
            source.recycle()
        }
    }

    private class RecordingOcrEngine(
        private val results: ArrayDeque<OcrEngine.OcrEngineResult>
    ) : OcrEngine {
        val calls = mutableListOf<Call>()

        override fun recognize(bitmap: Bitmap): String {
            error("recognizeWithScore is expected")
        }

        override fun recognizeWithScore(
            bitmap: Bitmap,
            rect: RectF?
        ): OcrEngine.OcrEngineResult {
            calls += Call(
                width = bitmap.width,
                height = bitmap.height,
                topLeftPixel = bitmap.getPixel(0, 0),
                rect = rect?.let(::RectF)
            )
            return results.removeFirst()
        }
    }

    private data class Call(
        val width: Int,
        val height: Int,
        val topLeftPixel: Int,
        val rect: RectF?
    )
}

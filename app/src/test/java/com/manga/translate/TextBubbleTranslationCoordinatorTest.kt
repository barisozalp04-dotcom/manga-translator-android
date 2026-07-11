package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TextBubbleTranslationCoordinatorTest {
    @Test
    fun `source echo raises model response error`() {
        val gateway = FakeLlmGateway(
            LlmBubbleTranslationResult(
                items = listOf(LlmBubbleTranslationItem(1, " 漫画\n原文 ")),
                glossaryUsed = emptyMap()
            )
        )
        val coordinator = TextBubbleTranslationCoordinator(gateway)

        val error = assertThrows(LlmResponseException::class.java) {
            runBlocking {
                coordinator.translateBubbles(
                    bubbles = listOf(pendingBubble(1, "漫画原文")),
                    glossary = emptyMap(),
                    promptAsset = "prompts/llm_prompts.json",
                    logTag = "Test",
                    translationMode = "standard"
                )
            }
        }

        assertEquals(LlmErrorCode.MissingTranslationItems, error.errorCode)
    }

    @Test
    fun `missing response item raises model response error`() {
        val gateway = FakeLlmGateway(
            LlmBubbleTranslationResult(
                items = listOf(LlmBubbleTranslationItem(1, "译文一")),
                glossaryUsed = emptyMap()
            )
        )
        val coordinator = TextBubbleTranslationCoordinator(gateway)

        assertThrows(LlmResponseException::class.java) {
            runBlocking {
                coordinator.translateBubbles(
                    bubbles = listOf(
                        pendingBubble(1, "原文一"),
                        pendingBubble(2, "原文二")
                    ),
                    glossary = emptyMap(),
                    promptAsset = "prompts/llm_prompts.json",
                    logTag = "Test",
                    translationMode = "standard"
                )
            }
        }
    }

    private fun pendingBubble(id: Int, source: String): BubbleTranslation {
        return BubbleTranslation.pending(id, RectF(0f, 0f, 10f, 10f), originalText = source)
    }
}

private class FakeLlmGateway(
    private val result: LlmBubbleTranslationResult
) : LlmGateway {
    override fun isConfigured(apiSettings: ApiSettings?): Boolean = true

    override fun isOcrConfigured(): Boolean = true

    override suspend fun translateBubbleItems(
        items: List<LlmBubbleTranslationRequestItem>,
        glossary: Map<String, String>,
        promptAsset: String,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?
    ): LlmBubbleTranslationResult = result

    override suspend fun extractGlossary(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String
    ): Map<String, String> = emptyMap()

    override suspend fun recognizeImageText(
        image: Bitmap,
        language: TranslationLanguage
    ): String? = null

    override suspend fun translateImageBubble(
        imageBase64: String,
        promptAsset: String,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?
    ): String? = null

    override suspend fun recognizeFullPageWithBaidu(
        image: Bitmap,
        language: TranslationLanguage
    ): List<BaiduOcrWord>? = null

    override fun resourceContext(): Context = RuntimeEnvironment.getApplication()
}

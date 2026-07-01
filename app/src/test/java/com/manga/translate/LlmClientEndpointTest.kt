package com.manga.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmClientEndpointTest {
    @Test
    fun `openai compatible chat endpoint appends chat completions to v1 base`() {
        assertEquals(
            "https://api.siliconflow.cn/v1/chat/completions",
            LlmClient.buildOpenAiCompatibleChatEndpoint("https://api.siliconflow.cn/v1")
        )
    }

    @Test
    fun `openai compatible chat endpoint appends chat completions without forcing v1`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            LlmClient.buildOpenAiCompatibleChatEndpoint("https://open.bigmodel.cn/api/paas/v4")
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            LlmClient.buildOpenAiCompatibleChatEndpoint("https://ark.cn-beijing.volces.com/api/v3")
        )
    }

    @Test
    fun `openai compatible chat endpoint keeps full chat url`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            LlmClient.buildOpenAiCompatibleChatEndpoint(
                "https://open.bigmodel.cn/api/paas/v4/chat/completions"
            )
        )
    }

    @Test
    fun `openai compatible model list endpoint appends models without forcing v1`() {
        assertEquals(
            "https://api.siliconflow.cn/v1/models",
            LlmClient.buildOpenAiCompatibleModelsEndpoint("https://api.siliconflow.cn/v1")
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            LlmClient.buildOpenAiCompatibleModelsEndpoint("https://open.bigmodel.cn/api/paas/v4")
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/models",
            LlmClient.buildOpenAiCompatibleModelsEndpoint("https://ark.cn-beijing.volces.com/api/v3")
        )
    }

    @Test
    fun `openai compatible model list endpoint keeps full models url`() {
        assertEquals(
            "https://api.siliconflow.cn/v1/models",
            LlmClient.buildOpenAiCompatibleModelsEndpoint("https://api.siliconflow.cn/v1/models")
        )
    }
}
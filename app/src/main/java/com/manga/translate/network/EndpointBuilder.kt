package com.manga.translate.network

import com.manga.translate.model.ApiFormat
import com.manga.translate.settings.ApiSettings
import java.net.URLEncoder

/**
 * 端点/URL 解析：按 AGENTS.md 网络契约拼接各供应商端点。
 *
 * - OpenAI 兼容地址：已以 /chat/completions 结尾则原样使用，否则直接追加；
 *   不得自动插入 /v1。模型列表地址追加 /models。
 * - OpenAI Responses 地址追加 /responses。
 * - Gemini 生成与模型列表端点按 v1/v1beta 规则拼接并把 apiKey 作为查询参数。
 *
 * LlmClient 的 companion 公开函数（buildOpenAiCompatibleChatEndpoint 等）与本对象
 * 为同一实现，仅保留门面上的调用形式。
 */
internal object EndpointBuilder {
    fun buildOpenAiCompatibleChatEndpoint(baseUrl: String): String {
        val trimmed = normalizeOpenAiCompatibleBaseUrl(baseUrl)
        return if (trimmed.endsWith("/chat/completions", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed/chat/completions"
        }
    }

    fun buildOpenAiResponsesApiEndpoint(baseUrl: String): String {
        val trimmed = normalizeOpenAiCompatibleBaseUrl(baseUrl)
        return if (trimmed.endsWith("/responses", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed/responses"
        }
    }

    fun buildOpenAiCompatibleModelsEndpoint(baseUrl: String): String {
        val trimmed = normalizeOpenAiCompatibleBaseUrl(baseUrl)
        return if (trimmed.endsWith("/models", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed/models"
        }
    }

    fun buildEndpoint(settings: ApiSettings, modelName: String): String {
        return when (settings.apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> buildOpenAiCompatibleChatEndpoint(settings.apiUrl)
            ApiFormat.OPENAI_RESPONSES -> buildOpenAiResponsesApiEndpoint(settings.apiUrl)
            ApiFormat.GEMINI -> buildGeminiGenerateEndpoint(settings.apiUrl, modelName, settings.apiKey)
        }
    }

    fun buildGeminiGenerateEndpoint(baseUrl: String, modelName: String, apiKey: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val normalizedModel = normalizeGeminiModelName(modelName)
        val baseEndpoint = when {
            trimmed.contains(":generateContent") -> trimmed
            trimmed.endsWith("/v1beta") || trimmed.endsWith("/v1") -> {
                "$trimmed/$normalizedModel:generateContent"
            }
            else -> "$trimmed/v1beta/$normalizedModel:generateContent"
        }
        return appendApiKeyQuery(baseEndpoint, apiKey)
    }

    fun buildGeminiModelsEndpoint(baseUrl: String, apiKey: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val baseEndpoint = when {
            trimmed.endsWith("/models") -> trimmed
            trimmed.endsWith("/v1beta") || trimmed.endsWith("/v1") -> "$trimmed/models"
            else -> "$trimmed/v1beta/models"
        }
        return appendApiKeyQuery(baseEndpoint, apiKey)
    }

    fun redactEndpoint(endpoint: String): String =
        endpoint.replace(Regex("(\\?|&)key=[^&]*"), "$1key=***")

    private fun normalizeOpenAiCompatibleBaseUrl(baseUrl: String): String {
        return baseUrl.trim().trimEnd('/')
    }

    private fun normalizeGeminiModelName(modelName: String): String {
        val trimmed = modelName.trim().removePrefix("/")
        return if (trimmed.startsWith("models/")) trimmed else "models/$trimmed"
    }

    private fun appendApiKeyQuery(endpoint: String, apiKey: String): String {
        if (apiKey.isBlank()) return endpoint
        val separator = if (endpoint.contains("?")) "&" else "?"
        return endpoint + separator + "key=" + URLEncoder.encode(apiKey, Charsets.UTF_8.name())
    }
}

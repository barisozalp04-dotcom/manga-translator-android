package com.manga.translate.network

import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import kotlinx.coroutines.delay

/**
 * 重试判定与退避：重试次数来自 SettingsStore（loadApiRetryCount），
 * 可重试范围与原始实现一致——网络错误、超时、HTTP 408/429/5xx、明确的暂时不可用响应。
 * 退避逻辑（指数退避 + 上限 / 固定延迟）原样迁移，不新增网络行为。
 */
internal class RetryHandler(private val settingsStore: SettingsStore) {
    /** 门面传入默认 RETRY_COUNT 时改用 SettingsStore 的配置值，否则使用调用方显式值。 */
    fun buildRetryPolicy(retryCount: Int): RetryPolicy {
        val configuredRetryCount = settingsStore.loadApiRetryCount()
        return RetryPolicy(
            maxAttempts = if (retryCount == RETRY_COUNT) configuredRetryCount else retryCount.coerceAtLeast(1),
            mode = RetryMode.CONFIGURABLE
        )
    }

    suspend fun maybeBackoffBeforeRetry(
        attempt: Int,
        retryPolicy: RetryPolicy,
        errorCode: String?,
        errorBody: String?
    ) {
        if (attempt >= retryPolicy.maxAttempts || !shouldRetry(errorCode, errorBody, retryPolicy.mode)) {
            return
        }
        val delayMs = when (retryPolicy.mode) {
            RetryMode.DEFAULT -> (RETRY_BASE_DELAY_MS shl (attempt - 1)).coerceAtMost(RETRY_MAX_DELAY_MS)
            RetryMode.CONFIGURABLE -> CONFIGURED_RETRY_DELAY_MS
        }
        AppLogger.log(
            "LlmClient",
            "Retrying request after ${delayMs}ms delay (attempt ${attempt + 1}/${retryPolicy.maxAttempts}, error=$errorCode)"
        )
        delay(delayMs.toLong())
    }

    private fun shouldRetry(
        errorCode: String?,
        errorBody: String?,
        mode: RetryMode
    ): Boolean {
        return when (mode) {
            RetryMode.DEFAULT -> shouldRetryWithBackoff(errorCode)
            RetryMode.CONFIGURABLE -> shouldRetryWithConfiguredMode(errorCode, errorBody)
        }
    }

    private fun shouldRetryWithBackoff(errorCode: String?): Boolean {
        if (errorCode == null) return false
        if (errorCode == "TIMEOUT" || errorCode == "NETWORK_ERROR" || errorCode == "HTTP 408" || errorCode == "HTTP 429") {
            return true
        }
        if (!errorCode.startsWith("HTTP ")) {
            return false
        }
        val status = errorCode.removePrefix("HTTP ").toIntOrNull() ?: return false
        return status >= 500
    }

    private fun shouldRetryWithConfiguredMode(errorCode: String?, errorBody: String?): Boolean {
        if (errorCode == null) return false
        if (errorCode == "TIMEOUT" || errorCode == "NETWORK_ERROR" || errorCode == "HTTP 408" || errorCode == "HTTP 429") {
            return true
        }
        val status = errorCode.removePrefix("HTTP ").toIntOrNull()
        if (status != null && status >= 500) {
            return true
        }
        if (errorBody != null) {
            val normalizedBody = errorBody.lowercase()
            if (
                normalizedBody.contains("temporarily unavailable") ||
                normalizedBody.contains("temporary unavailable") ||
                normalizedBody.contains("service unavailable") ||
                normalizedBody.contains("try again later") ||
                normalizedBody.contains("server busy") ||
                normalizedBody.contains("overloaded")
            ) {
                return true
            }
        }
        return false
    }

    companion object {
        const val RETRY_COUNT = 3
        const val RETRY_BASE_DELAY_MS = 750
        const val RETRY_MAX_DELAY_MS = 4_000
        const val CONFIGURED_RETRY_DELAY_MS = 3_000
    }
}

internal data class RetryPolicy(
    val maxAttempts: Int,
    val mode: RetryMode
)

internal enum class RetryMode {
    DEFAULT,
    CONFIGURABLE
}

package com.manga.translate.network

import com.manga.translate.model.ApiFormat
import com.manga.translate.settings.ApiSettings
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * 纯传输职责：OkHttp 请求头组装、执行、超时、取消与按超时缓存连接配置的客户端池。
 *
 * 超时/重试策略的实际取值与原始 LlmClient 实现完全一致，不在此新增网络行为。
 */
internal class HttpTransport {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseHttpClient = OkHttpClient()
    private val httpClientCache = object : LinkedHashMap<Int, OkHttpClient>(MAX_CACHED_HTTP_CLIENTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, OkHttpClient>?): Boolean {
            return size > MAX_CACHED_HTTP_CLIENTS
        }
    }

    /** JSON POST 请求：统一 Content-Type 头，OpenAI 兼容/Responses 格式附加 Bearer 鉴权头。 */
    fun buildJsonPostRequest(
        endpoint: String,
        payload: JSONObject,
        settings: ApiSettings
    ): Request {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
        if (settings.apiFormat.usesOpenAiAuth) {
            requestBuilder.header("Authorization", "Bearer ${settings.apiKey}")
        }
        return requestBuilder.build()
    }

    /** 模型列表 GET 请求：仅 OpenAI 兼容/Responses 格式且 apiKey 非空时附加 Bearer 鉴权头。 */
    fun buildModelListRequest(endpoint: String, apiKey: String, apiFormat: ApiFormat): Request {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .get()
            .header("Content-Type", "application/json")
        if (apiFormat.usesOpenAiAuth && apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        return requestBuilder.build()
    }

    suspend fun executeRequest(request: Request, timeoutMs: Int): Response {
        val client = getOrBuildClient(timeoutMs)
        return executeCallCancellable(client.newCall(request))
    }

    private fun getOrBuildClient(timeoutMs: Int): OkHttpClient {
        return synchronized(httpClientCache) {
            httpClientCache.getOrPut(timeoutMs) {
                baseHttpClient.newBuilder()
                    .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .build()
            }
        }
    }

    private suspend fun executeCallCancellable(call: Call): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
            }
            try {
                val response = call.execute()
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            } catch (t: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(t)
                }
            }
        }
}

/** 动态并发限流：OCR 请求并发数来自 OcrApiSettings，默认至少 1。 */
internal class DynamicConcurrencyLimiter {
    private val mutex = Mutex()
    private var active = 0

    suspend fun <T> withPermit(limit: Int, block: suspend () -> T): T {
        val normalizedLimit = limit.coerceAtLeast(1)
        while (true) {
            val acquired = mutex.withLock {
                if (active < normalizedLimit) {
                    active++
                    true
                } else {
                    false
                }
            }
            if (acquired) break
            delay(10)
        }
        return try {
            block()
        } finally {
            mutex.withLock { active-- }
        }
    }
}

private const val MAX_CACHED_HTTP_CLIENTS = 4

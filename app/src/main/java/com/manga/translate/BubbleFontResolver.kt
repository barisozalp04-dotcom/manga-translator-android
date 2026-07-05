package com.manga.translate

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class BubbleFont(
    val prefValue: String,
    val labelRes: Int
) {
    SYSTEM_DEFAULT("system_default", R.string.bubble_font_system_default),
    SYSTEM_SANS_SERIF("system_sans_serif", R.string.bubble_font_system_sans_serif),
    SYSTEM_SERIF("system_serif", R.string.bubble_font_system_serif),
    SYSTEM_MONOSPACE("system_monospace", R.string.bubble_font_system_monospace),
    GOOGLE_NOTO_SANS_SC("google_noto_sans_sc", R.string.bubble_font_google_noto_sans_sc),
    GOOGLE_NOTO_SERIF_SC("google_noto_serif_sc", R.string.bubble_font_google_noto_serif_sc),
    CUSTOM_URL("custom_url", R.string.bubble_font_custom_url),
    CUSTOM_FILE("custom_file", R.string.bubble_font_custom_file);

    companion object {
        fun fromPref(value: String?): BubbleFont {
            return entries.firstOrNull { it.prefValue == value } ?: SYSTEM_DEFAULT
        }
    }
}

object BubbleFontResolver {

    private const val CUSTOM_FONT_DIR = "custom_fonts"
    private const val CUSTOM_FILE_NAME = "bubble_custom_font.ttf"
    private const val DOWNLOAD_TIMEOUT_MS = 30_000L

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    fun getCustomFontFile(context: Context): File {
        return File(context.getDir(CUSTOM_FONT_DIR, Context.MODE_PRIVATE), CUSTOM_FILE_NAME)
    }

    fun resolveTypeface(context: Context, font: BubbleFont): Typeface {
        return resolveTypefaceInternal(context, font, getCustomFontFile(context))
    }

    suspend fun ensureTypeface(
        context: Context,
        font: BubbleFont,
        customUrl: String?
    ): Typeface {
        return when (font) {
            BubbleFont.CUSTOM_URL -> {
                val url = customUrl?.trim()
                if (!url.isNullOrBlank()) {
                    downloadAndLoadTypeface(context, url)
                } else {
                    resolveTypefaceInternal(context, BubbleFont.SYSTEM_DEFAULT, getCustomFontFile(context))
                }
            }
            BubbleFont.CUSTOM_FILE -> {
                val file = getCustomFontFile(context)
                if (file.exists() && file.length() > 0L) {
                    loadTypefaceFromFile(file)
                } else {
                    resolveTypefaceInternal(context, BubbleFont.SYSTEM_DEFAULT, getCustomFontFile(context))
                }
            }
            BubbleFont.GOOGLE_NOTO_SANS_SC -> fetchDownloadableFont(
                context,
                "Noto Sans SC",
                R.array.com_google_android_gms_fonts_certs
            )
            BubbleFont.GOOGLE_NOTO_SERIF_SC -> fetchDownloadableFont(
                context,
                "Noto Serif SC",
                R.array.com_google_android_gms_fonts_certs
            )
            else -> resolveTypefaceInternal(context, font, getCustomFontFile(context))
        }
    }

    private fun resolveTypefaceInternal(
        context: Context,
        font: BubbleFont,
        customFontFile: File
    ): Typeface {
        return when (font) {
            BubbleFont.SYSTEM_SANS_SERIF -> Typeface.SANS_SERIF
            BubbleFont.SYSTEM_SERIF -> Typeface.SERIF
            BubbleFont.SYSTEM_MONOSPACE -> Typeface.MONOSPACE
            BubbleFont.GOOGLE_NOTO_SANS_SC,
            BubbleFont.GOOGLE_NOTO_SERIF_SC -> {
                val family = if (font == BubbleFont.GOOGLE_NOTO_SANS_SC) "sans-serif" else "serif"
                Typeface.create(family, Typeface.NORMAL)
            }
            BubbleFont.CUSTOM_FILE -> {
                if (customFontFile.exists() && customFontFile.length() > 0L) {
                    loadTypefaceFromFile(customFontFile)
                } else {
                    Typeface.DEFAULT
                }
            }
            BubbleFont.CUSTOM_URL -> Typeface.DEFAULT
            BubbleFont.SYSTEM_DEFAULT -> Typeface.DEFAULT
        }
    }

    private fun loadTypefaceFromFile(file: File): Typeface {
        return try {
            Typeface.createFromFile(file)
        } catch (e: Exception) {
            AppLogger.log("BubbleFontResolver", "Failed to load typeface from file ${file.path}", e)
            Typeface.DEFAULT
        }
    }

    private suspend fun fetchDownloadableFont(
        context: Context,
        query: String,
        certsResId: Int
    ): Typeface = withContext(Dispatchers.IO) {
        val certs = readFontCertificates(context.resources, certsResId)
        val request = FontRequest(
            "com.google.android.gms.fonts",
            "com.google.android.gms",
            query,
            certs
        )
        val result = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : FontsContractCompat.FontRequestCallback() {
                    override fun onTypefaceRetrieved(typeface: Typeface) {
                        continuation.resume(typeface)
                    }

                    override fun onTypefaceRequestFailed(reason: Int) {
                        continuation.resumeWithException(
                            IOException("Downloadable font request failed: $reason")
                        )
                    }
                }
                FontsContractCompat.requestFont(
                    context.applicationContext,
                    request,
                    callback,
                    Handler(Looper.getMainLooper())
                )
                continuation.invokeOnCancellation {
                    // Cancellation is best-effort; the callback may still fire.
                }
            }
        }
        result ?: Typeface.DEFAULT
    }

    private fun readFontCertificates(resources: Resources, certsArrayResId: Int): List<List<ByteArray>> {
        val outerTypedArray = resources.obtainTypedArray(certsArrayResId)
        return try {
            (0 until outerTypedArray.length()).map { index ->
                val innerResId = outerTypedArray.getResourceId(index, 0)
                if (innerResId == 0) {
                    emptyList<ByteArray>()
                } else {
                    resources.getStringArray(innerResId).map { certString ->
                        certString.replace("\\s+".toRegex(), "").toByteArray(Charsets.UTF_8)
                    }
                }
            }
        } finally {
            outerTypedArray.recycle()
        }
    }

    private suspend fun downloadAndLoadTypeface(context: Context, url: String): Typeface {
        return withContext(Dispatchers.IO) {
            val destFile = getCustomFontFile(context)
            try {
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Font download failed: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty font response body")
                    destFile.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                loadTypefaceFromFile(destFile)
            } catch (e: Exception) {
                AppLogger.log("BubbleFontResolver", "Failed to download font from $url", e)
                if (destFile.exists() && destFile.length() > 0L) {
                    loadTypefaceFromFile(destFile)
                } else {
                    Typeface.DEFAULT
                }
            }
        }
    }
}

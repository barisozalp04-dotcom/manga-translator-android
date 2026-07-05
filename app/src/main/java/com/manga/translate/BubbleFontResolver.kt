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
    private const val DOWNLOAD_TIMEOUT_MS = 30_000L
    private const val URL_FONT_KIND = "url"
    private const val FILE_FONT_KIND = "file"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private data class CustomFontTarget(
        val file: File? = null,
        val assetPath: String? = null
    )

    private fun managedFontFile(context: Context, tag: String, kind: String): File {
        return File(
            context.getDir(CUSTOM_FONT_DIR, Context.MODE_PRIVATE),
            "bubble_custom_font_${tag}_${kind}.ttf"
        )
    }

    private fun downloadedFontFile(context: Context, tag: String): File {
        return managedFontFile(context, tag, URL_FONT_KIND)
    }

    private fun stagedCustomFontFile(context: Context, tag: String): File {
        return managedFontFile(context, tag, FILE_FONT_KIND)
    }

    private fun markerFile(fontFile: File, suffix: String): File {
        return File(fontFile.parentFile, fontFile.name + suffix)
    }

    private fun readMarker(file: File): String? {
        return runCatching { file.readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun resolveTypeface(
        context: Context,
        font: BubbleFont,
        customUrl: String? = null,
        customFileName: String? = null,
        tag: String = "normal"
    ): Typeface {
        return when (font) {
            BubbleFont.CUSTOM_URL -> {
                val url = customUrl?.trim().orEmpty()
                val cacheFile = downloadedFontFile(context, tag)
                val storedUrl = readMarker(markerFile(cacheFile, ".url"))
                if (url.isNotBlank() && storedUrl == url && cacheFile.exists() && cacheFile.length() > 0L) {
                    loadTypefaceFromFile(cacheFile)
                } else {
                    Typeface.DEFAULT
                }
            }
            BubbleFont.CUSTOM_FILE -> {
                resolveCustomFileTarget(context, customFileName)?.let { target ->
                    when {
                        target.file != null -> loadTypefaceFromFile(target.file)
                        target.assetPath != null -> {
                            val stagedFile = stagedCustomFontFile(context, tag)
                            val storedAssetPath = readMarker(markerFile(stagedFile, ".asset"))
                            if (
                                storedAssetPath == target.assetPath &&
                                stagedFile.exists() &&
                                stagedFile.length() > 0L
                            ) {
                                loadTypefaceFromFile(stagedFile)
                            } else {
                                Typeface.DEFAULT
                            }
                        }
                        else -> Typeface.DEFAULT
                    }
                } ?: Typeface.DEFAULT
            }
            else -> resolveTypefaceInternal(font)
        }
    }

    suspend fun ensureTypeface(
        context: Context,
        font: BubbleFont,
        customUrl: String?,
        customFileName: String? = null,
        tag: String = "normal"
    ): Typeface {
        return when (font) {
            BubbleFont.CUSTOM_URL -> {
                val url = customUrl?.trim()
                if (!url.isNullOrBlank()) {
                    downloadAndLoadTypeface(context, url, tag)
                } else {
                    Typeface.DEFAULT
                }
            }
            BubbleFont.CUSTOM_FILE -> {
                when (val target = resolveCustomFileTarget(context, customFileName)) {
                    null -> Typeface.DEFAULT
                    else -> when {
                        target.file != null -> loadTypefaceFromFile(target.file)
                        target.assetPath != null -> stageAssetFontAndLoad(context, target.assetPath, tag)
                        else -> Typeface.DEFAULT
                    }
                }
            }
            BubbleFont.GOOGLE_NOTO_SANS_SC -> fetchDownloadableFont(
                context,
                "Noto Sans SC",
                R.array.com_google_android_gms_fonts_certs,
                Typeface.SANS_SERIF
            )
            BubbleFont.GOOGLE_NOTO_SERIF_SC -> fetchDownloadableFont(
                context,
                "Noto Serif SC",
                R.array.com_google_android_gms_fonts_certs,
                Typeface.SERIF
            )
            else -> resolveTypefaceInternal(font)
        }
    }

    private fun resolveTypefaceInternal(font: BubbleFont): Typeface {
        return when (font) {
            BubbleFont.SYSTEM_SANS_SERIF -> Typeface.SANS_SERIF
            BubbleFont.SYSTEM_SERIF -> Typeface.SERIF
            BubbleFont.SYSTEM_MONOSPACE -> Typeface.MONOSPACE
            BubbleFont.GOOGLE_NOTO_SANS_SC,
            BubbleFont.GOOGLE_NOTO_SERIF_SC -> {
                val family = if (font == BubbleFont.GOOGLE_NOTO_SANS_SC) "sans-serif" else "serif"
                Typeface.create(family, Typeface.NORMAL)
            }
            BubbleFont.CUSTOM_FILE,
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
        certsResId: Int,
        fallbackTypeface: Typeface
    ): Typeface = withContext(Dispatchers.IO) {
        try {
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
            result ?: fallbackTypeface
        } catch (e: Exception) {
            AppLogger.log("BubbleFontResolver", "Failed to fetch downloadable font: $query", e)
            fallbackTypeface
        }
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
                        Base64.decode(certString.replace("\\s+".toRegex(), ""), Base64.DEFAULT)
                    }
                }
            }
        } finally {
            outerTypedArray.recycle()
        }
    }

    private fun resolveCustomFileTarget(context: Context, customFileName: String?): CustomFontTarget? {
        val trimmed = customFileName?.trim().orEmpty()
        if (trimmed.isBlank()) return null

        resolvePrivateFontFile(context, trimmed)?.let { file ->
            return CustomFontTarget(file = file)
        }

        resolveAssetFontPath(context, trimmed)?.let { assetPath ->
            return CustomFontTarget(assetPath = assetPath)
        }

        return null
    }

    private fun resolvePrivateFontFile(context: Context, customFileName: String): File? {
        val directFile = File(customFileName)
        if (directFile.isAbsolute && directFile.isFile && directFile.length() > 0L) {
            return directFile
        }

        val fontDir = context.getDir(CUSTOM_FONT_DIR, Context.MODE_PRIVATE)
        val baseName = File(customFileName).name
        val candidates = linkedSetOf(
            File(fontDir, customFileName),
            File(fontDir, baseName),
            File(context.filesDir, customFileName),
            File(context.filesDir, baseName),
            File(context.cacheDir, customFileName),
            File(context.cacheDir, baseName)
        )
        if (customFileName.startsWith("files/")) {
            candidates += File(context.filesDir, customFileName.removePrefix("files/"))
        }
        if (customFileName.startsWith("cache/")) {
            candidates += File(context.cacheDir, customFileName.removePrefix("cache/"))
        }
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
    }

    private fun resolveAssetFontPath(context: Context, customFileName: String): String? {
        val normalized = customFileName.removePrefix("assets/").trimStart('/')
        val candidates = linkedSetOf(normalized, customFileName)
        return candidates.firstOrNull { path ->
            path.isNotBlank() && runCatching {
                context.assets.open(path).close()
                true
            }.getOrDefault(false)
        }
    }

    private suspend fun stageAssetFontAndLoad(
        context: Context,
        assetPath: String,
        tag: String
    ): Typeface = withContext(Dispatchers.IO) {
        val destFile = stagedCustomFontFile(context, tag)
        try {
            destFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            markerFile(destFile, ".asset").writeText(assetPath)
            loadTypefaceFromFile(destFile)
        } catch (e: Exception) {
            AppLogger.log("BubbleFontResolver", "Failed to stage font asset: $assetPath", e)
            val storedAssetPath = readMarker(markerFile(destFile, ".asset"))
            if (storedAssetPath == assetPath && destFile.exists() && destFile.length() > 0L) {
                loadTypefaceFromFile(destFile)
            } else {
                Typeface.DEFAULT
            }
        }
    }

    private suspend fun downloadAndLoadTypeface(context: Context, url: String, tag: String = "normal"): Typeface {
        return withContext(Dispatchers.IO) {
            val destFile = downloadedFontFile(context, tag)
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
                markerFile(destFile, ".url").writeText(url)
                loadTypefaceFromFile(destFile)
            } catch (e: Exception) {
                AppLogger.log("BubbleFontResolver", "Failed to download font from $url", e)
                val storedUrl = readMarker(markerFile(destFile, ".url"))
                if (destFile.exists() && destFile.length() > 0L && storedUrl == url) {
                    loadTypefaceFromFile(destFile)
                } else {
                    Typeface.DEFAULT
                }
            }
        }
    }
}

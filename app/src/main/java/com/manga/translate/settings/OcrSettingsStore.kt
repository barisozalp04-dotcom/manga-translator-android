package com.manga.translate.settings

import com.manga.translate.model.OcrApiFormat

internal class OcrSettingsStore(
    private val storage: SettingsStoreStorage
) {
    fun loadOcrApiSettings(): OcrApiSettings {
        val apiFormatPref = storage.prefs.getString(SettingsStore.KEY_OCR_API_FORMAT, null)
        val hasUnsupportedApiFormat = OcrApiFormat.isUnsupportedPref(apiFormatPref)
        val useLocal = storage.prefs.getBoolean(SettingsStore.KEY_OCR_USE_LOCAL, true) ||
            hasUnsupportedApiFormat
        val url = if (hasUnsupportedApiFormat) {
            ""
        } else {
            storage.prefs.getString(
                SettingsStore.KEY_OCR_API_URL,
                SettingsStore.DEFAULT_OCR_API_URL
            ) ?: SettingsStore.DEFAULT_OCR_API_URL
        }
        val key = if (hasUnsupportedApiFormat) {
            ""
        } else {
            storage.prefs.getString(SettingsStore.KEY_OCR_API_KEY, "") ?: ""
        }
        val model = if (hasUnsupportedApiFormat) {
            ""
        } else {
            storage.prefs.getString(
                SettingsStore.KEY_OCR_MODEL_NAME,
                SettingsStore.DEFAULT_OCR_MODEL_NAME
            ) ?: SettingsStore.DEFAULT_OCR_MODEL_NAME
        }
        val timeoutSeconds = storage.prefs.getInt(
            SettingsStore.KEY_OCR_API_TIMEOUT_SECONDS,
            SettingsStore.DEFAULT_OCR_API_TIMEOUT_SECONDS
        ).coerceIn(
            SettingsStore.MIN_OCR_API_TIMEOUT_SECONDS,
            SettingsStore.MAX_OCR_API_TIMEOUT_SECONDS
        )
        return OcrApiSettings(
            useLocalOcr = useLocal,
            apiUrl = url,
            apiKey = key,
            modelName = model,
            timeoutSeconds = timeoutSeconds,
            apiOcrConcurrencyLimit = storage.prefs.getInt(
                SettingsStore.KEY_OCR_API_CONCURRENCY,
                SettingsStore.DEFAULT_OCR_API_CONCURRENCY
            ).coerceIn(
                SettingsStore.MIN_OCR_API_CONCURRENCY,
                SettingsStore.MAX_OCR_API_CONCURRENCY
            ),
            localOcrConcurrencyLimit = storage.prefs.getInt(
                SettingsStore.KEY_LOCAL_OCR_CONCURRENCY,
                SettingsStore.DEFAULT_LOCAL_OCR_CONCURRENCY
            ).coerceIn(
                SettingsStore.MIN_LOCAL_OCR_CONCURRENCY,
                SettingsStore.MAX_LOCAL_OCR_CONCURRENCY
            ),
            ocrApiFormat = OcrApiFormat.fromPref(apiFormatPref)
        )
    }

    fun saveOcrApiSettings(settings: OcrApiSettings) {
        val normalizedTimeout = settings.timeoutSeconds.coerceIn(
            SettingsStore.MIN_OCR_API_TIMEOUT_SECONDS,
            SettingsStore.MAX_OCR_API_TIMEOUT_SECONDS
        )
        val normalizedConcurrency = settings.apiOcrConcurrencyLimit.coerceIn(
            SettingsStore.MIN_OCR_API_CONCURRENCY,
            SettingsStore.MAX_OCR_API_CONCURRENCY
        )
        val normalizedLocalConcurrency = settings.localOcrConcurrencyLimit.coerceIn(
            SettingsStore.MIN_LOCAL_OCR_CONCURRENCY,
            SettingsStore.MAX_LOCAL_OCR_CONCURRENCY
        )
        storage.editSettings(
            setOf(
                SettingsStore.KEY_OCR_USE_LOCAL,
                SettingsStore.KEY_OCR_API_URL,
                SettingsStore.KEY_OCR_API_KEY,
                SettingsStore.KEY_OCR_MODEL_NAME,
                SettingsStore.KEY_OCR_API_TIMEOUT_SECONDS,
                SettingsStore.KEY_OCR_API_CONCURRENCY,
                SettingsStore.KEY_LOCAL_OCR_CONCURRENCY,
                SettingsStore.KEY_OCR_API_FORMAT
            )
        ) {
            putBoolean(SettingsStore.KEY_OCR_USE_LOCAL, settings.useLocalOcr)
                .putString(SettingsStore.KEY_OCR_API_URL, settings.apiUrl)
                .putString(SettingsStore.KEY_OCR_API_KEY, settings.apiKey)
                .putString(SettingsStore.KEY_OCR_MODEL_NAME, settings.modelName)
                .putInt(SettingsStore.KEY_OCR_API_TIMEOUT_SECONDS, normalizedTimeout)
                .putInt(SettingsStore.KEY_OCR_API_CONCURRENCY, normalizedConcurrency)
                .putInt(SettingsStore.KEY_LOCAL_OCR_CONCURRENCY, normalizedLocalConcurrency)
                .putString(
                    SettingsStore.KEY_OCR_API_FORMAT,
                    settings.ocrApiFormat.prefValue
                )
        }
    }
}

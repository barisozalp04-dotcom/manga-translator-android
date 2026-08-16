package com.manga.translate.model

import androidx.annotation.StringRes
import com.manga.translate.R

enum class OcrApiFormat(
    val prefValue: String,
    @param:StringRes val labelRes: Int
) {
    OPENAI_COMPATIBLE("openai_compatible", R.string.ocr_api_format_openai_compatible);

    companion object {
        fun fromPref(value: String?): OcrApiFormat {
            return entries.firstOrNull { it.prefValue == value } ?: OPENAI_COMPATIBLE
        }

        fun isUnsupportedPref(value: String?): Boolean {
            return value != null && entries.none { it.prefValue == value }
        }
    }
}

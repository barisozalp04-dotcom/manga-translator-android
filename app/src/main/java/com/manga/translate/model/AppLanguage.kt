package com.manga.translate.model

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import com.manga.translate.R
import java.util.Locale

enum class AppLanguage(
    val prefValue: String,
    @param:StringRes val labelRes: Int,
    private val languageTags: String?
) {
    FOLLOW_SYSTEM("follow_system", R.string.language_follow_system, null),
    SIMPLIFIED_CHINESE("zh_hans", R.string.language_simplified_chinese, "zh-Hans"),
    TRADITIONAL_CHINESE("zh_hant", R.string.language_traditional_chinese, "zh-Hant"),
    ENGLISH("en", R.string.language_english, "en"),
    RUSSIAN("ru", R.string.language_russian, "ru"),
    PORTUGUESE_BRAZIL("pt_br", R.string.language_portuguese_brazil, "pt-BR");

    fun toLocales(): LocaleListCompat {
        return if (languageTags.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTags)
        }
    }

    fun resolveApplicationLocales(context: Context): LocaleListCompat {
        return resolveApplicationLocales(LocaleManagerCompat.getSystemLocales(context))
    }

    internal fun resolveApplicationLocales(systemLocales: LocaleListCompat): LocaleListCompat {
        if (this != FOLLOW_SYSTEM) return toLocales()
        return if (hasSupportedSystemLanguage(systemLocales)) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            ENGLISH.toLocales()
        }
    }

    companion object {
        fun fromPref(value: String?): AppLanguage {
            return entries.firstOrNull { it.prefValue == value } ?: FOLLOW_SYSTEM
        }

        internal fun hasSupportedSystemLanguage(systemLocales: LocaleListCompat): Boolean {
            val tags = systemLocales.toLanguageTags()
            if (tags.isBlank()) return false
            return tags
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .any(::isSupportedLanguageTag)
        }

        private fun isSupportedLanguageTag(tag: String): Boolean {
            val locale = Locale.forLanguageTag(tag)
            val language = locale.language.lowercase(Locale.ROOT)
            return language in SUPPORTED_SYSTEM_LANGUAGES ||
                (language == "pt" && locale.country.equals("BR", ignoreCase = true))
        }

        private val SUPPORTED_SYSTEM_LANGUAGES = setOf("zh", "en", "ru")
    }
}

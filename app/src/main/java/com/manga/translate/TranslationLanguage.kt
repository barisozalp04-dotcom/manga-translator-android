package com.manga.translate

enum class TranslationLanguage(
    val prefValue: String,
    val displayNameResId: Int,
    val baiduLanguageType: String
) {
    JA_TO_ZH("ja_to_zh", R.string.folder_language_ja_to_zh, "JAP"),
    EN_TO_ZH("en_to_zh", R.string.folder_language_en_to_zh, "ENG"),
    KO_TO_ZH("ko_to_zh", R.string.folder_language_ko_to_zh, "KOR"),
    CHN_ENG_TO_ZH("chn_eng_to_zh", R.string.folder_language_chn_eng_to_zh, "CHN_ENG"),
    FR_TO_ZH("fr_to_zh", R.string.folder_language_fr_to_zh, "FRE"),
    ES_TO_ZH("es_to_zh", R.string.folder_language_es_to_zh, "SPA"),
    PT_TO_ZH("pt_to_zh", R.string.folder_language_pt_to_zh, "POR"),
    DE_TO_ZH("de_to_zh", R.string.folder_language_de_to_zh, "GER"),
    IT_TO_ZH("it_to_zh", R.string.folder_language_it_to_zh, "ITA"),
    RU_TO_ZH("ru_to_zh", R.string.folder_language_ru_to_zh, "RUS");

    fun supportsLocalOcr(): Boolean {
        return when (this) {
            JA_TO_ZH, EN_TO_ZH, KO_TO_ZH, FR_TO_ZH, ES_TO_ZH, PT_TO_ZH, DE_TO_ZH, IT_TO_ZH -> true
            CHN_ENG_TO_ZH, RU_TO_ZH -> false
        }
    }

    fun usesLatinOcr(): Boolean {
        return this in setOf(EN_TO_ZH, FR_TO_ZH, ES_TO_ZH, PT_TO_ZH, DE_TO_ZH, IT_TO_ZH)
    }

    companion object {
        fun fromPref(value: String?): TranslationLanguage {
            return entries.firstOrNull { it.prefValue == value || it.name == value } ?: JA_TO_ZH
        }

        fun fromString(value: String?): TranslationLanguage = fromPref(value)

        fun supportedForOcr(useLocalOcr: Boolean): List<TranslationLanguage> {
            return if (useLocalOcr) {
                entries.filter { it.supportsLocalOcr() }
            } else {
                entries
            }
        }

        fun resolveForOcr(language: TranslationLanguage, useLocalOcr: Boolean): TranslationLanguage {
            return if (!useLocalOcr || language.supportsLocalOcr()) {
                language
            } else {
                JA_TO_ZH
            }
        }
    }
}

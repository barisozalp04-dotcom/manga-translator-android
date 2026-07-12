package com.manga.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {

    @Test
    fun `resolveChangelogLanguageKey maps locales`() {
        assertEquals("en", UpdateChecker.resolveChangelogLanguageKey(Locale.ENGLISH))
        assertEquals("ru", UpdateChecker.resolveChangelogLanguageKey(Locale.forLanguageTag("ru-RU")))
        assertEquals("zh_hans", UpdateChecker.resolveChangelogLanguageKey(Locale.SIMPLIFIED_CHINESE))
        assertEquals(
            "zh_hant",
            UpdateChecker.resolveChangelogLanguageKey(Locale.forLanguageTag("zh-Hant-TW"))
        )
        assertEquals(
            "zh_hant",
            UpdateChecker.resolveChangelogLanguageKey(Locale.forLanguageTag("zh-TW"))
        )
        assertEquals("zh_hans", UpdateChecker.resolveChangelogLanguageKey(Locale.FRENCH))
    }

    @Test
    fun `resolveLocalizedChangelog prefers language field and falls back to default`() {
        val json = JSONObject(
            """
            {
              "changelog": "简体日志",
              "changelog_hant": "繁體日誌",
              "changelog_en": "English notes",
              "changelog_ru": "Русские заметки"
            }
            """.trimIndent()
        )
        assertEquals("简体日志", UpdateChecker.resolveLocalizedChangelog(json, "zh_hans"))
        assertEquals("繁體日誌", UpdateChecker.resolveLocalizedChangelog(json, "zh_hant"))
        assertEquals("English notes", UpdateChecker.resolveLocalizedChangelog(json, "en"))
        assertEquals("Русские заметки", UpdateChecker.resolveLocalizedChangelog(json, "ru"))

        val partial = JSONObject("""{"changelog":"默认日志"}""")
        assertEquals("默认日志", UpdateChecker.resolveLocalizedChangelog(partial, "en"))
        assertEquals("默认日志", UpdateChecker.resolveLocalizedChangelog(partial, "zh_hant"))
        assertEquals("默认日志", UpdateChecker.resolveLocalizedChangelog(partial, "ru"))
    }
}

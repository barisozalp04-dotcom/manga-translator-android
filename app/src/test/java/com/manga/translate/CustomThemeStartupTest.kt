package com.manga.translate

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.app.MainActivity
import com.manga.translate.model.ThemeMode
import com.manga.translate.settings.SettingsStore
import com.manga.translate.theming.ThemePaletteRuntime
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomThemeStartupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ThemePaletteRuntime.clear()
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        ThemePaletteRuntime.clear()
    }

    @Test
    fun `custom mode starts with default colors when no custom colors were saved`() {
        SettingsStore(context).saveThemeMode(ThemeMode.CUSTOM)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        assertNotNull(controller.get())
        assertNotNull(ThemePaletteRuntime.customPalette)
    }
}

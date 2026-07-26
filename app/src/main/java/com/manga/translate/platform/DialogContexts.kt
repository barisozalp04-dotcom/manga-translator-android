package com.manga.translate.platform

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.manga.translate.R
import com.manga.translate.theming.ThemePaletteRuntime

internal fun createAlertDialogContext(context: Context): Context {
    val baseTheme = ThemePaletteRuntime.customPalette?.let { palette ->
        if (palette.isDark) R.style.Theme_MangaTranslator_Custom_Dark
        else R.style.Theme_MangaTranslator_Custom_Light
    } ?: R.style.Theme_MangaTranslator
    val appCompatContext = ContextThemeWrapper(context, baseTheme)
    return ContextThemeWrapper(appCompatContext, R.style.ThemeOverlay_MangaTranslator_AlertDialog)
}

internal fun createAlertDialogBuilder(context: Context): AlertDialog.Builder {
    return AlertDialog.Builder(createAlertDialogContext(context))
}

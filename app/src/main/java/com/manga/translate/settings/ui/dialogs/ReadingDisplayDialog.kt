package com.manga.translate.settings.ui.dialogs

import com.manga.translate.R
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Reading display mode selection dialog.
 */
internal class ReadingDisplayDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        showSingleChoiceSettingDialog(
            context = fragment.requireContext(),
            titleRes = R.string.reading_display_title,
            options = ReadingDisplayMode.entries,
            current = settingsStore.loadReadingDisplayMode(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            settingsStore.saveReadingDisplayMode(selected)
            fragment.updateReadingDisplayButton(selected)
            AppLogger.log("Settings", "Reading display mode set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }
}

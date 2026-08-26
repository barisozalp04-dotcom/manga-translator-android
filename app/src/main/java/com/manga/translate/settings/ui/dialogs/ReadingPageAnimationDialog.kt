package com.manga.translate.settings.ui.dialogs

import com.manga.translate.R
import com.manga.translate.model.ReadingPageAnimationMode
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Reading page animation mode selection dialog.
 */
internal class ReadingPageAnimationDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        showSingleChoiceSettingDialog(
            context = fragment.requireContext(),
            titleRes = R.string.reading_page_animation_title,
            options = ReadingPageAnimationMode.entries,
            current = settingsStore.loadReadingPageAnimationMode(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            settingsStore.saveReadingPageAnimationMode(selected)
            fragment.updateReadingPageAnimationButton(selected)
            AppLogger.log("Settings", "Reading page animation mode set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }
}

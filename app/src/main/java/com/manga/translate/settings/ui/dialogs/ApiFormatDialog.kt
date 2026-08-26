package com.manga.translate.settings.ui.dialogs

import com.manga.translate.R
import com.manga.translate.model.ApiFormat
import com.manga.translate.model.ThinkingLength
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * API format selection dialog.
 */
internal class ApiFormatDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        showSingleChoiceSettingDialog(
            context = fragment.requireContext(),
            titleRes = R.string.api_format_title,
            options = ApiFormat.entries,
            current = fragment.currentApiFormat(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            fragment.updateApiFormatButton(selected)
            fragment.updateApiSettingsNote(selected)
            ensureThinkingLengthCompatible(selected)
            fragment.updateThinkingLengthButton()
            AppLogger.log("Settings", "API format set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }

    private fun ensureThinkingLengthCompatible(format: ApiFormat) {
        val current = settingsStore.loadLlmParameters()
        val options = ThinkingLength.optionsFor(format)
        if (current.thinkingLength in options) return
        settingsStore.saveLlmParameters(current.copy(thinkingLength = ThinkingLength.DEFAULT))
    }
}

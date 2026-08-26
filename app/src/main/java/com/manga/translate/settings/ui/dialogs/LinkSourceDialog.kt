package com.manga.translate.settings.ui.dialogs

import com.manga.translate.R
import com.manga.translate.model.LinkSource
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Link source selection dialog.
 */
internal class LinkSourceDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        showSingleChoiceSettingDialog(
            context = fragment.requireContext(),
            titleRes = R.string.link_source_title,
            options = LinkSource.entries,
            current = settingsStore.loadLinkSource(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            settingsStore.saveLinkSource(selected)
            fragment.updateLinkSourceButton(selected)
            AppLogger.log("Settings", "Link source set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }
}

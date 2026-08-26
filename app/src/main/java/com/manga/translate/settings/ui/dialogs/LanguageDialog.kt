package com.manga.translate.settings.ui.dialogs

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.manga.translate.R
import com.manga.translate.model.AppLanguage
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * App language selection dialog.
 */
internal class LanguageDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        showSingleChoiceSettingDialog(
            context = fragment.requireContext(),
            titleRes = R.string.language_setting_title,
            options = AppLanguage.entries,
            current = settingsStore.loadAppLanguage(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            settingsStore.saveAppLanguage(selected)
            fragment.updateLanguageButton(selected)
            AppCompatDelegate.setApplicationLocales(
                selected.resolveApplicationLocales(fragment.requireContext())
            )
            AppLogger.log("Settings", "App language set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }

    // The following two helpers were part of the original SettingsFragment and
    // are currently unused; they are preserved verbatim to avoid changing
    // behavior during the structural split.

    private fun setupTranslationLanguageDropdown(
        inputView: MaterialAutoCompleteTextView,
        currentLanguage: TranslationLanguage,
        languages: List<TranslationLanguage> = TranslationLanguage.entries
    ) {
        val labels = languages.map { it.displayName(fragment.requireContext()) }
        val textColor = fragment.resolveColorAttr(R.attr.dialogTextColor)
        inputView.setAdapter(
            object : ArrayAdapter<String>(
                fragment.requireContext(),
                android.R.layout.simple_list_item_1,
                labels
            ) {
                private fun applyThemeTextColor(view: View): View {
                    (view as? TextView)?.setTextColor(textColor)
                    return view
                }

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return applyThemeTextColor(super.getView(position, convertView, parent))
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    return applyThemeTextColor(super.getDropDownView(position, convertView, parent))
                }
            }
        )
        inputView.setText(currentLanguage.displayName(fragment.requireContext()), false)
    }

    private fun parseTranslationLanguage(
        inputView: MaterialAutoCompleteTextView,
        defaultLanguage: TranslationLanguage,
        languages: List<TranslationLanguage> = TranslationLanguage.entries
    ): TranslationLanguage {
        val selectedLabel = inputView.text?.toString()?.trim().orEmpty()
        if (selectedLabel.isBlank()) return defaultLanguage
        return languages.firstOrNull {
            it.displayName(fragment.requireContext()) == selectedLabel
        } ?: defaultLanguage
    }
}

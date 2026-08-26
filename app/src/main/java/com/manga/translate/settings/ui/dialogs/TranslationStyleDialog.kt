package com.manga.translate.settings.ui.dialogs

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Translation style editing dialog.
 */
internal class TranslationStyleDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val currentStyle = settingsStore.loadTranslationStyle()
        val padding = (fragment.resources.displayMetrics.density * 20).toInt()
        val input = EditText(fragment.requireContext()).apply {
            hint = fragment.getString(R.string.translation_style_hint)
            setText(currentStyle)
            setSelection(text.length)
            minLines = 3
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(fragment.resolveColorAttr(R.attr.dialogTextColor))
            setHintTextColor(fragment.resolveColorAttr(R.attr.dialogHintTextColor))
        }
        val noteView = TextView(fragment.requireContext()).apply {
            text = fragment.getString(R.string.translation_style_note)
            setPadding(0, (fragment.resources.displayMetrics.density * 8).toInt(), 0, 0)
            setTextColor(fragment.resolveColorAttr(R.attr.dialogHintTextColor))
            textSize = 12f
        }
        val container = LinearLayout(fragment.requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(noteView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.translation_style_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val style = input.text?.toString()?.trim().orEmpty()
                settingsStore.saveTranslationStyle(style)
                AppLogger.log("Settings", "Translation style updated")
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.translation_style_saved,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton(R.string.translation_style_reset) { _, _ ->
                settingsStore.saveTranslationStyle("")
                AppLogger.log("Settings", "Translation style reset to default")
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.translation_style_saved,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

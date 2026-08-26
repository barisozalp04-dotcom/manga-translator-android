package com.manga.translate.settings.ui.dialogs

import android.content.Context
import android.content.DialogInterface
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog

/**
 * Generic single-choice settings dialog shared by the language, API format,
 * reading display, reading page animation, link source and thinking-length
 * dialogs.
 *
 * Moved out of [com.manga.translate.settings.ui.SettingsFragment] as a plain
 * top-level helper; behavior is identical to the original private method.
 */
internal fun <T> showSingleChoiceSettingDialog(
    context: Context,
    @StringRes titleRes: Int,
    options: List<T>,
    current: T,
    labelRes: (T) -> Int,
    onSelected: (dialog: DialogInterface, selected: T) -> Unit
) {
    val labels = options.map { context.getString(labelRes(it)) }.toTypedArray()
    val checkedIndex = options.indexOf(current).coerceAtLeast(0)
    AlertDialog.Builder(context)
        .setTitle(titleRes)
        .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
            onSelected(dialog, options[which])
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

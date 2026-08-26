package com.manga.translate.settings.ui.dialogs

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.manga.translate.R
import com.manga.translate.databinding.DialogBubbleFontSettingsBinding
import com.manga.translate.databinding.ItemUploadedFontBinding
import com.manga.translate.rendering.BubbleFont
import com.manga.translate.settings.BubbleFontSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsDataController
import com.manga.translate.settings.ui.SettingsFragment
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Bubble font settings dialog. The document-picker launcher itself must stay
 * registered on [SettingsFragment] (only Fragment/Activity can register
 * activity results); the dialog triggers it through
 * [SettingsFragment.launchBubbleFontUpload] and receives the imported file
 * name back through [onUploadedFontImported].
 */
internal class BubbleFontSettingsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore,
    private val dataController: SettingsDataController
) {
    private data class ActiveBubbleFontDialogState(
        val binding: DialogBubbleFontSettingsBinding,
        var selectedFontFileName: String?,
        var uploadedFonts: MutableList<String>
    )

    private var activeState: ActiveBubbleFontDialogState? = null

    fun show() {
        val currentSettings = settingsStore.loadBubbleFontSettings()
        val dialogBinding = DialogBubbleFontSettingsBinding.inflate(fragment.layoutInflater)
        dialogBinding.bubbleFontBoldSwitch.isChecked = currentSettings.isBold
        dialogBinding.bubbleFontUploadButton.setOnClickListener {
            fragment.launchBubbleFontUpload()
        }
        val uploadedFonts = dataController.listUploadedFonts().toMutableList()
        val selectedFontFileName = when {
            currentSettings.font != BubbleFont.CUSTOM_FILE -> null
            currentSettings.customFontFileName.isBlank() -> null
            else -> {
                val selected = currentSettings.customFontFileName
                if (!uploadedFonts.contains(selected)) {
                    uploadedFonts.add(selected)
                    uploadedFonts.sortBy { it.lowercase(Locale.getDefault()) }
                }
                selected
            }
        }
        val dialogState = ActiveBubbleFontDialogState(
            binding = dialogBinding,
            selectedFontFileName = selectedFontFileName,
            uploadedFonts = uploadedFonts
        )
        activeState = dialogState
        fragment.activeBubbleFontDialog = this
        renderBubbleFontDialogList(dialogState)
        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.bubble_font_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            if (fragment.activeBubbleFontDialog === this) {
                fragment.activeBubbleFontDialog = null
            }
            activeState = null
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val state = activeState ?: return@setOnClickListener
            val selectedFile = state.selectedFontFileName?.trim().orEmpty()
            if (state.selectedFontFileName != null && selectedFile.isBlank()) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.bubble_font_upload_missing,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            settingsStore.saveBubbleFontSettings(
                BubbleFontSettings(
                    font = if (selectedFile.isBlank()) {
                        BubbleFont.SYSTEM_DEFAULT
                    } else {
                        BubbleFont.CUSTOM_FILE
                    },
                    customFontFileName = selectedFile,
                    isBold = dialogBinding.bubbleFontBoldSwitch.isChecked
                )
            )
            fragment.updateBubbleFontSettingsButton()
            dialog.dismiss()
        }
    }

    /**
     * Called by [SettingsFragment] after a font file has been imported through
     * the activity result launcher.
     */
    fun onUploadedFontImported(fileName: String) {
        val dialogState = activeState ?: return
        if (!dialogState.uploadedFonts.contains(fileName)) {
            dialogState.uploadedFonts.add(fileName)
            dialogState.uploadedFonts.sortBy { it.lowercase(Locale.getDefault()) }
        }
        dialogState.selectedFontFileName = fileName
        renderBubbleFontDialogList(dialogState)
    }

    private fun renderBubbleFontDialogList(dialogState: ActiveBubbleFontDialogState) {
        val dialogBinding = dialogState.binding
        val selectedFileName = dialogState.selectedFontFileName
        dialogBinding.bubbleFontSystemDefaultRadio.isChecked = selectedFileName == null
        dialogBinding.bubbleFontSystemDefaultRadio.setOnClickListener {
            if (dialogState.selectedFontFileName != null) {
                dialogState.selectedFontFileName = null
                renderBubbleFontDialogList(dialogState)
            } else {
                dialogBinding.bubbleFontSystemDefaultRadio.isChecked = true
            }
        }

        dialogBinding.bubbleFontUploadedList.removeAllViews()
        val hasUploadedFonts = dialogState.uploadedFonts.isNotEmpty()
        dialogBinding.bubbleFontUploadedEmpty.visibility =
            if (hasUploadedFonts) View.GONE else View.VISIBLE

        dialogState.uploadedFonts.forEach { fileName ->
            val itemBinding = ItemUploadedFontBinding.inflate(
                fragment.layoutInflater,
                dialogBinding.bubbleFontUploadedList,
                false
            )
            itemBinding.uploadedFontRadio.text = fileName
            itemBinding.uploadedFontRadio.isChecked = fileName == selectedFileName
            itemBinding.root.setOnClickListener {
                if (dialogState.selectedFontFileName != fileName) {
                    dialogState.selectedFontFileName = fileName
                    renderBubbleFontDialogList(dialogState)
                }
            }
            itemBinding.uploadedFontDeleteButton.setOnClickListener {
                confirmDeleteUploadedFont(dialogState, fileName)
            }
            dialogBinding.bubbleFontUploadedList.addView(itemBinding.root)
        }
    }

    private fun confirmDeleteUploadedFont(
        dialogState: ActiveBubbleFontDialogState,
        fileName: String
    ) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.bubble_font_delete_confirm_title)
            .setMessage(fragment.getString(R.string.bubble_font_delete_confirm_message, fileName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                deleteUploadedFont(dialogState, fileName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteUploadedFont(
        dialogState: ActiveBubbleFontDialogState,
        fileName: String
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val deleted = dataController.deleteUploadedFont(fileName)
            if (!deleted) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.bubble_font_delete_failed,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            dialogState.uploadedFonts.remove(fileName)
            if (dialogState.selectedFontFileName == fileName) {
                dialogState.selectedFontFileName = null
            }
            val savedSettings = settingsStore.loadBubbleFontSettings()
            if (
                savedSettings.font == BubbleFont.CUSTOM_FILE &&
                savedSettings.customFontFileName == fileName
            ) {
                settingsStore.saveBubbleFontSettings(
                    savedSettings.copy(
                        font = BubbleFont.SYSTEM_DEFAULT,
                        customFontFileName = ""
                    )
                )
                fragment.updateBubbleFontSettingsButton()
            }
            renderBubbleFontDialogList(dialogState)
            Toast.makeText(
                fragment.requireContext(),
                fragment.getString(R.string.bubble_font_delete_success, fileName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

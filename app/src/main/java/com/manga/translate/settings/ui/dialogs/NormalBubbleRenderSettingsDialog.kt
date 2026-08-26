package com.manga.translate.settings.ui.dialogs

import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.databinding.DialogNormalBubbleRenderSettingsBinding
import com.manga.translate.settings.NormalBubbleRenderSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment
import kotlin.math.roundToInt

/**
 * Normal bubble render settings editing dialog.
 */
internal class NormalBubbleRenderSettingsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val currentSettings = settingsStore.loadNormalBubbleRenderSettings()
        val dialogBinding = DialogNormalBubbleRenderSettingsBinding.inflate(fragment.layoutInflater)
        dialogBinding.normalBubbleShrinkPercentInput.setText(
            fragment.formatNumber(currentSettings.shrinkPercent)
        )
        dialogBinding.normalBubbleOpacityPercentInput.setText(
            fragment.formatNumber(currentSettings.opacityPercent)
        )
        dialogBinding.normalBubbleFreeShrinkPercentInput.setText(
            fragment.formatNumber(currentSettings.freeBubbleShrinkPercent)
        )
        dialogBinding.normalBubbleFreeOpacityPercentInput.setText(
            fragment.formatNumber(currentSettings.freeBubbleOpacityPercent)
        )
        val seekBarProgress = ((currentSettings.minAreaPerCharSp - 16f) / 2.4f).roundToInt().coerceIn(0, 100)
        dialogBinding.normalBubbleMinAreaSeekbar.progress = seekBarProgress
        dialogBinding.normalBubbleMinAreaValueLabel.text =
            fragment.getString(R.string.normal_bubble_min_area_value, currentSettings.minAreaPerCharSp.roundToInt())
        dialogBinding.normalBubbleMinAreaSeekbar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sp2 = (16f + progress * 2.4f).roundToInt()
                    dialogBinding.normalBubbleMinAreaValueLabel.text =
                        fragment.getString(R.string.normal_bubble_min_area_value, sp2)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )
        dialogBinding.normalBubbleVerticalTextSwitch.isChecked = !currentSettings.useHorizontalText
        dialogBinding.normalBubbleFreeAutoAdaptColorSwitch.isChecked = currentSettings.autoAdaptFreeBubbleColor
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.normal_bubble_render_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updated = NormalBubbleRenderSettings(
                    shrinkPercent = fragment.parseIntInput(
                        dialogBinding.normalBubbleShrinkPercentInput.text?.toString()
                    ) ?: currentSettings.shrinkPercent,
                    opacityPercent = fragment.parseIntInput(
                        dialogBinding.normalBubbleOpacityPercentInput.text?.toString()
                    ) ?: currentSettings.opacityPercent,
                    freeBubbleShrinkPercent = fragment.parseIntInput(
                        dialogBinding.normalBubbleFreeShrinkPercentInput.text?.toString()
                    ) ?: currentSettings.freeBubbleShrinkPercent,
                    freeBubbleOpacityPercent = fragment.parseIntInput(
                        dialogBinding.normalBubbleFreeOpacityPercentInput.text?.toString()
                    ) ?: currentSettings.freeBubbleOpacityPercent,
                    minAreaPerCharSp = 16f + dialogBinding.normalBubbleMinAreaSeekbar.progress * 2.4f,
                    useHorizontalText = !dialogBinding.normalBubbleVerticalTextSwitch.isChecked,
                    autoAdaptFreeBubbleColor = dialogBinding.normalBubbleFreeAutoAdaptColorSwitch.isChecked
                )
                settingsStore.saveNormalBubbleRenderSettings(updated)
                fragment.updateNormalBubbleRenderSettingsButton()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

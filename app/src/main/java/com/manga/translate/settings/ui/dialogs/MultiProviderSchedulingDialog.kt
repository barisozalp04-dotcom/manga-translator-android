package com.manga.translate.settings.ui.dialogs

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.core.view.isEmpty
import com.manga.translate.R
import com.manga.translate.databinding.DialogMultiProviderSchedulingBinding
import com.manga.translate.databinding.ItemAdditionalTranslationProviderBinding
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.AdditionalTranslationProvider
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.PRIMARY_PROVIDER_ID
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment
import kotlin.math.roundToInt

/**
 * Multi-provider scheduling editing dialog.
 */
internal class MultiProviderSchedulingDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val dialogBinding = DialogMultiProviderSchedulingBinding.inflate(fragment.layoutInflater)
        val existing = settingsStore.loadAdditionalTranslationProviders()

        fun updateRowVisualState(rowBinding: ItemAdditionalTranslationProviderBinding) {
            val enabled = rowBinding.translationProviderEnabledSwitch.isChecked
            rowBinding.translationProviderFieldsContainer.alpha = if (enabled) 1f else 0.58f
            rowBinding.translationProviderTitle.alpha = if (enabled) 1f else 0.72f
        }

        fun refreshRowTitles() {
            for (index in 0 until dialogBinding.multiProviderSchedulingContainer.childCount) {
                val child = dialogBinding.multiProviderSchedulingContainer.getChildAt(index)
                val rowBinding = ItemAdditionalTranslationProviderBinding.bind(child)
                rowBinding.translationProviderTitle.text = fragment.getString(
                    R.string.multi_provider_scheduling_row_title,
                    index + 1
                )
            }
        }

        fun addRow(
            provider: AdditionalTranslationProvider = AdditionalTranslationProvider(
                name = "",
                apiUrl = "",
                apiKey = "",
                modelName = "",
                weight = 1
            )
        ) {
            val rowBinding = ItemAdditionalTranslationProviderBinding.inflate(
                fragment.layoutInflater,
                dialogBinding.multiProviderSchedulingContainer,
                false
            )
            rowBinding.root.setTag(R.id.additional_translation_provider_uuid, provider.providerId)
            rowBinding.translationProviderEnabledSwitch.isChecked = provider.enabled
            rowBinding.translationProviderApiUrlInput.setText(provider.apiUrl)
            rowBinding.translationProviderApiKeyInput.setText(provider.apiKey)
            rowBinding.translationProviderModelNameInput.setText(provider.modelName)
            rowBinding.translationProviderWeightInput.setText(fragment.formatNumber(provider.weight))
            rowBinding.translationProviderEnabledSwitch.setOnCheckedChangeListener { _, _ ->
                updateRowVisualState(rowBinding)
            }
            rowBinding.translationProviderDeleteButton.setOnClickListener {
                dialogBinding.multiProviderSchedulingContainer.removeView(rowBinding.root)
                if (dialogBinding.multiProviderSchedulingContainer.isEmpty()) {
                    addRow()
                } else {
                    refreshRowTitles()
                }
            }
            dialogBinding.multiProviderSchedulingContainer.addView(rowBinding.root)
            updateRowVisualState(rowBinding)
            refreshRowTitles()
            dialogBinding.multiProviderSchedulingScroll.post {
                dialogBinding.multiProviderSchedulingScroll.fullScroll(View.FOCUS_DOWN)
            }
        }

        if (existing.isEmpty()) {
            addRow()
        } else {
            existing.forEach(::addRow)
        }
        dialogBinding.multiProviderSchedulingAddButton.setOnClickListener {
            addRow()
        }

        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.multi_provider_scheduling_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.llm_params_clear, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val providers = collectAdditionalTranslationProviders(dialogBinding)
                val validationError = validateAdditionalTranslationProviders(providers)
                if (validationError != null) {
                    Toast.makeText(fragment.requireContext(), validationError, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val requiredConcurrency = (
                    if (ApiSettings(
                            apiUrl = fragment.fragmentBinding.apiUrlInput.text?.toString()?.trim().orEmpty(),
                            apiKey = fragment.fragmentBinding.apiKeyInput.text?.toString()?.trim().orEmpty(),
                            modelName = fragment.fragmentBinding.modelNameInput.text?.toString()?.trim().orEmpty(),
                            apiFormat = fragment.currentApiFormat(),
                            providerId = PRIMARY_PROVIDER_ID
                        ).isValid()
                    ) 1 else 0
                    ) + providers.count { it.enabled && it.isConfigured() }
                val currentConcurrency = fragment.parseIntInput(
                    fragment.fragmentBinding.maxConcurrencyInput.text?.toString()?.trim()
                ) ?: settingsStore.loadMaxConcurrency()
                if (currentConcurrency < requiredConcurrency.coerceAtLeast(1)) {
                    Toast.makeText(
                        fragment.requireContext(),
                        fragment.getString(
                            R.string.max_concurrency_provider_count_error,
                            requiredConcurrency.coerceAtLeast(1)
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                settingsStore.saveAdditionalTranslationProviders(providers)
                val saved = settingsStore.loadAdditionalTranslationProviders()
                fragment.updateMultiProviderSchedulingButton(saved)
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.multi_provider_scheduling_saved,
                    Toast.LENGTH_SHORT
                ).show()
                AppLogger.log("Settings", "Multi-provider scheduling updated")
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                settingsStore.saveAdditionalTranslationProviders(emptyList())
                fragment.updateMultiProviderSchedulingButton(emptyList())
                AppLogger.log("Settings", "Multi-provider scheduling cleared")
                dialog.dismiss()
            }
            dialogBinding.root.doOnLayout {
                constrainMultiProviderDialogScroll(dialog, dialogBinding)
            }
        }
        dialog.show()
    }

    private fun constrainMultiProviderDialogScroll(
        dialog: AlertDialog,
        dialogBinding: DialogMultiProviderSchedulingBinding
    ) {
        val window = dialog.window ?: return
        val visibleFrame = android.graphics.Rect()
        window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
        val availableHeight = visibleFrame.height().takeIf { it > 0 }
            ?: fragment.resources.displayMetrics.heightPixels
        val maxDialogHeight = (availableHeight * 0.85f).roundToInt()
        val scrollView = dialogBinding.multiProviderSchedulingScroll
        val rootHeight = dialogBinding.root.height.takeIf { it > 0 } ?: return
        val fixedHeight = (rootHeight - scrollView.height).coerceAtLeast(0)
        val minScrollHeight = (160 * fragment.resources.displayMetrics.density).roundToInt()
        val maxScrollHeight = (maxDialogHeight - fixedHeight).coerceAtLeast(minScrollHeight)
        val contentHeight = scrollView.getChildAt(0)?.measuredHeight ?: scrollView.height
        val targetScrollHeight = contentHeight.coerceAtMost(maxScrollHeight)
        if (scrollView.layoutParams.height != targetScrollHeight) {
            scrollView.layoutParams = scrollView.layoutParams.apply {
                height = targetScrollHeight
            }
            scrollView.requestLayout()
        }
    }

    private fun collectAdditionalTranslationProviders(
        dialogBinding: DialogMultiProviderSchedulingBinding
    ): List<AdditionalTranslationProvider> {
        val collected = mutableListOf<AdditionalTranslationProvider>()
        for (index in 0 until dialogBinding.multiProviderSchedulingContainer.childCount) {
            val child = dialogBinding.multiProviderSchedulingContainer.getChildAt(index)
            val rowBinding = ItemAdditionalTranslationProviderBinding.bind(child)
            collected += AdditionalTranslationProvider(
                providerId = rowBinding.root.getTag(R.id.additional_translation_provider_uuid) as? String
                    ?: java.util.UUID.randomUUID().toString(),
                name = settingsStore.defaultAdditionalProviderName(index),
                apiUrl = rowBinding.translationProviderApiUrlInput.text?.toString()?.trim().orEmpty(),
                apiKey = rowBinding.translationProviderApiKeyInput.text?.toString()?.trim().orEmpty(),
                modelName = rowBinding.translationProviderModelNameInput.text?.toString()?.trim().orEmpty(),
                weight = fragment.parseIntInput(
                    rowBinding.translationProviderWeightInput.text?.toString()?.trim()
                ) ?: 0,
                enabled = rowBinding.translationProviderEnabledSwitch.isChecked
            )
        }
        return collected
    }

    private fun validateAdditionalTranslationProviders(
        providers: List<AdditionalTranslationProvider>
    ): String? {
        providers.forEach { provider ->
            val allBlank = provider.apiUrl.isBlank() &&
                provider.apiKey.isBlank() &&
                provider.modelName.isBlank()
            if (allBlank) return@forEach
            if (!provider.enabled) return@forEach
            if (!provider.isConfigured()) {
                return fragment.getString(R.string.multi_provider_scheduling_empty_field_error)
            }
            if (provider.weight <= 0) {
                return fragment.getString(R.string.multi_provider_scheduling_invalid_weight_error)
            }
        }
        return null
    }
}

package com.manga.translate.settings.ui.dialogs

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isEmpty
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.manga.translate.R
import com.manga.translate.databinding.DialogCustomRequestParamsBinding
import com.manga.translate.databinding.ItemCustomRequestParamBinding
import com.manga.translate.model.ApiFormat
import com.manga.translate.network.LlmClient
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.CustomRequestParameter
import com.manga.translate.settings.OCR_PROVIDER_ID
import com.manga.translate.settings.PRIMARY_PROVIDER_ID
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Custom request parameters editing dialog.
 */
internal class CustomRequestParamsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val dialogBinding = DialogCustomRequestParamsBinding.inflate(fragment.layoutInflater)
        val existing = settingsStore.loadCustomRequestParameters()
        val providerOptions = buildCustomRequestParamProviderOptions()

        fun updateRowVisualState(rowBinding: ItemCustomRequestParamBinding) {
            val enabled = rowBinding.customRequestParamEnabledSwitch.isChecked
            rowBinding.customRequestParamFieldsContainer.alpha = if (enabled) 1f else 0.58f
            rowBinding.customRequestParamTitle.alpha = if (enabled) 1f else 0.72f
        }

        fun refreshRowTitles() {
            for (index in 0 until dialogBinding.customRequestParamsContainer.childCount) {
                val child = dialogBinding.customRequestParamsContainer.getChildAt(index)
                val rowBinding = ItemCustomRequestParamBinding.bind(child)
                rowBinding.customRequestParamTitle.text = fragment.getString(
                    R.string.custom_request_params_row_title,
                    index + 1
                )
            }
        }

        fun addRow(
            parameter: CustomRequestParameter = CustomRequestParameter("", "")
        ) {
            val rowBinding = ItemCustomRequestParamBinding.inflate(
                fragment.layoutInflater,
                dialogBinding.customRequestParamsContainer,
                false
            )
            rowBinding.customRequestParamEnabledSwitch.isChecked = parameter.enabled
            rowBinding.customRequestParamKeyInput.setText(parameter.key)
            rowBinding.customRequestParamValueInput.setText(parameter.value)
            setupCustomRequestParamProviderDropdown(
                rowBinding.customRequestParamTargetProviderInput,
                providerOptions,
                parameter.targetProviderId
            )
            rowBinding.customRequestParamEnabledSwitch.setOnCheckedChangeListener { _, _ ->
                updateRowVisualState(rowBinding)
            }
            rowBinding.customRequestParamDeleteButton.setOnClickListener {
                dialogBinding.customRequestParamsContainer.removeView(rowBinding.root)
                if (dialogBinding.customRequestParamsContainer.isEmpty()) {
                    addRow()
                } else {
                    refreshRowTitles()
                }
            }
            dialogBinding.customRequestParamsContainer.addView(rowBinding.root)
            updateRowVisualState(rowBinding)
            refreshRowTitles()
        }

        if (existing.isEmpty()) {
            addRow()
        } else {
            existing.forEach(::addRow)
        }
        dialogBinding.customRequestParamsAddButton.setOnClickListener {
            addRow()
        }

        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.custom_request_params_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.llm_params_clear, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parameters = collectCustomRequestParameters(dialogBinding, providerOptions)
                val validationError = validateCustomRequestParameters(parameters)
                if (validationError != null) {
                    Toast.makeText(fragment.requireContext(), validationError, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                settingsStore.saveCustomRequestParameters(parameters)
                fragment.updateCustomRequestParamsButton(parameters)
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.custom_request_params_saved,
                    Toast.LENGTH_SHORT
                ).show()
                AppLogger.log("Settings", "Custom request params updated")
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                settingsStore.saveCustomRequestParameters(emptyList())
                fragment.updateCustomRequestParamsButton(emptyList())
                AppLogger.log("Settings", "Custom request params cleared")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private data class RequestParamProviderOption(
        val providerId: String,
        val label: String
    )

    private fun buildCustomRequestParamProviderOptions(): List<RequestParamProviderOption> {
        val options = mutableListOf(
            RequestParamProviderOption(
                providerId = PRIMARY_PROVIDER_ID,
                label = fragment.getString(R.string.custom_request_params_provider_primary)
            ),
            RequestParamProviderOption(
                providerId = OCR_PROVIDER_ID,
                label = fragment.getString(R.string.custom_request_params_provider_ocr)
            )
        )
        settingsStore.loadAdditionalTranslationProviders().forEachIndexed { index, provider ->
            options += RequestParamProviderOption(
                providerId = provider.providerId,
                label = settingsStore.defaultAdditionalProviderName(index)
            )
        }
        return options
    }

    private fun setupCustomRequestParamProviderDropdown(
        inputView: MaterialAutoCompleteTextView,
        options: List<RequestParamProviderOption>,
        selectedProviderId: String
    ) {
        val labels = options.map { it.label }
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
        val selectedLabel = options.firstOrNull { it.providerId == selectedProviderId }?.label
            ?: options.first().label
        inputView.setText(selectedLabel, false)
    }

    private fun parseCustomRequestParamProviderId(
        inputView: MaterialAutoCompleteTextView,
        options: List<RequestParamProviderOption>
    ): String {
        val selectedLabel = inputView.text?.toString()?.trim().orEmpty()
        return options.firstOrNull { it.label == selectedLabel }?.providerId
            ?: PRIMARY_PROVIDER_ID
    }

    private fun resolveCustomRequestParamProviderLabel(providerId: String): String {
        return buildCustomRequestParamProviderOptions()
            .firstOrNull { it.providerId == providerId }
            ?.label
            ?: fragment.getString(R.string.custom_request_params_provider_primary)
    }

    private fun collectCustomRequestParameters(
        dialogBinding: DialogCustomRequestParamsBinding,
        providerOptions: List<RequestParamProviderOption>
    ): List<CustomRequestParameter> {
        val collected = mutableListOf<CustomRequestParameter>()
        for (index in 0 until dialogBinding.customRequestParamsContainer.childCount) {
            val child = dialogBinding.customRequestParamsContainer.getChildAt(index)
            val rowBinding = ItemCustomRequestParamBinding.bind(child)
            collected += CustomRequestParameter(
                key = rowBinding.customRequestParamKeyInput.text?.toString()?.trim().orEmpty(),
                value = rowBinding.customRequestParamValueInput.text?.toString().orEmpty(),
                enabled = rowBinding.customRequestParamEnabledSwitch.isChecked,
                targetProviderId = parseCustomRequestParamProviderId(
                    rowBinding.customRequestParamTargetProviderInput,
                    providerOptions
                )
            )
        }
        return collected
    }

    private fun validateCustomRequestParameters(parameters: List<CustomRequestParameter>): String? {
        val activeKeys = LinkedHashSet<String>()
        parameters.forEach { parameter ->
            val key = parameter.key.trim()
            val value = parameter.value.trim()
            if (key.isBlank() && value.isBlank()) return@forEach
            if (key.isBlank()) {
                return fragment.getString(R.string.custom_request_params_empty_row_error)
            }
            if (!parameter.enabled) return@forEach
            val scopedKey = "${parameter.targetProviderId}\u0000$key"
            if (!activeKeys.add(scopedKey)) {
                return fragment.getString(
                    R.string.custom_request_params_duplicate_error_scoped,
                    resolveCustomRequestParamProviderLabel(parameter.targetProviderId),
                    key
                )
            }
        }
        val activeParamKeys = parameters
            .filter { it.enabled }
            .mapNotNull {
                val key = it.key.trim()
                if (key.isBlank() && it.value.trim().isBlank()) {
                    null
                } else {
                    parameterReservedKeyScope(it.targetProviderId) to key
                }
            }
        val conflict = activeParamKeys.firstOrNull { (providerId, key) ->
            key in LlmClient.reservedRequestKeys(resolveRequestParamApiFormat(providerId))
        }
        return if (conflict != null) {
            fragment.getString(R.string.custom_request_params_conflict_error, conflict.second)
        } else {
            null
        }
    }

    private fun parameterReservedKeyScope(providerId: String): String {
        return providerId.trim().ifBlank { PRIMARY_PROVIDER_ID }
    }

    private fun resolveRequestParamApiFormat(providerId: String): ApiFormat {
        return if (providerId == OCR_PROVIDER_ID) {
            ApiFormat.OPENAI_COMPATIBLE
        } else {
            fragment.currentApiFormat()
        }
    }
}

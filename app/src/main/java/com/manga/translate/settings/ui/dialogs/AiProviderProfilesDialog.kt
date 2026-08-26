package com.manga.translate.settings.ui.dialogs

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import com.manga.translate.R
import com.manga.translate.databinding.DialogAiProviderProfilesBinding
import com.manga.translate.settings.ui.SettingsDataController
import com.manga.translate.settings.ui.SettingsFragment
import kotlin.math.roundToInt

/**
 * AI provider profiles management dialog. Profile persistence goes through
 * [SettingsDataController].
 */
internal class AiProviderProfilesDialog(
    private val fragment: SettingsFragment,
    private val dataController: SettingsDataController
) {
    fun show() {
        val dialogBinding = DialogAiProviderProfilesBinding.inflate(fragment.layoutInflater)
        val profileNames = ArrayList<String>()
        var selectedName: String? = null
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = profileNames.size

            override fun getItem(position: Int): String = profileNames[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: fragment.layoutInflater.inflate(
                    R.layout.item_ai_provider_profile,
                    parent,
                    false
                )
                val name = getItem(position)
                val nameView = view.findViewById<TextView>(R.id.ai_provider_profile_name)
                val checkView = view.findViewById<CheckedTextView>(R.id.ai_provider_profile_check)
                val isChecked = name == selectedName
                nameView.text = name
                view.isActivated = isChecked
                checkView.isChecked = isChecked
                return view
            }
        }
        dialogBinding.aiProviderProfilesList.adapter = adapter

        fun refreshProfiles(preferredSelection: String? = selectedName) {
            val state = dataController.loadAiProviderProfilesState()
            val names = state.profiles.map { it.name }
            profileNames.clear()
            profileNames.addAll(names)
            adapter.notifyDataSetChanged()
            selectedName = preferredSelection?.takeIf { it in names } ?: state.activeProfileName
            val checkedIndex = selectedName?.let(names::indexOf) ?: -1
            if (checkedIndex >= 0) {
                dialogBinding.aiProviderProfilesList.setItemChecked(checkedIndex, true)
            } else {
                dialogBinding.aiProviderProfilesList.clearChoices()
            }
            adapter.notifyDataSetChanged()
            dialogBinding.aiProviderProfilesCurrentText.text = state.activeProfileName?.let {
                fragment.getString(R.string.ai_provider_profiles_current, it)
            } ?: fragment.getString(R.string.ai_provider_profiles_current_none)
            dialogBinding.aiProviderProfilesNoteText.text = if (names.isEmpty()) {
                fragment.getString(R.string.ai_provider_profiles_empty)
            } else {
                fragment.getString(R.string.ai_provider_profiles_note)
            }
            dialogBinding.aiProviderProfilesApplyButton.isEnabled = names.isNotEmpty()
            dialogBinding.aiProviderProfilesDeleteButton.isEnabled = selectedName != null
            dialogBinding.aiProviderProfilesOverwriteButton.isEnabled = state.activeProfileName != null
            fragment.updateAiProviderProfilesButton()
        }

        dialogBinding.aiProviderProfilesList.setOnItemClickListener { _, _, position, _ ->
            selectedName = profileNames.getOrNull(position)
            dialogBinding.aiProviderProfilesDeleteButton.isEnabled = selectedName != null
            adapter.notifyDataSetChanged()
        }
        dialogBinding.aiProviderProfilesList.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    val canScroll = view.canScrollVertically(-1) || view.canScrollVertically(1)
                    view.parent?.requestDisallowInterceptTouchEvent(canScroll)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                }
            }
            false
        }

        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.ai_provider_profiles_title)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialogBinding.aiProviderProfilesSaveNewButton.setOnClickListener {
            showCreateAiProviderProfileDialog { profileName ->
                fragment.persistSettings()
                val saved = dataController.saveCurrentAsAiProviderProfile(profileName)
                if (!saved) {
                    val message = if (
                        dataController.loadAiProviderProfilesState().profiles.any {
                            it.name == profileName
                        }
                    ) {
                        R.string.ai_provider_profiles_name_duplicate
                    } else {
                        R.string.ai_provider_profiles_write_failed
                    }
                    Toast.makeText(
                        fragment.requireContext(),
                        message,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@showCreateAiProviderProfileDialog
                }
                fragment.reloadSettingsUiFromStore()
                refreshProfiles(profileName)
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.ai_provider_profiles_saved,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        dialogBinding.aiProviderProfilesOverwriteButton.setOnClickListener {
            fragment.persistSettings()
            if (!dataController.overwriteActiveAiProviderProfile()) {
                val message = if (dataController.loadAiProviderProfilesState().activeProfileName == null) {
                    R.string.ai_provider_profiles_overwrite_missing
                } else {
                    R.string.ai_provider_profiles_write_failed
                }
                Toast.makeText(
                    fragment.requireContext(),
                    message,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            refreshProfiles()
            Toast.makeText(
                fragment.requireContext(),
                R.string.ai_provider_profiles_overwritten,
                Toast.LENGTH_SHORT
            ).show()
        }

        dialogBinding.aiProviderProfilesApplyButton.setOnClickListener {
            val profileName = selectedName
            if (profileName == null) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.ai_provider_profiles_select_required,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (!dataController.canApplyAiProviderProfile(profileName)) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.ai_provider_profiles_apply_invalid,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (!dataController.applyAiProviderProfile(profileName)) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.ai_provider_profiles_write_failed,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            fragment.reloadSettingsUiFromStore()
            Toast.makeText(
                fragment.requireContext(),
                fragment.getString(R.string.ai_provider_profiles_applied, profileName),
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }

        dialogBinding.aiProviderProfilesDeleteButton.setOnClickListener {
            val profileName = selectedName
            if (profileName == null) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.ai_provider_profiles_select_required,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(fragment.requireContext())
                .setMessage(fragment.getString(R.string.ai_provider_profiles_delete_confirm, profileName))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (dataController.deleteAiProviderProfile(profileName)) {
                        if (dataController.loadAiProviderProfilesState().activeProfileName == null) {
                            selectedName = null
                        }
                        refreshProfiles()
                        Toast.makeText(
                            fragment.requireContext(),
                            R.string.ai_provider_profiles_deleted,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            fragment.requireContext(),
                            R.string.ai_provider_profiles_write_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        refreshProfiles()
        dialog.setOnShowListener {
            dialogBinding.root.doOnLayout {
                constrainAiProviderProfilesDialogList(dialog, dialogBinding)
            }
        }
        dialog.show()
    }

    private fun constrainAiProviderProfilesDialogList(
        dialog: AlertDialog,
        dialogBinding: DialogAiProviderProfilesBinding
    ) {
        val window = dialog.window ?: return
        val visibleFrame = android.graphics.Rect()
        window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
        val availableHeight = visibleFrame.height().takeIf { it > 0 }
            ?: fragment.resources.displayMetrics.heightPixels
        val maxDialogHeight = (availableHeight * 0.85f).roundToInt()
        val listView = dialogBinding.aiProviderProfilesList
        val rootHeight = dialogBinding.root.height.takeIf { it > 0 } ?: return
        val fixedHeight = (rootHeight - listView.height).coerceAtLeast(0)
        val minListHeight = (160 * fragment.resources.displayMetrics.density).roundToInt()
        val maxListHeight = (maxDialogHeight - fixedHeight).coerceAtLeast(minListHeight)
        val preferredListHeight = (240 * fragment.resources.displayMetrics.density).roundToInt()
        val targetListHeight = preferredListHeight.coerceAtMost(maxListHeight)
        if (listView.layoutParams.height != targetListHeight) {
            listView.layoutParams = listView.layoutParams.apply {
                height = targetListHeight
            }
            listView.requestLayout()
        }
    }

    private fun showCreateAiProviderProfileDialog(onConfirm: (String) -> Unit) {
        val input = EditText(fragment.requireContext()).apply {
            hint = fragment.getString(R.string.ai_provider_profiles_name_hint)
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.ai_provider_profiles_name_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(
                        fragment.requireContext(),
                        R.string.ai_provider_profiles_name_empty,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                onConfirm(name)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}

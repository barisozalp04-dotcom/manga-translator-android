package com.manga.translate.settings.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.manga.translate.R
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.ui.SettingsDataController
import com.manga.translate.settings.ui.SettingsFragment
import java.io.File
import java.util.Locale

/**
 * Log viewing / sharing dialogs. Reads log data through [SettingsDataController].
 */
internal class LogsDialog(
    private val fragment: SettingsFragment,
    private val dataController: SettingsDataController
) {
    fun showLogs() {
        val logs = dataController.readLogs().ifBlank { fragment.getString(R.string.logs_empty) }
        showLogText(fragment.getString(R.string.logs_title), logs)
    }

    fun showLogFiles() {
        val files = dataController.listLogFiles()
        if (files.isEmpty()) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.logs_folder_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.logs_folder_title)
            .setItems(names) { _, which ->
                shareLogFile(files[which])
            }
            .setNeutralButton(R.string.share_error_logs) { _, _ ->
                shareErrorLogsArchive()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareErrorLogsArchive() {
        val archive = dataController.createErrorLogsArchive()
        if (archive == null || !archive.exists()) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.error_logs_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        shareLogFile(archive, fragment.getString(R.string.share_error_logs))
    }

    private fun shareLogFile(
        file: File,
        chooserTitle: String = fragment.getString(R.string.share_logs)
    ) {
        if (!file.exists()) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.logs_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            fragment.requireContext(),
            "${fragment.requireContext().packageName}.fileprovider",
            file
        )
        val mimeType = if (file.extension.lowercase(Locale.US) == "zip") {
            "application/zip"
        } else {
            "text/plain"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle)
        val manager = fragment.requireContext().packageManager
        if (chooser.resolveActivity(manager) != null) {
            AppLogger.log("Settings", "Share log file ${file.name}")
            fragment.startActivity(chooser)
        } else {
            Toast.makeText(
                fragment.requireContext(),
                R.string.share_logs_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun showLogText(title: String, logs: String) {
        val padding = (fragment.resources.displayMetrics.density * 16).toInt()
        val textView = TextView(fragment.requireContext()).apply {
            text = logs
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
            setTextColor(fragment.resolveColorAttr(R.attr.dialogTextColor))
        }
        val scrollView = ScrollView(fragment.requireContext()).apply {
            addView(textView)
        }
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(title)
            .setView(scrollView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.copy_logs) { _, _ ->
                val clipboard = fragment.requireContext()
                    .getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("logs", logs))
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.copy_logs,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }
}

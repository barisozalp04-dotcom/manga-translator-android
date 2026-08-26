package com.manga.translate.settings.ui

import com.manga.translate.app.UpdateChecker
import com.manga.translate.app.UpdateInfo
import com.manga.translate.model.ApiFormat
import com.manga.translate.network.LlmClient

/**
 * Network-facing operations for the settings UI: AI provider model list
 * fetching and remote update-info fetching.
 *
 * Keeps all network calls out of [SettingsFragment] and the dialog classes so
 * the UI layer only orchestrates dialogs and delegates the actual requests to
 * [LlmClient] / [UpdateChecker].
 */
internal class SettingsNetworkController(
    private val llmClient: LlmClient
) {
    suspend fun fetchModelList(
        apiUrl: String,
        apiKey: String,
        apiFormat: ApiFormat
    ): List<String> = llmClient.fetchModelList(apiUrl, apiKey, apiFormat)

    suspend fun fetchUpdateInfo(
        timeoutMs: Int,
        includePreview: Boolean,
        languageKey: String
    ): UpdateInfo? = UpdateChecker.fetchUpdateInfo(
        timeoutMs = timeoutMs,
        includePreview = includePreview,
        languageKey = languageKey
    )
}

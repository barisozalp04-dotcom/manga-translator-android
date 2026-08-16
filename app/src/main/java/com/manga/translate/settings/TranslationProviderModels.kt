package com.manga.translate.settings

import java.util.UUID

data class AdditionalTranslationProvider(
    val name: String,
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val weight: Int,
    val enabled: Boolean = true,
    val providerId: String = UUID.randomUUID().toString()
) {
    fun isConfigured(): Boolean {
        return apiUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank()
    }
}

data class WeightedProviderCandidate(
    val providerId: String,
    val displayName: String,
    val settings: ApiSettings,
    val weight: Int,
    val isPrimary: Boolean
)

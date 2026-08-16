package com.manga.translate.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class GlobalTaskProgressStage {
    PREPARING_TRANSLATION,
    DETECTING_REGIONS,
    OCR,
    GLOSSARY,
    TRANSLATING
}

internal data class GlobalTaskProgressState(
    val visible: Boolean = false,
    val title: String = "",
    val detail: String = "",
    val progress: Int? = null,
    val total: Int? = null,
    val failedCount: Int? = null,
    val stage: GlobalTaskProgressStage? = null,
    val terminal: Boolean = false,
    val error: Boolean = false
)

internal object GlobalTaskProgressStore {
    private val _state = MutableStateFlow(GlobalTaskProgressState())
    val state: StateFlow<GlobalTaskProgressState> = _state.asStateFlow()

    fun show(
        title: String,
        detail: String,
        progress: Int? = null,
        total: Int? = null,
        failedCount: Int? = null,
        stage: GlobalTaskProgressStage? = null
    ) {
        _state.value = GlobalTaskProgressState(
            visible = true,
            title = title,
            detail = detail,
            progress = progress,
            total = total,
            failedCount = failedCount,
            stage = stage
        )
    }

    fun complete(title: String, detail: String) {
        _state.value = GlobalTaskProgressState(
            visible = true,
            title = title,
            detail = detail,
            terminal = true
        )
    }

    fun fail(title: String, detail: String) {
        _state.value = GlobalTaskProgressState(
            visible = true,
            title = title,
            detail = detail,
            terminal = true,
            error = true
        )
    }

    fun hide() {
        _state.value = GlobalTaskProgressState()
    }
}

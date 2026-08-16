package com.manga.translate.library

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-scoped export owner. It keeps an export alive across UI recreation and prevents
 * the same destination from being rendered twice while the original task is still running.
 */
internal class ExportTaskHost(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val activeTargets = linkedSetOf<ExportTaskTarget>()

    fun launch(target: ExportTaskTarget, block: suspend () -> Unit): Boolean {
        synchronized(activeTargets) {
            if (!activeTargets.add(target)) return false
        }
        scope.launch {
            try {
                block()
            } finally {
                synchronized(activeTargets) {
                    activeTargets.remove(target)
                }
            }
        }
        return true
    }

    fun isExportActiveFor(folder: File): Boolean {
        val path = folder.absolutePath
        return synchronized(activeTargets) {
            activeTargets.any { it.folderPath == path }
        }
    }
}

internal data class ExportTaskTarget(
    val folderPath: String,
    val destination: String
)

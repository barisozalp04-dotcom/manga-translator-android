package com.manga.translate.background

internal data class ExportKeepAliveTask(
    val id: String,
    val title: String,
    val message: String,
    val content: String,
    val progress: Int? = null,
    val total: Int? = null
)

internal class KeepAliveTaskRegistry {
    private val exportTasks = linkedMapOf<String, ExportKeepAliveTask>()
    private var foregroundExportTaskId: String? = null

    var translationActive: Boolean = false
        private set

    val hasActiveTasks: Boolean
        get() = translationActive || exportTasks.isNotEmpty()

    val foregroundExportTask: ExportKeepAliveTask?
        get() = foregroundExportTaskId?.let(exportTasks::get)
            ?: exportTasks.values.lastOrNull()

    fun startTranslation() {
        translationActive = true
    }

    fun finishTranslation() {
        translationActive = false
    }

    fun startExport(task: ExportKeepAliveTask) {
        exportTasks[task.id] = task
        foregroundExportTaskId = task.id
    }

    fun updateExport(
        taskId: String,
        content: String,
        progress: Int? = null,
        total: Int? = null
    ): ExportKeepAliveTask? {
        val current = exportTasks[taskId] ?: return null
        return current.copy(
            content = content,
            progress = progress,
            total = total
        ).also {
            exportTasks[taskId] = it
            foregroundExportTaskId = taskId
        }
    }

    fun finishExport(taskId: String): ExportKeepAliveTask? {
        val finished = exportTasks.remove(taskId) ?: return null
        if (foregroundExportTaskId == taskId) {
            foregroundExportTaskId = exportTasks.keys.lastOrNull()
        }
        return finished
    }
}

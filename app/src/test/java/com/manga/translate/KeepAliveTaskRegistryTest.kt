package com.manga.translate

import com.manga.translate.background.ExportKeepAliveTask
import com.manga.translate.background.KeepAliveTaskRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveTaskRegistryTest {
    @Test
    fun exportCompletionDoesNotMakeRegistryIdleWhileTranslationRuns() {
        val registry = KeepAliveTaskRegistry()
        registry.startTranslation()
        registry.startExport(exportTask("export-1"))

        registry.finishExport("export-1")

        assertTrue(registry.hasActiveTasks)
        assertTrue(registry.translationActive)
        assertNull(registry.foregroundExportTask)

        registry.finishTranslation()
        assertFalse(registry.hasActiveTasks)
    }

    @Test
    fun translationCompletionKeepsLatestExportRegistered() {
        val registry = KeepAliveTaskRegistry()
        registry.startExport(exportTask("export-1"))
        registry.startExport(exportTask("export-2"))
        registry.startTranslation()

        registry.updateExport("export-1", "5/10", 5, 10)
        registry.finishTranslation()

        assertTrue(registry.hasActiveTasks)
        assertEquals("export-1", registry.foregroundExportTask?.id)
        assertEquals(5, registry.foregroundExportTask?.progress)

        registry.finishExport("export-1")
        assertEquals("export-2", registry.foregroundExportTask?.id)
        registry.finishExport("export-2")
        assertFalse(registry.hasActiveTasks)
    }

    private fun exportTask(id: String) = ExportKeepAliveTask(
        id = id,
        title = "Export",
        message = "Working",
        content = "0/10"
    )
}

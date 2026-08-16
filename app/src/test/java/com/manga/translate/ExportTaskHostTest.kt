package com.manga.translate

import com.manga.translate.library.ExportTaskHost
import com.manga.translate.library.ExportTaskTarget
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTaskHostTest {
    @Test
    fun `keeps a destination locked until its running task completes`() = runBlocking {
        val host = ExportTaskHost(this)
        val folder = File("/library/manga")
        val target = ExportTaskTarget(folder.absolutePath, "tree|manga|pdf")
        val release = CompletableDeferred<Unit>()

        assertTrue(host.launch(target) { release.await() })
        yield()

        assertTrue(host.isExportActiveFor(folder))
        assertFalse(host.launch(target) {})

        release.complete(Unit)
        yield()

        assertFalse(host.isExportActiveFor(folder))
    }
}

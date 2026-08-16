package com.manga.translate.settings

import android.util.AtomicFile
import com.manga.translate.platform.AppLogger
import java.io.File
import java.io.FileOutputStream

internal fun interface AiProviderProfilesFileWriter {
    fun write(file: File, content: String): Boolean
}

internal object AtomicAiProviderProfilesFileWriter : AiProviderProfilesFileWriter {
    override fun write(file: File, content: String): Boolean {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        return try {
            val startedOutput = atomicFile.startWrite()
            output = startedOutput
            startedOutput.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(startedOutput)
            output = null
            true
        } catch (e: Exception) {
            output?.let(atomicFile::failWrite)
            AppLogger.log("Settings", "Atomic write failed for AI provider profiles", e)
            false
        }
    }
}

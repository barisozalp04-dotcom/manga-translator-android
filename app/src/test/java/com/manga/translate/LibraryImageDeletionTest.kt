package com.manga.translate

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryImageDeletionTest {

    @Test
    fun `deleting an image also deletes its translation and OCR sidecars`() {
        val directory = Files.createTempDirectory("library-image-delete").toFile()
        try {
            val image = File(directory, "page.jpg").apply { writeText("image") }
            val translation = File(directory, "page.json").apply { writeText("translation") }
            val ocr = File(directory, "page.ocr.json").apply { writeText("ocr") }

            assertTrue(deleteImageAndSidecars(image, translation, ocr))

            assertFalse(image.exists())
            assertFalse(translation.exists())
            assertFalse(ocr.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failing to delete an image retains its translation and OCR sidecars`() {
        val directory = Files.createTempDirectory("library-image-delete").toFile()
        try {
            val image = File(directory, "page.jpg").apply { mkdir() }
            File(image, "child").writeText("prevents deletion")
            val translation = File(directory, "page.json").apply { writeText("translation") }
            val ocr = File(directory, "page.ocr.json").apply { writeText("ocr") }

            assertFalse(deleteImageAndSidecars(image, translation, ocr))

            assertTrue(image.exists())
            assertTrue(translation.exists())
            assertTrue(ocr.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}

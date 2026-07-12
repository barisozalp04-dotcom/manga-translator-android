package com.manga.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingScrollViewTest {
    @Test
    fun `outer scroll is enabled only for standard long images`() {
        assertFalse(
            shouldEnableReadingContainerScroll(
                readingMode = FolderReadingMode.STANDARD,
                isEditMode = false,
                isLongImage = false
            )
        )
        assertTrue(
            shouldEnableReadingContainerScroll(
                readingMode = FolderReadingMode.STANDARD,
                isEditMode = false,
                isLongImage = true
            )
        )
        assertFalse(
            shouldEnableReadingContainerScroll(
                readingMode = FolderReadingMode.STANDARD,
                isEditMode = true,
                isLongImage = true
            )
        )
        assertFalse(
            shouldEnableReadingContainerScroll(
                readingMode = FolderReadingMode.WEBTOON_SCROLL,
                isEditMode = false,
                isLongImage = true
            )
        )
    }
}

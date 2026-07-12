package com.manga.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `standard non-long content is pinned to the visible viewport`() {
        assertEquals(
            1376,
            resolveViewportPinnedContentHeight(
                readingMode = FolderReadingMode.STANDARD,
                isLongImage = false,
                viewportHeight = 1376
            )
        )
        assertNull(
            resolveViewportPinnedContentHeight(
                readingMode = FolderReadingMode.STANDARD,
                isLongImage = true,
                viewportHeight = 1376
            )
        )
        assertNull(
            resolveViewportPinnedContentHeight(
                readingMode = FolderReadingMode.WEBTOON_SCROLL,
                isLongImage = false,
                viewportHeight = 1376
            )
        )
        assertNull(
            resolveViewportPinnedContentHeight(
                readingMode = FolderReadingMode.STANDARD,
                isLongImage = false,
                viewportHeight = 0
            )
        )
    }
}

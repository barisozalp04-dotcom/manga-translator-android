package com.manga.translate

import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.reader.resolveEffectiveReadingDisplayMode
import com.manga.translate.reader.resolveFitWidthScrollableContentHeight
import com.manga.translate.reader.shouldEnableReadingContainerScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingScrollViewTest {
    @Test
    fun `outer scroll is enabled only for standard pages with vertical overflow`() {
        assertFalse(
            shouldEnableReadingContainerScroll(
                readingMode = FolderReadingMode.STANDARD,
                isEditMode = false,
                hasVerticalOverflow = false
            )
        )
        assertTrue(
            shouldEnableReadingContainerScroll(
                readingMode = FolderReadingMode.STANDARD,
                isEditMode = false,
                hasVerticalOverflow = true
            )
        )
        assertFalse(
            shouldEnableReadingContainerScroll(
                readingMode = FolderReadingMode.STANDARD,
                isEditMode = true,
                hasVerticalOverflow = true
            )
        )
        assertFalse(
            shouldEnableReadingContainerScroll(
                readingMode = FolderReadingMode.WEBTOON_SCROLL,
                isEditMode = false,
                hasVerticalOverflow = true
            )
        )
    }

    @Test
    fun `fit width overflow follows actual page and viewport aspect ratios`() {
        assertNull(
            resolveFitWidthScrollableContentHeight(
                readingMode = FolderReadingMode.STANDARD,
                displayMode = ReadingDisplayMode.FIT_WIDTH,
                contentWidth = 1350,
                contentHeight = 1920,
                viewportWidth = 1000,
                viewportHeight = 1435
            )
        )
        assertEquals(
            1452,
            resolveFitWidthScrollableContentHeight(
                readingMode = FolderReadingMode.STANDARD,
                displayMode = ReadingDisplayMode.FIT_WIDTH,
                contentWidth = 1322,
                contentHeight = 1920,
                viewportWidth = 1000,
                viewportHeight = 1435
            )
        )
        assertNull(
            resolveFitWidthScrollableContentHeight(
                readingMode = FolderReadingMode.STANDARD,
                displayMode = ReadingDisplayMode.FIT_HEIGHT,
                contentWidth = 1322,
                contentHeight = 1920,
                viewportWidth = 1000,
                viewportHeight = 1435
            )
        )
        assertEquals(
            6000,
            resolveFitWidthScrollableContentHeight(
                readingMode = FolderReadingMode.STANDARD,
                displayMode = ReadingDisplayMode.FIT_WIDTH,
                contentWidth = 1000,
                contentHeight = 6000,
                viewportWidth = 1000,
                viewportHeight = 1435
            )
        )
    }

    @Test
    fun `webtoon and long images always use fit width`() {
        assertEquals(
            ReadingDisplayMode.FIT_WIDTH,
            resolveEffectiveReadingDisplayMode(
                readingMode = FolderReadingMode.WEBTOON_SCROLL,
                configuredMode = ReadingDisplayMode.FIT_HEIGHT,
                isLongImage = false
            )
        )
        assertEquals(
            ReadingDisplayMode.FIT_WIDTH,
            resolveEffectiveReadingDisplayMode(
                readingMode = FolderReadingMode.STANDARD,
                configuredMode = ReadingDisplayMode.FIT_HEIGHT,
                isLongImage = true
            )
        )
        assertEquals(
            ReadingDisplayMode.FIT_HEIGHT,
            resolveEffectiveReadingDisplayMode(
                readingMode = FolderReadingMode.STANDARD,
                configuredMode = ReadingDisplayMode.FIT_HEIGHT,
                isLongImage = false
            )
        )
    }
}

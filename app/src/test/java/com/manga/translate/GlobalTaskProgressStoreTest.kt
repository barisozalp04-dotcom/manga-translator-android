package com.manga.translate

import com.manga.translate.platform.GlobalTaskProgressStage
import com.manga.translate.platform.GlobalTaskProgressStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalTaskProgressStoreTest {
    @After
    fun tearDown() {
        GlobalTaskProgressStore.hide()
    }

    @Test
    fun `show keeps structured translation progress`() {
        GlobalTaskProgressStore.show(
            title = "Translation",
            detail = "Processed 3/10 - Failed 1",
            progress = 3,
            total = 10,
            failedCount = 1,
            stage = GlobalTaskProgressStage.TRANSLATING
        )

        val state = GlobalTaskProgressStore.state.value
        assertTrue(state.visible)
        assertEquals(3, state.progress)
        assertEquals(10, state.total)
        assertEquals(1, state.failedCount)
        assertEquals(GlobalTaskProgressStage.TRANSLATING, state.stage)
    }

    @Test
    fun `hide clears structured translation progress`() {
        GlobalTaskProgressStore.show(
            title = "Translation",
            detail = "Preparing",
            stage = GlobalTaskProgressStage.PREPARING_TRANSLATION
        )

        GlobalTaskProgressStore.hide()

        val state = GlobalTaskProgressStore.state.value
        assertFalse(state.visible)
        assertNull(state.progress)
        assertNull(state.failedCount)
        assertNull(state.stage)
    }
}

package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.platform.PromptAssetResolver
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PromptAssetResolverTest {
    @Test
    fun `non-traditional locale does not require hant asset`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(PromptAssetResolver.isTraditionalChinese(context))
    }
}

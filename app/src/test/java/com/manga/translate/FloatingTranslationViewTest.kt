package com.manga.translate

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationResult
import com.manga.translate.reader.FloatingTranslationView
import com.manga.translate.settings.NormalBubbleRenderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingTranslationViewTest {
    private class RecordingCanvas : Canvas() {
        val pathBounds = mutableListOf<RectF>()

        override fun drawPath(path: Path, paint: Paint) {
            pathBounds += RectF().also { path.computeBounds(it, true) }
        }
    }

    @Test
    fun `normal draw records bubbles outside the current parent viewport`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        val overlay = FloatingTranslationView(activity)
        parent.addView(
            overlay,
            FrameLayout.LayoutParams(100, 1_000)
        )
        activity.setContentView(
            parent,
            ViewGroup.LayoutParams(100, 100)
        )
        shadowOf(Looper.getMainLooper()).idle()
        val exact100 = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        val exact1000 = View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY)
        parent.measure(exact100, exact100)
        parent.layout(0, 0, 100, 100)
        overlay.measure(exact100, exact1000)
        overlay.layout(0, 0, 100, 1_000)

        val visibleRect = Rect()
        assertTrue(overlay.getLocalVisibleRect(visibleRect))
        assertEquals(Rect(0, 0, 100, 100), visibleRect)

        overlay.setNormalBubbleRenderSettings(
            NormalBubbleRenderSettings(
                shrinkPercent = 0,
                opacityPercent = 100,
                freeBubbleShrinkPercent = 0,
                freeBubbleOpacityPercent = 100,
                minAreaPerCharSp = 48f,
                useHorizontalText = true,
                autoAdaptFreeBubbleColor = false
            )
        )
        overlay.setDisplayRect(RectF(0f, 0f, 100f, 1_000f))
        overlay.setTranslations(
            TranslationResult(
                imageName = "page.png",
                width = 100,
                height = 1_000,
                bubbles = listOf(
                    BubbleTranslation.pending(
                        id = 0,
                        rect = RectF(10f, 10f, 90f, 90f),
                        source = BubbleSource.MANUAL
                    ),
                    BubbleTranslation.pending(
                        id = 1,
                        rect = RectF(10f, 800f, 90f, 900f),
                        source = BubbleSource.MANUAL
                    )
                )
            )
        )

        val canvas = RecordingCanvas()
        val onDraw = FloatingTranslationView::class.java.getDeclaredMethod(
            "onDraw",
            Canvas::class.java
        ).apply {
            isAccessible = true
        }
        onDraw.invoke(overlay, canvas)

        assertTrue(
            "onscreen bubble was not drawn",
            canvas.pathBounds.any { 50f in it.top..it.bottom }
        )
        assertTrue(
            "offscreen bubble was culled",
            canvas.pathBounds.any { 850f in it.top..it.bottom }
        )
    }
}

package com.manga.translate.platform

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * RecyclerView used inside a wrapping scroll container. An unspecified height would otherwise
 * make RecyclerView measure and bind every item, defeating view recycling for large libraries.
 */
class BoundedRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthSpec, heightSpec)
            return
        }
        val maxHeight = (resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION).roundToInt()
        val boundedHeightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        super.onMeasure(widthSpec, boundedHeightSpec)
    }

    private companion object {
        const val MAX_HEIGHT_FRACTION = 0.6f
    }
}

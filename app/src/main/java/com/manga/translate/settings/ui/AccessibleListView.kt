package com.manga.translate.settings.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ListView

class AccessibleListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.listViewStyle
) : ListView(context, attrs, defStyleAttr) {

    override fun performClick(): Boolean = super.performClick()
}

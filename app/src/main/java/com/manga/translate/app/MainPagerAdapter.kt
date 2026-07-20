package com.manga.translate.app

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.manga.translate.R
import com.manga.translate.library.LibraryFragment
import com.manga.translate.reader.ReadingFragment
import com.manga.translate.settings.ui.SettingsFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> LibraryFragment()
            1 -> ReadingFragment()
            else -> SettingsFragment()
        }
    }

    @StringRes
    fun getTitleRes(position: Int): Int {
        return when (position) {
            0 -> R.string.tab_library
            1 -> R.string.tab_reading
            else -> R.string.tab_settings
        }
    }

    companion object {
        const val LIBRARY_INDEX = 0
        const val READING_INDEX = 1
        const val SETTINGS_INDEX = 2
    }
}

package com.manga.translate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.manga.translate.di.AppContainer
import java.util.concurrent.atomic.AtomicInteger

class MangaTranslateApp : Application() {
    internal val appContainer by lazy { AppContainer(this) }
    private val startedActivities = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                syncAppLocales()
                if (startedActivities.incrementAndGet() == 1) {
                    appContainer.localModelMemoryManager.setAppInForeground(true)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                val remaining = startedActivities.decrementAndGet().coerceAtLeast(0)
                if (remaining == 0) {
                    startedActivities.set(0)
                    appContainer.localModelMemoryManager.setAppInForeground(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        syncAppLocales()
        val settingsStore = appContainer.settingsStore
        val themeMode = settingsStore.loadThemeMode()
        AppCompatDelegate.setDefaultNightMode(themeMode.nightMode)
        val crashStateStore = appContainer.crashStateStore
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.log("Crash", "Uncaught exception on ${thread.name}", throwable)
            crashStateStore.markCrashed()
            previousHandler?.uncaughtException(thread, throwable)
        }
        val taskPersistence = TranslationTaskPersistence(this)
        val pendingTask = taskPersistence.load()
        if (pendingTask != null) {
            val ageMs = System.currentTimeMillis() - pendingTask.startedAtEpochMs
            if (ageMs < 24 * 60 * 60 * 1000L) {
                TranslationKeepAliveService.resumePendingTask(this)
            } else {
                taskPersistence.clear()
            }
        }
    }

    private fun syncAppLocales() {
        val resolvedLocales = appContainer.settingsStore.loadAppLanguage().resolveApplicationLocales()
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() == resolvedLocales.toLanguageTags()) {
            return
        }
        AppCompatDelegate.setApplicationLocales(resolvedLocales)
    }
}

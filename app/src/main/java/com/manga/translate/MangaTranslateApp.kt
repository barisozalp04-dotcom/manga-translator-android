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
        val taskPersistence = TranslationTaskPersistence(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Force-sync so the stack survives process death (appendText alone is often lost).
            AppLogger.logFatal("Crash", "Uncaught exception on ${thread.name}", throwable)
            crashStateStore.markCrashed()
            // Drop in-flight translation so the next cold start does not resume a crashing task.
            taskPersistence.clear()
            previousHandler?.uncaughtException(thread, throwable)
        }
        val pendingTask = taskPersistence.load()
        if (pendingTask != null) {
            val ageMs = System.currentTimeMillis() - pendingTask.startedAtEpochMs
            val crashedLastRun = crashStateStore.wasCrashedLastRun()
            if (crashedLastRun || ageMs >= 24 * 60 * 60 * 1000L) {
                if (crashedLastRun) {
                    AppLogger.log(
                        "Library",
                        "Discarding pending translation after crash: mode=${pendingTask.mode}"
                    )
                }
                taskPersistence.clear()
            } else {
                TranslationKeepAliveService.resumePendingTask(this)
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

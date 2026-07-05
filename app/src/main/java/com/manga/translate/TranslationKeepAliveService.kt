package com.manga.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.manga.translate.di.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

class TranslationKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val taskPersistence by lazy(LazyThreadSafetyMode.NONE) {
        TranslationTaskPersistence(applicationContext)
    }
    private var translationJob: Job? = null
    private var localModelLease: LocalModelMemoryManager.LocalModelLease? = null
    private var currentTaskLabel: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_TRANSLATION) {
            handleCancelTranslation()
            return START_NOT_STICKY
        }
        ensureForegroundNotificationChannel()
        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.translation_keepalive_title)
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: getString(R.string.translation_keepalive_message)
        val content = intent?.getStringExtra(EXTRA_CONTENT)
            ?: getString(R.string.translation_preparing)
        if (intent?.action == ACTION_START_TRANSLATION_TASK) {
            loadDescriptor(intent)?.let { descriptor ->
                currentTaskLabel = describeTaskLabel(this, descriptor)
            }
        } else if (intent?.action == ACTION_RESUME_TRANSLATION_TASK && currentTaskLabel.isBlank()) {
            taskPersistence.load()?.let { descriptor ->
                currentTaskLabel = describeTaskLabel(this, descriptor)
            }
        }
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                this,
                title,
                message,
                content,
                null,
                null
            )
        )
        when (intent?.action) {
            ACTION_START_TRANSLATION_TASK -> {
                val descriptor = loadDescriptor(intent)
                if (descriptor == null) {
                    stopIdleTranslationTask(clearPersistedTask = false)
                    return START_NOT_STICKY
                }
                startTranslationTask(descriptor)
            }
            ACTION_RESUME_TRANSLATION_TASK -> {
                if (translationJob?.isActive != true) {
                    val descriptor = taskPersistence.load()
                    if (descriptor == null) {
                        stopIdleTranslationTask(clearPersistedTask = true)
                        return START_NOT_STICKY
                    }
                    startTranslationTask(descriptor)
                }
            }
            else -> {
                // null intent (STICKY restart with no pending task) or unknown action
                stopIdleTranslationTask(clearPersistedTask = false)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        translationJob?.cancel()
        releaseLocalModelLease()
        serviceScope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun handleCancelTranslation() {
        if (TranslationCancellationRegistry.requestCancel()) {
            cancelActionEnabled = false
            updateStatus(this, getString(R.string.translation_canceling))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MangaTranslator:TranslationKeepAlive"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun loadDescriptor(intent: Intent): TranslationTaskDescriptor? {
        val raw = intent.getStringExtra(EXTRA_TASK_DESCRIPTOR) ?: return null
        return runCatching {
            parseTranslationTaskDescriptor(org.json.JSONObject(raw))
        }.getOrNull()
    }

    private fun stopIdleTranslationTask(clearPersistedTask: Boolean) {
        cancelActionEnabled = false
        if (clearPersistedTask) {
            taskPersistence.clear()
        }
        GlobalTaskProgressStore.hide()
        releaseWakeLock()
        stopSelf()
    }

    private fun startTranslationTask(descriptor: TranslationTaskDescriptor) {
        if (translationJob?.isActive == true) return
        taskPersistence.save(descriptor)
        acquireWakeLock()
        localModelLease = applicationContext.appContainer.localModelMemoryManager.acquire("TranslationKeepAlive")
        val translationActionsCallback: (Boolean) -> Unit = { enabled ->
            LibraryUiBridge.setTranslationActionsEnabled(enabled)
        }
        translationActionsCallback(false)
        val coordinator = applicationContext.appContainer.createFolderTranslationCoordinator(
            translationPipeline = applicationContext.appContainer.createTranslationPipeline(),
            ui = ServiceLibraryUiCallbacks
        )
        val tasks = descriptor.toFolderTasks()
        if (tasks.isEmpty()) {
            GlobalTaskProgressStore.fail(
                getString(R.string.translation_keepalive_title),
                getString(R.string.translation_failed)
            )
            translationActionsCallback(true)
            taskPersistence.clear()
            releaseLocalModelLease()
            releaseWakeLock()
            stopSelf()
            return
        }
        translationJob = when (descriptor.mode) {
            TranslationTaskPersistence.MODE_COLLECTION -> {
                val collectionFolder = descriptor.collectionFolderPath?.let(::File)
                if (collectionFolder == null || !collectionFolder.exists()) {
                    GlobalTaskProgressStore.fail(
                        getString(R.string.translation_keepalive_title),
                        getString(R.string.translation_failed)
                    )
                    translationActionsCallback(true)
                    taskPersistence.clear()
                    releaseLocalModelLease()
                    releaseWakeLock()
                    stopSelf()
                    return
                }
                coordinator.translateCollection(
                    scope = serviceScope,
                    collectionFolder = collectionFolder,
                    tasks = tasks,
                    onTranslateEnabled = translationActionsCallback
                )
            }
            TranslationTaskPersistence.MODE_BATCH -> {
                coordinator.translateBatch(
                    scope = serviceScope,
                    tasks = tasks,
                    onTranslateEnabled = translationActionsCallback
                )
            }
            else -> {
                val first = tasks.first()
                coordinator.translateFolder(
                    scope = serviceScope,
                    folder = first.folder,
                    images = first.images,
                    force = first.force,
                    fullTranslate = first.fullTranslate,
                    glossaryProcessingEnabled = first.glossaryProcessingEnabled,
                    useVlDirectTranslate = first.useVlDirectTranslate,
                    language = first.language,
                    onTranslateEnabled = translationActionsCallback
                )
            }
        }
        if (translationJob == null) {
            // The coordinator may return null when everything is already done,
            // inputs are empty, or startup validation fails before launching a job.
            translationActionsCallback(true)
            releaseLocalModelLease()
            stopIdleTranslationTask(clearPersistedTask = true)
            return
        }
        translationJob?.invokeOnCompletion {
            translationJob = null
            translationActionsCallback(true)
            maybeNotifyTranslationFinished()
            taskPersistence.clear()
            releaseLocalModelLease()
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun releaseLocalModelLease() {
        localModelLease?.close()
        localModelLease = null
    }

    private fun ensureForegroundNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(this, manager)
    }

    private fun maybeNotifyTranslationFinished() {
        val terminalState = GlobalTaskProgressStore.state.value.takeIf { it.terminal } ?: return
        showResultNotification(
            context = this,
            resultTitle = terminalState.detail.ifBlank {
                getString(R.string.translation_done)
            },
            taskLabel = currentTaskLabel.ifBlank {
                getString(R.string.translation_result_task_fallback_label)
            },
            isError = terminalState.error,
            isCanceled = terminalState.detail == getString(R.string.translation_canceled)
        )
    }

    companion object {
        private const val CHANNEL_ID = "translation_keepalive"
        private const val ALERT_CHANNEL_ID = "translation_alerts"
        private const val RESULT_CHANNEL_ID = "translation_results"
        private const val SUCCESS_RESULT_CHANNEL_ID = "translation_success_results"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val RESULT_NOTIFICATION_ID = 1003
        private const val NOTIFICATION_REQUEST_CODE = 0
        private const val ALERT_NOTIFICATION_REQUEST_CODE = 2
        private const val RESULT_NOTIFICATION_REQUEST_CODE = 3
        private const val CANCEL_REQUEST_CODE = 1
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_MESSAGE = "extra_message"
        private const val EXTRA_CONTENT = "extra_content"
        private const val EXTRA_TASK_DESCRIPTOR = "extra_task_descriptor"
        private const val ACTION_CANCEL_TRANSLATION = "com.manga.translate.action.CANCEL_TRANSLATION"
        private const val ACTION_START_TRANSLATION_TASK = "com.manga.translate.action.START_TRANSLATION_TASK"
        private const val ACTION_RESUME_TRANSLATION_TASK = "com.manga.translate.action.RESUME_TRANSLATION_TASK"
        const val EXTRA_OPEN_LIBRARY_TAB = "extra_open_library_tab"
        @Volatile
        private var cancelActionEnabled: Boolean = false

        fun start(context: Context) {
            cancelActionEnabled = true
            GlobalTaskProgressStore.show(
                title = context.getString(R.string.translation_keepalive_title),
                detail = context.getString(R.string.translation_preparing)
            )
            val intent = Intent(context, TranslationKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(
            context: Context,
            title: String,
            message: String,
            content: String,
            showCancelAction: Boolean = false
        ) {
            cancelActionEnabled = showCancelAction
            GlobalTaskProgressStore.show(
                title = title,
                detail = content
            )
            val intent = Intent(context, TranslationKeepAliveService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_CONTENT, content)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            cancelActionEnabled = false
            clearModelErrorAttention(context)
            val intent = Intent(context, TranslationKeepAliveService::class.java)
            context.stopService(intent)
        }

        fun updateStatus(context: Context, status: String) {
            GlobalTaskProgressStore.show(
                title = context.getString(R.string.translation_keepalive_title),
                detail = status
            )
            notifyProgress(
                context,
                context.getString(R.string.translation_keepalive_title),
                context.getString(R.string.translation_keepalive_message),
                status,
                null,
                null
            )
        }

        fun notifyModelErrorNeedsAttention(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureAlertChannel(context, manager)
            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_LIBRARY_TAB, true)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                ALERT_NOTIFICATION_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.model_response_failed_title))
                .setContentText(context.getString(R.string.model_error_attention_message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        }

        fun clearModelErrorAttention(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(ALERT_NOTIFICATION_ID)
        }

        internal fun startTranslationTask(
            context: Context,
            descriptor: TranslationTaskDescriptor,
            title: String = context.getString(R.string.translation_keepalive_title),
            message: String = context.getString(R.string.translation_keepalive_message),
            content: String = context.getString(R.string.translation_preparing)
        ) {
            cancelActionEnabled = true
            GlobalTaskProgressStore.show(title = title, detail = content)
            val intent = Intent(context, TranslationKeepAliveService::class.java).apply {
                action = ACTION_START_TRANSLATION_TASK
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_CONTENT, content)
                putExtra(EXTRA_TASK_DESCRIPTOR, descriptor.toJsonString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        internal fun resumePendingTask(context: Context) {
            val intent = Intent(context, TranslationKeepAliveService::class.java).apply {
                action = ACTION_RESUME_TRANSLATION_TASK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateStatus(context: Context, status: String, title: String, message: String) {
            GlobalTaskProgressStore.show(title = title, detail = status)
            notifyProgress(context, title, message, status, null, null)
        }

        fun updateProgress(context: Context, progress: Int, total: Int) {
            GlobalTaskProgressStore.show(
                title = context.getString(R.string.translation_keepalive_title),
                detail = "$progress/$total",
                progress = progress,
                total = total
            )
            notifyProgress(
                context,
                context.getString(R.string.translation_keepalive_title),
                context.getString(R.string.translation_keepalive_message),
                "$progress/$total",
                progress,
                total
            )
        }

        fun updateProgress(
            context: Context,
            progress: Int,
            total: Int,
            content: String,
            title: String,
            message: String
        ) {
            GlobalTaskProgressStore.show(
                title = title,
                detail = content,
                progress = progress,
                total = total
            )
            notifyProgress(context, title, message, content, progress, total)
        }

        private fun notifyProgress(
            context: Context,
            title: String,
            message: String,
            content: String,
            progress: Int?,
            total: Int?
        ) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(context, manager)
            val notification = buildNotification(context, title, message, content, progress, total)
            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun buildNotification(
            context: Context,
            title: String,
            message: String,
            content: String,
            progress: Int?,
            total: Int?
        ): Notification {
            val openIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(title)
                .setContentText(content)
                .setSubText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (cancelActionEnabled) {
                val cancelIntent = Intent(context, TranslationKeepAliveService::class.java).apply {
                    action = ACTION_CANCEL_TRANSLATION
                }
                val cancelPendingIntent = PendingIntent.getService(
                    context,
                    CANCEL_REQUEST_CODE,
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.translation_cancel_action),
                    cancelPendingIntent
                )
            }
            if (progress != null && total != null && total > 0) {
                builder.setProgress(total, progress.coerceAtMost(total), false)
            } else {
                builder.setProgress(0, 0, false)
            }
            return builder.build()
        }

        private fun showResultNotification(
            context: Context,
            resultTitle: String,
            taskLabel: String,
            isError: Boolean,
            isCanceled: Boolean
        ) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureResultChannel(context, manager)
            ensureSuccessResultChannel(context, manager)
            val pendingIntent = buildOpenLibraryPendingIntent(
                context = context,
                requestCode = RESULT_NOTIFICATION_REQUEST_CODE
            )
            val isSuccess = !isError && !isCanceled
            val icon = when {
                isError -> android.R.drawable.stat_notify_error
                isCanceled -> android.R.drawable.ic_menu_close_clear_cancel
                else -> android.R.drawable.stat_sys_upload_done
            }
            val category = when {
                isError -> NotificationCompat.CATEGORY_ERROR
                else -> NotificationCompat.CATEGORY_STATUS
            }
            val priority = when {
                isError -> NotificationCompat.PRIORITY_HIGH
                else -> NotificationCompat.PRIORITY_DEFAULT
            }
            val notification = NotificationCompat.Builder(
                context,
                if (isSuccess) SUCCESS_RESULT_CHANNEL_ID else RESULT_CHANNEL_ID
            )
                .setSmallIcon(icon)
                .setContentTitle(resultTitle)
                .setContentText(context.getString(R.string.translation_result_message, taskLabel))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.translation_result_message, taskLabel)
                    )
                )
                .setPriority(priority)
                .setCategory(category)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .apply {
                    if (isSuccess && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        setDefaults(Notification.DEFAULT_SOUND)
                    }
                }
                .build()
            manager.notify(RESULT_NOTIFICATION_ID, notification)
        }

        private fun buildOpenLibraryPendingIntent(
            context: Context,
            requestCode: Int
        ): PendingIntent {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_LIBRARY_TAB, true)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun describeTaskLabel(context: Context, descriptor: TranslationTaskDescriptor): String {
            return when (descriptor.mode) {
                TranslationTaskPersistence.MODE_COLLECTION -> descriptor.collectionFolderPath
                    ?.let(::File)
                    ?.name
                    .orEmpty()
                TranslationTaskPersistence.MODE_BATCH -> context.getString(
                    R.string.translation_result_batch_task_label,
                    descriptor.tasks.map { it.folderPath }.distinct().size.coerceAtLeast(1)
                )
                else -> descriptor.tasks.firstOrNull()
                    ?.folderPath
                    ?.let(::File)
                    ?.name
                    .orEmpty()
            }.ifBlank {
                context.getString(R.string.translation_result_task_fallback_label)
            }
        }

        private fun ensureChannel(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.translation_keepalive_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }

        private fun ensureResultChannel(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    RESULT_CHANNEL_ID,
                    context.getString(R.string.translation_result_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                channel.setSound(null, null)
                manager.createNotificationChannel(channel)
            }
        }

        private fun ensureSuccessResultChannel(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    SUCCESS_RESULT_CHANNEL_ID,
                    context.getString(R.string.translation_success_result_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                channel.setSound(soundUri, audioAttributes)
                manager.createNotificationChannel(channel)
            }
        }

        private fun ensureAlertChannel(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    ALERT_CHANNEL_ID,
                    context.getString(R.string.model_error_attention_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
                manager.createNotificationChannel(channel)
            }
        }
    }
}

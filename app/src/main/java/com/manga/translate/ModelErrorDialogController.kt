package com.manga.translate

import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque

internal class ModelErrorDialogController(
    private val fragment: Fragment,
    private val dialogs: LibraryDialogs
) {
    private data class PendingRequest(
        val content: String,
        val onRetry: (() -> Unit)?,
        val onSkip: (() -> Unit)?,
        val useSystemOverlay: Boolean
    )

    private val pending = ArrayDeque<PendingRequest>()
    private var activeDialog: AlertDialog? = null
    private var activeRequest: PendingRequest? = null

    val hasActiveDialog: Boolean get() = activeDialog != null

    fun enqueue(
        content: String,
        useSystemOverlay: Boolean,
        onRetry: (() -> Unit)?,
        onSkip: (() -> Unit)?
    ) {
        if (!fragment.isAdded) {
            onSkip?.invoke()
            return
        }
        pending.addLast(
            PendingRequest(
                content = content,
                onRetry = onRetry,
                onSkip = onSkip,
                useSystemOverlay = useSystemOverlay
            )
        )
        showNext()
    }

    fun onResume() {
        showNext()
    }

    fun onDestroy() {
        resolveAllAsSkip()
    }

    private fun showNext() {
        if (!fragment.isAdded || activeDialog != null) return
        if (pending.isEmpty()) return
        val request = pending.first()
        if (!request.useSystemOverlay && !LibraryUiBridge.isAppInForeground()) return
        pending.removeFirst()

        val dialog = dialogs.showModelErrorDialog(
            fragment.requireContext(),
            request.content,
            onRetry = request.onRetry,
            onSkip = request.onSkip,
            onUnresolvedDismiss = { requeue(request) },
            onDialogDismissed = {
                activeRequest = null
                activeDialog = null
                showNext()
            },
            windowType = if (request.useSystemOverlay) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            } else {
                null
            }
        )
        activeRequest = request
        activeDialog = dialog
    }

    private fun requeue(request: PendingRequest) {
        if (!fragment.isAdded) {
            request.onSkip?.invoke()
            return
        }
        activeRequest = null
        activeDialog = null
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            delay(15_000L)
            if (!fragment.isAdded || fragment.view == null) {
                request.onSkip?.invoke()
                return@launch
            }
            pending.addFirst(request)
            showNext()
        }
    }

    private fun resolveAllAsSkip() {
        val toSkip = ArrayList<PendingRequest>(pending.size + 1)
        activeRequest?.let(toSkip::add)
        toSkip.addAll(pending)
        pending.clear()
        activeRequest = null
        val dialog = activeDialog
        activeDialog = null
        if (dialog != null) {
            dialog.setOnDismissListener(null)
            runCatching { dialog.dismiss() }
        }
        toSkip.forEach { request ->
            runCatching { request.onSkip?.invoke() }
        }
    }
}

package app.fuckclip.hook

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.fuckclip.core.PrefKeys
import app.fuckclip.core.limiter.RogueAppLimiter
import app.fuckclip.core.model.WhitelistRule
import app.fuckclip.hook.model.PendingRequest
import io.github.libxposed.api.XposedModule

private const val WRITE_PROMPT_TIMEOUT_MS = 14_000L

/**
 * Manages clipboard authorization prompt queueing, dialog display, timeout scheduling, and decision dispatch.
 *
 * 管理剪贴板授权弹窗排队、界面展示、超时控制与用户授权决策派发。
 */
internal class ClipboardPromptManager(
    private val hook: ClipboardHook,
    private val module: XposedModule,
    private val rogueLimiter: RogueAppLimiter
) {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingQueue = ArrayList<PendingRequest>()
    private var activeDialog: ClipboardRequestDialog? = null
    private var dialogTimeoutRunnable: Runnable? = null

    /**
     * Enqueues a clipboard access request for prompting on the main thread.
     *
     * 将剪贴板访问请求排入授权弹窗队列，并调度至主线程展示。
     *
     * The interceptor runs on a Binder thread that has no Looper of its own, so dialogs can never be
     * created there; every dialog and queue operation is therefore marshalled onto the main thread.
     *
     * 拦截器运行在不具备 Looper 的 Binder 线程上，无法在该线程创建对话框；
     * 因此所有弹窗与队列操作统一转交主线程执行。
     *
     * @param pending Clipboard access request awaiting a user decision.
     */
    fun enqueueRequest(pending: PendingRequest) {
        handler.post { enqueueOnMainThread(pending) }
    }

    fun expireRead(pending: PendingRequest) {
        denyRead(pending)
        handler.post {
            if (pendingQueue.firstOrNull() === pending) {
                failPendingRequest(pending, "Read prompt timed out")
            } else if (pendingQueue.remove(pending)) {
                rogueLimiter.onPromptFinished(pending.callerKey)
                hook.notifyHookLog(pending.packageName, "READ", allowed = false, detail = "Read prompt timed out")
            }
        }
    }

    /**
     * Registers a pending request and starts prompting when no dialog is currently active.
     *
     * 注册待处理请求，并在当前没有活动弹窗时立即拉起询问流程。
     */
    private fun enqueueOnMainThread(pending: PendingRequest) {
        if (pending.isWrite && pendingQueue.any {
                it.isWrite && it.isClear == pending.isClear && it.callerKey == pending.callerKey
            }
        ) {
            return
        }

        pendingQueue.add(pending)
        displayHeadRequest()
    }

    /**
     * Dismisses active dialogs and cancels pending requests during reset.
     *
     * 在模块重置时取消弹窗超时、解散当前对话框并拒绝队列中所有等待请求。
     */
    fun reset() {
        handler.post {
            cancelDialogTimeout()
            for (pending in pendingQueue) {
                finishRead(pending, granted = false)
            }
            pendingQueue.clear()
            activeDialog?.dismiss()
            activeDialog = null
        }
    }

    /**
     * Creates and shows the authorization dialog for the head request of the pending queue.
     *
     * 为等待队列头部的请求创建并显示授权弹窗。
     *
     * Must run on the main thread. If the dialog cannot be created or shown, the request is denied
     * once and the queue keeps moving instead of stalling forever.
     *
     * 必须在主线程执行。若弹窗无法创建或显示，则该请求被单次拒绝，队列继续推进而不会永久阻塞。
     */
    private fun displayHeadRequest() {
        if (activeDialog != null) return
        val pending = pendingQueue.firstOrNull() ?: return
        if (pending.readSync?.remainingMs() == 0L) {
            failPendingRequest(pending, "Read prompt timed out")
            return
        }

        val context = hook.getSystemContext()
        if (context == null) {
            module.log(Log.ERROR, TAG, "System context unavailable, denying prompt for ${pending.packageName}")
            failPendingRequest(pending, "Denied by prompt failure (no system context)")
            return
        }

        val prefs = try { module.getRemotePreferences(PrefKeys.PREFS_GROUP) } catch (_: Throwable) { null }
        val preferredLanguage = prefs?.getString(PrefKeys.KEY_SELECTED_LANGUAGE, "").orEmpty()
        val colorMode = prefs?.getInt(PrefKeys.KEY_THEME_COLOR_MODE, 0) ?: 0
        val keyColor = prefs?.getInt(PrefKeys.KEY_THEME_KEY_COLOR, 0) ?: 0

        val dialog = try {
            ClipboardRequestDialog(
                context = context,
                preferredLanguage = preferredLanguage,
                colorMode = colorMode,
                keyColor = keyColor
            )
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "Failed creating clipboard request dialog", t)
            failPendingRequest(pending, "Denied by prompt failure (dialog creation)")
            return
        }
        activeDialog = dialog

        val pm = context.packageManager
        val appInfo = try {
            pm.getApplicationInfo(pending.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val appLabel = appInfo?.loadLabel(pm) ?: pending.packageName
        val appIcon: Drawable? = appInfo?.loadIcon(pm)

        val clipText = if (pending.isWrite) {
            hook.extractClipText(pending.clipData, pending.captureContent)
        } else {
            val snapshot = pending.readPreviewClip?.takeIf { ReadClipboardPreview.canShow(context) }
            hook.extractClipText(snapshot, captureContent = true, textOnly = true)?.also {
                pending.readSync?.previewedClip = snapshot
            }
        }

        dialog.bindRequest(
            appLabel = appLabel,
            packageName = pending.packageName,
            appIcon = appIcon,
            isWrite = pending.isWrite,
            isClear = pending.isClear,
            clipContent = clipText
        ) { granted, remember ->
            handleDecision(pending, granted, remember)
        }

        try {
            dialog.show()
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "Failed showing clipboard request dialog", t)
            failPendingRequest(pending, "Denied by prompt failure (dialog display)")
            return
        }
        scheduleDialogTimeout(pending)
    }

    private fun handleDecision(pending: PendingRequest, granted: Boolean, remember: Boolean) {
        if (pendingQueue.firstOrNull() !== pending) return
        if (pending.readSync?.remainingMs() == 0L) {
            failPendingRequest(pending, "Read prompt timed out")
            return
        }

        if (!pending.isWrite && !granted) denyRead(pending)
        pending.readSync?.rememberApproval = remember
        val decidedRule = if (granted) WhitelistRule.RULE_ALLOW else WhitelistRule.RULE_DENY
        if (pending.isWrite || !granted) {
            hook.notifyHookLog(
                packageName = pending.packageName,
                action = if (pending.isWrite) "WRITE" else "READ",
                allowed = granted,
                detail = when {
                    pending.isClear && granted -> "Clipboard clear allowed by user dialog"
                    pending.isClear -> "Clipboard clear denied by user dialog"
                    granted -> "Allowed by user dialog"
                    else -> "Denied by user dialog"
                },
                content = hook.extractClipText(pending.clipData, pending.captureContent)
            )
        }

        if (remember && (pending.isWrite || !granted)) {
            hook.broadcastRuleUpdate(pending.packageName, pending.isWrite, decidedRule)
        }

        if (pending.isWrite && granted) {
            pending.replay?.transact()
        }

        settlePendingRequest(pending, granted)
    }

    /**
     * Denies a request whose dialog could not be raised and releases all of its waiting state.
     *
     * 当授权弹窗无法弹出时，以拒绝方式终结该请求并释放其全部等待状态。
     *
     * @param pending Request that never reached the user.
     * @param detail Audit detail describing the failure cause.
     */
    private fun failPendingRequest(pending: PendingRequest, detail: String) {
        if (!pending.isWrite) denyRead(pending)
        hook.notifyHookLog(
            packageName = pending.packageName,
            action = if (pending.isWrite) "WRITE" else "READ",
            allowed = false,
            detail = if (pending.isClear) "Clipboard clear: $detail" else detail,
            content = hook.extractClipText(pending.clipData, pending.captureContent)
        )

        settlePendingRequest(pending, granted = false)
    }

    /**
     * Releases the waiting latches of [pending], drops it from the queue and advances to the next request.
     *
     * 释放 [pending] 的等待闭锁，将其移出队列并推进显示下一条待处理请求。
     *
     * @param pending Request that has just been resolved.
     * @param granted Whether clipboard access was granted.
     */
    private fun settlePendingRequest(pending: PendingRequest, granted: Boolean) {
        cancelDialogTimeout()
        pendingQueue.remove(pending)
        val dialog = activeDialog
        activeDialog = null
        dialog?.dismiss()
        finishRead(pending, granted)
        handler.post { displayHeadRequest() }
    }

    private fun finishRead(pending: PendingRequest, granted: Boolean) {
        val sync = pending.readSync ?: return
        rogueLimiter.onPromptFinished(pending.callerKey)
        synchronized(sync) {
            val remaining = sync.remainingMs()
            if (granted && remaining > 0) {
                sync.approval = rogueLimiter.beginReadGrant(pending.callerKey, remaining)
                sync.granted.set(true)
            }
            sync.latch.countDown()
        }
    }

    private fun denyRead(pending: PendingRequest) {
        val duration = hook.readSettingMs(PrefKeys.KEY_DENY_COOLDOWN_SECONDS, RogueAppLimiter.DEFAULT_DENY_COOLDOWN_MS)
        rogueLimiter.setDenyCooldown(pending.callerKey, duration)
    }

    private fun scheduleDialogTimeout(pending: PendingRequest) {
        cancelDialogTimeout()
        val r = Runnable {
            if (pendingQueue.firstOrNull() === pending) {
                failPendingRequest(pending, "Authorization prompt timed out")
            }
        }
        dialogTimeoutRunnable = r
        handler.postDelayed(r, pending.readSync?.remainingMs() ?: WRITE_PROMPT_TIMEOUT_MS)
    }

    private fun cancelDialogTimeout() {
        dialogTimeoutRunnable?.let { handler.removeCallbacks(it) }
        dialogTimeoutRunnable = null
    }

    private companion object {
        const val TAG = "ClipboardPromptManager"
    }
}

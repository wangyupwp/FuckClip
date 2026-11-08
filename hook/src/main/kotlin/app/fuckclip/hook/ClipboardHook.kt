package app.fuckclip.hook

import android.app.AppOpsManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import app.fuckclip.core.ModuleConstants
import app.fuckclip.core.PrefKeys
import app.fuckclip.core.limiter.ReadThrottleResult
import app.fuckclip.core.limiter.RogueAppLimiter
import app.fuckclip.core.model.WhitelistRule
import app.fuckclip.hook.model.PendingRequest
import app.fuckclip.hook.model.ReadSync
import app.fuckclip.hook.model.Replay
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Maximum number of clipboard characters captured for audit logs and prompt previews.
 *
 * 记录日志与弹窗预览时截取的剪贴板最大字符数。
 */
private const val MAX_CAPTURED_TEXT_CHARS = 8_192
private const val SENSITIVE_CLIP_KEY = "android.content.extra.IS_SENSITIVE"

/**
 * Marker appended when clipboard text is cut off at [MAX_CAPTURED_TEXT_CHARS].
 *
 * 剪贴板文本超出 [MAX_CAPTURED_TEXT_CHARS] 截断时追加的省略标记。
 */
private const val TRUNCATION_MARKER = "…"
 

/**
 * Interceptor responsible for managing clipboard access permissions (Ask, Allow, Deny) across applications.
 *
 * 负责管理应用剪贴板权限（询问、允许、拒绝三种模式）并提供悬浮拦截弹窗的核心钩子协调器。
 */
internal class ClipboardHook(
    /**
     * Module instance providing hooking APIs and remote preferences.
     *
     * 提供 Hook API 与远程配置的模块实例。
     */
    internal val module: XposedModule
) {
    private val androidHook = AndroidClipboardHook(this)
    private val samsungHook = SamsungClipboardHook(this)

    private val rogueLimiter = RogueAppLimiter(SystemClock::elapsedRealtime)
    private val waitingReads = Semaphore(4)
    private val hookedMethods = HashSet<Method>()
    private val scannedClasses = HashSet<Class<*>>()

    private val promptManager = ClipboardPromptManager(
        hook = this,
        module = module,
        rogueLimiter = rogueLimiter
    )

    /**
     * Installs clipboard interception hooks for system services using dual-path discovery.
     *
     * 通过双路径发现机制为系统服务安装剪贴板拦截钩子。
     */
    fun install() {
        androidHook.install()
        samsungHook.install()
    }

    /**
     * Resets internal caches, queue, and hooked method states during hot reloading.
     *
     * 在模块热重载时重置内部缓存、等待队列与已挂钩方法状态。
     */
    fun reset() {
        promptManager.reset()
        hookedMethods.clear()
        scannedClasses.clear()
        SystemContextResolver.reset()
        rogueLimiter.reset()
    }

    /**
     * Inspects the clipboard service implementation [clazz] and attaches interceptors to getPrimaryClip and setPrimaryClip.
     *
     * 扫描剪贴板服务实现类 [clazz]，为 getPrimaryClip 与 setPrimaryClip 方法挂载拦截器。
     */
    internal fun hookClipboardService(clazz: Class<*>) {
        if (!scannedClasses.add(clazz)) {
            return
        }

        var currentClass: Class<*>? = clazz
        while (currentClass != null && currentClass != Any::class.java) {
            for (method in currentClass.declaredMethods) {
                if ((method.modifiers and Modifier.PUBLIC) == 0) {
                    continue
                }
                val params = method.parameterTypes

                if (method.name == "getPrimaryClip" &&
                    method.returnType == ClipData::class.java &&
                    params.isNotEmpty() &&
                    params[0] == String::class.java
                ) {
                    hookMethod(method, isWrite = false)
                } else if (method.returnType == java.lang.Void.TYPE &&
                    ((method.name == "setPrimaryClip" && params.size >= 2 &&
                        params[0] == ClipData::class.java && params[1] == String::class.java) ||
                        (method.name == "clearPrimaryClip" && params.firstOrNull() == String::class.java))
                ) {
                    hookMethod(method, isWrite = true)
                }
            }
            currentClass = currentClass.superclass
        }
    }

    private fun hookMethod(method: Method, isWrite: Boolean) {
        if (!hookedMethods.add(method)) {
            return
        }

        try {
            method.isAccessible = true
            val invoker = module.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN)

            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (isWrite) {
                        handleClipboardWrite(chain, method, invoker)
                    } else {
                        handleGetPrimaryClip(chain)
                    }
                }
            module.log(Log.INFO, TAG, "Hooked clipboard method: $method (write=$isWrite)")
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "Failed to hook method $method", t)
        }
    }

    private fun handleGetPrimaryClip(chain: XposedInterface.Chain): Any? {
        if (isFrameworkCaller() || !isMasterEnabled()) return chain.proceed()
        val packageName = (chain.getArg(0) as? String)
            ?: getSystemContext()?.packageManager?.getNameForUid(Binder.getCallingUid())
            ?: return null
        if (!isCallerPackage(packageName)) return null

        val rule = resolvePackageRule(packageName, isWrite = false)
        val caller = callerKey(packageName)

        val prefs = try { module.getRemotePreferences(PrefKeys.PREFS_GROUP) } catch (_: Throwable) { null }
        val rogueThrottleEnabled = prefs?.getBoolean(PrefKeys.KEY_ROGUE_THROTTLE_ENABLED, true) ?: true
        val maxReadFrequency = prefs?.getInt(PrefKeys.KEY_MAX_READ_FREQUENCY, RogueAppLimiter.DEFAULT_MAX_ATTEMPTS)
            ?: RogueAppLimiter.DEFAULT_MAX_ATTEMPTS
        val readTolerance = prefs?.getInt(PrefKeys.KEY_READ_TOLERANCE_COUNT, RogueAppLimiter.DEFAULT_PROMPT_TOLERANCE)
            ?: RogueAppLimiter.DEFAULT_PROMPT_TOLERANCE

        val throttleResult = rogueLimiter.evaluateRead(
            packageName = caller,
            isRuleRequest = (rule == WhitelistRule.RULE_REQUEST),
            enabled = rogueThrottleEnabled,
            maxAttempts = maxReadFrequency,
            promptTolerance = readTolerance
        )

        when (throttleResult) {
            is ReadThrottleResult.Throttled -> {
                notifyHookLog(packageName, "READ", allowed = false, detail = throttleResult.reason)
                return null
            }
            is ReadThrottleResult.FastTrackAllow -> {
                val grant = throttleResult.grant
                val expected = grant.clipIdentity as? ClipData
                if (expected != null && snapshotForRead(chain) === expected) {
                    val result = readFromPlatform(chain, packageName, "Allowed by burst window", expected) {
                        rogueLimiter.isReadGrantCurrent(caller, grant)
                    }
                    if (result == null) rogueLimiter.cancelReadGrant(caller, grant)
                    return result
                }
                rogueLimiter.cancelReadGrant(caller, grant)
            }
            ReadThrottleResult.Proceed -> {
                // Proceed with normal evaluation
            }
        }

        when (rule) {
            WhitelistRule.RULE_ALLOW -> {
                return readFromPlatform(chain, packageName, "Allowed by rule")
            }
            WhitelistRule.RULE_DENY -> {
                notifyHookLog(packageName, "READ", allowed = false, detail = "Denied by configured rule")
                return null
            }
            else -> {
                if (!waitingReads.tryAcquire()) {
                    notifyHookLog(packageName, "READ", allowed = false, detail = "Too many pending read prompts")
                    return null
                }
                try {
                    return awaitReadDecision(chain, packageName)
                } finally {
                    waitingReads.release()
                }
            }
        }
    }

    private fun snapshotForRead(chain: XposedInterface.Chain): ClipData? {
        val target = ReadClipboardPreview.forRequest(chain.thisObject, chain.args, Binder.getCallingUid())
            ?: return null
        val token = Binder.clearCallingIdentity()
        return try {
            getSystemContext()?.let(target::snapshot)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun awaitReadDecision(chain: XposedInterface.Chain, packageName: String): ClipData? {
        val sync = ReadSync()
        val pending = PendingRequest(
            packageName = packageName,
            callerKey = callerKey(packageName),
            isWrite = false,
            clipData = null,
            replay = null,
            readSync = sync,
            readPreviewClip = snapshotForRead(chain)
        )

        rogueLimiter.onPromptStarted(pending.callerKey)
        promptManager.enqueueRequest(pending)

        val answered = try {
            sync.latch.await(sync.remainingMs(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }

        if (!answered) {
            synchronized(sync) {
                sync.cancel()
                promptManager.expireRead(pending)
            }
            return null
        }
        if (!sync.granted.get()) return null
        val approval = sync.approval ?: return null
        try {
            val expected = sync.previewedClip
            if (expected != null && snapshotForRead(chain) !== expected) {
                notifyHookLog(packageName, "READ", allowed = false, detail = "Clipboard changed after preview")
                return null
            }
            val clip = readFromPlatform(chain, packageName, "Allowed by user dialog", expected) {
                rogueLimiter.isReadGrantCurrent(pending.callerKey, approval)
            } ?: return null
            if (sync.rememberApproval && rogueLimiter.isReadGrantCurrent(pending.callerKey, approval)) {
                broadcastRuleUpdate(packageName, isWrite = false, WhitelistRule.RULE_ALLOW)
            } else if (!sync.rememberApproval && expected != null) {
                val duration = readSettingMs(PrefKeys.KEY_GRANT_BURST_SECONDS, RogueAppLimiter.DEFAULT_GRANT_BURST_MS)
                rogueLimiter.completeReadGrant(pending.callerKey, approval, clip, duration)
            }
            return clip
        } finally {
            rogueLimiter.cancelReadGrant(pending.callerKey, approval)
        }
    }

    private fun readFromPlatform(
        chain: XposedInterface.Chain,
        packageName: String,
        detail: String,
        previewedClip: ClipData? = null,
        isAuthorized: () -> Boolean = { true }
    ): ClipData? {
        if (!isAuthorized()) return null
        val captureContent = callerMayShareContent()
        val clip = chain.proceed() as? ClipData
        if (!isAuthorized()) return null
        if (previewedClip != null && clip != null && clip !== previewedClip) {
            notifyHookLog(packageName, "READ", allowed = false, detail = "Clipboard changed after preview")
            return null
        }
        notifyHookLog(
            packageName, "READ", allowed = clip != null,
            detail = if (clip == null) "Android returned no clipboard data" else detail,
            content = extractClipText(clip, captureContent)
        )
        return clip
    }

    private fun handleClipboardWrite(
        chain: XposedInterface.Chain,
        method: Method,
        invoker: XposedInterface.Invoker<*, Method>
    ): Any? {
        if (isFrameworkCaller() || !isMasterEnabled()) return chain.proceed()
        val isClear = method.name == "clearPrimaryClip"
        val packageName = (chain.getArg(if (isClear) 0 else 1) as? String)
            ?: getSystemContext()?.packageManager?.getNameForUid(Binder.getCallingUid())
            ?: return null
        val clipData = if (isClear) null else chain.getArg(0) as? ClipData

        if (!isCallerPackage(packageName)) return null

        val rule = resolvePackageRule(packageName, isWrite = true)
        val captureContent = callerMayShareContent()

        when (rule) {
            WhitelistRule.RULE_ALLOW -> {
                val result = chain.proceed()
                val text = extractClipText(clipData, captureContent)
                val detail = if (isClear) "Clipboard clear allowed by rule" else "Allowed by rule"
                notifyHookLog(packageName, "WRITE", allowed = true, detail = detail, content = text)
                return result
            }
            WhitelistRule.RULE_DENY -> {
                val text = extractClipText(clipData, captureContent)
                val detail = if (isClear) "Clipboard clear denied by rule" else "Denied by rule"
                notifyHookLog(packageName, "WRITE", allowed = false, detail = detail, content = text)
                return null
            }
            else -> {
                // RULE_REQUEST: suspend call and prompt asynchronously
                val callerKey = callerKey(packageName)
                val callingToken = Binder.clearCallingIdentity()
                try {
                    val replay = Replay(method, invoker, chain.thisObject, chain.args.toTypedArray<Any?>(), callingToken)
                    val pending = PendingRequest(
                        packageName = packageName,
                        callerKey = callerKey,
                        isWrite = true,
                        clipData = clipData,
                        replay = replay,
                        captureContent = captureContent,
                        isClear = isClear
                    )
                    promptManager.enqueueRequest(pending)
                    return null
                } finally {
                    Binder.restoreCallingIdentity(callingToken)
                }
            }
        }
    }

    private fun isFrameworkCaller(): Boolean =
        Binder.getCallingUid() == android.os.Process.SYSTEM_UID ||
            Binder.getCallingPid() == android.os.Process.myPid()

    private fun callerKey(packageName: String): String = "${Binder.getCallingUid()}:$packageName"

    internal fun readSettingMs(key: String, defaultMs: Long): Long = try {
        module.getRemotePreferences(PrefKeys.PREFS_GROUP)
            .getInt(key, (defaultMs / 1000).toInt()).coerceAtLeast(0) * 1000L
    } catch (_: Exception) {
        defaultMs
    }

    // Audit broadcasts go to the manager in the system process's user.
    private fun callerMayShareContent(): Boolean =
        UserHandle.getUserHandleForUid(Binder.getCallingUid()) == Process.myUserHandle()

    private fun isCallerPackage(packageName: String): Boolean {
        val appOps = getSystemContext()?.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            appOps.checkPackage(Binder.getCallingUid(), packageName)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isMasterEnabled(): Boolean = try {
        module.getRemotePreferences(PrefKeys.PREFS_GROUP).getBoolean(PrefKeys.KEY_MASTER_ENABLED, true)
    } catch (_: Exception) {
        true
    }

    private fun resolvePackageRule(packageName: String, isWrite: Boolean): Int {
        try {
            val prefs = module.getRemotePreferences(PrefKeys.PREFS_GROUP)
            val rawWhitelist = prefs.getString(PrefKeys.KEY_WHITELIST, null)
            if (!rawWhitelist.isNullOrBlank()) {
                val array = JSONArray(rawWhitelist)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (obj.optString("packageName") == packageName) {
                        val readRule = obj.optInt("readRule", WhitelistRule.RULE_REQUEST)
                        val writeRule = obj.optInt("writeRule", WhitelistRule.RULE_REQUEST)
                        return if (isWrite) writeRule else readRule
                    }
                }
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Failed resolving rule for $packageName", t)
        }
        return WhitelistRule.RULE_REQUEST
    }

    /**
     * Broadcasts updated permission rules for [packageName] to the manager app.
     *
     * 向管理器应用广播发送指定 [packageName] 更新后的权限规则。
     */
    internal fun broadcastRuleUpdate(packageName: String, isWrite: Boolean, rule: Int) {
        try {
            val context = getSystemContext() ?: return
            val intent = Intent(ModuleConstants.ACTION_UPDATE_RULE).apply {
                setClassName(ModuleConstants.APPLICATION_ID, ModuleConstants.HOOK_RECEIVER_CLASS)
                putExtra("pkg", packageName)
                putExtra("isWrite", isWrite)
                putExtra("rule", rule)
            }
            val token = Binder.clearCallingIdentity()
            try {
                context.sendBroadcast(intent)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Failed sending rule update broadcast", t)
        }
    }

    /**
     * Extracts a bounded preview without opening app-supplied content URIs.
     *
     * 提取有限长度的预览，不打开应用提供的内容 URI。
     */
    internal fun extractClipText(clipData: ClipData?, captureContent: Boolean, textOnly: Boolean = false): String? {
        if (!captureContent || clipData == null) return null
        val sensitive = try {
            clipData.description.extras?.getBoolean(SENSITIVE_CLIP_KEY) == true
        } catch (_: RuntimeException) {
            true
        }
        if (sensitive) return null
        val sb = StringBuilder()
        for (i in 0 until clipData.itemCount) {
            val item = clipData.getItemAt(i) ?: continue
            val text = item.text?.toString()
                ?: if (textOnly) null else item.htmlText ?: item.uri?.toString() ?: item.intent?.toUri(0)
            if (!text.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                val remaining = MAX_CAPTURED_TEXT_CHARS - sb.length
                if (remaining <= 0) break
                if (text.length > remaining) {
                    sb.append(text, 0, remaining)
                    sb.append(TRUNCATION_MARKER)
                    break
                }
                sb.append(text)
            }
        }
        return sb.toString().ifEmpty { null }
    }

    /**
     * Emits a broadcast notifying the manager app of a clipboard hook event.
     *
     * 发送广播通知管理器应用剪贴板拦截或放行事件。
     */
    internal fun notifyHookLog(
        packageName: String,
        action: String,
        allowed: Boolean,
        detail: String,
        content: String? = null
    ) {
        val (shouldEmit, suppressedCount) = rogueLimiter.checkLogDebounce(packageName)
        if (!shouldEmit) {
            return
        }
        val finalDetail = if (suppressedCount > 0) {
            "$detail (+$suppressedCount suppressed)"
        } else {
            detail
        }
        try {
            val context = getSystemContext() ?: return
            val intent = Intent(ModuleConstants.ACTION_HOOK_LOG).apply {
                setClassName(ModuleConstants.APPLICATION_ID, ModuleConstants.HOOK_RECEIVER_CLASS)
                putExtra("pkg", packageName)
                putExtra("action", action)
                putExtra("allowed", allowed)
                putExtra("source", finalDetail)
                putExtra("timestamp", System.currentTimeMillis())
                putExtra(ModuleConstants.EXTRA_AUDIT_CONTENT_FILTERED, true)
                putExtra("content", content)
                putExtra("count", suppressedCount + 1)
            }
            val token = Binder.clearCallingIdentity()
            try {
                context.sendBroadcast(intent)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Failed to send hook log broadcast", t)
        }
    }

    /**
     * Resolves and caches the system server [Context].
     *
     * 解析并缓存 system_server 进程的系统级 [Context]。
     */
    internal fun getSystemContext(): Context? = SystemContextResolver.getSystemContext()

    private companion object {
        const val TAG = "ClipboardHook"
    }
}

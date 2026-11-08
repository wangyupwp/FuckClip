package app.fuckclip.hook.model

import android.content.ClipData
import android.os.Binder
import android.os.SystemClock
import android.util.Log
import app.fuckclip.core.limiter.ReadGrant
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronization latch and grant flag for suspended synchronous read requests.
 *
 * 用于挂起阻塞型读取请求的同步闭锁与授权标识。
 */
internal class ReadSync {
    private val deadlineMs = SystemClock.elapsedRealtime() + 12_000L
    private val cancelled = AtomicBoolean(false)

    fun remainingMs(): Long =
        if (cancelled.get()) 0 else (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Latch awaiting user decision on the prompt dialog.
     *
     * 等待用户在授权弹窗中作出决断的同步闭锁。
     */
    val latch = CountDownLatch(1)

    /**
     * Whether clipboard access was granted by the user.
     *
     * 剪贴板访问是否获得用户授权。
     */
    val granted = AtomicBoolean(false)

    // Published to the waiting Binder thread through latch.countDown().
    var previewedClip: ClipData? = null
    var approval: ReadGrant? = null
    var rememberApproval = false
}

/**
 * Intercepted write operation replay descriptor.
 *
 * 拦截到的写剪贴板操作重放描述符，在用户点击允许后恢复执行。
 */
internal class Replay(
    /**
     * Original clipboard write method intercepted by the hook.
     *
     * 被钩子拦截的原始剪贴板写入方法。
     */
    val method: Method,

    /**
     * Module invoker used to execute the original write method directly.
     *
     * 用于直接调用原写入方法的模块调用器。
     */
    val invoker: XposedInterface.Invoker<*, Method>,

    /**
     * Target clipboard service instance on which to invoke the method.
     *
     * 执行写入方法的目标剪贴板服务实例。
     */
    val service: Any?,

    /**
     * Method arguments passed to the original clipboard write call.
     *
     * 传递给原剪贴板写入调用的参数数组。
     */
    val args: Array<Any?>,

    /**
     * Binder IPC calling identity token restored during replay execution.
     *
     * 在重放执行期间用于恢复的 Binder IPC 调用方身份令牌。
     */
    val identity: Long
) {
    /**
     * Replays the suspended write transaction under the original caller's Binder identity.
     *
     * 在原始调用者的 Binder 身份下重放挂起的写入事务。
     */
    fun transact() {
        val id = Binder.clearCallingIdentity()
        try {
            Binder.restoreCallingIdentity(identity)
            invoker.invoke(service, *args)
        } catch (t: Throwable) {
            Log.e("Replay", "Failed replaying write transaction", t)
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }
}

/**
 * Representation of a pending clipboard access request awaiting user prompt decision.
 *
 * 等待用户在授权弹窗中做出决定的剪贴板访问请求对象。
 */
internal class PendingRequest(
    /**
     * Package name of the calling application.
     *
     * 发起剪贴板访问请求的应用包名。
     */
    val packageName: String,
    val callerKey: String,

    /**
     * Whether this request is a write operation (true for setPrimaryClip, false for getPrimaryClip).
     *
     * 当前请求是否为写入操作（true 为 setPrimaryClip，false 为 getPrimaryClip）。
     */
    val isWrite: Boolean,

    /**
     * Captured clipboard data involved in the operation, if available.
     *
     * 本次操作所捕获的剪贴板数据内容（若有）。
     */
    val clipData: ClipData?,

    /**
     * Replay descriptor for resuming suspended write operations upon user approval.
     *
     * 在用户允许后用于恢复挂起写入操作的重放描述符。
     */
    val replay: Replay?,

    val readSync: ReadSync? = null,
    val readPreviewClip: ClipData? = null,
    val captureContent: Boolean = false,
    val isClear: Boolean = false
)

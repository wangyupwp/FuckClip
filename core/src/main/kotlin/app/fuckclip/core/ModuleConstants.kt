package app.fuckclip.core

/**
 * Global module identity constants.
 *
 * 模块全局标识常量定义。
 */
object ModuleConstants {
    /**
     * Application ID of the manager application and module package on device.
     *
     * 模块管理器在 Android 系统中注册的 Application ID（包名）。
     */
    const val APPLICATION_ID: String = "io.github.cail_gainey.fuckclip"
    const val HOOK_RECEIVER_CLASS: String = "app.fuckclip.receiver.HookLogReceiver"

    /**
     * Broadcast action sent from system_server to manager app when a hook event is logged.
     *
     * 拦截日志广播动作。
     */
    const val ACTION_HOOK_LOG: String = "$APPLICATION_ID.ACTION_HOOK_LOG"
    const val EXTRA_AUDIT_CONTENT_FILTERED = "auditContentFiltered"

    /**
     * Broadcast action sent from system_server to manager app when a rule is updated.
     *
     * 规则更新广播动作。
     */
    const val ACTION_UPDATE_RULE: String = "$APPLICATION_ID.ACTION_UPDATE_RULE"
}

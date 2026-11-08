package app.fuckclip.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.fuckclip.core.ModuleConstants
import app.fuckclip.core.model.HookLog
import app.fuckclip.core.model.WhitelistRule
import app.fuckclip.data.SettingsRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

/**
 * Broadcast receiver listening for hook interception events and rule updates originating from system services.
 *
 * 接收来自系统服务钩子拦截事件与规则更新广播的接收器。
 */
class HookLogReceiver : BroadcastReceiver(), KoinComponent {

    private val repository: SettingsRepository by inject()

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            ACTION_UPDATE_RULE -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val rule = intent.getIntExtra("rule", WhitelistRule.RULE_REQUEST)
                if (rule != WhitelistRule.RULE_ALLOW && rule != WhitelistRule.RULE_DENY) return
                repository.applyHookRule(pkg, intent.getBooleanExtra("isWrite", false), rule)
            }
            ACTION_HOOK_LOG -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val action = intent.getStringExtra("action") ?: "READ"
                val allowed = intent.getBooleanExtra("allowed", true)
                val source = intent.getStringExtra("source") ?: ""
                val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
                // An older Hook may still be loaded until the system framework restarts.
                val content = if (intent.getBooleanExtra(ModuleConstants.EXTRA_AUDIT_CONTENT_FILTERED, false)) {
                    intent.getStringExtra("content")
                } else {
                    null
                }
                val count = intent.getIntExtra("count", 1)

                val appName = try {
                    context?.packageManager?.let { pm ->
                        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                    }
                } catch (_: Throwable) {
                    null
                }

                val log = HookLog(
                    id = UUID.randomUUID().toString(),
                    packageName = pkg,
                    action = action,
                    allowed = allowed,
                    detail = source,
                    timestamp = timestamp,
                    content = content,
                    appName = appName,
                    count = count.coerceAtLeast(1)
                )
                val settings = repository
                val pending = goAsync()
                settings.recordHookLog(log).invokeOnCompletion { pending.finish() }
            }
        }
    }

    companion object {
        /**
         * Broadcast action indicating a clipboard hook interception or access event.
         *
         * 标识剪贴板钩子拦截或放行访问事件的广播动作。
         */
        const val ACTION_HOOK_LOG: String = ModuleConstants.ACTION_HOOK_LOG

        /**
         * Broadcast action requesting an update to application permission rules.
         *
         * 请求更新应用权限规则的广播动作。
         */
        const val ACTION_UPDATE_RULE: String = ModuleConstants.ACTION_UPDATE_RULE
    }
}

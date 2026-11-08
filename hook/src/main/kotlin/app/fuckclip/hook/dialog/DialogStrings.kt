package app.fuckclip.hook.dialog

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import app.fuckclip.core.ModuleConstants
import java.util.Locale

/**
 * Localized string resources used by the clipboard request dialog.
 *
 * 剪贴板权限申请弹窗所使用的本地化文案集合。
 */
internal data class DialogStrings(
    /**
     * Text badge label for clipboard write operations.
     *
     * 写入剪贴板操作的徽标标签文案。
     */
    val badgeWrite: String,

    /**
     * Text badge label for clipboard read operations.
     *
     * 读取剪贴板操作的徽标标签文案。
     */
    val badgeRead: String,

    /**
     * Prompt explanation message for clipboard write operations.
     *
     * 剪贴板写入操作的提示说明文案。
     */
    val promptNoticeWrite: String,
    val promptNoticeClear: String,

    /**
     * Prompt explanation message for clipboard read operations.
     *
     * 剪贴板读取操作的提示说明文案。
     */
    val promptNoticeRead: String,

    /**
     * Header title label above write content preview.
     *
     * 写入内容预览区上方的标题文案。
     */
    val previewHeaderWrite: String,

    /**
     * Header title label above read content preview.
     *
     * 当前剪贴板内容预览区上方的标题文案。
     */
    val previewHeaderRead: String,

    /**
     * Placeholder displayed when clipboard content is empty.
     *
     * 剪贴板内容为空时显示的占位文案。
     */
    val previewEmpty: String,

    /**
     * String format pattern for displaying character count.
     *
     * 展示字符计数的格式化模板字符串。
     */
    val previewCharsFormat: String,

    /**
     * Primary label for the remember choice checkbox.
     *
     * “记住此选择”复选框的标题文案。
     */
    val rememberTitle: String,

    /**
     * Subtitle explanation for the remember choice checkbox.
     *
     * “记住此选择”复选框的副标题说明文案。
     */
    val rememberSubtitle: String,

    /**
     * Label text for the deny action button.
     *
     * 拒绝操作按钮的文字文案。
     */
    val deny: String,

    /**
     * Label text for the allow action button.
     *
     * 允许操作按钮的文字文案。
     */
    val allow: String,

    /**
     * Fallback text when the application name is unavailable.
     *
     * 无法解析应用名称时使用的默认回退文案。
     */
    val unknownApp: String
) {
    companion object {
        /**
         * Built-in string resources for Simplified Chinese.
         *
         * 简体中文内置字符串资源集合。
         */
        val ZH_CN = DialogStrings(
            badgeWrite = "写入剪贴板",
            badgeRead = "读取剪贴板",
            promptNoticeWrite = "该应用正在尝试修改您的系统剪贴板内容。",
            promptNoticeClear = "该应用正在请求清空您的剪贴板。",
            promptNoticeRead = "该应用正在申请读取当前剪贴板内容，请注意保护隐私安全。",
            previewHeaderWrite = "即将写入的内容",
            previewHeaderRead = "当前剪贴板内容",
            previewEmpty = "(空内容)",
            previewCharsFormat = "%d 字符",
            rememberTitle = "记住此选择",
            rememberSubtitle = "后续将自动套用此规则",
            deny = "拒绝",
            allow = "允许",
            unknownApp = "未知应用"
        )

        /**
         * Built-in string resources for Traditional Chinese.
         *
         * 繁体中文内置字符串资源集合。
         */
        val ZH_TW = DialogStrings(
            badgeWrite = "寫入剪貼簿",
            badgeRead = "讀取剪貼簿",
            promptNoticeWrite = "該應用程式正在嘗試修改您的系統剪貼簿內容。",
            promptNoticeClear = "該應用程式正在要求清空您的剪貼簿。",
            promptNoticeRead = "該應用程式正在申請讀取目前剪貼簿內容，請注意保護隱私安全。",
            previewHeaderWrite = "即將寫入的內容",
            previewHeaderRead = "目前剪貼簿內容",
            previewEmpty = "(空內容)",
            previewCharsFormat = "%d 字元",
            rememberTitle = "記住此選擇",
            rememberSubtitle = "後續將自動套用此規則",
            deny = "拒絕",
            allow = "允許",
            unknownApp = "未知應用程式"
        )

        /**
         * Built-in string resources for English.
         *
         * 英文内置字符串资源集合。
         */
        val EN = DialogStrings(
            badgeWrite = "Write Clipboard",
            badgeRead = "Read Clipboard",
            promptNoticeWrite = "This application is attempting to modify your clipboard content.",
            promptNoticeClear = "This application is requesting to clear your clipboard.",
            promptNoticeRead = "This application is requesting to read your clipboard content.",
            previewHeaderWrite = "Content to Write",
            previewHeaderRead = "Current Clipboard Content",
            previewEmpty = "(Empty)",
            previewCharsFormat = "%d chars",
            rememberTitle = "Remember this choice",
            rememberSubtitle = "Apply to future requests automatically",
            deny = "Deny",
            allow = "Allow",
            unknownApp = "Unknown app"
        )

        /**
         * Loads string resources dynamically from the module package if available, falling back to embedded dictionaries.
         *
         * 优先从模块包上下文动态加载本地化资源文案，在异常或未就绪时安全回退至内置词典。
         */
        fun load(context: Context, locale: DialogLocale): DialogStrings {
            val base = when (locale) {
                DialogLocale.ZH_TW -> ZH_TW
                DialogLocale.ZH_CN -> ZH_CN
                DialogLocale.EN -> EN
            }

            val pkgContext = try {
                val appCtx = context.createPackageContext(ModuleConstants.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
                val targetLocale = when (locale) {
                    DialogLocale.ZH_TW -> Locale.TRADITIONAL_CHINESE
                    DialogLocale.ZH_CN -> Locale.SIMPLIFIED_CHINESE
                    DialogLocale.EN -> Locale.ENGLISH
                }
                val config = Configuration(appCtx.resources.configuration)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    config.setLocales(LocaleList(targetLocale))
                } else {
                    @Suppress("DEPRECATION")
                    config.locale = targetLocale
                }
                appCtx.createConfigurationContext(config)
            } catch (_: Throwable) {
                null
            } ?: return base

            fun resStr(name: String, fallback: String): String {
                val id = pkgContext.resources.getIdentifier(name, "string", ModuleConstants.APPLICATION_ID)
                if (id == 0) return fallback
                return runCatching { pkgContext.getString(id) }.getOrDefault(fallback)
            }

            return DialogStrings(
                badgeWrite = resStr("request_badge_write", base.badgeWrite),
                badgeRead = resStr("request_badge_read", base.badgeRead),
                promptNoticeWrite = resStr("request_write_notice", base.promptNoticeWrite),
                promptNoticeClear = resStr("request_clear_notice", base.promptNoticeClear),
                promptNoticeRead = resStr("request_read_notice", base.promptNoticeRead),
                previewHeaderWrite = resStr("request_write_preview_title", base.previewHeaderWrite),
                previewHeaderRead = resStr("request_read_preview_title", base.previewHeaderRead),
                previewEmpty = resStr("request_content_empty", base.previewEmpty),
                previewCharsFormat = resStr("request_content_chars", base.previewCharsFormat),
                rememberTitle = resStr("request_remember", base.rememberTitle),
                rememberSubtitle = resStr("request_remember_hint", base.rememberSubtitle),
                deny = resStr("request_deny", base.deny),
                allow = resStr("request_allow", base.allow),
                unknownApp = resStr("request_unknown_app", base.unknownApp)
            )
        }
    }
}

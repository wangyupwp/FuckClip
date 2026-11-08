package app.fuckclip.data

import android.content.Context
import android.content.SharedPreferences
import app.fuckclip.core.PrefKeys
import app.fuckclip.core.model.HookLog
import app.fuckclip.core.limiter.RogueAppLimiter
import app.fuckclip.core.model.WhitelistRule
import app.fuckclip.ui.theme.ColorMode
import com.materialkolor.PaletteStyle
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Repository managing module activation state, remote preferences, whitelist rules, and hook logs.
 *
 * 管理模块激活状态、远程配置、白名单规则以及 Hook 拦截日志的核心仓库类。
 */
class SettingsRepository(
    /**
     * Application context used for local preference fallback and system lookups.
     *
     * 用于本地配置兜底及系统查询的应用上下文。
     */
    private val context: Context,
    private val overridePrefs: SharedPreferences? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var xposedService: XposedService? = null
    private val pendingHookRules = LinkedHashMap<Pair<String, Boolean>, Int>()

    private val _isActivated = MutableStateFlow(false)

    /**
     * Observable stream indicating whether the module has been activated by the Xposed framework.
     *
     * 标识模块是否已被 Xposed 框架成功激活的可观察流。
     */
    val isActivated: StateFlow<Boolean> = _isActivated.asStateFlow()

    private val _frameworkName = MutableStateFlow<String?>(null)

    /**
     * Observable stream containing the name of the active Xposed framework.
     *
     * 包含当前激活的 Xposed 框架名称的可观察流。
     */
    val frameworkName: StateFlow<String?> = _frameworkName.asStateFlow()

    private val _frameworkVersion = MutableStateFlow<String?>(null)

    /**
     * Observable stream containing the version string of the active Xposed framework.
     *
     * 包含当前激活的 Xposed 框架版本字符串的可观察流。
     */
    val frameworkVersion: StateFlow<String?> = _frameworkVersion.asStateFlow()

    private val _masterEnabled = MutableStateFlow(true)

    /**
     * Observable stream representing the global master toggle for clipboard interception.
     *
     * 代表剪贴板拦截全局总开关的可观察流。
     */
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    private val _selectedLanguageTag = MutableStateFlow("")

    /**
     * Observable stream representing the user-selected language BCP 47 tag (e.g. "", "zh-CN", "zh-TW", "en").
     *
     * 代表用户所选语言 BCP 47 标签（例如："" 跟随系统、"zh-CN"、"zh-TW"、"en"）的可观察流。
     */
    val selectedLanguageTag: StateFlow<String> = _selectedLanguageTag.asStateFlow()

    private val _rogueThrottleEnabled = MutableStateFlow(true)

    /**
     * Observable stream indicating whether rogue application frequent read throttling is enabled.
     *
     * 标识是否启用毒瘤应用频繁读取限流与防轰炸保护的可观察流。
     */
    val rogueThrottleEnabled: StateFlow<Boolean> = _rogueThrottleEnabled.asStateFlow()

    private val _denyCooldownSeconds = MutableStateFlow(30)

    /**
     * Observable stream of prompt denial cooldown duration in seconds.
     *
     * 授权弹窗被拒绝或关闭后的静默冷却时长（秒）可观察流。
     */
    val denyCooldownSeconds: StateFlow<Int> = _denyCooldownSeconds.asStateFlow()

    private val _grantBurstSeconds = MutableStateFlow(5)

    /**
     * Observable stream of burst allowance window duration in seconds following a one-time grant.
     *
     * 单次允许后快速连读免弹窗通行窗口时长（秒）可观察流。
     */
    val grantBurstSeconds: StateFlow<Int> = _grantBurstSeconds.asStateFlow()

    private val _maxReadFrequency = MutableStateFlow(5)

    /**
     * Observable stream of maximum allowed read attempts per sliding window.
     *
     * 滑动时间窗口内触发限流截断的最大读取尝试次数阈值可观察流。
     */
    val maxReadFrequency: StateFlow<Int> = _maxReadFrequency.asStateFlow()

    private val _readToleranceCount = MutableStateFlow(RogueAppLimiter.DEFAULT_PROMPT_TOLERANCE)

    /**
     * Observable stream of reads tolerated within a short window before silent throttling kicks in.
     *
     * 短时间内允许连续放行、超出后才触发静默限流的读取次数上限可观察流。
     */
    val readToleranceCount: StateFlow<Int> = _readToleranceCount.asStateFlow()

    // Sub-domain stores
    private val whitelistStore = WhitelistStore(::getActivePreferences, json)
    private val themeStore = ThemeSettingsStore(::getActivePreferences)
    private val hookLogStore = HookLogStore(context, json)
    private val updateStore = UpdateSettingsStore(::getActivePreferences)

    // Delegated observable streams

    /**
     * Observable stream of configured whitelist rules.
     *
     * 已配置的白名单规则列表可观察流。
     */
    val whitelist: StateFlow<List<WhitelistRule>> = whitelistStore.whitelist

    /**
     * Observable stream of recent hook interception events.
     *
     * 最近发生的 Hook 拦截事件列表可观察流。
     */
    val hookLogs: StateFlow<List<HookLog>> = hookLogStore.hookLogs

    /**
     * Observable stream representing the display color mode (System, Light, Dark, AMOLED).
     *
     * 代表显示色彩模式（跟随系统、浅色、深色、纯黑）的可观察流。
     */
    val themeColorMode: StateFlow<ColorMode> = themeStore.themeColorMode

    /**
     * Observable stream representing the key seed color (0 for dynamic Monet, non-zero for custom ARGB).
     *
     * 代表主题关键种子颜色的可观察流（0 为莫奈动态壁纸取色，非 0 为自定义 ARGB）。
     */
    val themeKeyColor: StateFlow<Int> = themeStore.themeKeyColor

    /**
     * Observable stream representing the Material 3 palette style algorithm.
     *
     * 代表 Material 3 调色板风格算法的可观察流。
     */
    val themePaletteStyle: StateFlow<PaletteStyle> = themeStore.themePaletteStyle

    /**
     * Observable stream representing whether the floating navigation bar style is enabled.
     *
     * 代表是否启用悬浮底栏样式的可观察流。
     */
    val isFloatingNavEnabled: StateFlow<Boolean> = themeStore.isFloatingNavEnabled

    /**
     * Observable stream indicating whether automatic update checking on app launch is enabled.
     *
     * 标识应用启动时是否自动检查更新的可观察流。
     */
    val isAutoUpdateCheckEnabled: StateFlow<Boolean> = updateStore.isAutoUpdateCheckEnabled

    /**
     * Observable stream indicating whether system applications should be shown in app lists.
     *
     * 标识应用列表中是否显示系统组件/应用的可观察流。
     */
    val showSystemApps: StateFlow<Boolean> = updateStore.showSystemApps

    init {
        loadInitialLocalPreferences()
        initXposedService()
    }

    private fun getActivePreferences(): SharedPreferences {
        if (overridePrefs != null) return overridePrefs
        return xposedService?.let { service ->
            try {
                service.getRemotePreferences(PrefKeys.PREFS_GROUP)
            } catch (_: Throwable) {
                null
            }
        } ?: context.getSharedPreferences(PrefKeys.PREFS_GROUP, Context.MODE_PRIVATE)
    }

    private fun loadInitialLocalPreferences() {
        val prefs = getActivePreferences()
        _masterEnabled.value = prefs.getBoolean(PrefKeys.KEY_MASTER_ENABLED, true)
        _selectedLanguageTag.value = prefs.getString(PrefKeys.KEY_SELECTED_LANGUAGE, "") ?: ""
        _rogueThrottleEnabled.value = prefs.getBoolean(PrefKeys.KEY_ROGUE_THROTTLE_ENABLED, true)
        _denyCooldownSeconds.value = prefs.getInt(PrefKeys.KEY_DENY_COOLDOWN_SECONDS, 30)
        _grantBurstSeconds.value = prefs.getInt(PrefKeys.KEY_GRANT_BURST_SECONDS, 5)
        _maxReadFrequency.value = prefs.getInt(PrefKeys.KEY_MAX_READ_FREQUENCY, 5)
        _readToleranceCount.value = prefs.getInt(
            PrefKeys.KEY_READ_TOLERANCE_COUNT,
            RogueAppLimiter.DEFAULT_PROMPT_TOLERANCE
        )

        whitelistStore.loadInitial(prefs)
        themeStore.loadInitial(prefs)
        updateStore.loadInitial(prefs)
    }

    private fun initXposedService() {
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    synchronized(this@SettingsRepository) {
                        xposedService = service
                        syncPreferences(service)
                    }
                    _isActivated.value = true
                    try {
                        _frameworkName.value = service.frameworkName
                        _frameworkVersion.value = service.frameworkVersion
                    } catch (_: Throwable) {}
                }

                override fun onServiceDied(service: XposedService) {
                    synchronized(this@SettingsRepository) {
                        if (xposedService !== service) return
                        xposedService = null
                    }
                    _isActivated.value = false
                    _frameworkName.value = null
                    _frameworkVersion.value = null
                }
            })
        } catch (_: Throwable) {
            _isActivated.value = false
        }
    }

    private fun syncPreferences(service: XposedService) {
        try {
            val remotePrefs = service.getRemotePreferences(PrefKeys.PREFS_GROUP)
            _masterEnabled.value = remotePrefs.getBoolean(PrefKeys.KEY_MASTER_ENABLED, _masterEnabled.value)
            _selectedLanguageTag.value = remotePrefs.getString(PrefKeys.KEY_SELECTED_LANGUAGE, _selectedLanguageTag.value) ?: ""
            _rogueThrottleEnabled.value = remotePrefs.getBoolean(PrefKeys.KEY_ROGUE_THROTTLE_ENABLED, _rogueThrottleEnabled.value)
            _denyCooldownSeconds.value = remotePrefs.getInt(PrefKeys.KEY_DENY_COOLDOWN_SECONDS, _denyCooldownSeconds.value)
            _grantBurstSeconds.value = remotePrefs.getInt(PrefKeys.KEY_GRANT_BURST_SECONDS, _grantBurstSeconds.value)
            _maxReadFrequency.value = remotePrefs.getInt(PrefKeys.KEY_MAX_READ_FREQUENCY, _maxReadFrequency.value)
            _readToleranceCount.value = remotePrefs.getInt(
                PrefKeys.KEY_READ_TOLERANCE_COUNT,
                _readToleranceCount.value
            )

            whitelistStore.sync(remotePrefs)
            for ((target, rule) in pendingHookRules) {
                val (packageName, isWrite) = target
                whitelistStore.setOperationRule(packageName, isWrite, rule)
            }
            pendingHookRules.clear()
            themeStore.sync(remotePrefs)
            updateStore.sync(remotePrefs)
        } catch (_: Throwable) {}
    }

    /**
     * Updates the master toggle state and synchronizes it to remote preferences.
     *
     * 更新总开关状态并同步至远程配置。
     */
    fun setMasterEnabled(enabled: Boolean) {
        _masterEnabled.value = enabled
        getActivePreferences().edit().putBoolean(PrefKeys.KEY_MASTER_ENABLED, enabled).apply()
    }

    /**
     * Updates the user-selected language tag and synchronizes it to remote preferences.
     *
     * 更新用户所选语言标签并持久化至远程配置。
     */
    fun setSelectedLanguageTag(tag: String) {
        _selectedLanguageTag.value = tag
        getActivePreferences().edit().putString(PrefKeys.KEY_SELECTED_LANGUAGE, tag).apply()
    }

    // --- Whitelist Delegations ---

    /**
     * Updates or creates the permission rules for the specified [packageName].
     *
     * 更新或创建指定 [packageName] 的权限规则。
     */
    fun setPackageRule(packageName: String, readRule: Int, writeRule: Int) =
        whitelistStore.setPackageRule(packageName, readRule, writeRule)

    @Synchronized
    internal fun applyHookRule(packageName: String, isWrite: Boolean, rule: Int) {
        // A broadcast can start the app before LibXposed connects its remote preferences.
        if (xposedService == null && overridePrefs == null) {
            pendingHookRules[packageName to isWrite] = rule
        } else {
            whitelistStore.setOperationRule(packageName, isWrite, rule)
        }
    }

    /**
     * Retrieves the permission rule configured for the specified [packageName].
     *
     * 获取指定 [packageName] 的配置权限规则。
     */
    fun getPackageRule(packageName: String): WhitelistRule =
        whitelistStore.getPackageRule(packageName)

    /**
     * Removes any configured rule for [packageName], resetting it to default request state.
     *
     * 移除 [packageName] 的已配置规则，将其重置为默认询问状态。
     */
    fun removePackageRule(packageName: String) =
        whitelistStore.removePackageRule(packageName)

    /**
     * Toggles whether the specified [packageName] is allowed to read clipboard.
     *
     * 切换指定 [packageName] 的读取放行权限。
     */
    fun togglePackage(packageName: String) =
        whitelistStore.togglePackage(packageName)

    /**
     * Checks whether the specified [packageName] is currently allowed to read clipboard.
     *
     * 检查指定的 [packageName] 当前是否被允许读取剪贴板。
     */
    fun isPackageWhitelisted(packageName: String): Boolean =
        whitelistStore.isPackageWhitelisted(packageName)

    /**
     * Exports all configured whitelist rules into a formatted JSON string.
     *
     * 将所有已配置的白名单规则导出为格式化 JSON 字符串。
     */
    fun exportWhitelist(): String =
        whitelistStore.exportWhitelist()

    /**
     * Imports whitelist rules from the given [jsonString], returning the number of imported rules.
     *
     * 从指定的 [jsonString] 导入白名单规则，并返回成功导入的规则数量。
     */
    fun importWhitelist(jsonString: String): Result<Int> =
        whitelistStore.importWhitelist(jsonString)

    // --- Hook Logs Delegations ---

    /**
     * Records a new hook interception [log] into the recent log list.
     *
     * 将新的 Hook 拦截 [log] 记录存入最近日志列表中。
     */
    fun recordHookLog(log: HookLog) =
        hookLogStore.recordHookLog(log)

    internal suspend fun readArchivedLogs(before: Long?) = hookLogStore.readArchive(before)

    /**
     * Clears all recorded hook interception logs.
     *
     * 清空所有已记录的 Hook 拦截日志。
     */
    fun clearHookLogs() =
        hookLogStore.clearHookLogs()

    // --- Theme Settings Delegations ---

    /**
     * Updates the active [ColorMode] and persists it to preferences.
     *
     * 更新当前生效的 [ColorMode] 并持久化到配置中。
     */
    fun setThemeColorMode(mode: ColorMode) =
        themeStore.setThemeColorMode(mode)

    /**
     * Updates the active seed key color and persists it to preferences.
     *
     * 更新当前生效的种子关键颜色并持久化到配置中。
     */
    fun setThemeKeyColor(color: Int) =
        themeStore.setThemeKeyColor(color)

    /**
     * Updates the active [PaletteStyle] and persists it to preferences.
     *
     * 更新当前生效的 [PaletteStyle] 调色风格并持久化到配置中。
     */
    fun setThemePaletteStyle(style: PaletteStyle) =
        themeStore.setThemePaletteStyle(style)

    /**
     * Toggles the floating navigation bar appearance and persists it to preferences.
     *
     * 切换悬浮底栏外观并持久化到配置中。
     */
    fun setFloatingNavEnabled(enabled: Boolean) =
        themeStore.setFloatingNavEnabled(enabled)

    // --- Rogue Limiter Settings ---

    /**
     * Updates whether rogue application frequent read throttling is enabled.
     *
     * 更新是否启用毒瘤应用频繁读取限流保护状态。
     */
    fun setRogueThrottleEnabled(enabled: Boolean) {
        _rogueThrottleEnabled.value = enabled
        getActivePreferences().edit().putBoolean(PrefKeys.KEY_ROGUE_THROTTLE_ENABLED, enabled).apply()
    }

    /**
     * Updates the prompt denial cooldown duration in seconds.
     *
     * 更新授权弹窗被拒绝后的静默冷却时长（秒）。
     */
    fun setDenyCooldownSeconds(seconds: Int) {
        _denyCooldownSeconds.value = seconds
        getActivePreferences().edit().putInt(PrefKeys.KEY_DENY_COOLDOWN_SECONDS, seconds).apply()
    }

    /**
     * Updates the one-time grant burst window duration in seconds.
     *
     * 更新单次允许后的临时有效快速放行窗口时长（秒）。
     */
    fun setGrantBurstSeconds(seconds: Int) {
        _grantBurstSeconds.value = seconds
        getActivePreferences().edit().putInt(PrefKeys.KEY_GRANT_BURST_SECONDS, seconds).apply()
    }

    /**
     * Updates the maximum allowed read frequency attempts per sliding window.
     *
     * 更新滑动时间窗口内触发限流截断的最大读取尝试次数。
     */
    fun setMaxReadFrequency(frequency: Int) {
        _maxReadFrequency.value = frequency
        getActivePreferences().edit().putInt(PrefKeys.KEY_MAX_READ_FREQUENCY, frequency).apply()
    }

    /**
     * Updates how many reads within a short window are tolerated before silent throttling applies.
     *
     * 更新短时间内允许连续放行的读取次数上限（超出后才触发静默限流）。
     */
    fun setReadToleranceCount(count: Int) {
        _readToleranceCount.value = count
        getActivePreferences().edit().putInt(PrefKeys.KEY_READ_TOLERANCE_COUNT, count).apply()
    }

    // --- Update Settings Delegations ---

    /**
     * Timestamp in milliseconds when the last update check completed successfully.
     *
     * 上次成功完成更新检查的时间戳（毫秒）。
     */
    var lastUpdateCheck: Long
        get() = updateStore.lastUpdateCheck
        set(value) { updateStore.lastUpdateCheck = value }

    /**
     * Release tag skipped by the user for automatic update alerts.
     *
     * 用户选择跳过的版本 Tag，启动自动检查将不再为该版本弹窗。
     */
    var ignoredVersion: String?
        get() = updateStore.ignoredVersion
        set(value) { updateStore.ignoredVersion = value }

    /**
     * Checks whether a new automatic update check is due based on the daily interval.
     *
     * 根据每日（24 小时）间隔判断当前是否应触发启动自动更新检查。
     */
    fun isUpdateCheckDue(now: Long = System.currentTimeMillis()): Boolean =
        updateStore.isUpdateCheckDue(now)

    /**
     * Toggles whether updates should be checked automatically on launch.
     *
     * 切换启动时是否自动检查更新并持久化。
     */
    fun setAutoUpdateCheckEnabled(enabled: Boolean) =
        updateStore.setAutoUpdateCheckEnabled(enabled)

    /**
     * Toggles whether system apps should be listed in whitelist views.
     *
     * 切换白名单列表是否显示系统组件并持久化。
     */
    fun setShowSystemApps(show: Boolean) =
        updateStore.setShowSystemApps(show)

    companion object {
        /**
         * Preference key for theme color mode.
         *
         * 主题色彩模式的配置键。
         */
        const val KEY_THEME_COLOR_MODE = PrefKeys.KEY_THEME_COLOR_MODE

        /**
         * Preference key for theme seed key color.
         *
         * 主题关键种子颜色的配置键。
         */
        const val KEY_THEME_KEY_COLOR = PrefKeys.KEY_THEME_KEY_COLOR

        /**
         * Preference key for Material 3 palette style algorithm.
         *
         * Material 3 调色板风格算法的配置键。
         */
        const val KEY_THEME_PALETTE_STYLE = ThemeSettingsStore.KEY_THEME_PALETTE_STYLE

        /**
         * Preference key for floating bottom navigation bar toggle.
         *
         * 悬浮底栏外观开关的配置键。
         */
        const val KEY_THEME_FLOATING_NAV = ThemeSettingsStore.KEY_THEME_FLOATING_NAV

        /**
         * Preference key for automatic update checking toggle.
         *
         * 启动自动检查更新开关的配置键。
         */
        const val KEY_AUTO_UPDATE_CHECK = UpdateSettingsStore.KEY_AUTO_UPDATE_CHECK

        /**
         * Preference key for timestamp of last successful update check.
         *
         * 上次成功更新检查时间戳的配置键。
         */
        const val KEY_LAST_UPDATE_CHECK = UpdateSettingsStore.KEY_LAST_UPDATE_CHECK

        /**
         * Preference key for user-ignored release version tag.
         *
         * 用户选择跳过的版本 Tag 的配置键。
         */
        const val KEY_IGNORED_VERSION = UpdateSettingsStore.KEY_IGNORED_VERSION

        /**
         * Preference key for whether system applications are shown in app lists.
         *
         * 应用列表中是否显示系统组件的配置键。
         */
        const val KEY_SHOW_SYSTEM_APPS = UpdateSettingsStore.KEY_SHOW_SYSTEM_APPS

        /**
         * Interval in milliseconds between automatic update checks (24 hours).
         *
         * 自动更新检查的时间间隔（毫秒，默认 24 小时）。
         */
        const val UPDATE_CHECK_INTERVAL_MS = UpdateSettingsStore.UPDATE_CHECK_INTERVAL_MS
    }
}

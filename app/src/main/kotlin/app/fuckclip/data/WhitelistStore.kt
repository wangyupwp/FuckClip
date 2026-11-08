package app.fuckclip.data

import android.content.SharedPreferences
import app.fuckclip.core.PrefKeys
import app.fuckclip.core.model.WhitelistBackup
import app.fuckclip.core.model.WhitelistRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages whitelist rules persistence, queries, toggling, and backup import/export.
 *
 * 负责白名单放行规则的持久化、检索、状态切换以及备份导入导出的数据存储管理类。
 */
internal class WhitelistStore(
    private val getPreferences: () -> SharedPreferences,
    private val json: Json
) {
    private val _whitelist = MutableStateFlow<List<WhitelistRule>>(emptyList())

    /**
     * Observable stream of configured whitelist rules.
     *
     * 已配置的白名单规则列表可观察流。
     */
    val whitelist: StateFlow<List<WhitelistRule>> = _whitelist.asStateFlow()

    /**
     * Loads initial whitelist from local preferences.
     *
     * 从本地偏好配置中加载初始白名单规则。
     */
    fun loadInitial(prefs: SharedPreferences) {
        val rawWhitelist = prefs.getString(PrefKeys.KEY_WHITELIST, null)
        if (!rawWhitelist.isNullOrBlank()) {
            try {
                val decoded = json.decodeFromString<List<WhitelistRule>>(rawWhitelist)
                val normalized = decoded.filterNot { isDefaultRule(it.readRule, it.writeRule) }
                _whitelist.value = normalized
                if (normalized.size != decoded.size) {
                    saveWhitelist(normalized)
                }
            } catch (_: Throwable) {
                _whitelist.value = emptyList()
            }
        }
    }

    /**
     * Synchronizes whitelist state with remote preferences.
     *
     * 与远程偏好配置同步白名单状态。
     */
    @Synchronized
    fun sync(remotePrefs: SharedPreferences) {
        val remoteWhitelist = remotePrefs.getString(PrefKeys.KEY_WHITELIST, null)
        if (!remoteWhitelist.isNullOrBlank()) {
            val decoded = json.decodeFromString<List<WhitelistRule>>(remoteWhitelist)
            val normalized = decoded.filterNot { isDefaultRule(it.readRule, it.writeRule) }
            _whitelist.value = normalized
            if (normalized.size != decoded.size) {
                remotePrefs.edit().putString(PrefKeys.KEY_WHITELIST, json.encodeToString(normalized)).apply()
            }
        } else if (_whitelist.value.isNotEmpty()) {
            remotePrefs.edit().putString(PrefKeys.KEY_WHITELIST, json.encodeToString(_whitelist.value)).apply()
        }
    }

    /**
     * Updates or creates permission rules for [packageName], removing stored entry if reset to defaults.
     *
     * 更新或创建指定 [packageName] 的权限规则，重置为默认值时直接移除存储条目。
     */
    @Synchronized
    fun setPackageRule(packageName: String, readRule: Int, writeRule: Int) {
        if (isDefaultRule(readRule, writeRule)) {
            removePackageRule(packageName)
            return
        }
        val current = _whitelist.value.toMutableList()
        val index = current.indexOfFirst { it.packageName == packageName }
        val newRule = WhitelistRule(packageName = packageName, readRule = readRule, writeRule = writeRule)
        if (index >= 0) {
            current[index] = newRule
        } else {
            current.add(newRule)
        }
        _whitelist.value = current
        saveWhitelist(current)
    }

    @Synchronized
    fun setOperationRule(packageName: String, isWrite: Boolean, rule: Int) {
        val current = getPackageRule(packageName)
        if (isWrite) {
            setPackageRule(packageName, current.readRule, rule)
        } else {
            setPackageRule(packageName, rule, current.writeRule)
        }
    }

    /**
     * Retrieves the permission rule configured for the specified [packageName].
     *
     * 获取指定 [packageName] 的配置权限规则，若未配置则返回默认询问规则。
     */
    fun getPackageRule(packageName: String): WhitelistRule {
        return _whitelist.value.firstOrNull { it.packageName == packageName }
            ?: WhitelistRule(packageName = packageName, readRule = WhitelistRule.RULE_REQUEST, writeRule = WhitelistRule.RULE_REQUEST)
    }

    /**
     * Removes any configured rule for [packageName], resetting it to default request state.
     *
     * 移除 [packageName] 的已配置规则，将其重置为默认询问状态。
     */
    @Synchronized
    fun removePackageRule(packageName: String) {
        val current = _whitelist.value.toMutableList()
        val index = current.indexOfFirst { it.packageName == packageName }
        if (index >= 0) {
            current.removeAt(index)
            _whitelist.value = current
            saveWhitelist(current)
        }
    }

    /**
     * Toggles whether the specified [packageName] is allowed to read clipboard.
     *
     * 切换指定 [packageName] 的读取放行权限。
     */
    @Synchronized
    fun togglePackage(packageName: String) {
        val current = _whitelist.value.toMutableList()
        val index = current.indexOfFirst { it.packageName == packageName }
        if (index >= 0) {
            val existing = current[index]
            if (existing.readRule == WhitelistRule.RULE_ALLOW) {
                current.removeAt(index)
            } else {
                current[index] = existing.copy(readRule = WhitelistRule.RULE_ALLOW)
            }
        } else {
            current.add(WhitelistRule(packageName = packageName, readRule = WhitelistRule.RULE_ALLOW))
        }
        _whitelist.value = current
        saveWhitelist(current)
    }

    /**
     * Checks whether the specified [packageName] is currently allowed to read clipboard.
     *
     * 检查指定的 [packageName] 当前是否被允许读取剪贴板。
     */
    fun isPackageWhitelisted(packageName: String): Boolean {
        return _whitelist.value.any { it.packageName == packageName && it.readRule == WhitelistRule.RULE_ALLOW }
    }

    /**
     * Exports all configured whitelist rules into a formatted JSON string.
     *
     * 将所有已配置的白名单规则导出为格式化 JSON 字符串。
     */
    fun exportWhitelist(): String {
        val backup = WhitelistBackup(
            version = WhitelistBackup.CURRENT_VERSION,
            rules = _whitelist.value
        )
        return backup.toJson()
    }

    /**
     * Imports whitelist rules from the given [jsonString], returning the number of imported rules.
     *
     * 从指定的 [jsonString] 导入白名单规则，并返回成功导入的规则数量。
     */
    @Synchronized
    fun importWhitelist(jsonString: String): Result<Int> {
        return runCatching {
            require(jsonString.length <= MAX_BACKUP_CHARS) {
                "Backup exceeds the supported size of $MAX_BACKUP_CHARS characters"
            }
            val backup = WhitelistBackup.fromJson(jsonString)
            val imported = backup.rules.filterNot { isDefaultRule(it.readRule, it.writeRule) }
            val merged = (_whitelist.value + imported).distinctBy { it.packageName }
            _whitelist.value = merged
            saveWhitelist(merged)
            imported.size
        }
    }

    /**
     * Whether a rule pair carries no explicit user decision and therefore means the default state.
     *
     * 判断一组读写规则是否未包含任何用户决策，即等同于默认状态。
     */
    private fun isDefaultRule(readRule: Int, writeRule: Int): Boolean =
        readRule == WhitelistRule.RULE_REQUEST && writeRule == WhitelistRule.RULE_REQUEST

    private fun saveWhitelist(rules: List<WhitelistRule>) {
        val serialized = json.encodeToString(rules)
        getPreferences().edit().putString(PrefKeys.KEY_WHITELIST, serialized).apply()
    }
}

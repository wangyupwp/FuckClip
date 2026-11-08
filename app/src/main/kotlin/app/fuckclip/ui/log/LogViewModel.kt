package app.fuckclip.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fuckclip.core.model.HookLog
import app.fuckclip.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Filter modes applied to the clipboard activity history list.
 *
 * 剪贴板使用记录列表所支持的筛选模式。
 */
enum class LogFilterMode {
    /**
     * Every recorded access.
     *
     * 全部访问记录。
     */
    ALL,

    /**
     * Clipboard read accesses only.
     *
     * 仅剪贴板读取记录。
     */
    READ,

    /**
     * Clipboard write accesses only.
     *
     * 仅剪贴板写入记录。
     */
    WRITE,

    /**
     * Accesses that were allowed.
     *
     * 仅被放行的记录。
     */
    ALLOWED,

    /**
     * Accesses that were denied or throttled.
     *
     * 仅被拦截或限流的记录。
     */
    DENIED
}

/**
 * Applies the free-text [query] and the [mode] filter to [logs] while preserving the incoming order.
 *
 * 按搜索关键字 [query] 与筛选模式 [mode] 过滤 [logs]，并保持原有顺序。
 *
 * @param logs Raw activity records, newest first.
 * @param query Text matched against app label, package name, clipboard content and decision detail.
 * @param mode Direction or outcome filter.
 * @return Records matching both the query and the filter mode.
 */
internal fun filterHookLogs(logs: List<HookLog>, query: String, mode: LogFilterMode): List<HookLog> {
    val trimmedQuery = query.trim().lowercase()
    return logs.filter { log ->
        val matchesMode = when (mode) {
            LogFilterMode.ALL -> true
            LogFilterMode.READ -> log.action.equals("READ", ignoreCase = true)
            LogFilterMode.WRITE -> log.action.equals("WRITE", ignoreCase = true)
            LogFilterMode.ALLOWED -> log.allowed
            LogFilterMode.DENIED -> !log.allowed
        }
        if (!matchesMode) {
            return@filter false
        }
        trimmedQuery.isEmpty() ||
            log.packageName.lowercase().contains(trimmedQuery) ||
            log.appName?.lowercase()?.contains(trimmedQuery) == true ||
            log.content?.lowercase()?.contains(trimmedQuery) == true ||
            log.detail.lowercase().contains(trimmedQuery)
    }
}

/**
 * ViewModel managing recent hook interception records, history search and filtering.
 *
 * 管理最近 Hook 拦截记录、记录搜索与筛选的 ViewModel。
 */
class LogViewModel(
    private val repository: SettingsRepository
) : ViewModel() {

    /**
     * All recorded hook interception events, newest first.
     *
     * 全部已记录的 Hook 拦截事件列表（最新的在前）。
     */
    val logs: StateFlow<List<HookLog>> = repository.hookLogs

    private val _searchQuery = MutableStateFlow("")

    /**
     * Observable stream representing the current history search keyword.
     *
     * 代表当前记录搜索关键字的可观察流。
     */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterMode = MutableStateFlow(LogFilterMode.ALL)

    /**
     * Observable stream representing the active [LogFilterMode].
     *
     * 代表当前生效 [LogFilterMode] 的可观察流。
     */
    val filterMode: StateFlow<LogFilterMode> = _filterMode.asStateFlow()

    /**
     * Records matching the current search keyword and filter mode.
     *
     * 匹配当前搜索关键字与筛选模式的使用记录列表。
     *
     * The initial value mirrors the raw record list so the first composition never flashes an empty state.
     *
     * 初始值直接采用原始记录列表，避免首帧闪现空状态。
     */
    val displayedLogs: StateFlow<List<HookLog>> = combine(
        repository.hookLogs,
        _searchQuery,
        _filterMode
    ) { logs, query, mode ->
        filterHookLogs(logs, query, mode)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), repository.hookLogs.value)

    /**
     * Updates the history search keyword.
     *
     * 更新记录搜索关键字。
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Updates the active history filter mode.
     *
     * 更新记录列表筛选模式。
     */
    fun setFilterMode(mode: LogFilterMode) {
        _filterMode.value = mode
    }

    /**
     * Clears all recorded hook interception logs.
     *
     * 清空所有已记录的 Hook 拦截日志。
     */
    fun clearLogs() {
        repository.clearHookLogs()
    }

    internal suspend fun readArchive(before: Long?) = repository.readArchivedLogs(before)
}

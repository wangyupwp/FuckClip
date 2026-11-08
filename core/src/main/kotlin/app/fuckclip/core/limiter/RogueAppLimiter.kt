package app.fuckclip.core.limiter

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of evaluating whether a clipboard read operation should proceed or be throttled.
 *
 * 评估剪贴板读取操作是继续执行还是被限制截断的结果枚举与数据载体。
 */
sealed class ReadThrottleResult {
    /**
     * The read operation may proceed with normal rule evaluation.
     *
     * 读取操作可正常继续进行规则匹配与弹窗/放行流程。
     */
    object Proceed : ReadThrottleResult()

    /**
     * The read operation is silently denied due to anti-spam, concurrency limits, or high-frequency throttling.
     *
     * 读取操作因防弹窗轰炸、并发限制或高频限流而被静默拦截。
     *
     * @property reason Human-readable explanation for throttling (e.g., cooldown, rate limit, concurrency).
     */
    data class Throttled(val reason: String) : ReadThrottleResult()

    /**
     * The read operation is fast-tracked and allowed immediately due to an active temporary grant burst window.
     *
     * 读取操作因处于用户单次允许的有效时间窗口内而被直接放行，无需重复弹窗。
     */
    data class FastTrackAllow(val grant: ReadGrant) : ReadThrottleResult()
}

/** An approval is pending until Android returns the approved clipboard instance. */
class ReadGrant internal constructor(val clipIdentity: Any? = null)

/**
 * High-performance rate limiter and anti-spam controller protecting system resources against rogue applications.
 *
 * 针对恶意/流氓应用的高频狂读、弹窗轰炸及系统 Binder 线程池耗尽提供保护的高性能限流控制器。
 */
class RogueAppLimiter(
    /**
     * Provider returning current timestamp in milliseconds, customizable for testing.
     *
     * 返回当前毫秒时间戳的提供器，可在测试中注入以实现确定性验证。
     */
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private data class ReadWindow(val expiresAt: Long, val grant: ReadGrant? = null)
    private val readWindows = ConcurrentHashMap<String, ReadWindow>()

    /** Expiration timestamp in milliseconds for high-frequency penalty per package. */
    private val rateLimitPenaltyUntil = ConcurrentHashMap<String, Long>()

    /** Active pending prompt count per package to prevent Binder thread exhaustion. */
    private val activePromptCounts = ConcurrentHashMap<String, Int>()

    /** Timestamps of recent read attempts within the sliding window per package. */
    private val readAttemptTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Timestamp of the last emitted log broadcast per package. */
    private val lastLogEmitTimes = ConcurrentHashMap<String, Long>()

    /**
     * Aggregated suppressed log event count since last emitted log broadcast.
     *
     * 距离上次发送日志广播以来聚合的被压制日志事件数。
     */
    private val suppressedLogCounts = ConcurrentHashMap<String, Int>()

    /**
     * Timestamp of the last opportunistic stale-state sweep.
     *
     * 上次执行过期状态清理的时间戳。
     */
    @Volatile
    private var lastPruneAt = 0L

    /**
     * Number of packages still holding limiter bookkeeping, exposed for diagnostics and tests.
     *
     * 当前仍保留限流簿记的应用数量，用于诊断与测试。
     */
    val trackedPackageCount: Int
        get() = (readWindows.keys + rateLimitPenaltyUntil.keys +
            readAttemptTimestamps.keys + lastLogEmitTimes.keys + suppressedLogCounts.keys +
            activePromptCounts.keys).size

    /**
     * Drops per-package bookkeeping that can no longer influence any decision at timestamp [now].
     *
     * 清理在时间戳 [now] 已不再影响判定的过期应用簿记数据。
     */
    fun pruneStaleState(now: Long = timeProvider()) {
        val staleBefore = now - STALE_STATE_TTL_MS
        readWindows.entries.removeIf { it.value.expiresAt <= now }
        rateLimitPenaltyUntil.entries.removeIf { it.value <= now }
        readAttemptTimestamps.entries.removeIf { entry ->
            val attempts = entry.value
            synchronized(attempts) {
                while (attempts.isNotEmpty() && attempts.peekFirst() < staleBefore) {
                    attempts.pollFirst()
                }
                attempts.isEmpty()
            }
        }
        lastLogEmitTimes.entries.removeIf { it.value < staleBefore }
        suppressedLogCounts.entries.removeIf { (lastLogEmitTimes[it.key] ?: 0L) < staleBefore }
    }

    /**
     * Runs [pruneStaleState] at timestamp [now] if the prune interval has elapsed.
     *
     * 若距上次清理已超过清理间隔，则在时间戳 [now] 执行 [pruneStaleState]。
     */
    private fun pruneStaleStateIfDue(now: Long) {
        if (now - lastPruneAt < PRUNE_INTERVAL_MS) {
            return
        }
        lastPruneAt = now
        pruneStaleState(now)
    }

    /**
     * Checks whether an incoming clipboard read request should be allowed, throttled, or fast-tracked.
     *
     * 检查传入的剪贴板读取请求是否应予放行、截断限流或快速通行。
     *
     * @param packageName Target application package name.
     * @param isRuleRequest Whether the effective rule for this package is RULE_REQUEST (prompt user).
     * @param enabled Whether the rogue app restriction feature is globally enabled.
     * @param windowMs Duration of the sliding rate-limiting window in milliseconds.
     * @param maxAttempts Maximum allowed read attempts within the sliding window.
     * @param penaltyMs Penalty duration in milliseconds applied when exceeding max frequency.
     * @param promptTolerance Number of concurrent reads tolerated for one package before silent throttling.
     * @return [ReadThrottleResult] indicating the action to take.
     */
    fun evaluateRead(
        packageName: String,
        isRuleRequest: Boolean,
        enabled: Boolean = true,
        windowMs: Long = DEFAULT_WINDOW_MS,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        penaltyMs: Long = DEFAULT_PENALTY_MS,
        promptTolerance: Int = DEFAULT_PROMPT_TOLERANCE
    ): ReadThrottleResult {
        if (!enabled) {
            return ReadThrottleResult.Proceed
        }

        val now = timeProvider()
        pruneStaleStateIfDue(now)

        // 1. High-frequency penalty check (applies to all apps)
        val penaltyEnd = rateLimitPenaltyUntil[packageName] ?: 0L
        if (now < penaltyEnd) {
            return ReadThrottleResult.Throttled("Rate limited (too frequent)")
        }

        // 2. Sliding window frequency check
        val isRateLimitExceeded = recordAttemptAndCheckRateLimit(packageName, now, windowMs, maxAttempts)
        if (isRateLimitExceeded) {
            rateLimitPenaltyUntil[packageName] = now + penaltyMs
            return ReadThrottleResult.Throttled("Rate limited (exceeded $maxAttempts reads in ${windowMs / 1000}s)")
        }

        if (isRuleRequest) {
            val window = readWindows[packageName]
            if (window != null && now < window.expiresAt) {
                val grant = window.grant ?: return ReadThrottleResult.Throttled("Denied by cooldown (anti-spam)")
                if (grant.clipIdentity != null) return ReadThrottleResult.FastTrackAllow(grant)
            }

            // 5. Concurrency check: tolerate a short burst of reads before silently throttling
            val currentPrompts = activePromptCounts[packageName] ?: 0
            if (currentPrompts >= promptTolerance.coerceAtLeast(1)) {
                return ReadThrottleResult.Throttled("Concurrency limit reached")
            }
        }

        return ReadThrottleResult.Proceed
    }

    /**
     * Records a read attempt and verifies if the sliding window threshold was breached.
     *
     * 记录一次读取尝试并验证是否突破了滑动时间窗口内的频次阈值。
     */
    private fun recordAttemptAndCheckRateLimit(
        packageName: String,
        now: Long,
        windowMs: Long,
        maxAttempts: Int
    ): Boolean {
        val deque = readAttemptTimestamps.computeIfAbsent(packageName) { ArrayDeque() }
        synchronized(deque) {
            val threshold = now - windowMs
            while (deque.isNotEmpty() && deque.peekFirst() < threshold) {
                deque.pollFirst()
            }
            deque.addLast(now)
            return deque.size > maxAttempts
        }
    }

    /**
     * Marks that a user prompt dialog has been posted and is awaiting decision.
     *
     * 标记该应用的授权询问弹窗已激活并正在等待用户决断。
     *
     * @param packageName Target package name.
     */
    fun onPromptStarted(packageName: String) {
        activePromptCounts.compute(packageName) { _, current -> (current ?: 0) + 1 }
    }

    /**
     * Marks that a user prompt dialog has finished (by user tap, dismissal, or timeout).
     *
     * 标记该应用的授权询问弹窗已结束（用户点击、取消或超时关闭）。
     *
     * @param packageName Target package name.
     */
    fun onPromptFinished(packageName: String) {
        activePromptCounts.compute(packageName) { _, current ->
            val updated = (current ?: 1) - 1
            if (updated <= 0) null else updated
        }
    }

    /**
     * Registers a cooldown period following a denied, dismissed, or timed-out prompt.
     *
     * 记录弹窗被拒绝、取消或超时后的静默冷却周期。
     *
     * @param packageName Target package name.
     * @param cooldownMs Cooldown duration in milliseconds.
     */
    fun setDenyCooldown(packageName: String, cooldownMs: Long) {
        readWindows[packageName] = ReadWindow(timeProvider() + cooldownMs.coerceAtLeast(0))
    }

    fun beginReadGrant(packageName: String, pendingTimeoutMs: Long): ReadGrant {
        val grant = ReadGrant()
        readWindows[packageName] = ReadWindow(timeProvider() + pendingTimeoutMs, grant)
        return grant
    }

    fun completeReadGrant(packageName: String, pending: ReadGrant, clipIdentity: Any, durationMs: Long) {
        readWindows.computeIfPresent(packageName) { _, current ->
            val now = timeProvider()
            if (current.grant !== pending || now >= current.expiresAt) current
            else ReadWindow(now + durationMs.coerceAtLeast(0), ReadGrant(clipIdentity))
        }
    }

    fun isReadGrantCurrent(packageName: String, grant: ReadGrant): Boolean =
        readWindows[packageName]?.let { it.grant === grant && timeProvider() < it.expiresAt } == true

    fun cancelReadGrant(packageName: String, grant: ReadGrant) {
        readWindows.computeIfPresent(packageName) { _, current ->
            if (current.grant === grant) null else current
        }
    }

    /**
     * Evaluates whether a log broadcast for this package should be debounced to prevent flooding.
     *
     * 评估是否应防刷屏抑制该应用的日志广播，并在达到间隔时返回累计被抑制的次数。
     *
     * @param packageName Target package name.
     * @param minIntervalMs Minimum interval between log broadcasts in milliseconds.
     * @return Pair of (shouldEmit, aggregatedSuppressedCount).
     */
    fun checkLogDebounce(packageName: String, minIntervalMs: Long = DEFAULT_LOG_DEBOUNCE_MS): Pair<Boolean, Int> {
        val now = timeProvider()
        val lastTime = lastLogEmitTimes[packageName] ?: 0L
        if (now - lastTime < minIntervalMs) {
            suppressedLogCounts.compute(packageName) { _, count -> (count ?: 0) + 1 }
            return Pair(false, 0)
        }
        val suppressed = suppressedLogCounts.remove(packageName) ?: 0
        lastLogEmitTimes[packageName] = now
        return Pair(true, suppressed)
    }

    /**
     * Clears all cached cooldowns, burst windows, and attempt histories.
     *
     * 清空所有已缓存的冷却、突发窗口与尝试记录。
     */
    fun reset() {
        readWindows.clear()
        rateLimitPenaltyUntil.clear()
        activePromptCounts.clear()
        readAttemptTimestamps.clear()
        lastLogEmitTimes.clear()
        suppressedLogCounts.clear()
    }

    companion object {
        /**
         * Default sliding window duration in milliseconds (3 seconds).
         *
         * 默认滑动时间窗口时长（毫秒，默认 3 秒）。
         */
        const val DEFAULT_WINDOW_MS: Long = 3_000L

        /**
         * Default maximum allowed read attempts within sliding window.
         *
         * 滑动时间窗口内允许的最大读取尝试次数（默认 5 次）。
         */
        const val DEFAULT_MAX_ATTEMPTS: Int = 5

        /**
         * Default rate-limiting penalty duration in milliseconds (5 seconds).
         *
         * 触发频率限制后的惩罚静默时长（毫秒，默认 5 秒）。
         */
        const val DEFAULT_PENALTY_MS: Long = 5_000L

        /**
         * Default number of concurrent reads tolerated for a single package before silent throttling.
         *
         * 单个应用在触发静默限流前允许的并发读取次数上限（默认 3 次）。
         */
        const val DEFAULT_PROMPT_TOLERANCE: Int = 3

        /**
         * Default deny cooldown duration in milliseconds (30 seconds).
         *
         * 用户拒绝或关闭授权弹窗后的默认静默冷却时长（毫秒，默认 30 秒）。
         */
        const val DEFAULT_DENY_COOLDOWN_MS: Long = 30_000L

        /**
         * Default one-time grant burst window duration in milliseconds (5 seconds).
         *
         * 用户单次允许后的临时免弹窗连读窗口时长（毫秒，默认 5 秒）。
         */
        const val DEFAULT_GRANT_BURST_MS: Long = 5_000L

        /**
         * Default minimum interval between hook log broadcasts in milliseconds (2 seconds).
         *
         * Hook 拦截日志广播之间的最小防抖间隔（毫秒，默认 2 秒）。
         */
        const val DEFAULT_LOG_DEBOUNCE_MS: Long = 2_000L

        /**
         * Minimum interval in milliseconds between opportunistic stale-state sweeps (30 seconds).
         *
         * 相邻两次按应用簿记清理之间的最小间隔（毫秒，默认 30 秒）。
         */
        const val PRUNE_INTERVAL_MS: Long = 30_000L

        /**
         * Age in milliseconds after which dormant per-package bookkeeping is dropped (5 minutes).
         *
         * 按应用簿记数据在静默多久之后可被清理（毫秒，默认 5 分钟）。
         */
        const val STALE_STATE_TTL_MS: Long = 300_000L
    }
}

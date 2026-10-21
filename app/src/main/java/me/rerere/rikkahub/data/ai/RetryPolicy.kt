package me.rerere.rikkahub.data.ai

/* ───【域 B·AI 传输】RetryPolicy.kt
 * 职责: 重试策略 (发起池/流中断链/思考静默链/退避参数)
 * 常用改动: 重试参数 → 各链常量; 断流 → 两轮链 12 次
 * 问题定位: 连接易断/重试风暴 → 本文件
 * 基线: v5.0.58 新重试机制（存档 retry-baseline-v5.0.57）| 地图: docs/APP_MAP.md §B
 * ───────────────────────────────────────────────────────────────*/

import me.rerere.ai.core.StreamTimeoutException
import kotlin.random.Random

/**
 * v5.0.58（用户定版·新重试机制）—— 综合 Codex CLI / OpenAI SDK / Anthropic SDK 的做法，
 * 针对"长期蜂窝网络 + 网络不稳 + 不能输出断很久"重排预算。
 *
 * 与旧的 `retry-baseline-v5.0.57` 的差异（可整体回滚到该 tag）：
 *
 * | 链            | 旧        | 新   | 理由 |
 * |---------------|-----------|------|------|
 * | Init 发起     | 5 线性    | 8 指数| 连接失败没花算力，便宜；指数避免撞同一拥塞点 |
 * | Stream 流中断 | 18 固定序 | 12 指数| 固定序列第 2 轮起节奏重复；指数+jitter 更抗抖动 |
 * | DataTimeout   | **1** 固定| 3 递增| 旧值 1 次等于没有容错（用户"忽然很频繁"的根源） |
 * | 空响应        | 1         | 2     | 平台偶发空流，第 2 次多半成功 |
 *
 * 另外两处结构性改动：
 *  ① **分类不再靠中文字符串嗅探** —— 优先读 [StreamTimeoutException] 的 phase 字段；
 *     旧文案 `contains("生成无有效数据超时")` 仅作为其它抛出点的兼容兜底。
 *  ② **快速失败检测** —— 连续 N 次尝试都在很短时间内失败（连接被立即拒绝），
 *     说明是硬故障而非抖动，退避直接拉长，避免 12 次预算在十几秒内烧光。
 *
 * 设计不变量：
 * - 全部用户可见报错文案保持原样（除 DataTimeout 的次数占位）
 * - [RetryPhase] 只做配额与推进，[RetryState] 只做计数，决策全在 [RetryDecision]
 * - [RetryDecision.decide] 是**纯函数**（jitter 可关闭），单测直接覆盖全分支
 */

/** 重试阶段 — 封装配额与推进, 替代散落的 round/phase 算术 */
sealed class RetryPhase {
    abstract val used: Int
    abstract val total: Int

    /** 是否还有重试余量 */
    val hasQuota: Boolean get() = used < total

    /**
     * 发起阶段: 重试 8 次 (1 次初始请求 + 8 次重试)。
     * v5.0.58: 5 → 8；退避由线性 0.5s×n 改为指数 0.5/1/2/4/8s(封顶) + jitter。
     * 依据：Codex 的连接类错误是"无上限 + 指数封顶 60s"；我们保留有限预算但给指数。
     */
    data class Init(override val used: Int = 0) : RetryPhase() {
        override val total get() = 8

        /** 第 n 次（1-based）的等待时长；header 判死型不 delay（已耗 25-60s） */
        fun backoffMs(attempt: Int, jitter: Boolean = true): Long =
            RetryPhase.backoff(attempt, base = 500L, cap = 8_000L, jitter = jitter)

        fun next() = copy(used = used + 1)
        fun exhaustedMessage(): String =
            "发起对话失败: 已尝试 $used 次仍无法建立连接 (网关冷启动或瞬时不可达)，已保留输入内容，请稍后重试"
    }

    /**
     * 流中断阶段: 两轮链 2×6 = 12 次（v5.0.58: 旧三轮 18 次）。
     * 退避指数 0.5/1/2/4/8/8s（封顶 8s）+ jitter，取代旧的固定序列 300/700/1s/2s/3s/5s。
     */
    data class Stream(override val used: Int = 0) : RetryPhase() {
        override val total get() = 12

        fun delayMs(jitter: Boolean = true): Long =
            RetryPhase.backoff(used + 1, base = 500L, cap = 8_000L, jitter = jitter)

        /** 日志描述: round x/2 step y/6 */
        fun describe(): String {
            val round = used / 6 + 1
            val phaseInRound = used % 6 + 1
            return "round $round/2 step $phaseInRound/6"
        }

        fun next() = copy(used = used + 1)
        fun exhaustedMessage(): String =
            "生成中断: 自动恢复 2 轮 (共 $used 次) 已达上限，已保留已生成内容"
    }

    /**
     * 数据超时阶段 (v5.0.12 引入 / v5.0.58 重定预算): 建连成功但思考/排队静默超时
     * (看门狗 first-event/thinking 阶段判死)。
     *
     * 旧值 1 次 —— 等于没有容错（用户实证"思考链中断忽然很频繁"）。新值 3 次，
     * 退避 2s/5s/12s 递增（总等待约 19s，不长）。
     *
     * 为什么不能并进 12 次流中断链：思考被杀后每次重试都要从头再付整段思考
     * (最长 480s × 4)，12 次就是马拉松。
     */
    data class DataTimeout(override val used: Int = 0) : RetryPhase() {
        override val total get() = 3

        fun delayMs(): Long = longArrayOf(2_000L, 5_000L, 12_000L)
            .getOrElse(used) { 12_000L }

        fun next() = copy(used = used + 1)
        fun exhaustedMessage(): String =
            "上游长时间无输出: 已等待并重试 $used 次仍无任何数据 (思考静默超时)，已保留输入内容，请稍后重试"
    }

    companion object {
        /**
         * 指数退避 + 可选 jitter。
         * attempt 1-based: base × 2^(attempt-1)，封顶 cap；jitter 取 50%~100%（full-ish jitter）。
         * 不加 jitter 时纯指数（单测用，保证断言稳定）。
         */
        fun backoff(attempt: Int, base: Long, cap: Long, jitter: Boolean): Long {
            val n = (attempt - 1).coerceIn(0, 10)
            val raw = base shl n
            val capped = raw.coerceAtMost(cap)
            if (!jitter) return capped
            val factor = 0.5 + Random.nextDouble() * 0.5
            return (capped * factor).toLong().coerceAtLeast(base / 2)
        }
    }
}

/** 失败分类 — 类型优先 (v5.0.58), 字符串仅在旧抛出点兜底 */
sealed class StreamFailure {
    /** 复读熔断 (ClientGenerationGuardException): 保留内容终态 */
    data object Guarded : StreamFailure()
    /** OpenCode Zen 无完成信号关流: 保留内容终态 */
    data object Unconfirmed : StreamFailure()
    /** 发起阶段失败: 从未收到任何流数据 */
    data class InitPhase(val headerDead: Boolean) : StreamFailure()
    /** 数据超时 (v5.0.12): 建连成功但思考/排队静默超时 — 独立预算链, 不进流中断链 */
    data object DataTimeout : StreamFailure()
    /** 流中断: 收到过数据或 watchdog 正文断流 */
    data object StreamInterrupted : StreamFailure()
}

/** 判决: 重试 或 终止 */
sealed class RetryVerdict {
    /** 重试: delay 后 continue 循环 */
    data class Retry(val delayMs: Long, val logDetail: String) : RetryVerdict()
    /** 终止: 抛出携带原文案的 IOException */
    data class Abort(val message: String) : RetryVerdict()
}

/**
 * 失败判别。
 *
 * v5.0.58 核心改动：**先看类型，再看字符串**。
 *
 * 旧实现全靠 `msg.contains("生成无有效数据超时")` 这类中文字面量反推阶段 ——
 * 文案改一个字、加 "[vX.Y.Z] " 前缀、或做多语言，分类立刻错位：
 * 思考链静默超时会被当成"发起失败"（预算 5）或反过来，这正是"思考链莫名中断"的温床。
 *
 * 现在 [me.rerere.ai.core.StreamWatchdog] 直接抛 [StreamTimeoutException] 并携带
 * `phase`，分类读字段即可；字符串分支只服务其它（非看门狗）抛出点。
 */
object StreamFailureClassifier {
    private const val WATCHDOG_DATA_TIMEOUT = "生成无有效数据超时"
    private const val HEADER_DEAD = "平台连接无响应"

    fun classify(e: java.io.IOException, receivedAnyData: Boolean): StreamFailure {
        // ① 结构化路径（看门狗）
        if (e is StreamTimeoutException) {
            return when (e.phase) {
                // 正文流出中的间隙 → 真断流域，走流中断链
                "stream" -> StreamFailure.StreamInterrupted
                // 响应头都没来 → 发起阶段；headerDead=true 表示不 delay
                "header" -> StreamFailure.InitPhase(headerDead = true)
                // 已连接但首事件没来（排队/冷启动）或思考中途静默 → 独立小预算链
                "first-event", "thinking" -> StreamFailure.DataTimeout
                else -> if (receivedAnyData) StreamFailure.StreamInterrupted
                else StreamFailure.InitPhase(headerDead = false)
            }
        }

        // ② 兼容路径（旧抛出点/其它 provider 包装成 IOException 的场景）
        val msg = e.message ?: ""
        return when {
            msg.contains(WATCHDOG_DATA_TIMEOUT) &&
                !msg.contains(me.rerere.ai.core.StreamWatchdog.TAIL_STREAM_BREAK) ->
                StreamFailure.DataTimeout
            !receivedAnyData && !msg.contains(WATCHDOG_DATA_TIMEOUT) ->
                StreamFailure.InitPhase(headerDead = msg.contains(HEADER_DEAD))
            else -> StreamFailure.StreamInterrupted
        }
    }
}

/**
 * 统一重试决策 — 输入失败分类与当前阶段, 输出判决。
 *
 * [fastFailStreak]: 连续"秒失败"次数（调用方维护）。连接被立即拒绝/系统级封锁时，
 * 每次尝试都在 <1s 内失败，12 次预算会在十几秒内烧光，用户只看到"重试了半天还是失败"。
 * 达到阈值后退避直接拉到 [FAST_FAIL_MIN_DELAY_MS]，把节奏还给网络恢复。
 *
 * [jitter]: 是否加随机抖动（生产 true；单测 false 保证断言稳定）。
 *
 * 纯函数：不读写任何全局状态，单测可覆盖全分支。
 */
object RetryDecision {
    /** 连续秒失败达到此值 → 视为硬故障 */
    const val FAST_FAIL_THRESHOLD = 3

    /** 硬故障时的最小退避 */
    const val FAST_FAIL_MIN_DELAY_MS = 30_000L

    fun decide(
        failure: StreamFailure,
        phase: RetryPhase,
        autoRetryEnabled: Boolean,
        fastFailStreak: Int = 0,
        /**
         * v5.0.61: 服务器建议的重试等待（来自 `Retry-After` 头，已在
         * [me.rerere.ai.core.RetryAfter] 里封顶 120s）。
         *
         * 优先级最高 —— 服务器说"等 30s"就别 0.5s 再去撞（429/503 场景
         * 固定短间隔重试会被上游判成恶意流量，越炸越狠）。仅作用于
         * Init / Stream 两条链；思考静默（DataTimeout）不读它。
         */
        retryAfterMs: Long? = null,
        jitter: Boolean = true,
    ): RetryVerdict = when (failure) {
        is StreamFailure.InitPhase -> when {
            !autoRetryEnabled -> RetryVerdict.Abort(
                "发起对话失败: 自动重试已关闭"
            )
            !phase.hasQuota -> RetryVerdict.Abort((phase as RetryPhase.Init).exhaustedMessage())
            else -> {
                val p = phase as RetryPhase.Init
                val raw = when {
                    retryAfterMs != null -> retryAfterMs
                    failure.headerDead -> 0L
                    else -> p.backoffMs(p.used + 1, jitter)
                }
                val delay = applyFastFail(raw, fastFailStreak)
                RetryVerdict.Retry(
                    delay,
                    "connect fail, retry ${p.used + 1}/${p.total} (headerDead=${failure.headerDead}" +
                        (if (retryAfterMs != null) ", retryAfter=${retryAfterMs}ms" else "") +
                        (if (fastFailStreak >= FAST_FAIL_THRESHOLD) ", fastFail=$fastFailStreak" else "") + ")"
                )
            }
        }
        is StreamFailure.StreamInterrupted -> when {
            !autoRetryEnabled -> RetryVerdict.Abort(
                "生成中断: 自动重试已关闭"
            )
            !phase.hasQuota -> RetryVerdict.Abort((phase as RetryPhase.Stream).exhaustedMessage())
            else -> {
                val p = phase as RetryPhase.Stream
                val raw = retryAfterMs ?: p.delayMs(jitter)
                val delay = applyFastFail(raw, fastFailStreak)
                RetryVerdict.Retry(
                    delay,
                    "${p.describe()} retry ${p.used + 1}/${p.total}" +
                        (if (retryAfterMs != null) " retryAfter=${retryAfterMs}ms" else "") +
                        (if (fastFailStreak >= FAST_FAIL_THRESHOLD) " fastFail=$fastFailStreak" else "")
                )
            }
        }
        // 思考/排队静默超时 — 3 次递增退避（v5.0.58: 1 → 3）。
        is StreamFailure.DataTimeout -> when {
            !autoRetryEnabled -> RetryVerdict.Abort(
                "生成中断: 自动重试已关闭"
            )
            !phase.hasQuota -> RetryVerdict.Abort((phase as RetryPhase.DataTimeout).exhaustedMessage())
            else -> {
                val p = phase as RetryPhase.DataTimeout
                RetryVerdict.Retry(p.delayMs(), "data-timeout retry ${p.used + 1}/${p.total}")
            }
        }
        // Guarded/Unconfirmed 在 catch 链薄分支直接终态, 不进入决策
        is StreamFailure.Guarded, is StreamFailure.Unconfirmed ->
            RetryVerdict.Abort("unreachable")
    }

    /** 秒失败 streak 达到阈值时抬高退避（只抬不降） */
    private fun applyFastFail(delayMs: Long, fastFailStreak: Int): Long =
        if (fastFailStreak >= FAST_FAIL_THRESHOLD) maxOf(delayMs, FAST_FAIL_MIN_DELAY_MS)
        else delayMs
}

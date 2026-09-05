package me.rerere.rikkahub.data.ai

/**
 * 4.0.0 重写工程 · 模块 1: 流式重试策略 (原 GenerationHandler catch 链 135 行
 * 嵌套策略混杂 → 策略对象模式)。
 *
 * 设计不变量 (行为等价重构, 数值与文案逐字保留):
 * - 发起池: 4 次, 线性退避 0.5s×n (header 判死型不 delay)
 * - 流中断: 三轮链 3 轮×6 次=18 次, 每轮 = 风暴 3×300ms + 经典 1/2/4s
 * - 全部用户可见报错文案保持原样
 *
 * 职责边界:
 * - RetryState (GenerationHandler.kt) 只做计数器, 策略逻辑零残留
 * - 本文件只做决策, IO/状态回滚/UI 更新留在 GenerationHandler catch 链
 */

/** 重试阶段 — 封装配额与推进, 替代散落的 round/phase 算术 */
sealed class RetryPhase {
    abstract val used: Int
    abstract val total: Int

    /** 是否还有重试余量 */
    val hasQuota: Boolean get() = used < total

    /** 发起阶段: 4 次连接尝试 */
    data class Init(override val used: Int = 0) : RetryPhase() {
        override val total get() = 4
        /** 快速失败型线性退避 0.5s×n (header 判死型已耗 25s 等待不 delay) */
        fun backoffMs(attempt: Int): Long = 500L * attempt
        fun next() = copy(used = used + 1)
        fun exhaustedMessage(): String =
            "发起对话失败: 已尝试 $used 次仍无法建立连接 (网关冷启动或瞬时不可达)，已保留输入内容，请稍后重试"
    }

    /** 流中断阶段: 三轮链 18 次 */
    data class Stream(override val used: Int = 0) : RetryPhase() {
        override val total get() = 18
        /** 第 n 次 (0-based 已用计数) 的等待时长: 轮内前 3 次风暴 300ms, 后 3 次经典 1/2/4s */
        fun delayMs(): Long {
            val phaseInRound = used % 6 + 1
            return if (phaseInRound <= 3) 300L else longArrayOf(1000L, 2000L, 4000L)[phaseInRound - 4]
        }
        /** 日志描述: round x/3 phase storm|classic y/6 */
        fun describe(): String {
            val round = used / 6 + 1
            val phaseInRound = used % 6 + 1
            val kind = if (phaseInRound <= 3) "storm" else "classic"
            return "round $round/3 $kind phase $phaseInRound/6"
        }
        fun next() = copy(used = used + 1)
        fun exhaustedMessage(): String =
            "生成中断: 自动恢复 3 轮 (共 $used 次) 已达上限，已保留已生成内容"
    }
}

/** 失败分类 — 字符串嗅探收敛于此 (原 catch 链内散落的 message.contains 判定) */
sealed class StreamFailure {
    /** 复读熔断 (ClientGenerationGuardException): 保留内容终态 */
    data object Guarded : StreamFailure()
    /** OpenCode Zen 无完成信号关流: 保留内容终态 */
    data object Unconfirmed : StreamFailure()
    /** 发起阶段失败: 从未收到任何流数据 */
    data class InitPhase(val headerDead: Boolean) : StreamFailure()
    /** 流中断: 收到过数据或 watchdog 数据超时 */
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
 * 失败判别 — 判据说明 (v3.15.1 用户实证定版, 原样保留):
 * receivedAnyData==false 且非 watchdog 数据超时 → 发起失败 (数据超时说明
 * 连接已建立, 属流阶段); 收到过任何数据一律走三轮链。
 */
object StreamFailureClassifier {
    private const val WATCHDOG_DATA_TIMEOUT = "生成无有效数据超时"
    private const val HEADER_DEAD = "平台连接无响应"

    fun classify(e: java.io.IOException, receivedAnyData: Boolean): StreamFailure {
        return when {
            !receivedAnyData && e.message?.contains(WATCHDOG_DATA_TIMEOUT) != true ->
                StreamFailure.InitPhase(headerDead = e.message?.contains(HEADER_DEAD) == true)
            else -> StreamFailure.StreamInterrupted
        }
    }
}

/**
 * 统一重试决策 — 输入失败分类与当前阶段, 输出判决。
 * autoRetryEnabled=false 的两处拦截 (发起/流中) 在此收敛, 文案原样保留。
 */
object RetryDecision {
    fun decide(
        failure: StreamFailure,
        phase: RetryPhase,
        autoRetryEnabled: Boolean,
    ): RetryVerdict = when (failure) {
        is StreamFailure.InitPhase -> when {
            !autoRetryEnabled -> RetryVerdict.Abort(
                "发起对话失败: 自动重试已关闭"
            )
            !phase.hasQuota -> RetryVerdict.Abort((phase as RetryPhase.Init).exhaustedMessage())
            else -> {
                val p = phase as RetryPhase.Init
                val headerDead = (failure as StreamFailure.InitPhase).headerDead
                val delay = if (headerDead) 0L else p.backoffMs(p.used + 1)
                RetryVerdict.Retry(delay, "connect fail, retry ${p.used + 1}/${p.total} (headerDead=$headerDead)")
            }
        }
        is StreamFailure.StreamInterrupted -> when {
            !autoRetryEnabled -> RetryVerdict.Abort(
                "生成中断: 自动重试已关闭"
            )
            !phase.hasQuota -> RetryVerdict.Abort((phase as RetryPhase.Stream).exhaustedMessage())
            else -> {
                val p = phase as RetryPhase.Stream
                RetryVerdict.Retry(p.delayMs(), "${p.describe()} retry ${p.used + 1}/${p.total}")
            }
        }
        // Guarded/Unconfirmed 在 catch 链薄分支直接终态, 不进入决策
        is StreamFailure.Guarded, is StreamFailure.Unconfirmed ->
            RetryVerdict.Abort("unreachable")
    }
}

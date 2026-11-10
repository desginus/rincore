/* 【域 A·对话核心】 — 预热有效性观测 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.service

import android.util.Log

/**
 * ★v5.3.71：预热有效性观测。
 *
 * 用户的问题不是「预热有没有跑」，而是「预热到底有没有让首字/首个工具更快」。
 * 旧实现只有 `keepalive ping ok` 日志 —— 那只证明连接建立了，不证明主请求复用了它。
 *
 * 这里记录三类可对照的事实：
 *  1. `PREWARM/conn`   ：哪一刻、哪个 host、建立了连接（one-shot / keepalive / startup）；
 *  2. `PREWARM/ttfb`   ：真实首字耗时 + 距上次预热的毫秒数（warmAge）；
 *  3. `PREWARM/sandbox`：沙箱预热耗时 + 预热后第一条 shell 的耗时（只记第一条，防刷屏）；
 *  4. `PREWARM/report` ：每 10 个首字样本输出一次 warm/cold 平均，直接看有没有收益。
 *
 * 刻意不做「自动关闭预热」：首字耗时受模型、网络、服务端排队影响，样本不足时
 * 自动关闭可能误杀。只提供可判读的数据，由用户/后续版本决定。
 */
object PrewarmMetrics {
    private const val TAG = "PREWARM"
    private const val WARM_WINDOW_MS = 120_000L
    private const val SANDBOX_WINDOW_MS = 10 * 60_000L

    @Volatile
    private var lastConnHost: String? = null

    @Volatile
    private var lastConnAt: Long = 0L

    private var ttfbWarmCount = 0
    private var ttfbWarmTotal = 0L
    private var ttfbColdCount = 0
    private var ttfbColdTotal = 0L

    @Volatile
    private var sandboxWarmAt: Long = 0L

    @Volatile
    private var sandboxWarmDuration: Long = 0L

    @Volatile
    private var sandboxFirstLogged: Boolean = false

    /** 连接预热成功（one-shot / keepalive / startup / poke）。 */
    fun onConnWarm(host: String, pingMs: Long, mode: String) {
        lastConnHost = host
        lastConnAt = System.currentTimeMillis()
        Log.d(TAG, "conn host=$host ping=${pingMs}ms mode=$mode")
    }

    /** 距上次该 host 预热成功的毫秒数；-1 = 没有可用的预热记录。 */
    fun warmAgeMs(host: String?): Long {
        if (host.isNullOrBlank() || host != lastConnHost) return -1L
        return System.currentTimeMillis() - lastConnAt
    }

    /** 真实首字耗时。warmAge 在 2 分钟内才计入 warm 组。 */
    fun onTtfb(modelId: String, ttfbMs: Long, host: String?) {
        val age = warmAgeMs(host)
        val warm = age in 0L..WARM_WINDOW_MS
        synchronized(this) {
            if (warm) {
                ttfbWarmCount += 1
                ttfbWarmTotal += ttfbMs
            } else {
                ttfbColdCount += 1
                ttfbColdTotal += ttfbMs
            }
            Log.d(
                TAG,
                "ttfb model=$modelId ttfb=${ttfbMs}ms " +
                    "warmAge=${if (age >= 0) "${age}ms" else "cold"} warm=$warm",
            )
            if ((ttfbWarmCount + ttfbColdCount) % 10 == 0) reportLocked()
        }
    }

    /** 沙箱预热完成（无论 officecli 成功与否，proot 已走热）。 */
    fun onSandboxWarm(durationMs: Long, ok: Boolean) {
        sandboxWarmAt = System.currentTimeMillis()
        sandboxWarmDuration = durationMs
        sandboxFirstLogged = false
        Log.d(TAG, "sandbox warm=${durationMs}ms ok=$ok")
    }

    /**
     * 沙箱预热后的第一条 shell 命令：记录执行耗时与 warmAge。
     * 同时识别渲染类命令（officecli view / chromium / playwright / weasyprint），
     * 用来判断浏览器内核预热到底有没有被用上。
     */
    fun onShellCommand(command: String, durationMs: Long) {
        val now = System.currentTimeMillis()
        val warmAge = if (sandboxWarmAt > 0L) now - sandboxWarmAt else -1L
        if (!sandboxFirstLogged && warmAge in 0L..SANDBOX_WINDOW_MS) {
            sandboxFirstLogged = true
            Log.i(
                TAG,
                "sandbox firstShell=${durationMs}ms warmAge=${warmAge}ms " +
                    "warmDuration=${sandboxWarmDuration}ms",
            )
        }
        val lower = command.lowercase()
        val render = (lower.contains("officecli") && (lower.contains("view") || lower.contains("screenshot"))) ||
            lower.contains("chromium") ||
            lower.contains("playwright") ||
            lower.contains("weasyprint")
        if (render) {
            Log.i(
                TAG,
                "browser used warmAge=${if (warmAge >= 0) "${warmAge}ms" else "cold"}",
            )
        }
    }

    private fun reportLocked() {
        val warmAvg = if (ttfbWarmCount > 0) ttfbWarmTotal / ttfbWarmCount else -1L
        val coldAvg = if (ttfbColdCount > 0) ttfbColdTotal / ttfbColdCount else -1L
        Log.i(
            TAG,
            "report warmSamples=$ttfbWarmCount warmAvg=${warmAvg}ms " +
                "coldSamples=$ttfbColdCount coldAvg=${coldAvg}ms",
        )
    }
}

/* 【域 D·工作区沙箱】 — 服务 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.service

/* ───【自研】SandboxPrewarmer.kt — 沙箱预热 (v5.0.78 触发式重写)
 * 触发点 = 每次在输入框中"开始输入"（空→非空的沿），由 ChatVM 调起；
 * 进程内 30 分钟窗口节流 + 互斥幂等并发安全；启动时不再预热
 * （旧启动预热与启动初始化抢 IO，用户实测拖慢启动且收益有限）。
 * 检修点：warmOnce 日志记录触发源与耗时。
 * ───────────────────────────────────────────────────────────────*/

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext

object SandboxPrewarmer {
    private const val TAG = "SandboxPrewarmer"
    // v5.0.78：防抖窗 60s —— "每次开始输入进行一次"，但 60s 内的连续输入沿合并为一次
    // （proot 走热是秒级 IO，重复预热无意义；旧 30 分钟窗口会让预热"没什么用"）。
    private const val WARM_WINDOW_MS = 60_000L

    // ═══ v5.3.42 ★★熔断：预热在部分 rootfs/机型上会失败（officecli 崩溃）═══
    // 旧实现在失败后只 Log.w，下一个输入沿继续试 —— 每试一次都可能再写一个 43MB core dump。
    // 现在：连续 3 次失败 → 24 小时内不再尝试（预热是优化项，失败不应有副作用）。
    // 成功一次即清零，避免曾经失败过的机型永久失去预热。
    private const val FAIL_THRESHOLD = 3
    private const val FAIL_COOLDOWN_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var consecutiveFailures = 0
    @Volatile
    private var disabledUntilMs = 0L

    @Volatile
    private var lastWarmAtMs = 0L

    private val mutex = Mutex()

    /**
     * 触发式预热（fire-and-forget）：调用方 = "用户开始在输入框输入"。
     * 节流窗口内零成本；失败无害；扩展点 = 新预热项追加到 runCatching 块。
     */
    fun warmOnTypingStartAsync(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { runCatching { warmOnce() } }
    }

    suspend fun warmOnce() {
        val now = System.currentTimeMillis()
        if (now < disabledUntilMs) return
        if (now - lastWarmAtMs < WARM_WINDOW_MS) return
        mutex.withLock {
            if (System.currentTimeMillis() < disabledUntilMs) return@withLock
            if (System.currentTimeMillis() - lastWarmAtMs < WARM_WINDOW_MS) return@withLock
            lastWarmAtMs = System.currentTimeMillis()
            val started = System.currentTimeMillis()
            runCatching {
                val koin = GlobalContext.get()
                val repo = koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
                val ws = repo.getAllWorkspaces().firstOrNull() ?: return@runCatching
                // ═══ v5.3.42 ★★三个修复 ═══
                // (1) `ulimit -c 0` —— 关键。officecli 在部分 rootfs 上 `--version` 直接
                //     SIGSEGV/SIGABRT，内核把 **43MB core dump** 写进当前工作目录（= 用户工作区
                //     CWD，文件名是一个空格或 core，用户根本不会注意到，存储被静默吃掉）。
                //     禁用 core 后即使崩溃也只多一行日志，不再产生文件。
                // (2) 探测改用 `--help` 而不是 `--version`：--version 走的初始化路径更短但更容易
                //     在异常 rootfs 上崩；--help 同样会加载 34MB 二进制并触发页缓存，收益一致。
                // (3) 失败要能被看见：返回非零即视为失败（旧实现用 `; true` 把失败也变成成功）。
                val result = repo.executeCommand(
                    ws.id,
                    "ulimit -c 0 2>/dev/null; officecli --help >/dev/null 2>&1",
                    "",
                )
                // ═══ ★关键：executeCommand **不会**因非零退出码抛异常（它返回
                // WorkspaceCommandResult），所以不加这一句的话上面那句永远算「成功」→
                // 连续失败熔断永远不会触发（我第一版就是这么写的，实测发现）。
                // 非零退出 = officecli 缺失或崩溃 = 预热确实没成功。
                check(result.exitCode == 0 && !result.timedOut) {
                    "exit=${result.exitCode} timedOut=${result.timedOut} ${result.stderr.take(160)}"
                }
            }.onFailure {
                consecutiveFailures += 1
                if (consecutiveFailures >= FAIL_THRESHOLD) {
                    disabledUntilMs = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                    Log.w(
                        TAG,
                        "warm failed ${consecutiveFailures}x consecutively — disabled for 24h " +
                            "(last: ${it.message})",
                    )
                } else {
                    Log.w(TAG, "warm failed (${consecutiveFailures}/${FAIL_THRESHOLD}): ${it.message} — 无害，下个输入沿再试")
                }
            }.onSuccess {
                consecutiveFailures = 0
            }
            // 检修点：触发源与耗时（判断预热是否真的抢了输入期的资源）
            Log.d(TAG, "warm done in ${System.currentTimeMillis() - started}ms (source=typing-start)")
        }
    }
}

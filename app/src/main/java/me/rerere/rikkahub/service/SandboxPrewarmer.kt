/* 【域 D·工作区沙箱】 — 服务 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.service

/* ───【自研】SandboxPrewarmer.kt — 沙箱预热 (v5.3.65: 启动即预热 + 浏览器内核)
 *
 * 触发点（两条，互为补充）：
 *   ① 应用启动后 1.2s（IO 线程，不阻塞首帧）→ PRoot + officecli + 浏览器内核；
 *   ② 用户开始在输入框输入（空→非空的沿）→ PRoot + officecli（轻量，不拉浏览器）。
 * 进程内 60s 窗口节流 + 互斥幂等并发安全。
 *
 * v5.3.65 用户定案：「每次打开应用的同时，默认同步拉起 PRoot，包括浏览器内核」。
 * 与 v5.0.78「启动不预热」的差异：旧实现是在 onCreate 同步链路里抢启动 IO，
 * 且只预热 officecli；现在放到启动完成后 1.2s 的独立 IO 协程，并把浏览器内核
 * （Playwright Chromium / headless_shell）一并走热，专门服务 officecli view
 * screenshot / 渲染类工具的首个调用。
 *
 * 检修点：warm done 日志带 source 与耗时；浏览器内核预热单独一行，失败不影响主预热。
 * ───────────────────────────────────────────────────────────────*/

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.core.context.GlobalContext

object SandboxPrewarmer {
    private const val TAG = "SandboxPrewarmer"

    /** 「每次开始输入进行一次」，但 60s 内的连续输入沿合并为一次。 */
    private const val WARM_WINDOW_MS = 60_000L

    /** 启动预热延迟：让首帧、Room、Coil 先把启动 IO 跑完，再走沙箱重活。 */
    private const val STARTUP_DELAY_MS = 1_200L

    /** 生成结束后的空闲预热延迟（给收尾落库/通知让路）。 */
    private const val POST_GENERATION_DELAY_MS = 5_000L

    /**
     * 启动预热里「先 PRoot/officecli，再等 8 秒才拉 Chromium」。
     * 这 8 秒是给用户发第一条消息的窗口：一旦发送，cancelForGeneration 会取消
     * 整个任务，Chromium 根本不会启动。
     */
    private const val BROWSER_DEFER_MS = 8_000L

    /** Chromium 走热最多 10 分钟一次（它是 20~30 秒的重活，不能每轮都做）。 */
    private const val BROWSER_WARM_WINDOW_MS = 10 * 60_000L

    // ═══ v5.3.42 ★★熔断：预热在部分 rootfs/机型上会失败（officecli 崩溃）═══
    // 连续 3 次失败 → 24 小时内不再尝试；成功一次即清零。
    private const val FAIL_THRESHOLD = 3
    private const val FAIL_COOLDOWN_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var disabledUntilMs = 0L

    @Volatile
    private var lastWarmAtMs = 0L

    /** 浏览器内核最近一次走热时间（10 分钟内不重复拉 Chromium）。 */
    @Volatile
    private var lastBrowserWarmAtMs = 0L

    /** 当前在跑的预热任务（生成开始时会被取消 —— 预热永远给首字让路）。 */
    @Volatile
    private var warmJob: kotlinx.coroutines.Job? = null

    /** 每次生成开始自增；预热在关键步骤前比对，发现已开始生成就安静退出。 */
    @Volatile
    private var generationEpoch: Long = 0L

    private val mutex = Mutex()

    /**
     * 启动预热（fire-and-forget）：PRoot + officecli + 浏览器内核。
     * 由 RikkaHubApp.onCreate 在 Koin 就绪后调用；内部自行延迟到启动 IO 之后。
     */
    fun warmOnAppStartAsync(scope: CoroutineScope) {
        warmJob?.cancel()
        warmJob = scope.launch(Dispatchers.IO) {
            delay(STARTUP_DELAY_MS)
            runCatching { warmOnce(source = "app-start") }
        }
    }

    /**
     * v5.3.67：生成开始 = 预热立即让路。
     *
     * 用户实测：首字从 ~1s 被拉到十几秒，时间点正好在「启动预热（PRoot +
     * officecli + Chromium）」之后。Chromium 在 proot 里启动会吃掉 10~30 秒的
     * CPU/IO，与 DNS/TLS/首包完全重叠。
     *
     * 这里取消在跑的预热；v5.3.63 起 executeCommand 走 runInterruptible，
     * 取消会转成线程中断并杀掉 proot 进程。同时清空节流窗口，让本轮结束后的
     * 空闲预热可以重新开始。
     */
    fun cancelForGeneration() {
        generationEpoch += 1
        lastWarmAtMs = 0L
        val job = warmJob
        if (job != null && job.isActive) {
            job.cancel()
            Log.i(TAG, "prewarm cancelled: generation started")
        }
        warmJob = null
    }

    /**
     * 一轮生成结束后的空闲预热：不抢首字，只服务下一轮的工具调用。
     * 延迟 5 秒执行；期间若又开始生成，epoch 变化会让它安静退出。
     */
    fun warmAfterGeneration(scope: CoroutineScope) {
        if (warmJob?.isActive == true) return
        val epoch = generationEpoch
        warmJob = scope.launch(Dispatchers.IO) {
            delay(POST_GENERATION_DELAY_MS)
            if (epoch != generationEpoch) return@launch
            runCatching { warmOnce(source = "idle") }
        }
    }

    /**
     * 触发式预热（fire-and-forget）：调用方 = "用户开始在输入框输入"。
     * 节流窗口内零成本；失败无害。
     */
    fun warmOnTypingStartAsync(scope: CoroutineScope) {
        // 已有预热在跑（可能是启动预热的 Chromium 阶段）就不要替换它 ——
        // 替换只会把重活取消在一个中间状态，反而留下孤儿进程。
        if (warmJob?.isActive == true) return
        warmJob = scope.launch(Dispatchers.IO) { runCatching { warmOnce(source = "typing-start") } }
    }

    suspend fun warmOnce(source: String = "typing-start") {
        val now = System.currentTimeMillis()
        if (now < disabledUntilMs) return
        if (now - lastWarmAtMs < WARM_WINDOW_MS) return
        mutex.withLock {
            if (System.currentTimeMillis() < disabledUntilMs) return@withLock
            if (System.currentTimeMillis() - lastWarmAtMs < WARM_WINDOW_MS) return@withLock
            lastWarmAtMs = System.currentTimeMillis()
            val started = System.currentTimeMillis()

            val repo = runCatching {
                GlobalContext.get().get<WorkspaceRepository>()
            }.getOrNull() ?: return@withLock
            val ws = runCatching { repo.getAllWorkspaces().firstOrNull() }.getOrNull()
                ?: return@withLock

            // ── 预热项 1：PRoot + officecli（失败计入熔断，因为它会在部分 rootfs 崩溃）──
            runCatching {
                // (1) `ulimit -c 0` —— 关键。officecli 在部分 rootfs 上 `--version` 直接
                //     SIGSEGV/SIGABRT，内核把 **43MB core dump** 写进当前工作目录（= 用户
                //     工作区 CWD，文件名是一个空格或 core），用户根本不会注意到。
                // (2) 探测改用 `--help` 而不是 `--version`：同样会加载 34MB 二进制并触发
                //     页缓存，但避开更容易崩溃的初始化路径。
                // (3) 返回非零即视为失败（旧实现用 `; true` 把失败也变成成功）。
                // (4) v5.3.70：`nice -n 19` 降低 CPU 优先级 —— 预热是后台活，
                //     不能让 proot/officecli 与首帧/滚动抢 CPU。rootfs 无 nice 时自动退化为原命令。
                val result = repo.executeCommand(
                    ws.id,
                    "ulimit -c 0 2>/dev/null; NICE=; command -v nice >/dev/null 2>&1 && NICE='nice -n 19'; " +
                        "\$NICE officecli --help >/dev/null 2>&1",
                    "",
                )
                // executeCommand **不会**因非零退出码抛异常，必须显式 check。
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
                    Log.w(TAG, "warm failed (${consecutiveFailures}/${FAIL_THRESHOLD}): ${it.message} — 无害，下次再试")
                }
            }.onSuccess {
                consecutiveFailures = 0
            }

            // ── 预热项 2：浏览器内核（best-effort，失败不计熔断）──
            // ★v5.3.67：Chromium 在 proot 里启动是 20~30 秒的 CPU/IO 重活，
            // 绝不能在「用户刚发消息」的首字窗口里做。策略：
            //   ① 启动预热先让 PRoot/officecli 走热，再等 8 秒；期间发送即被取消；
            //   ② 生成结束后的空闲预热可以补做，但 10 分钟内最多一次；
            //   ③ 每一步前 ensureActive()，取消后不再启动下一个重活。
            val browserAllowed = source == "app-start" ||
                (source == "idle" && System.currentTimeMillis() - lastBrowserWarmAtMs >= BROWSER_WARM_WINDOW_MS)
            if (browserAllowed) {
                if (source == "app-start") delay(BROWSER_DEFER_MS)
                currentCoroutineContext().ensureActive()
                lastBrowserWarmAtMs = System.currentTimeMillis()
                warmBrowserKernel(repo, ws.id)
            }

            Log.d(
                TAG,
                "warm done in ${System.currentTimeMillis() - started}ms " +
                    "(source=$source, browser=${source == "app-start"})",
            )
        }
    }

    /**
     * 浏览器内核走热（Playwright Chromium / headless_shell）。
     *
     * 只做「找到二进制 + 空跑一次 --dump-dom」：把 100MB+ 的 chromium 与
     * 共享库读进页缓存，首个「officecli view screenshot / 渲染」调用
     * 不必再付冷启动。找不到二进制就静默跳过（rootfs 未装浏览器 = 正常状态）。
     * 不 check 退出码：浏览器在部分环境会因 GPU/sandbox 报错退出，
     * 但页缓存已经热了 —— 这正是我们唯一的收益。
     */
    private suspend fun warmBrowserKernel(repo: WorkspaceRepository, workspaceId: String) {
        val command = buildString {
            append("ulimit -c 0 2>/dev/null; ")
            // 注意：这里必须转义 \$ —— Kotlin 会把 $B 当成字符串模板。
            append("B=\$(ls -d /root/.cache/ms-playwright/chromium-*/chrome-linux*/chrome 2>/dev/null | head -1); ")
            append("[ -z \"\$B\" ] && B=\$(ls -d /root/.cache/ms-playwright/chromium_headless_shell-*/chrome-linux*/headless_shell 2>/dev/null | head -1); ")
            append("if [ -n \"\$B\" ]; then ")
            // v5.3.70：同样降优先级；无 nice 时 NICE 为空，退化为原命令。
            append("NICE=; command -v nice >/dev/null 2>&1 && NICE='nice -n 19'; ")
            append("\$NICE timeout 30 \"\$B\" --headless=new --no-sandbox --disable-gpu ")
            append("--disable-dev-shm-usage --dump-dom about:blank >/dev/null 2>&1 || true; ")
            append("fi")
        }
        runCatching { repo.executeCommand(workspaceId, command, "") }
            .onSuccess { Log.d(TAG, "browser kernel warm exit=${it.exitCode} timedOut=${it.timedOut}") }
            .onFailure { Log.d(TAG, "browser kernel warm skipped: ${it.message}") }
    }
}

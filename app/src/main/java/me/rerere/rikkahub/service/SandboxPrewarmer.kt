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
        if (now - lastWarmAtMs < WARM_WINDOW_MS) return
        mutex.withLock {
            if (System.currentTimeMillis() - lastWarmAtMs < WARM_WINDOW_MS) return@withLock
            lastWarmAtMs = System.currentTimeMillis()
            val started = System.currentTimeMillis()
            runCatching {
                val koin = GlobalContext.get()
                val repo = koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
                val ws = repo.getAllWorkspaces().firstOrNull() ?: return@runCatching
                // 一条轻量命令把 proot 启动 / rootfs patch / 页缓存全部走热;
                // v4.8.102: 同时预热 officecli 二进制 (34MB 首次载入 ~3.5s — 预热后首渲 ~1s)
                repo.executeCommand(ws.id, "officecli --version >/dev/null 2>&1; true", "")
            }.onFailure {
                Log.w(TAG, "warm failed: ${it.message} — 无害，下个输入沿再试")
            }
            // 检修点：触发源与耗时（判断预热是否真的抢了输入期的资源）
            Log.d(TAG, "warm done in ${System.currentTimeMillis() - started}ms (source=typing-start)")
        }
    }
}

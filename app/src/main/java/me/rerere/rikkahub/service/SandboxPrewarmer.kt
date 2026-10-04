/* 【域 D·工作区沙箱】 — 服务 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.service

/* ───【自研】SandboxPrewarmer.kt — 沙箱预热 (v4.8.98, 软件-沙箱一体化)
 * 启动即后台走一遍 proot (rootfs patch + 进程页缓存), 首个工具调用不再付冷启动;
 * 进程内 30 分钟窗口节流, 互斥幂等并发安全。
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext

object SandboxPrewarmer {
    private const val WARM_WINDOW_MS = 30 * 60 * 1000L

    @Volatile
    private var lastWarmAtMs = 0L

    private val mutex = Mutex()

    /** 后台预热 (fire-and-forget); 节流窗口内零成本, 失败无害。 */
    fun warmAsync(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { runCatching { warmOnce() } }
    }

    suspend fun warmOnce() {
        val now = System.currentTimeMillis()
        if (now - lastWarmAtMs < WARM_WINDOW_MS) return
        mutex.withLock {
            if (System.currentTimeMillis() - lastWarmAtMs < WARM_WINDOW_MS) return@withLock
            lastWarmAtMs = System.currentTimeMillis()
            runCatching {
                val koin = GlobalContext.get()
                val repo = koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
                val ws = repo.getAllWorkspaces().firstOrNull() ?: return@runCatching
                // 一条轻量命令把 proot 启动 / rootfs patch / 页缓存全部走热
                repo.executeCommand(ws.id, "true", "")
            }
        }
    }
}

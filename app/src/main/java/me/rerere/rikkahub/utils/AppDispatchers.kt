/* 【域 L·基础设施】 — 线程与调度 | 地图: docs/APP_MAP.md §L */
package me.rerere.rikkahub.utils

/* ───【自研】AppDispatchers.kt — 全应用调度器单一事实源 (v4.8.99 线程改革)
 *
 * 分层模型（详见 APP_MAP §L「线程与调度总览」）:
 *   ① Main    — UI: 只做决策与派发; 首帧 Markdown 同步解析 = 用户定版保护特区。
 *   ② Io      — 磁盘/网络/DB 等"等待型"阻塞 (标准共享池, 保持原 Dispatchers.IO 语义)。
 *   ③ Compute — CPU 密集: 序列化/解析/编码/压缩 (Dispatchers.Default)。
 *   ④ Sandbox — 沙箱进程生命周期专用隔离池: proot 启动 / 命令阻塞等待 (最长 600s) /
 *               PTY / MCP stdio。长命令的阻塞等待**不得**占用共享 IO 池 —
 *               否则 UI 侧磁盘/网络 IO 被饿死 (历史: 全部散落在 IO 池)。
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object AppDispatchers {
    /** UI 主线程 (等价 Dispatchers.Main) */
    val Main: CoroutineDispatcher get() = Dispatchers.Main

    /** 等待型阻塞 IO — 磁盘/网络/数据库 (共享池, 勿放长阻塞进程等待) */
    val Io: CoroutineDispatcher get() = Dispatchers.IO

    /** CPU 密集计算 — 序列化/解析/编码 (线程数=核数) */
    val Compute: CoroutineDispatcher get() = Dispatchers.Default

    /** 沙箱进程专用隔离池 — proot 启动/阻塞等待/PTY/MCP stdio (与共享 IO 池隔离) */
    val Sandbox: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(8)
}

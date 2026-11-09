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

    /** 生成链路专用池 (v5.3.65) — agent 循环 / SSE 收集 / 工具编排。
     *
     *  为什么需要独立一层：
     *  ① 与 Main 解耦 —— 旧实现 `appScope.launch` 用的是 Dispatchers.Main，
     *     组请求/收流/写库/下一轮全部跑在主线程，与 Compose 重组抢同一根线程；
     *     界面本该是显示层，不该同时是执行层。
     *  ② 与 Sandbox 解耦 —— 工具里的长阻塞命令（最长 660s）走 Sandbox 池，
     *     循环本身不能被它拖住，也不能占用 CPU 池（Default 只有核数个线程）。
     *  ③ 用 IO 视图而非 Default：循环内可能调用 runInterruptible / 阻塞工具，
     *     占 Default 会把解析、序列化等 CPU 任务饿死。
     *  limitedParallelism 只是调度视图，不新增线程。 */
    val Generation: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    /** 沙箱进程专用隔离池 — proot 启动/阻塞等待/PTY/MCP stdio (与共享 IO 池隔离)。
     *  v5.0.79：8 → 12 —— 工具层并行度 MAX_PARALLEL_TOOL_EXECUTIONS=6，每条 shell 最长
     *  阻塞 660s，8 个槽位极易被长命令占满导致 PTY/MCP stdio 排队；12 给短驻留
     *  连接留余量（limitedParallelism 只是 IO 池的调度视图，无线程新增成本）。 */
    // v5.3.36 ★12 → 20：多对话同时进行时「偶尔卡住」的一个结构性成因 ——
    // 每个对话的工具批次各自持 `Semaphore(MAX_PARALLEL_TOOL_EXECUTIONS = 6)`，
    // 而 shell 最长阻塞 660s。两个对话并发就是 12 条长命令，**正好占满 12 个槽位** →
    // 第三个对话（或同一对话的 MCP/PTY/job 查询）只能排队 → 用户看到「卡住」。
    // limitedParallelism 只是 IO 池的调度视图（不新增线程），加余量零成本。
    val Sandbox: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(20)
}

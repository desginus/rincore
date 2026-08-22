package me.rerere.rikkahub.data.log


/* ───【自研】LogSessionStore.kt — 原版无此文件
 * 来源: RinCore 自研新增 (v3.8.34 运行日志持久化重写)
 * 职责: 每一轮消息处理的运行轨迹落盘保存, 最多保留 10 轮,
 *       轮次 ID = 精确时间戳 (yyyyMMdd-HHmmss-SSS), 支持整批 Markdown 导出。
 * 设计: 内存态为唯一真源 (进程内实时), 文件为持久化快照;
 *       写入在 Mutex 内进行, 落盘按 1s 节流, 完成时强制落盘。
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogSessionStore {
    private const val TAG = "LogSessionStore"
    private const val MAX_SESSIONS = 10
    private const val PERSIST_THROTTLE_MS = 1_000L

    /** 会话内单条轨迹事件 */
    @Serializable
    data class LogSessionEvent(
        @SerialName("ts") val ts: Long,
        @SerialName("phase") val phase: String,
        @SerialName("step") val step: String,
        @SerialName("detail") val detail: String,
        @SerialName("metrics") val metrics: Map<String, String> = emptyMap(),
    )

    /** 一轮消息处理 = 一个会话；finishedAt 为空表示仍在记录中 */
    @Serializable
    data class LogSession(
        @SerialName("id") val id: String,
        @SerialName("startedAt") val startedAt: Long,
        @SerialName("finishedAt") val finishedAt: Long? = null,
        @SerialName("events") val events: List<LogSessionEvent> = emptyList(),
    ) {
        val isActive: Boolean get() = finishedAt == null
        val durationMs: Long get() = (finishedAt ?: System.currentTimeMillis()) - startedAt
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var context: Context
    private val mutex = Mutex()

    // 内存态: sessionId -> session (唯一真源)
    private val sessions = LinkedHashMap<String, LogSession>()
    private val _sessionsFlow = MutableStateFlow<List<LogSession>>(emptyList())
    val sessionsFlow: StateFlow<List<LogSession>> = _sessionsFlow.asStateFlow()

    private var lastPersistMs = 0L
    private var diskDirty = false

    fun init(appContext: Context) {
        context = appContext.applicationContext
        // 启动时加载磁盘快照 (文件极小, 主线程阻塞可忽略; 避免首次读写竞态)
        runBlocking { reloadFromDisk() }
    }

    private fun storeFile(): File = File(context.filesDir, "log_sessions.json")

    private fun sessionIdFormatter() =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    /** 生成精确时间戳轮次 ID */
    fun newSessionId(now: Long = System.currentTimeMillis()): String =
        sessionIdFormatter().format(Date(now))

    /** 从磁盘加载快照, 并触发 Flow 更新 */
    suspend fun reloadFromDisk() {
        mutex.withLock {
            val f = storeFile()
            if (!f.exists()) return
            runCatching {
                val loaded = json.decodeFromString<List<LogSession>>(f.readText())
                sessions.clear()
                loaded.sortedByDescending { it.startedAt }
                    .take(MAX_SESSIONS)
                    .forEach { sessions[it.id] = it }
                _sessionsFlow.value = sessions.values.sortedByDescending { it.startedAt }
            }.onFailure { Log.w(TAG, "加载磁盘快照失败: ${it.message}") }
        }
    }

    /** 新建一轮会话, 返回时间戳 ID */
    suspend fun startSession(): String {
        mutex.withLock {
            val session = LogSession(
                id = newSessionId(),
                startedAt = System.currentTimeMillis(),
                finishedAt = null,
                events = emptyList(),
            )
            sessions[session.id] = session
            trimToMaxLocked()
            _sessionsFlow.value = sessions.values.sortedByDescending { it.startedAt }
            persistLocked(force = true)
            return session.id
        }
    }

    /** 追加一条事件 (内存立即生效, 落盘 1s 节流) */
    suspend fun appendEvent(sessionId: String, event: LogSessionEvent) {
        mutex.withLock {
            val cur = sessions[sessionId] ?: return
            sessions[sessionId] = cur.copy(events = cur.events + event)
            _sessionsFlow.value = sessions.values.sortedByDescending { it.startedAt }
            persistLocked(force = false)
        }
    }

    /** 结束一轮会话 (强制落盘) */
    suspend fun finishSession(sessionId: String) {
        mutex.withLock {
            val cur = sessions[sessionId] ?: return
            sessions[sessionId] = cur.copy(finishedAt = System.currentTimeMillis())
            _sessionsFlow.value = sessions.values.sortedByDescending { it.startedAt }
            persistLocked(force = true)
        }
    }

    /** 若会话仍处于记录中则结束 (兜底收尾, 任意路径可调) */
    suspend fun finishSessionIfOpen(sessionId: String) {
        mutex.withLock {
            val cur = sessions[sessionId] ?: return
            if (cur.isActive) {
                sessions[sessionId] = cur.copy(finishedAt = System.currentTimeMillis())
                _sessionsFlow.value = sessions.values.sortedByDescending { it.startedAt }
                persistLocked(force = true)
            }
        }
    }

    /** 删除全部会话 */
    suspend fun deleteAll() {
        mutex.withLock {
            sessions.clear()
            storeFile().delete()
            _sessionsFlow.value = emptyList()
        }
    }

    /** 最近会话列表 (新在前, 最多 MAX_SESSIONS) */
    suspend fun listSessions(): List<LogSession> {
        mutex.withLock {
            return sessions.values.sortedByDescending { it.startedAt }.take(MAX_SESSIONS)
        }
    }

    /** 单个会话 */
    suspend fun getSession(id: String): LogSession? {
        mutex.withLock { return sessions[id] }
    }

    /** 单个会话导出为 Markdown */
    suspend fun exportSessionMarkdown(id: String): String? {
        val session = getSession(id) ?: return null
        val list = listOf(session)
        return buildString {
            append("# RinCore 运行日志\n\n")
            append("- 导出时间: ${sessionIdFormatter().format(Date())}\n")
            append("- 会话轮次: ${session.id}\n\n")
            append("---\n\n")
            append("## ${session.id}\n\n")
            append("- 状态: ${if (session.isActive) "记录中" else "已完成"}\n")
            append("- 开始: ${sessionIdFormatter().format(Date(session.startedAt))}\n")
            session.finishedAt?.let { append("- 结束: ${sessionIdFormatter().format(Date(it))}\n") }
            append("- 时长: ${session.durationMs}ms\n")
            append("- 事件: ${session.events.size} 条\n\n")
            if (session.events.isEmpty()) {
                append("（无事件）\n\n")
            } else {
                append("| 耗时 | 阶段/步骤 | 详情 | 指标 |\n")
                append("|---|---|---|---|\n")
                session.events.forEach { e ->
                    val detail = e.detail.replace("|", "\\|").replace("\n", " ")
                    val metrics = if (e.metrics.isEmpty()) "-" else
                        e.metrics.entries.joinToString(" ") { "${it.key}=${it.value}" }
                            .replace("|", "\\|")
                    append("| +${e.ts - session.startedAt}ms | ${e.phase}/${e.step} | ${detail} | ${metrics} |\n")
                }
                append("\n")
            }
        }
    }

    /** 全部会话导出为 Markdown (新在前) */
    suspend fun exportMarkdown(): String {
        val list = listSessions()
        if (list.isEmpty()) return "# RinCore 运行日志\n\n暂无记录。\n"
        val sb = StringBuilder()
        sb.append("# RinCore 运行日志\n\n")
        sb.append("- 导出时间: ${sessionIdFormatter().format(Date())}\n")
        sb.append("- 会话轮次: ${list.size} 轮\n\n")
        list.forEachIndexed { index, session ->
            sb.append("---\n\n")
            sb.append("## ${index + 1}. ${session.id}\n\n")
            sb.append("- 状态: ${if (session.isActive) "记录中" else "已完成"}\n")
            sb.append("- 开始: ${sessionIdFormatter().format(Date(session.startedAt))}\n")
            session.finishedAt?.let { sb.append("- 结束: ${sessionIdFormatter().format(Date(it))}\n") }
            sb.append("- 时长: ${session.durationMs}ms\n")
            sb.append("- 事件: ${session.events.size} 条\n\n")
            if (session.events.isEmpty()) {
                sb.append("（无事件）\n\n")
            } else {
                sb.append("| 耗时 | 阶段/步骤 | 详情 | 指标 |\n")
                sb.append("|---|---|---|---|\n")
                session.events.forEach { e ->
                    val detail = e.detail.replace("|", "\\|").replace("\n", " ")
                    val metrics = if (e.metrics.isEmpty()) "-" else
                        e.metrics.entries.joinToString(" ") { "${it.key}=${it.value}" }
                            .replace("|", "\\|")
                    sb.append("| +${e.ts - session.startedAt}ms | ${e.phase}/${e.step} | ${detail} | ${metrics} |\n")
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    /** 截断到 MAX_SESSIONS (新在前), 调用方须持有锁 */
    private fun trimToMaxLocked() {
        val sorted = sessions.values.sortedByDescending { it.startedAt }
        if (sorted.size > MAX_SESSIONS) {
            sorted.drop(MAX_SESSIONS).forEach { sessions.remove(it.id) }
        }
    }

    /** 落盘 (调用方须持有锁); force=false 时按 1s 节流 */
    private fun persistLocked(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && diskDirty && now - lastPersistMs < PERSIST_THROTTLE_MS) return
        diskDirty = true
        lastPersistMs = now
        trimToMaxLocked()
        val snapshot = sessions.values.sortedByDescending { it.startedAt }
        runCatching {
            storeFile().writeText(json.encodeToString(snapshot))
            diskDirty = false
        }.onFailure { Log.w(TAG, "落盘失败: ${it.message}") }
    }
}
/* 【域 E·工作区】 | 地图: docs/APP_MAP.md */
package me.rerere.rikkahub.data.workspace

import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceManager

/**
 * 常驻 shell 会话池 —— v5.3.27（修「workspace_shell 尤其是渲染类命令慢得惊人」）。
 *
 * ## 问题
 * `WorkspaceManager.executeCommand` 每次都是**一次性 proot 进程**：重新载入 rootfs、
 * 重建全部 bind mount（/dev /proc /sys、/dev/shm、skills、upload、tool_outputs…）、
 * 再起 `env -i` + sh。单条命令里 1~3s 是纯启动开销，与命令本身无关。
 * 渲染类命令（要在 proot 内再拉一次浏览器内核）把这个开销又叠了一层，
 * 于是用户看到「渲染一条就慢得惊人」。
 *
 * ## 做法
 * 同一个 key 复用**一个常驻 bash**（killOnExit=false 的 proot），命令经 stdin 送入，
 * 用唯一标记行分隔输出与退出码。第 2 条命令起只剩命令本身的耗时。
 *
 * ## 安全（可失败旁路）
 * - **默认不启用**：只有调用方显式传 `session` 才走这里；不传仍是原一次性路径。
 * - 会话起不来 / 标记读不到 / 超时 → **立即回退一次性执行**，绝不让功能不可用。
 * - 超时或子进程死亡后**丢弃整个会话**（可能残留输出会污染下一条命令）。
 * - 同一 key 用 [Mutex] 串行，不同 key 互不阻塞。
 * - 闲置超过 [IDLE_TTL_MS] 自动关闭，避免长期占内存。
 */
class ShellSessionPool(private val manager: WorkspaceManager) {

    private class Session(
        val key: String,
        val process: Process,
        val stdin: BufferedWriter,
        val lines: LinkedBlockingQueue<String>,
        val readerAlive: AtomicBoolean,
    ) {
        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 在 key 对应的常驻会话里执行命令。
     *
     * @return 会话不可用时会**自动回退**到一次性执行，调用方无需处理。
     */
    suspend fun run(
        key: String,
        root: String,
        cwd: String,
        command: String,
        timeoutMillis: Long,
    ): WorkspaceCommandResult {
        reapIdle()
        val mutex = locks.getOrPut(key) { Mutex() }
        return mutex.withLock {
            val session = try {
                ensureSession(key, root, cwd)
            } catch (t: Throwable) {
                Log.w(TAG, "open session '$key' failed — fallback to one-shot", t)
                null
            }
            if (session == null) {
                return@withLock oneShot(root, cwd, command, timeoutMillis)
            }
            try {
                runInSession(session, root, cwd, command, timeoutMillis)
            } catch (t: Throwable) {
                Log.w(TAG, "run in session '$key' failed — fallback to one-shot", t)
                close(key)
                oneShot(root, cwd, command, timeoutMillis)
            }
        }
    }

    /** 关闭指定会话（工具层可主动释放）。 */
    fun close(key: String) {
        val session = sessions.remove(key) ?: return
        runCatching { session.stdin.close() }
        runCatching { session.process.destroy() }
        runCatching { session.process.waitFor(500, TimeUnit.MILLISECONDS) }
        runCatching { if (session.process.isAlive) session.process.destroyForcibly() }
        Log.d(TAG, "session '$key' closed")
    }

    /** 进程级清理（应用退出/工作区切换时调用，非必须）。 */
    fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    private fun oneShot(
        root: String,
        cwd: String,
        command: String,
        timeoutMillis: Long,
    ): WorkspaceCommandResult =
        manager.executeCommand(root = root, command = command, cwd = cwd, timeoutMillis = timeoutMillis)

    private fun ensureSession(key: String, root: String, cwd: String): Session? {
        sessions[key]?.let { existing ->
            if (existing.process.isAlive && existing.readerAlive.get()) return existing
            close(key)
        }
        val process = manager.launchPersistentShell(root, cwd) ?: return null
        val stdin = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        val lines = LinkedBlockingQueue<String>()
        val readerAlive = AtomicBoolean(true)
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.forEach { lines.put(it) }
                }
            } catch (_: Throwable) {
                // 进程被杀/管道关闭，属正常结束路径
            } finally {
                readerAlive.set(false)
            }
        }, "rin-shell-reader-$key").apply {
            isDaemon = true
            start()
        }
        // 合并 stderr 到 stdout：否则子进程往 stderr 写满 64KB 管道缓冲区后会整条命令卡死。
        // 清空提示符：非 tty 下本来就没有，但 bash 在某些环境下仍会输出 PS1。
        runCatching {
            stdin.write("exec 2>&1")
            stdin.newLine()
            stdin.write("PS1= PS2=")
            stdin.newLine()
            stdin.flush()
        }
        val session = Session(key, process, stdin, lines, readerAlive)
        sessions[key] = session
        Log.d(TAG, "session '$key' opened (root=$root cwd=$cwd)")
        return session
    }

    private fun runInSession(
        session: Session,
        root: String,
        cwd: String,
        command: String,
        timeoutMillis: Long,
    ): WorkspaceCommandResult {
        // 唯一标记：命令本身几乎不可能产出这个字符串（含随机 token）
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val marker = "__RIN_DONE_${token}__"

        session.lines.clear() // 丢弃上一条残留（主防线是超时即 close）
        val output = StringBuilder()
        var exitCode = -1
        var timedOut = false

        try {
            session.stdin.write(command)
            session.stdin.newLine()
            // 单独一行输出「标记 + 上一条命令的退出码」
            session.stdin.write("printf '\\n%s%s\\n' '$marker' \"\$?\"")
            session.stdin.newLine()
            session.stdin.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "session '${session.key}' write failed", t)
            close(session.key)
            return oneShot(root, cwd, command, timeoutMillis)
        }

        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0L) {
                timedOut = true
                break
            }
            val line = try {
                session.lines.poll(remain, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } ?: continue

            if (line.startsWith(marker)) {
                exitCode = line.removePrefix(marker).trim().toIntOrNull() ?: -1
                break
            }
            if (!session.readerAlive.get()) {
                // 子进程已死且队列已空：不会再有标记出现
                close(session.key)
                break
            }
            output.append(line).append('\n')
        }

        session.lastUsedAt = System.currentTimeMillis()
        if (timedOut || !session.process.isAlive) {
            // 超时后会话里可能还有未读输出，继续复用会污染下一条命令 —— 直接丢弃
            close(session.key)
        }
        return WorkspaceCommandResult(
            exitCode = if (timedOut) -1 else exitCode,
            stdout = output.toString(),
            stderr = "",
            timedOut = timedOut,
        )
    }

    private fun reapIdle() {
        val cutoff = System.currentTimeMillis() - IDLE_TTL_MS
        sessions.entries
            .filter { it.value.lastUsedAt < cutoff }
            .map { it.key }
            .forEach {
                Log.d(TAG, "session '$it' idle > ${IDLE_TTL_MS}ms — closing")
                close(it)
            }
    }

    companion object {
        private const val TAG = "ShellSession"

        /** 闲置多久自动关闭（毫秒）。太短会频繁重开，太长会长期占内存。 */
        private const val IDLE_TTL_MS = 5 * 60 * 1000L
    }
}

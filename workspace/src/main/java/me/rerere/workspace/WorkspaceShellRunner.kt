package me.rerere.workspace


/* ───【自研改动】WorkspaceShellRunner.kt | 差异 +55/-25 行
 * 来源: 原版移植 + v4.8.82 自研改写 (输出截断策略: 头+尾双保留)
 * ───────────────────────────────────────────────────────────────*/
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult

    /**
     * 启动常驻进程 (不等待结束) — 供 MCP stdio 桥接使用。
     * 进程的 stdin/stdout/stderr 由调用方接管, 生命周期由调用方管理。
     * 默认不支持 (返回 null), 由各 Runner 实现。
     */
    fun launchProcess(context: WorkspaceShellContext): Process? = null
}

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
    val bindMounts: List<WorkspaceBindMount> = emptyList(),
    val shellCompatibilityMode: Boolean = false,
    // v4.8.72: 后台任务原语 — false 时省略 --kill-on-exit (proot 退出后子进程存活)
    val killOnExit: Boolean = true,
    // v4.8.110 (B4): 调用方环境变量 — MCP stdio 启动等场景注入沙箱进程
    // (在 `env -i` 白名单后以 KEY=VALUE argv 追加; key 非法跳过, 值无需转义)
    val env: Map<String, String> = emptyMap(),
)

class HostShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    override fun launchProcess(context: WorkspaceShellContext): Process? {
        require(context.command.isNotBlank()) { "Command is required" }
        return ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

// 单个流回给模型的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

// v4.8.82: 头 + 尾 双保留 —— 编译器/构建器把错误与总结打在**尾部**, 只留开头会把
// 最关键的报错整段丢掉 (开头多为下载进度/守护进程启动一类噪声)。中段省略并显式回报省略量。
// OUTPUT_HEAD_CHARS + OUTPUT_TAIL_CHARS + 省略标记 <= MAX_OUTPUT_CHARS。
private const val OUTPUT_HEAD_CHARS = 48 * 1024
private const val OUTPUT_TAIL_CHARS = 76 * 1024
private const val OMIT_MARKER_HEAD = "\n... [已省略中间 "
private const val OMIT_MARKER_TAIL = " 字符] ...\n"

fun Process.readResult(timeoutMillis: Long, stdin: ByteArray? = null): WorkspaceCommandResult {
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    val stdinWriter = stdin?.let { bytes -> StreamWriter(outputStream, bytes) }
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            destroyForcibly()
        }
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        return WorkspaceCommandResult(
            exitCode = if (finished) exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
            omittedChars = stdout.omittedChars + stderr.omittedChars,
        )
    } catch (e: InterruptedException) {
        // 调用方线程被中断（如协程取消时的 runInterruptible），杀掉进程避免命令继续执行
        destroyForcibly()
        // 进程被杀后 stdout/stderr 会关闭, 这里 join 回收两个采集线程, 避免每次取消泄漏一对线程
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

private class StreamWriter(
    private val stream: java.io.OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // 子进程提前退出或被强杀时 stdin 可能关闭, 忽略即可, 退出状态会由进程本身返回
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

/**
 * v4.8.82 改写: 头 + 尾 双保留。
 * 旧实现只保留前 maxChars 个字符, 超出即丢弃 —— 对编译/构建类命令等于"把报错扔掉、
 * 只留下开头一片进度噪声"(Gradle/CMake/pip/npm 的错误与总结都在尾部)。
 * 新实现: 前 OUTPUT_HEAD_CHARS 字符 + 末尾 OUTPUT_TAIL_CHARS 字符环形缓冲,
 * 中段丢弃并记录省略量, 由 text() 在省略处插入显式标记。
 */
private class StreamCollector(
    stream: InputStream,
    private val headChars: Int = OUTPUT_HEAD_CHARS,
    private val tailChars: Int = OUTPUT_TAIL_CHARS,
) {
    private val head = StringBuilder()

    /** 末尾 tailChars 个字符的环形缓冲 */
    private val ring = CharArray(tailChars)
    private var ringPos = 0
    private var ringLen = 0

    /** 本流读到的总字符数 (含被省略的中段) */
    @Volatile
    var totalChars = 0L
        private set

    val truncated: Boolean
        get() = synchronized(this) { totalChars > (headChars + tailChars).toLong() }

    /** 被省略的字符数 (头+尾之外的中段) */
    val omittedChars: Long
        get() = synchronized(this) { (totalChars - headChars - ringLen).coerceAtLeast(0L) }

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    // 超出上限后继续读到 EOF 并丢弃，否则管道写满会阻塞子进程导致其无法退出
                    synchronized(this) { collect(buffer, read) }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        }
    }.apply {
        // 设为 daemon: 即使 proot grandchild 残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    private fun collect(buffer: CharArray, read: Int) {
        var offset = 0
        val headRoom = headChars - head.length
        if (headRoom > 0) {
            val n = minOf(read, headRoom)
            head.append(buffer, 0, n)
            offset = n
        }
        while (offset < read) {
            ring[ringPos] = buffer[offset]
            ringPos = (ringPos + 1) % tailChars
            if (ringLen < tailChars) ringLen++
            offset++
        }
        totalChars += read
    }

    fun join(millis: Long) = thread.join(millis)

    /** 未超限 -> 完整内容; 超限 -> 开头 + 省略标记 + 结尾。总长不超过 MAX_OUTPUT_CHARS。 */
    fun text(): String = synchronized(this) {
        val headStr = head.toString()
        val rest = totalChars - headStr.length
        if (rest <= 0L) {
            headStr
        } else {
            val omitted = (rest - ringLen).coerceAtLeast(0L)
            buildString {
                append(headStr)
                if (omitted > 0L) {
                    append(OMIT_MARKER_HEAD).append(omitted).append(OMIT_MARKER_TAIL)
                }
                if (ringLen > 0) {
                    var i = ((ringPos - ringLen) % tailChars + tailChars) % tailChars
                    repeat(ringLen) {
                        append(ring[i])
                        i = (i + 1) % tailChars
                    }
                }
            }
        }
    }
}

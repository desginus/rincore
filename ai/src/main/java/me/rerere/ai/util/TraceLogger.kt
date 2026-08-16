package me.rerere.ai.util


/* ───【自研】TraceLogger.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 运行时轨迹记录器。
 *
 * 固定长度环形缓冲区，记录关键代码路径的执行日志。
 * 当发生异常时可调用 [dump] 获取异常前的最后 N 条记录。
 *
 * 使用:
 *   TraceLogger.log("SSE", "processing event $id")
 *   TraceLogger.log("Compress", "search_web: 5000->320c")
 *   TraceLogger.log("ToolExec", "executing $toolName")
 *
 * 在异常处理处:
 *   Log.e(TAG, "Stream failed\n" + TraceLogger.dump())
 */
object TraceLogger {
    private const val TAG = "TraceLogger"
    private const val MAX_ENTRIES = 200

    private val buffer = ConcurrentLinkedDeque<TraceEntry>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    data class TraceEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val thread: String = Thread.currentThread().name,
        val tag: String,
        val message: String,
    )

    /**
     * 记录一条轨迹。
     */
    fun log(tag: String, message: String) {
        buffer.addFirst(TraceEntry(tag = tag, message = message))
        while (buffer.size > MAX_ENTRIES) {
            buffer.pollLast()
        }
    }

    /**
     * 记录一条轨迹（包含耗时）。
     * @param tag 标签
     * @param msg 描述
     * @param durationMs 耗时（毫秒），传 0 或负值则不显示
     */
    fun log(tag: String, msg: String, durationMs: Long) {
        val suffix = if (durationMs > 0) " (${durationMs}ms)" else ""
        log(tag, msg + suffix)
    }

    /**
     * 获取完整轨迹文本（最新在前）。
     */
    fun dump(maxLines: Int = MAX_ENTRIES): String {
        return buildString {
            val it = buffer.iterator()
            var count = 0
            while (it.hasNext() && count < maxLines) {
                val entry = it.next()
                val timeStr = formatter.format(Date(entry.timestamp))
                append("[${timeStr}][${entry.thread}][${entry.tag}] ${entry.message}\n")
                count++
            }
        }
    }

    /**
     * 在异常日志中打印轨迹。
     */
    fun dumpAndLog(callerTag: String, error: Throwable, maxLines: Int = 80) {
        Log.e(callerTag, buildString {
            append("Error: ${error::class.simpleName}: ${error.message}\n")
            append("=== Trace Log (last $maxLines) ===\n")
            append(dump(maxLines))
            append("=== End Trace Log ===\n")
        })
    }

    fun clear() {
        buffer.clear()
    }
}

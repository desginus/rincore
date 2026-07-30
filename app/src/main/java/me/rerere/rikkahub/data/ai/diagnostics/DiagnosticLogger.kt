package me.rerere.rikkahub.data.ai.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 轻量 HTTP 层诊断日志 — JSON Lines 格式, append-only。
 *
 * 每条请求产生 3 个事件: request_start → chunk_received* → request_end|request_error
 * 每条独立 JSON 行, 增量追加, 解析中断不丢历史数据。
 *
 * 导出: getDiagnosticText() → 最近一次异常会话的完整诊断文本。
 */
object DiagnosticLogger {
    private const val TAG = "DiagLogger"
    private var logFile: File? = null
    private var currentSession: SessionState? = null

    data class SessionState(
        val mid: String,
        val startedAt: Long,
        val model: String,
        val messageCount: Int,
        val toolsCount: Int,
        val estimatedTokens: Int,
        var firstChunkAt: Long? = null,
        var lastChunkAt: Long? = null,
        var chunksReceived: Int = 0,
        var completed: Boolean = false,
        var errorType: String? = null,
        var errorMessage: String? = null,
        var statusCode: Int? = null,
        var totalOutputChars: Int = 0,
    ) {
        val ttftMs: Long get() = if (firstChunkAt != null) firstChunkAt - startedAt else -1
        val durationMs: Long get() = if (lastChunkAt != null) lastChunkAt - startedAt else -1
    }

    fun initialize(ctx: Context) {
        logFile = File(ctx.filesDir, "logs/diagnostics.jsonl").apply {
            parentFile?.mkdirs()
        }
        Log.i(TAG, "initialized: ${logFile?.absolutePath}")
    }

    fun startSession(
        model: String,
        messageCount: Int,
        toolsCount: Int,
        estimatedTokens: Int,
    ): String {
        val mid = UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        currentSession = SessionState(
            mid = mid, startedAt = now, model = model,
            messageCount = messageCount, toolsCount = toolsCount,
            estimatedTokens = estimatedTokens,
        )
        append(buildJson {
            put("type", "request_start"); put("mid", mid)
            put("ts", iso(now)); put("model", model)
            put("messages", messageCount); put("tools", toolsCount)
            put("estTokens", estimatedTokens)
        })
        Log.i(TAG, "session $mid start: model=$model msgs=$messageCount tools=$toolsCount")
        return mid
    }

    fun chunkReceived(chars: Int) {
        val s = currentSession ?: return
        val now = System.currentTimeMillis()
        if (s.firstChunkAt == null) {
            s.firstChunkAt = now
            append(buildJson {
                put("type", "ttft"); put("mid", s.mid)
                put("ts", iso(now)); put("ttftMs", s.ttftMs)
            })
        }
        s.lastChunkAt = now
        s.chunksReceived++
        s.totalOutputChars += chars
    }

    fun sessionComplete(statusCode: Int = 200) {
        val s = currentSession ?: return
        s.completed = true
        s.statusCode = statusCode
        val now = System.currentTimeMillis()
        s.lastChunkAt = now
        append(buildJson {
            put("type", "request_end"); put("mid", s.mid)
            put("ts", iso(now)); put("status", statusCode)
            put("durationMs", s.durationMs); put("ttftMs", s.ttftMs)
            put("chunks", s.chunksReceived); put("outputChars", s.totalOutputChars)
        })
        Log.i(TAG, "session ${s.mid} end: ${s.durationMs}ms ttft=${s.ttftMs}ms chunks=${s.chunksReceived}")
    }

    fun sessionError(statusCode: Int?, message: String?) {
        val s = currentSession ?: return
        s.errorType = "stream_error"
        s.errorMessage = message
        s.statusCode = statusCode
        val now = System.currentTimeMillis()
        if (s.firstChunkAt == null) s.firstChunkAt = now
        s.lastChunkAt = now
        append(buildJson {
            put("type", "request_error"); put("mid", s.mid)
            put("ts", iso(now)); put("status", statusCode ?: 0)
            put("error", message ?: "unknown")
            put("chunksBeforeError", s.chunksReceived)
            put("durationMs", s.durationMs); put("ttftMs", s.ttftMs)
        })
        Log.w(TAG, "session ${s.mid} error: $statusCode $message chunks=${s.chunksReceived}")
    }

    fun getDiagnosticText(): String {
        val logFile = this.logFile ?: return ""
        if (!logFile.isFile) return ""
        val lines = logFile.readLines()
        if (lines.isEmpty()) return ""

        // 找最近一条 request_error 或 request_end
        val lastEvent = lines.lastOrNull { it.contains("\"type\":\"request_error\"") || it.contains("\"type\":\"request_end\"") }
            ?: lines.lastOrNull() ?: return ""

        val mid = Regex("\"mid\":\"([^\"]+)\"").find(lastEvent)?.groupValues?.get(1) ?: return ""
        val sessionLines = lines.filter { "\"mid\":\"$mid\"" in it }

        return buildString {
            appendLine("=== Diagnostic Report ===")
            appendLine("Session: $mid")
            appendLine()
            sessionLines.forEach { appendLine(it) }
        }
    }

    private suspend fun append(json: String) = withContext(Dispatchers.IO) {
        try {
            logFile?.let { f ->
                RandomAccessFile(f, "rw").use { raf ->
                    raf.seek(f.length())
                    raf.writeBytes(json + "\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "append failed: ${e.message}")
        }
    }

    private fun buildJson(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String {
        return kotlinx.serialization.json.buildJsonObject {
            builder()
        }.toString()
    }

    private fun iso(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.ofHours(8)).format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        )
    }
}

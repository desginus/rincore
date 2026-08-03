/**
 * 诊断日志器 — 模块: A. 传输链 / diagnostics
 *
 * 职责: 结构化诊断输出 (CallTracer/诊断事件)。供问题定位使用。
 * 关联: GenerationHandler 的 cache/Request total/toolsInternal 日志。
 *
 * 问题定位: 需要诊断数据 → 查本文件输出格式
 */
package me.rerere.rikkahub.data.ai.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

object DiagnosticLogger {
    private const val TAG = "DiagLogger"
    private var logFile: File? = null

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
        val ttftMs: Long get() = (firstChunkAt ?: 0L) - startedAt
        val durationMs: Long get() = (lastChunkAt ?: 0L) - startedAt
    }

    private var currentSession: SessionState? = null

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
        val json = buildJsonObject {
            put("type", JsonPrimitive("request_start"))
            put("mid", JsonPrimitive(mid))
            put("ts", JsonPrimitive(iso(now)))
            put("model", JsonPrimitive(model))
            put("messages", JsonPrimitive(messageCount))
            put("tools", JsonPrimitive(toolsCount))
            put("estTokens", JsonPrimitive(estimatedTokens))
        }.toString()
        appendSync(json)
        Log.i(TAG, "session $mid start: model=$model msgs=$messageCount tools=$toolsCount")
        return mid
    }

    fun chunkReceived(chars: Int) {
        val s = currentSession ?: return
        val now = System.currentTimeMillis()
        if (s.firstChunkAt == null) {
            s.firstChunkAt = now
            val json = buildJsonObject {
                put("type", JsonPrimitive("ttft"))
                put("mid", JsonPrimitive(s.mid))
                put("ts", JsonPrimitive(iso(now)))
                put("ttftMs", JsonPrimitive(s.ttftMs))
            }.toString()
            appendSync(json)
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
        val json = buildJsonObject {
            put("type", JsonPrimitive("request_end"))
            put("mid", JsonPrimitive(s.mid))
            put("ts", JsonPrimitive(iso(now)))
            put("status", JsonPrimitive(statusCode))
            put("durationMs", JsonPrimitive(s.durationMs))
            put("ttftMs", JsonPrimitive(s.ttftMs))
            put("chunks", JsonPrimitive(s.chunksReceived))
            put("outputChars", JsonPrimitive(s.totalOutputChars))
        }.toString()
        appendSync(json)
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
        val json = buildJsonObject {
            put("type", JsonPrimitive("request_error"))
            put("mid", JsonPrimitive(s.mid))
            put("ts", JsonPrimitive(iso(now)))
            put("status", JsonPrimitive(statusCode ?: 0))
            put("error", JsonPrimitive(message ?: "unknown"))
            put("chunksBeforeError", JsonPrimitive(s.chunksReceived))
            put("durationMs", JsonPrimitive(s.durationMs))
            put("ttftMs", JsonPrimitive(s.ttftMs))
        }.toString()
        appendSync(json)
        Log.w(TAG, "session ${s.mid} error: $statusCode $message chunks=${s.chunksReceived}")
    }

    fun getDiagnosticText(): String {
        val f = logFile ?: return ""
        if (!f.isFile) return ""
        val lines = f.readLines()
        if (lines.isEmpty()) return ""

        val lastEvent = lines.lastOrNull {
            it.contains("\"type\":\"request_error\"") || it.contains("\"type\":\"request_end\"")
        } ?: lines.lastOrNull() ?: return ""

        val mid = Regex("\"mid\":\"([^\"]+)\"").find(lastEvent)?.groupValues?.get(1) ?: return ""
        val sessionLines = lines.filter { "\"mid\":\"$mid\"" in it }

        return buildString {
            appendLine("=== Diagnostic Report ===")
            appendLine("Session: $mid")
            appendLine()
            sessionLines.forEach { appendLine(it) }
        }
    }

    private fun appendSync(json: String) {
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

    private fun iso(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.ofHours(8)).format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        )
    }
}

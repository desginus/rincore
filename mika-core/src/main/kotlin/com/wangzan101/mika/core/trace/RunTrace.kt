package com.wangzan101.mika.core.trace

import com.wangzan101.mika.core.model.InferenceRequest
import com.wangzan101.mika.core.model.InferenceProtocol
import com.wangzan101.mika.core.model.ReportedTokens
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.IOException
import io.opentelemetry.proto.trace.v1.Span.SpanKind
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Kept outside ordinary run directories, which may be mirrored for evaluation. */
class TraceStore(private val directory: File, private val byteLimit: Int = 8 * 1024 * 1024,
    storageLimitBytes: Long = DEFAULT_LIMIT_MIB * MIB,
) {
    private val active = mutableMapOf<String, RunTrace>()
    private val disk = TraceDisk(directory, storageLimitBytes)

    fun setStorageLimit(bytes: Long) = disk.setLimit(bytes)

    @Synchronized fun start(id: String): RunTrace? {
        if (!TraceDisk.RUN_ID.matches(id) || id in active) return null
        val dir = File(directory, id)
        return runCatching {
            if (!disk.open(dir)) return null
            try {
                val files = TraceArchive(dir, disk)
                files.initialize()
                RunTrace(files, byteLimit).also { active[id] = it }
            } catch (e: Exception) {
                disk.close(dir)
                throw e
            }
        }.getOrNull()
    }

    @Synchronized fun finish(trace: RunTrace?) {
        if (trace != null && active.remove(trace.archive.runId, trace)) {
            try { trace.finish() } finally { disk.close(trace.archive.dir) }
        }
    }

    @Synchronized fun stopAll() {
        for (trace in active.values) {
            try { trace.finish("recording_disabled") } finally { disk.close(trace.archive.dir) }
        }
        active.clear()
    }

    /** The run is still writing spans: a viewer opened mid-run has to read again to see later requests. */
    @Synchronized fun recording(id: String): Boolean = id in active

    fun archive(id: String): TraceArchive? = if (TraceDisk.RUN_ID.matches(id))
        TraceArchive(File(directory, id), disk).takeIf { it.root.isFile } else null

    companion object {
        const val DEFAULT_LIMIT_MIB = 100
        const val MIB = 1024L * 1024
    }
}

/** Exists only in the main loop model invocation, including the adapter's own retry loop. */
internal class TraceCall(
    val run: RunTrace, val step: Int, val model: ChatModel, val verb: (String?) -> Verb?, change: String?, private val messages: List<Message> = emptyList(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceCall>
    private var current: Attempt? = null
    private var pendingChange = change
    fun takeChange(): String? = pendingChange.also { pendingChange = null }

    internal inner class Attempt(val span: LocalSpan, private val protocol: InferenceProtocol) {
        fun httpStatus(code: Int) = run.httpStatus(span, code)

        @Volatile var usage: ReportedTokens? = null
            private set

        fun packet(data: String) {
            // Most SSE events are text deltas. Escaped keys still need JSON decoding.
            if (data.firstOrNull { !it.isWhitespace() } != '{') return
            if ("usage" !in data && "\\u" !in data) return
            val packet = try { Json.parseToJsonElement(data) as? JsonObject } catch (_: IllegalArgumentException) { null }
            val next = packet?.let(protocol::usageIn) ?: return
            val prev = usage
            usage = ReportedTokens(next.input ?: prev?.input, next.output ?: prev?.output, next.cached ?: prev?.cached)
        }
    }

    fun sending(req: InferenceRequest): Attempt? {
        finish("error", null)
        val span = begin(req.payload, messages, req.provider, req.protocol) ?: return null
        return Attempt(span, req.protocol).also { current = it }
    }

    fun finish(status: String, reply: ModelReply?) {
        val attempt = current ?: return
        current = null
        val usage = attempt.usage
        run.end(attempt.span, status, usage?.input, usage?.cached, usage?.output,
            reply?.let { { TracePayload.reply(it, verb) } })
    }

    fun begin(body: JsonObject, messages: List<Message>, provider: String = "openai_compatible",
        protocol: InferenceProtocol = InferenceProtocol.Responses,
    ) = run.begin(this, body, messages, provider = provider, protocol = protocol)
}

class RunTrace internal constructor(internal val archive: TraceArchive, private val byteLimit: Int) : RunSink {
    private var writeFailure: Exception? = null
    private var failedWriteStep: Long? = null
    private val wallStart = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
    private val tickStart = System.nanoTime()
    private fun nowNanos() = wallStart + (System.nanoTime() - tickStart)
    private val root = LocalSpan(LocalSpan.randomId(16), "invoke_agent Mika", start = nowNanos())
        .setAttribute("gen_ai.operation.name", "invoke_agent").setAttribute("mika.status", "partial")
        .setAttribute("mika.trace.format_version", 2L)
    private val pending = mutableSetOf<LocalSpan>()
    private val toolSpans = mutableMapOf<Pair<Int, String>, LocalSpan>()
    private val previous = IdentityHashMap<ChatModel, List<Pair<String, String>>>()
    private val attempts = mutableMapOf<Int, Long>()
    private var toolsOpened = false
    private var outcome: String? = null
    private var count = 0
    private var bytes = 0
    private var truncated = false
    @Volatile private var recording = true

    init { checkpoint(root) }

    private fun checkpoint(span: LocalSpan) = archive.store(span.snapshot())

    private fun seal(span: LocalSpan, end: Long = nowNanos()) {
        try { archive.store(span.snapshot(end)) } catch (e: Exception) {
            if (writeFailure == null) { writeFailure = e; failedWriteStep = span.step }
        }
    }

    internal suspend fun modelCall(
        step: Int, model: ChatModel, verb: (String?) -> Verb?, change: String?, messages: List<Message>, block: suspend () -> ModelReply,
    ): ModelReply {
        if (!recording) return block()
        val scope = TraceCall(this, step, model, verb, change, messages)
        var reply: ModelReply? = null
        var status = "error"
        try {
            return withContext(scope) { block() }.also { reply = it; status = "ok" }
        } catch (e: CancellationException) {
            status = "cancelled"
            throw e
        } finally {
            scope.finish(status, reply)
        }
    }

    internal fun begin(
        call: TraceCall, body: JsonObject, messages: List<Message>, start: Long = System.currentTimeMillis(), provider: String, protocol: InferenceProtocol,
    ): LocalSpan? {
        if (!recording) return null
        // Do not hold the stop lock while fingerprinting last turn's images or redacting a long prompt.
        val blocks = try { TracePayload.blocks(body, messages, call.verb, protocol) }
            catch (_: Exception) { finish("capture_error", call.step.toLong(), "request"); return null }
            catch (_: StackOverflowError) { finish("capture_error", call.step.toLong(), "request"); return null }
        return beginPrepared(call, blocks, start, provider)
    }

    @Synchronized private fun beginPrepared(call: TraceCall, blocks: List<RequestBlock>, start: Long, provider: String): LocalSpan? = record(call.step.toLong()) {
        val span = child("chat " + TracePayload.label(call.model.name), call.step, SpanKind.SPAN_KIND_CLIENT, start)
        val index = (attempts[call.step] ?: 0) + 1
        attempts[call.step] = index
        span.setAttribute("gen_ai.operation.name", "chat").setAttribute("gen_ai.request.model", TracePayload.label(call.model.name))
            .setAttribute("mika.attempt", index).setAttribute("mika.request.captured", true)
        span.setAttribute("gen_ai.provider.name", provider)
        val change = listOfNotNull("tools_opened".takeIf { toolsOpened }, call.takeChange()).joinToString(",")
        if (change.isNotEmpty()) span.setAttribute("mika.context.change", change)
        toolsOpened = false
        val signatures = blocks.map { it.path to it.digest }
        val last = previous.put(call.model, signatures)
        val prefix = if (last == null) 0 else (0 until minOf(last.size, blocks.size))
            .firstOrNull { last[it] != signatures[it] } ?: minOf(last.size, blocks.size)
        val diff = when {
            last == null -> "first"
            prefix == blocks.size && prefix == last.size -> "same"
            prefix == last.size -> "append " + blocks[prefix].path
            prefix == blocks.size -> "removed " + last[prefix].first
            last[prefix].first != blocks[prefix].path && signatures.none { it.first == last[prefix].first } ->
                "removed " + last[prefix].first
            else -> blocks[prefix].path
        }
        span.setAttribute("mika.context.first_changed_block", diff)
        val parts = ArrayList<BodyPart>()
        for (b in blocks) {
            val content = b.text.toByteArray()
            val key = content.toByteString().sha256().hex()
            val extra = if (archive.hasBlock(key)) 0 else content.size
            if (bytes + extra + (parts.size + 1) * 192 > byteLimit) {
                span.setAttribute("mika.status", "truncated")
                parts.add(BodyPart(b.path, null, true, "run_body_limit"))
                bytes += archive.writeBody(span.id, parts)
                checkpoint(span)
                finish("run_body_limit", call.step.toLong(), b.path)
                return@record null
            }
            if (b.truncation != null) truncated = true
            bytes += archive.putBlock(key, content, b.path)
            parts.add(BodyPart(b.path, key, b.truncation != null, b.truncation))
        }
        bytes += archive.writeBody(span.id, parts)
        checkpoint(span)
        span
    }

    @Synchronized internal fun httpStatus(span: LocalSpan, code: Int) {
        if (span in pending) span.setAttribute("http.response.status_code", code.toLong())
    }

    @Synchronized internal fun end(span: LocalSpan?, status: String, input: Int?, cached: Int?, output: Int?,
        replyText: (() -> CapturedReply)? = null,
    ) {
        if (span == null || !recording || span !in pending) return
        record(span.step) {
            input?.let { span.setAttribute("gen_ai.usage.input_tokens", it.toLong()) }
            cached?.let { span.setAttribute("gen_ai.usage.cache_read.input_tokens", it.toLong()) }
            output?.let { span.setAttribute("gen_ai.usage.output_tokens", it.toLong()) }
            if (replyText != null) {
                val reply = replyText()
                val data = reply.text.toByteArray()
                val fits = bytes + data.size <= byteLimit
                val reason = if (fits) reply.truncation else "run_body_limit"
                if (reason != null) {
                    truncated = true
                    span.setAttribute("mika.trace.reply_truncation", reason)
                }
                val saved = if (fits) data else "[truncated]".toByteArray()
                archive.writeReply(span.id, saved)
                bytes += saved.size
            }
            span.setAttribute("mika.status", status)
            if (status == "error") span.error()
            pending.remove(span)
            seal(span)
        }
    }

    @Synchronized internal fun toolStarted(step: Int, name: String, callId: String, start: Long) {
        record(step.toLong()) {
            val span = tool(step, name, callId, start)
            toolSpans[step to callId] = span
            checkpoint(span)
        }
    }

    private fun tool(step: Int, name: String, callId: String?, start: Long): LocalSpan {
        val safeName = TracePayload.label(name)
        return child("execute_tool " + safeName, step, SpanKind.SPAN_KIND_INTERNAL, start).apply {
            setAttribute("gen_ai.operation.name", "execute_tool")
            setAttribute("gen_ai.tool.name", safeName)
            callId?.let { setAttribute("gen_ai.tool.call.id", TracePayload.label(it)) }
        }
    }

    @Synchronized override fun event(e: RunEvent) {
        record((e as? RunEvent.ToolResult)?.step?.toLong()) {
            when (e) {
                is RunEvent.ToolResult -> if (e.source == "model") {
                    val span = e.callId?.let { toolSpans.remove(e.step to it) }
                        ?: tool(e.step, e.tool, e.callId, e.atMs - e.durationMs)
                    span.setAttribute("mika.tool.duration_ms", e.durationMs)
                    val failed = runCatching { Json.parseToJsonElement(e.resultJson).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == false }
                        .getOrDefault(false) || Regex("""^\{\s*"ok"\s*:\s*false\b""").containsMatchIn(e.resultJson)
                    span.setAttribute("mika.status", if (failed) "error" else "ok")
                    if (failed) span.error()
                    seal(span, TimeUnit.MILLISECONDS.toNanos(e.atMs))
                    pending.remove(span)
                }
                is RunEvent.State -> if (e.name == "tools_opened") toolsOpened = true
                is RunEvent.TaskEnded -> outcome = e.outcome
                else -> Unit
            }
        }
    }

    private fun child(name: String, step: Int, kind: SpanKind, start: Long): LocalSpan {
        if (count >= 1024) throw SpanLimitReached()
        count++
        return LocalSpan(root.traceId, name, root, kind, step.toLong(), TimeUnit.MILLISECONDS.toNanos(start))
            .setAttribute("mika.step.index", step.toLong()).setAttribute("mika.sequence", count.toLong())
            .setAttribute("mika.status", "partial")
            .also { pending.add(it) }
    }

    /** Cancellation, quota exhaustion and storage failure seal the trace. Late model callbacks cannot reopen it. */
    @Synchronized internal fun finish(reason: String? = null, step: Long? = null, block: String? = null) {
        if (!recording) return
        recording = false
        val interrupted = pending.isNotEmpty()
        pending.forEach { seal(it) }
        pending.clear()
        toolSpans.clear()
        previous.clear()
        val failure = writeFailure
        val stop = reason ?: when {
            failure != null -> failureReason(failure)
            outcome == "cancelled" -> "run_cancelled"
            interrupted -> "unfinished_spans"
            outcome == null -> "missing_run_outcome"
            else -> null
        }
        val state = if (stop != null || truncated) "partial" else outcome!!
        stop?.let { root.setAttribute("mika.trace.stop_reason", it) }
        (step ?: failedWriteStep)?.let { root.setAttribute("mika.trace.stopped_step", it) }
        (block ?: (failure as? TraceDiskFull)?.block)?.let { root.setAttribute("mika.trace.stopped_block", it) }
        outcome?.let { root.setAttribute("mika.run.outcome", it) }
        root.setAttribute("mika.status", state)
        if (state == "failed" || state == "error") root.error()
        seal(root)
    }

    private class SpanLimitReached : Exception()
    private fun failureReason(e: Exception): String = when (e) {
        is SpanLimitReached -> "span_limit"
        is TraceDiskFull -> "storage_limit"
        is IOException -> "write_error"
        else -> "capture_error"
    }

    private inline fun <T> record(step: Long?, block: () -> T): T? {
        if (!recording) return null
        if (writeFailure != null) { finish(); return null }
        return try { block() }
        catch (e: Exception) { finish(failureReason(e), step, (e as? TraceDiskFull)?.block); null }
        catch (_: StackOverflowError) { finish("capture_error", step); null }
    }

}

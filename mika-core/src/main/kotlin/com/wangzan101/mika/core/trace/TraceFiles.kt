package com.wangzan101.mika.core.trace

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.ArrayValue
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.InstrumentationScope
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString

/** UI reads only span metadata until the user asks for a particular request. */
data class TraceRow(
    val id: String, val name: String, val step: Long?, val attempt: Long?, val status: String,
    val input: Long?, val cached: Long?, val output: Long?, val durationMs: Long,
    val difference: String?, val contextChange: String?, val captured: Boolean, val toolCallId: String?,
) {
    val uncached: Long? get() = if (input != null && cached != null) input - cached else null
}

@Serializable
internal data class BodyPart(val path: String, val blob: String?, val truncated: Boolean, val reason: String? = null)

class TraceArchive internal constructor(internal val dir: File, private val disk: TraceDisk) {
    val runId: String get() = dir.name
    internal val root = File(dir, "root.pb")
    private val spanDir = File(dir, "spans")
    private val bodyDir = File(dir, "requests")
    private val blobs = File(dir, "blocks")

    internal fun initialize() {
        if (!spanDir.mkdir() || !bodyDir.mkdir() || !blobs.mkdir()) throw IOException("trace directory")
    }

    internal fun store(message: Span) {
        val file = if (message.parentSpanId.isEmpty) root else File(spanDir, message.id() + ".pb")
        disk.write(dir, file, message.toByteArray(), "span")
    }

    internal fun hasBlock(key: String) = File(blobs, key).isFile

    internal fun putBlock(key: String, bytes: ByteArray, path: String): Int {
        val dest = File(blobs, key)
        if (dest.exists()) return 0
        disk.write(dir, dest, bytes, path)
        return bytes.size
    }

    internal fun writeBody(id: String, parts: List<BodyPart>): Int {
        val content = Json.encodeToString(parts).toByteArray()
        disk.write(dir, File(bodyDir, id + ".json"), content, "request_manifest")
        return content.size
    }

    private fun content(id: String): Sequence<String>? {
        if (!SPAN_ID.matches(id)) return null
        val manifest = File(bodyDir, id + ".json")
        if (!manifest.isFile) return null
        return sequence {
            for (part in Json.decodeFromString<List<BodyPart>>(manifest.readText())) {
                yield(part.path + "\n")
                part.blob?.let { key ->
                    require(BLOCK_ID.matches(key))
                    yield(File(blobs, key).readText())
                }
                if (part.truncated) yield("\n[truncated]")
                yield("\n\n")
            }
            val reply = File(bodyDir, id + ".reply")
            if (reply.isFile) {
                yield("response\n")
                yield(reply.readText())
            }
        }
    }

    internal fun writeReply(id: String, data: ByteArray) = disk.write(dir, File(bodyDir, id + ".reply"), data, "response")

    data class Page(val text: String, val hasMore: Boolean)

    /** Materialize only one page, even when a long run repeatedly references the same block. */
    fun bodyPage(id: String, offset: Int = 0, limit: Int = 3000): Page? {
        require(offset >= 0 && limit in 1..65536)
        val sections = content(id) ?: return null
        val result = StringBuilder()
        var skip = offset
        for (section in sections) {
            if (skip >= section.length) { skip -= section.length; continue }
            val end = minOf(section.length, skip + limit - result.length)
            result.append(section, skip, end)
            if (end < section.length) return Page(result.toString(), true)
            skip = 0
        }
        return Page(result.toString(), false)
    }

    private fun spans(): List<Span> {
        val children = spanDir.listFiles().orEmpty().filter { it.extension == "pb" }.map { Span.parseFrom(it.readBytes()) }
        return listOf(Span.parseFrom(root.readBytes())) + children.sortedBy { span -> span.attributesList.firstOrNull { it.key == "mika.sequence" }?.value?.intValue }
    }

    fun rows(): List<TraceRow> = spans().map { span ->
        val a = span.attributesList.associate { it.key to it.value }
        TraceRow(span.id(), span.name, a["mika.step.index"]?.intValue, a["mika.attempt"]?.intValue,
            a["mika.status"]?.stringValue ?: "partial",
            a["gen_ai.usage.input_tokens"]?.intValue, a["gen_ai.usage.cache_read.input_tokens"]?.intValue,
            a["gen_ai.usage.output_tokens"]?.intValue,
            TimeUnit.NANOSECONDS.toMillis(span.endTimeUnixNano - span.startTimeUnixNano),
            a["mika.context.first_changed_block"]?.stringValue, a["mika.context.change"]?.stringValue,
            a["mika.request.captured"]?.boolValue == true, a["gen_ai.tool.call.id"]?.stringValue)
    }

    /** Location and reason come from manifests even for runs recorded before diagnostics were exported. */
    private fun exportSpans(): List<Span> {
        val recorded = spans()
        val root = recorded.first()
        val legacy = root.attributesList.none { it.key == "mika.trace.format_version" }
        if (!legacy && root.attribute("mika.status") != "partial") return recorded
        val reasons = linkedSetOf<String>()
        root.attribute("mika.trace.stop_reason")?.let(reasons::add)
        val children = recorded.drop(1).map { span ->
            if (span.attributesList.none { it.key == "mika.attempt" }) return@map span
            val builder = span.toBuilder()
            fun truncated(path: String, reason: String, time: Long) {
                reasons += reason
                builder.addEvents(Span.Event.newBuilder().setName("mika.trace.truncated").setTimeUnixNano(time)
                    .addAttributes(textAttribute("mika.block.path", path))
                    .addAttributes(textAttribute("mika.truncation.reason", reason)))
            }
            val manifest = File(bodyDir, span.id() + ".json")
            if (manifest.isFile) {
                val parts = try {
                    Json.decodeFromString<List<BodyPart>>(manifest.readText())
                } catch (_: SerializationException) {
                    truncated("request_manifest", "manifest_unreadable", span.startTimeUnixNano)
                    emptyList()
                } catch (_: IOException) {
                    truncated("request_manifest", "manifest_unreadable", span.startTimeUnixNano)
                    emptyList()
                }
                for (part in parts) {
                    if (part.truncated) {
                        val reason = part.reason ?: if (part.blob == null) "run_body_limit" else "block_limit"
                        truncated(part.path, reason, span.startTimeUnixNano)
                    }
                }
            }
            val reply = span.attribute("mika.trace.reply_truncation")
                ?: if (legacy) legacyReplyTruncation(span.id()) else null
            reply?.let { truncated("response", it, span.endTimeUnixNano) }
            builder.build()
        }
        if (root.attribute("mika.status") == "partial") {
            if (root.startTimeUnixNano == root.endTimeUnixNano) reasons += "not_finalized"
            if (legacy && root.attribute("mika.run.outcome") == "cancelled") reasons += "run_cancelled"
            if (reasons.isEmpty()) reasons += "unknown"
        }
        if (reasons.isEmpty()) return recorded
        val summary = ArrayValue.newBuilder().addAllValues(reasons.map { AnyValue.newBuilder().setStringValue(it).build() })
        val updated = root.toBuilder().addAttributes(KeyValue.newBuilder().setKey("mika.trace.partial_reasons")
            .setValue(AnyValue.newBuilder().setArrayValue(summary)))
        // Older writers did not mark the root when only a reply was clipped.
        if (root.attribute("mika.status") != "partial") {
            updated.setAttributes(root.attributesList.indexOfFirst { it.key == "mika.status" }, textAttribute("mika.status", "partial"))
        }
        return listOf(updated.build()) + children
    }

    private fun legacyReplyTruncation(id: String): String? {
        val file = File(bodyDir, "$id.reply")
        if (!file.isFile) return null
        // The old writer put this marker outside the JSON-encoded visible reply.
        val suffix = "\n[truncated]".toByteArray()
        return RandomAccessFile(file, "r").use { input ->
            val n = minOf(input.length(), suffix.size.toLong()).toInt()
            input.seek(input.length() - n)
            val tail = ByteArray(n).also(input::readFully)
            when {
                tail.contentEquals(suffix) -> "block_limit"
                input.length() == n.toLong() && tail.decodeToString() == "[truncated]" -> "run_body_limit"
                else -> null
            }
        }
    }

    private fun payload(spans: List<Span>): ByteArray {
        val scope = ScopeSpans.newBuilder().setScope(InstrumentationScope.newBuilder().setName("mika.harness")).addAllSpans(spans)
        val resource = Resource.newBuilder().addAttributes(KeyValue.newBuilder().setKey("service.name")
            .setValue(AnyValue.newBuilder().setStringValue("Mika")))
        return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder().setResource(resource)
            .addScopeSpans(scope)).build().toByteArray()
    }

    /** Only the explicitly selected ZIP contains local request and reply text. */
    fun save(output: OutputStream, includeBody: Boolean): Unit = disk.read(dir) {
        val recorded = exportSpans()
        val data = payload(recorded)
        if (!includeBody) {
            output.write(data)
            return@read
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("trace.pb"))
            zip.write(data)
            zip.closeEntry()
            for (span in recorded) {
                val id = span.id()
                val sections = content(id) ?: continue
                zip.putNextEntry(ZipEntry("requests/$id.txt"))
                for (section in sections) zip.write(section.toByteArray())
                zip.closeEntry()
            }
        }
    }

    companion object {
        private val SPAN_ID = Regex("[a-f0-9]{16}")
        private val BLOCK_ID = Regex("[a-f0-9]{64}")
        private fun textAttribute(key: String, value: String) = KeyValue.newBuilder().setKey(key)
            .setValue(AnyValue.newBuilder().setStringValue(value)).build()
        private fun Span.attribute(key: String) = attributesList.firstOrNull { it.key == key }?.value?.stringValue
        private fun Span.id() = spanId.toByteArray().toByteString().hex()
    }
}

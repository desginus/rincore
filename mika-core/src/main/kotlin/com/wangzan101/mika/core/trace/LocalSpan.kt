package com.wangzan101.mika.core.trace

import com.google.protobuf.ByteString
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom

/** A running span is checkpointed with end == start until the model or tool finishes. */
internal class LocalSpan(
    val traceId: ByteString, val name: String, parent: LocalSpan? = null,
    kind: Span.SpanKind = Span.SpanKind.SPAN_KIND_INTERNAL, val step: Long? = null, private val start: Long,
) {
    private val spanId = randomId(8)
    val id = spanId.toByteArray().toByteString().hex()
    private val span = Span.newBuilder().setTraceId(traceId).setSpanId(spanId).setFlags(1)
        .setName(name).setKind(kind).setStartTimeUnixNano(start).apply {
            if (parent != null) parentSpanId = parent.spanId
        }
    private val attributes = linkedMapOf<String, AnyValue>()

    fun setAttribute(key: String, value: String) = apply { attributes[key] = AnyValue.newBuilder().setStringValue(value).build() }
    fun setAttribute(key: String, value: Long) = apply { attributes[key] = AnyValue.newBuilder().setIntValue(value).build() }
    fun setAttribute(key: String, value: Boolean) = apply { attributes[key] = AnyValue.newBuilder().setBoolValue(value).build() }
    fun error() { span.status = Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).build() }

    fun snapshot(end: Long = start): Span {
        span.endTimeUnixNano = maxOf(start, end)
        span.clearAttributes()
        attributes.forEach { (key, value) -> span.addAttributes(KeyValue.newBuilder().setKey(key).setValue(value)) }
        return span.build()
    }

    companion object {
        private val random = SecureRandom()
        fun randomId(size: Int): ByteString {
            val bytes = ByteArray(size)
            do { random.nextBytes(bytes) } while (bytes.all { it == 0.toByte() })
            return ByteString.copyFrom(bytes)
        }
    }
}

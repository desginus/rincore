package com.wangzan101.mika.core.trace

import com.wangzan101.mika.core.model.InferenceProtocol
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.*
import okio.ByteString.Companion.encodeUtf8

internal data class RequestBlock(val path: String, val digest: String, val text: String, val truncation: String?)
internal data class CapturedReply(val text: String, val truncation: String?)

internal object TracePayload {
    const val MAX_BLOCK = 32_768
    private const val MASK = "[redacted]"
    private val HIDDEN_TYPES = setOf("reasoning", "reasoning_content", "thinking", "redacted_thinking")
    private val HIDDEN_FIELDS = setOf("encrypted_content", "reasoning_content", "thinking", "signature", "thoughtSignature", "thought_signature")
    private val headers = Regex("""(?im)\b(?:authorization|proxy-authorization|set-cookie|cookie)\s*:\s*[^\r\n]*""")
    private val url = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val userInfo = Regex("""(://)[^/?#\s]*@""")
    private val dataUri = Regex("""(?i)data:[^,\s"']{0,100};base64,[a-z0-9+/=_-]+""")
    private val assignment = Regex("""(?i)((?:\b(?:password|passwd|token|api[_-]?key|secret|otp|pin|authorization|cookie|session[_-]?id)|密码|口令)\s*[=:：]\s*)[^\s,;&]+""")
    private val identifier = Regex("[a-zA-Z0-9_.:/-]{1,96}")

    private fun scrub(value: String): String {
        val urls = url.replace(dataUri.replace(value, "[attachment]")) { m ->
            val address = userInfo.replace(m.value, "$1")
            val cut = address.indexOfAny(charArrayOf('?', '#'))
            if (cut < 0) address else address.take(cut) + "?[redacted]"
        }
        return Redactor.evidence(Redactor.pageData(assignment.replace(headers.replace(urls, MASK), "$1[redacted]")))
    }

    fun label(value: String): String =
        if (value.length <= 96) scrub(value).takeIf { it.matches(identifier) } ?: "other" else "other"

    /** Fingerprint the actual serialized blocks before redaction. Keep these digests in memory only. */
    fun blocks(body: JsonObject, messages: List<Message>, verb: (String?) -> Verb?, protocol: InferenceProtocol): List<RequestBlock> {
        val images = messages.asSequence().flatMap { it.parts.asSequence() }.filterIsInstance<Part.Image>().iterator()
        val view = View(images, verb)
        return buildList {
            fun block(path: String, value: JsonElement) {
                val digest = value.toString().encodeUtf8().sha256().hex()
                view.truncation = null
                val cleaned = view.clean(value).toString()
                add(RequestBlock(path, digest, cleaned.take(MAX_BLOCK), view.reasonFor(cleaned)))
            }
            for ((k, v) in body) if (k != protocol.instruction && k != "tools" && k != protocol.messages) block(k, v)
            protocol.instruction?.let { path -> body[path]?.let { block(path, it) } }
            (body["tools"] as? JsonArray)?.forEachIndexed { i, v -> block("tools[$i]", v) }
            (body[protocol.messages] as? JsonArray)?.forEachIndexed { i, v -> block("${protocol.messages}[$i]", v) }
        }
    }

    fun reply(reply: ModelReply, verb: (String?) -> Verb?): CapturedReply {
        val view = View(emptyList<Part.Image>().iterator(), verb)
        val visible = buildString {
            for (part in reply.parts) {
                when (part) {
                    is Part.Text -> appendLine(view.clean(JsonPrimitive(part.text)))
                    is Part.ToolCall -> appendLine(view.clean(buildJsonObject {
                        put("type", "function_call"); put("name", part.name); put("call_id", part.id); put("arguments", part.argsJson)
                    }))
                    else -> Unit
                }
                if (length > MAX_BLOCK) break
            }
        }
        val reason = view.reasonFor(visible)
        return CapturedReply(if (reason != null) visible.take(MAX_BLOCK) + "\n[truncated]" else visible, reason)
    }

    private class View(private val images: Iterator<Part.Image>, private val verb: (String?) -> Verb?) {
        private val calls = mutableMapOf<String, Verb?>()
        var truncation: String? = null

        fun reasonFor(text: String) = truncation ?: "block_size_limit".takeIf { text.length > MAX_BLOCK }

        fun clean(v: JsonElement, depth: Int = 0): JsonElement {
            if (depth > 32) {
                truncation = "nesting_limit"
                return JsonPrimitive("[truncated]")
            }
            if (v is JsonPrimitive && v.isString && v.content.length > MAX_BLOCK) {
                truncation = "block_size_limit"
                // Cut at a boundary so a partial credential cannot evade the redactor.
                val prefix = v.content.take(MAX_BLOCK)
                val boundary = prefix.lastIndexOfAny(charArrayOf(' ', '\n', '\r', '\t'))
                return JsonPrimitive(scrub(if (boundary < 0) "" else prefix.take(boundary)) + "[truncated]")
            }
            if (v is JsonArray) return JsonArray(v.map { clean(it, depth + 1) })
            if (v !is JsonObject) return if (v is JsonPrimitive && v.isString) JsonPrimitive(scrub(v.content)) else v

            fun str(key: String) = (v[key] as? JsonPrimitive)?.contentOrNull
            val type = str("type")
            val imageData = when (type) {
                "input_image" -> str("image_url")
                "image_url" -> (v["image_url"] as? JsonObject)?.text("url")
                "image" -> (v["source"] as? JsonObject)?.text("data")
                else -> (v["inlineData"] as? JsonObject)?.text("data")
            }
            if (imageData != null) {
                val img = if (images.hasNext()) images.next() else null
                return buildJsonObject {
                    put("type", "input_image"); put("width", img?.width ?: 0); put("height", img?.height ?: 0)
                    put("sha256", imageData.encodeUtf8().sha256().hex())
                }
            }
            if (type in HIDDEN_TYPES || str("password") == "true" || str("isPassword") == "true" ||
                type.equals("password", true) || str("thought") == "true") return JsonPrimitive(MASK)
            fun arguments(name: String?, args: JsonElement): JsonElement {
                val raw = (args as? JsonPrimitive)?.takeIf { it.isString }?.content ?: args.toString()
                val safe = clean(parsed(Redactor.args(verb(name), raw, secretField = true)), depth + 1)
                return if (args is JsonPrimitive && args.isString) encoded(raw, safe) else safe
            }
            fun result(op: Verb?, item: JsonElement): JsonElement {
                if (op == Verb.AskUser) return JsonPrimitive(MASK)
                fun plain(element: JsonElement): JsonElement {
                    val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val value = raw?.let(::parsed) ?: element
                    val safe = clean(if (value is JsonObject) Redactor.result(op, value) else value, depth + 1)
                    return if (raw == null) safe else encoded(raw, safe)
                }
                // Explicit-cache chat endpoints can wrap the tool result in text blocks.
                if (item !is JsonArray) return plain(item)
                return JsonArray(item.map { part ->
                    if (part is JsonObject && part.text("type") == "text") {
                        JsonObject(part.mapValues { (field, element) -> if (field == "text") plain(element) else clean(element, depth + 1) })
                    } else clean(part, depth + 1)
                })
            }
            val callId = str("call_id") ?: str("id")
            if (callId != null) {
                val name = when (type) {
                    "function_call", "tool_use" -> str("name")
                    "function" -> (v["function"] as? JsonObject)?.text("name")
                    else -> null
                }
                if (name != null) calls[callId] = verb(name)
            }
            return JsonObject(v.mapValues { (key, item) ->
                when {
                    Redactor.secretName(key) || key in HIDDEN_FIELDS || key.contains("base64", ignoreCase = true) -> JsonPrimitive(MASK)
                    key == "reasoning" && item !is JsonObject -> JsonPrimitive(MASK)
                    key == "arguments" && (type == "function_call" || str("name") != null) -> arguments(str("name"), item)
                    key == "input" && type == "tool_use" -> arguments(str("name"), item)
                    key == "args" && str("name") != null -> arguments(str("name"), item)
                    key == "output" && type == "function_call_output" -> result(calls[str("call_id")], item)
                    key == "content" && type == "tool_result" -> result(calls[str("tool_use_id")], item)
                    key == "content" && str("role") == "tool" -> result(calls[str("tool_call_id")], item)
                    key == "response" && str("name") != null && item is JsonObject && "content" in item ->
                        JsonObject(item.mapValues { (k, v) -> if (k == "content") result(verb(str("name")), v) else clean(v, depth + 1) })
                    else -> clean(item, depth + 1)
                }
            })
        }

        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

        // Responses encodes arguments and tool output as strings, even when they contain JSON.
        private fun encoded(original: String, safe: JsonElement): JsonPrimitive {
            if (parsed(original) == safe) return JsonPrimitive(original)
            return if (safe is JsonPrimitive && safe.isString) safe else JsonPrimitive(safe.toString())
        }

        private fun parsed(s: String): JsonElement =
            runCatching { Json.parseToJsonElement(s) }.getOrElse { JsonPrimitive(s) }
    }
}

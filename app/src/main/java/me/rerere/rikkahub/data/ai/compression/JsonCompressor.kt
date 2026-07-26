package me.rerere.rikkahub.data.ai.compression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Headroom SmartCrusher 风格的 JSON 压缩器。
 *
 * 策略:
 * - 检测顶层 JSON 数组: 保留前 3 + 后 2 条, 中间替换为列描述 + 计数
 * - 检测顶层 JSON 对象: 提取所有 key 名 + 类型信息
 * - 嵌套数组: 相同策略递归应用
 * - 非 JSON: 返回 null
 */
object JsonCompressor {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val MAX_SAMPLE_FIRST = 3
    private const val MAX_SAMPLE_LAST = 2
    private const val MIN_ITEMS_TO_COMPRESS = 6

    /**
     * 尝试压缩 JSON 内容。如果不是有效 JSON 或不需要压缩则返回 null。
     */
    fun compress(input: String): String? {
        val parsed = try {
            json.parseToJsonElement(input.trim())
        } catch (_: Exception) {
            return null
        }

        return when (parsed) {
            is JsonArray -> compressArray(parsed)
            is JsonObject -> compressObject(parsed)
            else -> null
        }
    }

    private fun compressArray(arr: JsonArray): String {
        if (arr.size < MIN_ITEMS_TO_COMPRESS) return arr.toString()

        val first = arr.take(MAX_SAMPLE_FIRST)
        val last = arr.takeLast(MAX_SAMPLE_LAST)
        val middleCount = arr.size - MAX_SAMPLE_FIRST - MAX_SAMPLE_LAST

        val schema = extractArraySchema(first + last)

        return buildString {
            appendLine("[")
            first.forEachIndexed { i, item ->
                append("  ")
                append(item.toCompactString())
                if (i < first.size - 1 || last.isNotEmpty()) appendLine(",")
            }
            appendLine("  /* ... $middleCount items omitted. Schema: $schema */")
            last.forEachIndexed { i, item ->
                append("  ")
                append(item.toCompactString())
                if (i < last.size - 1) appendLine(",")
            }
            appendLine()
            append("]")
        }
    }

    private fun compressObject(obj: JsonObject): String? {
        if (obj.size < 5) return null

        val schema = obj.map { (k, v) ->
            "$k: ${jsonTypeName(v)}"
        }.joinToString(", ")

        return "{ /* ${obj.size} keys: $schema */ }"
    }

    private fun extractArraySchema(samples: List<JsonElement>): String {
        if (samples.isEmpty()) return "empty"
        val keys = mutableSetOf<String>()
        val types = mutableMapOf<String, MutableSet<String>>()
        samples.forEach { item ->
            if (item is JsonObject) {
                item.forEach { (k, v) ->
                    keys.add(k)
                    types.getOrPut(k) { mutableSetOf() }.add(jsonTypeName(v))
                }
            }
        }
        if (keys.isEmpty()) return "array of ${jsonTypeName(samples.first())}"
        return keys.joinToString(", ") { k ->
            val t = types[k]?.joinToString("|") ?: "?"
            "$k($t)"
        }
    }

    private fun jsonTypeName(element: JsonElement): String = when (element) {
        is JsonObject -> "object"
        is JsonArray -> "array[${element.size}]"
        is JsonPrimitive -> when {
            element.isString -> "string"
            element.content == "true" || element.content == "false" -> "bool"
            element.content.toIntOrNull() != null -> "int"
            element.content.toDoubleOrNull() != null -> "number"
            else -> "string"
        }
    }

    private fun JsonElement.toCompactString(): String = when (this) {
        is JsonObject -> {
            val inner = entries.take(3).joinToString(", ") { (k, v) ->
                "\"$k\": ${v.toCompactString()}"
            }
            if (entries.size > 3) "{ $inner, ... }" else "{ $inner }"
        }
        is JsonArray -> "[${size} items]"
        is JsonPrimitive -> toString()
    }

    /** 估算压缩前字符数 */
    fun originalSize(input: String): Int = input.length

    /** 估算压缩后字符数。如果未压缩则为 0 */
    fun compressedSize(input: String): Int {
        return compress(input)?.length ?: 0
    }
}

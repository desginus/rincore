package me.rerere.rikkahub.data.ai.compression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Headroom SmartCrusher 风格 JSON 压缩器。
 *
 * 策略:
 * - 顶层 JSON 数组: 保留前 3 + 后 2 条, 中间替换为列描述 + 计数
 * - 顶层 JSON 对象包裹数组 (如 {"items":[...], "total":N}):
 *   提取外层元信息 + 压缩内层数组
 * - 嵌套数组: 递归压缩
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
            is JsonArray -> compressArray(parsed, indent = "")
            is JsonObject -> {
                // 检测对象是否包裹了可压缩数组 (如搜索结果的 {"items":[...]})
                val compressibleArray = findCompressibleArray(parsed)
                if (compressibleArray != null) {
                    compressWrappedObject(parsed, compressibleArray)
                } else {
                    compressSimpleObject(parsed)
                }
            }
            else -> null
        }
    }

    /**
     * 在 JSON 对象中查找可压缩的数组:
     * - "items", "results", "data", "documents", "entries" 等键
     * - 数组长度 >= MIN_ITEMS_TO_COMPRESS
     */
    private fun findCompressibleArray(obj: JsonObject): Pair<String, JsonArray>? {
        val candidateKeys = listOf("items", "results", "data", "documents", "entries", "records", "matches", "hits")
        for (key in candidateKeys) {
            val arr = obj[key]?.jsonArray
            if (arr != null && arr.size >= MIN_ITEMS_TO_COMPRESS) {
                return key to arr
            }
        }
        return null
    }

    private fun compressWrappedObject(obj: JsonObject, arrayInfo: Pair<String, JsonArray>): String {
        val (key, arr) = arrayInfo
        val compressedArray = compressArray(arr, indent = "  ")

        // 提取外层元信息 (排除数组本身)
        val meta = obj.filterKeys { it != key }
            .map { (k, v) -> "\"$k\": ${v.toCompactString()}" }
            .joinToString(", ")

        return buildString {
            appendLine("{")
            if (meta.isNotEmpty()) {
                appendLine("  $meta,")
            }
            appendLine("  \"$key\": $compressedArray")
            append("}")
        }
    }

    private fun compressSimpleObject(obj: JsonObject): String? {
        if (obj.size < 5) return null
        val schema = obj.map { (k, v) -> "$k: ${jsonTypeName(v)}" }.joinToString(", ")
        return "{ /* ${obj.size} keys: $schema */ }"
    }

    private fun compressArray(arr: JsonArray, indent: String): String {
        if (arr.size < MIN_ITEMS_TO_COMPRESS) return arr.toString()

        val first = arr.take(MAX_SAMPLE_FIRST)
        val last = arr.takeLast(MAX_SAMPLE_LAST)
        val middleCount = arr.size - MAX_SAMPLE_FIRST - MAX_SAMPLE_LAST
        val schema = extractArraySchema(first + last)

        return buildString {
            appendLine("[")
            first.forEachIndexed { i, item ->
                append("$indent  ")
                append(item.toCompactString())
                if (i < first.size - 1 || last.isNotEmpty()) append(",")
                appendLine()
            }
            appendLine("$indent  /* ... $middleCount items omitted. Schema: $schema */")
            last.forEachIndexed { i, item ->
                append("$indent  ")
                append(item.toCompactString())
                if (i < last.size - 1) append(",")
                appendLine()
            }
            append("$indent]")
        }
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
}

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
 * - 顶层数组: 保留前 3 + 后 2, 中间替换为 schema + 计数
 * - 包裹对象 (如 {"items":[...]}): 保留外层元信息 + 压缩内层数组
 * - 广告过滤: 排除含 "ad"/"sponsored"/"广告"/"推广" 的条目
 */
object JsonCompressor {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val MAX_SAMPLE_FIRST = 3
    private const val MAX_SAMPLE_LAST = 2
    private const val MIN_ITEMS_TO_COMPRESS = 6

    fun compress(input: String): String? {
        val parsed = try {
            json.parseToJsonElement(input.trim())
        } catch (_: Exception) {
            return null
        }

        return when (parsed) {
            is JsonArray -> compressArray(parsed, indent = "")
            is JsonObject -> {
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

    private fun findCompressibleArray(obj: JsonObject): Pair<String, JsonArray>? {
        val candidateKeys = listOf(
            "items", "results", "data", "documents", "entries",
            "records", "matches", "hits", "list", "rows"
        )
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
        // 过滤广告条目
        val cleaned = filterAds(arr)
        val compressedArray = compressArray(cleaned, indent = "  ")
        val meta = obj.filterKeys { it != key }
            .map { (k, v) -> "${jsonKey(k)}: ${v.toCompactString()}" }
            .joinToString(", ")

        return buildString {
            appendLine("{")
            if (meta.isNotEmpty()) {
                appendLine("  $meta,")
            }
            append("  ${jsonKey(key)}: ")
            append(compressedArray)
            appendLine()
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

    /**
     * 过滤广告/赞助/推广条目。
     * 匹配模式: title/name/description 中包含 "ad"/"sponsored"/"广告"/"推广"。
     */
    private fun filterAds(arr: JsonArray): JsonArray {
        val adKeywords = listOf(
            "\"ad\"", "\"sponsored\"", "\"promotion\"",
            "广告", "推广", "赞助", "Ad", "AD"
        )
        val filtered = arr.filter { item ->
            val text = item.toString().lowercase()
            !adKeywords.any { kw -> kw.lowercase() in text }
        }
        // 过滤后仍然足够多就用过滤后的
        return if (filtered.size >= MIN_ITEMS_TO_COMPRESS) {
            JsonArray(filtered)
        } else arr
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

    private fun jsonKey(key: String): String = "\"$key\""

    private fun JsonElement.toCompactString(): String = when (this) {
        is JsonObject -> {
            val inner = entries.take(3).joinToString(", ") { (k, v) ->
                "${jsonKey(k)}: ${v.toCompactString()}"
            }
            if (entries.size > 3) "{ $inner, ... }" else "{ $inner }"
        }
        is JsonArray -> "[${size} items]"
        is JsonPrimitive -> toString()
    }
}

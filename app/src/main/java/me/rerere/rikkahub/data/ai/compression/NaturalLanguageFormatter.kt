package me.rerere.rikkahub.data.ai.compression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON 搜索结果 -> 自然语言格式化。
 *
 * 去掉 JSON 结构字符: [] {} "" , :
 * 提取 title/name + url/link + text/snippet
 * 过滤广告条目
 */
object NaturalLanguageFormatter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX_ITEMS = 8
    private val adPatterns = listOf("ad", "sponsored", "promotion", "广告", "推广", "赞助", "[广告]")

    fun format(input: String): String? {
        val trimmed = input.trim()
        val parsed = try { json.parseToJsonElement(trimmed) } catch (_: Exception) { return null }

        return when (parsed) {
            is JsonArray -> formatArray(parsed)
            is JsonObject -> formatObject(parsed)
            else -> null
        }
    }

    private fun formatArray(arr: JsonArray): String {
        val items = arr.filter { it is JsonObject }.map { it.jsonObject }
        val clean = filterAds(items)
        if (clean.isEmpty()) return formatGeneric(arr)

        val show = clean.take(MAX_ITEMS)
        val remaining = clean.size - show.size

        val sb = StringBuilder()
        sb.appendLine("${clean.size} results:")
        sb.appendLine()
        show.forEachIndexed { i, obj -> sb.appendLine(formatResult(i + 1, obj)) }
        if (remaining > 0) {
            sb.appendLine()
            sb.append("... and $remaining more results")
        }
        return sb.toString().trimEnd()
    }

    private fun formatObject(obj: JsonObject): String? {
        for (key in listOf("items", "results", "data", "documents", "entries", "records")) {
            val arr = obj[key]?.jsonArray
            if (arr != null && arr.size >= 3) {
                val head = StringBuilder()
                val meta = obj.filterKeys { it != key }
                    .filterValues { it is JsonPrimitive }
                    .map { (k, v) -> "${humanKey(k)}: ${(v as JsonPrimitive).content}" }
                if (meta.isNotEmpty()) {
                    head.append(meta.joinToString(" | "))
                    head.appendLine().appendLine()
                }
                return head.toString() + formatArray(arr)
            }
        }
        val parts = obj.entries
            .filterValues { it is JsonPrimitive }
            .take(10)
            .joinToString(", ") { (k, v) -> "${humanKey(k)}: ${(v as JsonPrimitive).content}" }
        return parts.ifBlank { null }
    }

    private fun formatResult(index: Int, obj: JsonObject): String {
        val sb = StringBuilder()
        sb.append(index).append(". ")

        val title = pick(obj, "title", "name", "heading", "subject")
        if (title.isNotBlank()) sb.append(title) else sb.append("[untitled]")

        val url = pick(obj, "url", "link", "href", "source")
        if (url.isNotBlank()) sb.append(" | ").append(url)

        val desc = pick(obj, "text", "snippet", "description", "summary", "content", "abstract")
        if (desc.isNotBlank()) {
            sb.appendLine()
            sb.append("   ").append(desc.take(200))
            if (desc.length > 200) sb.append("...")
        }
        return sb.toString()
    }

    private fun formatGeneric(arr: JsonArray): String {
        if (arr.size <= 3) return arr.joinToString("\n") { "  $it" }
        val first = arr.take(2).joinToString("\n") { "  $it" }
        return "$first\n  ... and ${arr.size - 2} more items"
    }

    private fun filterAds(items: List<JsonObject>): List<JsonObject> {
        return items.filter { item ->
            val text = item.toString().lowercase()
            !adPatterns.any { it.lowercase() in text }
        }
    }

    private fun pick(obj: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val v = obj[key]?.jsonPrimitive
            if (v != null && v.isString) return v.content
        }
        return ""
    }

    private fun humanKey(key: String): String {
        return key.replace("_", " ").replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
    }
}

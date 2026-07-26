package me.rerere.rikkahub.data.ai.compression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON 搜索结果 -> 纯自然语言。
 *
 * 去掉所有结构字符: [] {} "" : , / \ ` 等
 * 提取 title/name/url/link/text/snippet/description
 * 过滤广告条目
 *
 * JSON 解析失败时用正则兜底提取。
 */
object NaturalLanguageFormatter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX = 8

    private val adWords = listOf(
        "ad", "sponsored", "promotion", "广告", "推广", "赞助", "[广告]", "[推]"
    )

    // ── 主入口 ──

    fun format(input: String): String {
        val t = input.trim()

        // 尝试 JSON 解析
        try {
            return when (val p = json.parseToJsonElement(t)) {
                is JsonArray -> fmtArray(p)
                is JsonObject -> fmtObj(p)
                else -> regexFallback(t)
            }
        } catch (_: Exception) {
            // JSON 解析失败 -> 正则兜底
            return regexFallback(t)
        }
    }

    // ── JSON 路径 ──

    private fun fmtArray(arr: JsonArray): String {
        val objs = arr.filterIsInstance<JsonObject>()
        if (objs.isEmpty()) return fmtGeneric(arr)

        val clean = filterAds(objs)
        return buildResult(clean)
    }

    private fun fmtObj(obj: JsonObject): String {
        // 检测包裹对象: {"items":[...], "results":[...], ...}
        for (k in listOf("items", "results", "data", "documents", "entries", "records")) {
            val arr = obj[k]?.jsonArray
            if (arr != null && arr.isNotEmpty()) {
                val objs = arr.filterIsInstance<JsonObject>()
                val head = metaLine(obj.filterKeys { it != k })
                return head + buildResult(filterAds(objs))
            }
        }
        // 普通对象
        return obj.entries
            .filter { it.value is JsonPrimitive }
            .take(10)
            .joinToString(" | ") { (k, v) -> "${human(k)}: ${(v as JsonPrimitive).content}" }
            .ifEmpty { regexFallback(obj.toString()) }
    }

    private fun metaLine(meta: Map<String, *>): String {
        val parts = meta.entries
            .filter { it.value is JsonPrimitive }
            .map { (k, v) -> "${human(k)}: ${(v as JsonPrimitive).content}" }
        return if (parts.isNotEmpty()) parts.joinToString(" | ") + "\n\n" else ""
    }

    private fun buildResult(items: List<JsonObject>): String {
        if (items.isEmpty()) return "No results."
        val show = items.take(MAX)
        val rest = items.size - show.size
        val sb = StringBuilder()
        sb.appendLine("${items.size} results:")
        sb.appendLine()
        show.forEachIndexed { i, o -> sb.appendLine(fmtItem(i + 1, o)) }
        if (rest > 0) sb.append("\n... and $rest more results")
        return sb.toString().trimEnd()
    }

    private fun fmtItem(idx: Int, obj: JsonObject): String {
        val sb = StringBuilder()
        sb.append(idx).append(". ")

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

    private fun fmtGeneric(arr: JsonArray): String {
        if (arr.size <= 3) return arr.joinToString("\n") { "- $it" }
        return "- ${arr[0]}\n- ${arr[1]}\n  ... ${arr.size - 2} more"
    }

    // ── Regex 兜底 ──

    /**
     * 不用 JSON 解析器，直接从文本中提取结构化字段。
     * 去掉所有 []{}""\,/: 等字符。
     */
    private fun regexFallback(text: String): String {
        // 先做全局清洗：去 JSON 结构字符
        var cleaned = text
            .replace(Regex("""[\[\]{}"\\]"""), "")
            .replace(Regex(""":\s*"""), ": ")

        // 尝试匹配 "items": [...] 或 "results": [...] 模式
        val itemBlocks = Regex("""(?:items|results|data|documents|entries)\s*:\s*\[""")
            .find(cleaned)?.let {
                // 从匹配位置提取后续内容
                val start = it.range.last + 1
                val substr = cleaned.substring(start)
                // 找每个对象的 title/url/text
                extractItemsRegex(substr)
            }

        if (itemBlocks != null && itemBlocks.isNotEmpty()) {
            val sb = StringBuilder()
            sb.appendLine("${itemBlocks.size} results:")
            sb.appendLine()
            itemBlocks.take(MAX).forEachIndexed { i, item ->
                sb.appendLine("${i + 1}. ${item.title}")
                if (item.url.isNotBlank()) sb.appendLine("   ${item.url}")
                if (item.text.isNotBlank()) sb.appendLine("   ${item.text.take(200)}")
            }
            if (itemBlocks.size > MAX) sb.append("\n... and ${itemBlocks.size - MAX} more results")
            return sb.toString().trimEnd()
        }

        // 匹配顶层数组的条目 [{...}, {...}]
        val topLevelItems = extractTopLevelItemsRegex(text)
        if (topLevelItems.isNotEmpty()) {
            val clean = topLevelItems.filterNot { it.isAd }
            return buildRegexResult(clean)
        }

        // 最后的兜底: 全局清洗后返回
        return cleaned
            .replace(Regex("""[:,]{2,}"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .take(3000)
    }

    private fun extractItemsRegex(text: String): List<RegexItem> {
        val results = mutableListOf<RegexItem>()
        // 每个条目以 { 开始，找到对应的 }
        val blocks = Regex("""\{([^}]+)\}""").findAll(text)
        for (block in blocks) {
            val inner = block.groupValues[1]
            val title = Regex("""(?:title|name|heading)\s*:\s*"([^"]+)""").find(inner)?.groupValues?.get(1) ?: ""
            val url = Regex("""(?:url|link|href|source)\s*:\s*"([^"]+)""").find(inner)?.groupValues?.get(1) ?: ""
            val txt = Regex("""(?:text|snippet|description|summary|content)\s*:\s*"([^"]+)""").find(inner)?.groupValues?.get(1) ?: ""
            if (title.isNotBlank() || url.isNotBlank()) {
                results.add(RegexItem(title, url, txt, isAd(inner)))
            }
        }
        return results
    }

    private data class RegexItem(val title: String, val url: String, val text: String, val isAd: Boolean)

    private fun extractTopLevelItemsRegex(text: String): List<RegexItem> {
        return extractItemsRegex(text)
    }

    private fun buildRegexResult(items: List<RegexItem>): String {
        val show = items.take(MAX)
        val rest = items.size - show.size
        val sb = StringBuilder()
        sb.appendLine("${items.size} results:")
        sb.appendLine()
        show.forEachIndexed { i, item ->
            sb.appendLine("${i + 1}. ${item.title}")
            if (item.url.isNotBlank()) sb.appendLine("   ${item.url}")
            if (item.text.isNotBlank()) sb.appendLine("   ${item.text.take(200)}")
        }
        if (rest > 0) sb.append("\n... and $rest more results")
        return sb.toString().trimEnd()
    }

    // ── 广告过滤 ──

    private fun filterAds(items: List<JsonObject>): List<JsonObject> {
        return items.filter { !isAd(it.toString()) }
    }

    private fun isAd(text: String): Boolean {
        val lower = text.lowercase()
        return adWords.any { it.lowercase() in lower }
    }

    // ── 工具函数 ──

    private fun pick(obj: JsonObject, vararg keys: String): String {
        for (k in keys) {
            val v = obj[k]?.jsonPrimitive
            if (v != null && v.isString) return v.content
        }
        return ""
    }

    private fun human(key: String): String {
        return key.replace("_", " ")
            .replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
    }
}

package me.rerere.rikkahub.data.ai.compression

import android.util.Log

/**
 * 工具输出压缩: 结构化 JSON -> 自然语言。
 *
 * 搜索判定 (子串匹配，不限于开头):
 *   工具名中任意位置含 search/find/query/web/browse/internet/google/bing/
 *   搜索/检索/查找/联网/上网/查 → 搜索工具 → JSON转自然语言
 *
 * 输出启发式:
 *   内容以 [{ 或 {"items" 开头 → 同样按搜索处理
 */
object ToolOutputCompressor {
    private const val TAG = "ToolOutputCompress"
    private const val MIN_CHARS = 500

    fun compress(toolName: String, output: String): String? {
        if (output.length < MIN_CHARS) return null

        val isSearch = isSearchTool(toolName.lowercase())
        val looksLikeSearch = output.trimStart().let {
            it.startsWith("[{") || it.startsWith("[ {") ||
                (it.startsWith("{") && "\"items\"" in it)
        }

        if (isSearch || looksLikeSearch) {
            Log.d(TAG, "compressing $toolName (${output.length} chars, isSearch=$isSearch, heuristic=$looksLikeSearch)")
            return NaturalLanguageFormatter.format(output)
        }

        // Shell 输出
        if (isShellTool(toolName)) return compressShell(output)

        // 非搜索工具: 仍尝试 JSON 自然语言化
        return NaturalLanguageFormatter.format(output) ?: TextCompressor.compress(output)
    }

    /**
     * 子串匹配: 只要工具名中包含这些字段即判定。
     * 不限于开头 — 如 mcp__web_search, domain_find_results 都能匹配。
     */
    fun isSearchTool(name: String): Boolean {
        val kw = listOf(
            "search", "find", "query",
            "web", "browse", "internet",
            "google", "bing", "duckduckgo", "brave", "serp", "searx",
            "搜索", "检索", "查找", "搜寻", "尋",
            "联网", "上网", "查"
        )
        return kw.any { name.contains(it, ignoreCase = true) }
    }

    private fun isShellTool(name: String): Boolean {
        val kw = listOf("shell", "exec", "cmd", "bash", "terminal", "sh")
        return kw.any { name.contains(it, ignoreCase = true) }
    }

    private fun compressShell(output: String): String? {
        val lines = output.lines()
        if (lines.size <= 100) return null
        val head = lines.take(50)
        val tail = lines.takeLast(30)
        val errs = lines.filter { "error" in it.lowercase() || "exception" in it.lowercase() }.take(20)
        return buildString {
            appendLine("Shell output (${lines.size} lines):")
            head.forEach { appendLine(it) }
            appendLine("... ${lines.size - 80} lines ...")
            if (errs.isNotEmpty()) { appendLine("Errors:"); errs.forEach { appendLine(it) } }
            appendLine("--- Tail ---")
            tail.forEach { appendLine(it) }
        }
    }
}

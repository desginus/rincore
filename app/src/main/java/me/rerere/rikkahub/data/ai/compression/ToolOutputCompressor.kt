package me.rerere.rikkahub.data.ai.compression

import android.util.Log

/**
 * 工具输出压缩: JSON -> 纯自然语言。
 *
 * 搜索判定 (子串匹配):
 *   工具名中任何位置含 search/find/query/web/browse/internet/
 *   google/bing/搜索/检索/查找/联网/上网/查 -> 搜索
 *
 * 输出启发式:
 *   内容以 [{ 或 {"items" 开头 -> 搜索
 */
object ToolOutputCompressor {
    private const val TAG = "ToolOutputCompress"
    private const val MIN_CHARS = 200

    fun compress(toolName: String, output: String): String? {
        if (output.length < MIN_CHARS) return null

        val isSearch = isSearchTool(toolName.lowercase())
        val looksSearch = output.trimStart().let {
            it.startsWith("[{") || it.startsWith("[ {") ||
                (it.startsWith("{") && ("\"items\"" in it || "\"results\"" in it))
        }

        val result = if (isSearch || looksSearch) {
            Log.d(TAG, "formatting $toolName (${output.length}c, search=$isSearch heuristic=$looksSearch)")
            NaturalLanguageFormatter.format(output)
        } else if (isShellTool(toolName)) {
            compressShell(output)
        } else {
            NaturalLanguageFormatter.format(output)
            ?: TextCompressor.compress(output)
        }

        if (result != null) Log.d(TAG, "$toolName: ${output.length} -> ${result.length} chars")
        return result
    }

    fun isSearchTool(name: String): Boolean {
        val kw = listOf(
            "search", "find", "query", "web", "browse", "internet",
            "google", "bing", "duckduckgo", "brave", "serp", "searx",
            "搜索", "检索", "查找", "搜寻", "联网", "上网", "查"
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
            appendLine("Output (${lines.size} lines, ${output.length} chars):")
            head.forEach { appendLine(it) }
            appendLine("... ${lines.size - 80} lines ...")
            if (errs.isNotEmpty()) { appendLine("Errors:"); errs.forEach { appendLine(it) } }
            tail.forEach { appendLine(it) }
        }
    }
}

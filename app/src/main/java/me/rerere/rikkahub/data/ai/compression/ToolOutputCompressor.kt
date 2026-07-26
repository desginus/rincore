package me.rerere.rikkahub.data.ai.compression

import android.util.Log

/**
 * 工具输出专用压缩器。
 *
 * 搜索判定 (极宽松):
 * - 工具名中任意位置包含 search / find / query / 搜索 / 检索 / 查找 / 搜寻 /
 *   web / browse / internet 之一 → 搜索工具
 * - 输出内容以 [{ 或 {"items" 开头 → 自动搜索压缩
 *
 * Shell 判定: 工具名含 shell / exec / cmd / bash / terminal / run
 * 抓取判定: 工具名含 scrape / fetch / crawl
 * 读文件判定: 工具名含 read / cat / view / open
 */
object ToolOutputCompressor {
    private const val TAG = "ToolOutputCompressor"
    private const val MIN_CHARS_TO_COMPRESS = 500
    private const val SHELL_KEEP_LINES = 100
    private const val SHELL_TAIL_LINES = 30

    fun compress(toolName: String, output: String): String? {
        if (output.length < MIN_CHARS_TO_COMPRESS) return null
        val lower = toolName.lowercase()

        return when {
            isSearchTool(lower) -> {
                Log.d(TAG, "search compress: $toolName (${output.length} chars)")
                compressSearchOutput(output)
            }
            isShellTool(lower) -> compressShellOutput(output)
            isReadFileTool(lower) -> compressFileOutput(output)
            isScrapeTool(lower) -> compressScrapedContent(output)
            looksLikeSearchResults(output) -> {
                Log.d(TAG, "heuristic search compress: $toolName (${output.length} chars)")
                compressSearchOutput(output)
            }
            else -> genericCompress(output)
        }
    }

    /** 极宽松搜索判定: 只要工具名中出现这些词段即判定 */
    fun isSearchTool(name: String): Boolean {
        val keywords = listOf(
            "search", "find", "query",
            "搜索", "检索", "查找", "搜寻", "尋",
            "web", "browse", "internet", "google", "bing",
            "duckduckgo", "serp", "searx", "brave",
            "联网", "上网", "查"
        )
        return keywords.any { name.contains(it, ignoreCase = true) }
    }

    private fun isShellTool(name: String): Boolean {
        val keywords = listOf(
            "shell", "exec", "cmd", "bash", "terminal",
            "run_command", "execute", "powershell", "sh"
        )
        return keywords.any { name.contains(it, ignoreCase = true) }
    }

    private fun isReadFileTool(name: String): Boolean {
        val keywords = listOf(
            "read_file", "read_text", "read_document",
            "cat_file", "view_file", "open_file"
        )
        return keywords.any { name.contains(it, ignoreCase = true) }
    }

    private fun isScrapeTool(name: String): Boolean {
        val keywords = listOf("scrape", "fetch_url", "web_fetch", "crawl")
        return keywords.any { name.contains(it, ignoreCase = true) }
    }

    private fun looksLikeSearchResults(output: String): Boolean {
        val t = output.trimStart()
        return t.startsWith("[{\"") || t.startsWith("[{") ||
            (t.startsWith("{\"") && ("\"items\"" in t || "\"results\"" in t))
    }

    private fun compressSearchOutput(output: String): String? {
        val jsonResult = JsonCompressor.compress(output)
        if (jsonResult != null) return jsonResult
        return compressTextWithLabel(output, "search results")
    }

    private fun compressShellOutput(output: String): String? {
        val lines = output.lines()
        if (lines.size <= SHELL_KEEP_LINES) return null
        val head = lines.take(SHELL_KEEP_LINES / 2)
        val tail = lines.takeLast(SHELL_TAIL_LINES)
        val errorLines = lines.filter {
            "error" in it.lowercase() || "exception" in it.lowercase()
        }.take(20)
        val middleCount = lines.size - head.size - tail.size
        return buildString {
            appendLine("--- Output (${lines.size} lines, ${output.length} chars) ---")
            head.forEach { appendLine(it) }
            appendLine("... ($middleCount lines omitted)")
            if (errorLines.isNotEmpty()) {
                appendLine("--- Errors ---")
                errorLines.forEach { appendLine(it) }
            }
            appendLine("--- Tail ---")
            tail.forEach { appendLine(it) }
        }
    }

    private fun compressFileOutput(output: String): String? {
        if (output.length <= 2000) return null
        val head = output.take(1000)
        val tail = output.takeLast(500)
        return "$head\n\n ... ${output.length - 1500} chars omitted ...\n\n$tail"
    }

    private fun compressScrapedContent(output: String): String? {
        val cleaned = output.replace(Regex("\\s{3,}"), "\n\n").trim()
        return if (cleaned.length < output.length * 0.7) cleaned
        else compressTextWithLabel(output, "scraped content")
    }

    private fun genericCompress(output: String): String? {
        JsonCompressor.compress(output)?.let { return it }
        return TextCompressor.compress(output)
    }

    private fun compressTextWithLabel(text: String, label: String): String? {
        val compressed = TextCompressor.compress(text) ?: return null
        return "[$label]\n$compressed"
    }
}

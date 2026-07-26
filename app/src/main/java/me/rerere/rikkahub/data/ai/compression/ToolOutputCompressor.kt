package me.rerere.rikkahub.data.ai.compression

/**
 * 工具输出专用压缩器。
 *
 * 分类策略 (不再硬编码工具名):
 * - 搜索工具: 工具名含 search/find/query/搜索/检索/查找 之一 -> JSON 压缩
 * - Shell 工具: 工具名含 shell/exec/cmd/bash/run 之一 -> Shell 输出压缩
 * - 抓取工具: 工具名含 scrape/fetch/web 之一 -> 去冗余空白
 * - 阅读工具: 工具名含 read/cat/view/open 之一 -> 长文件截断
 * - 不匹配: 输出内容启发式检测 (先试 JSON, 再试文本)
 *
 * 核心原则: 如果 invoke_tools 加载的域带有 "搜索" 标签,
 * 该域下所有工具输出都按搜索处理 (输出通常为 JSON 搜索结果)
 */
object ToolOutputCompressor {
    private const val MIN_CHARS_TO_COMPRESS = 500
    private const val SHELL_KEEP_LINES = 100
    private const val SHELL_TAIL_LINES = 30

    /**
     * 压缩工具输出。返回压缩后文本, 不需压缩时返回 null。
     */
    fun compress(toolName: String, output: String): String? {
        if (output.length < MIN_CHARS_TO_COMPRESS) return null

        val lowerName = toolName.lowercase()

        return when {
            isSearchTool(lowerName) -> compressSearchOutput(output)
            isShellTool(lowerName) -> compressShellOutput(output)
            isReadFileTool(lowerName) -> compressFileOutput(output)
            isScrapeTool(lowerName) -> compressScrapedContent(output)
            looksLikeSearchResults(output) -> compressSearchOutput(output)
            else -> genericCompress(output)
        }
    }

    /** 工具名是否属于搜索类 (含中文标签) */
    fun isSearchTool(name: String): Boolean {
        val keywords = listOf("search", "find", "query", "搜索", "检索", "查找", "搜寻")
        return keywords.any { name.contains(it, ignoreCase = true) }
    }

    private fun isShellTool(name: String): Boolean {
        val keywords = listOf("shell", "exec", "cmd", "bash", "terminal", "run_command")
        return keywords.any { name.contains(it, ignoreCase = true) }
    }

    private fun isReadFileTool(name: String): Boolean {
        val keywords = listOf("read_file", "read_text", "read_document", "cat_file", "view_file", "open_file")
        return keywords.any { name.contains(it, ignoreCase = true) }
    }

    private fun isScrapeTool(name: String): Boolean {
        val keywords = listOf("scrape", "fetch_url", "web_fetch", "crawl")
        return keywords.any { name.contains(it, ignoreCase = true) }
    }

    /** 输出开头是否像搜索结果 (JSON 数组或带 items/results 的对象) */
    private fun looksLikeSearchResults(output: String): Boolean {
        val trimmed = output.trimStart()
        return trimmed.startsWith("[{\"") || trimmed.startsWith("[{") ||
            (trimmed.startsWith("{\"") && ("\"items\"" in trimmed || "\"results\"" in trimmed))
    }

    private fun compressSearchOutput(output: String): String? {
        val jsonResult = JsonCompressor.compress(output)
        if (jsonResult != null) return "[Search Results]\n$jsonResult"
        return compressTextWithLabel(output, "search results")
    }

    private fun compressShellOutput(output: String): String? {
        val lines = output.lines()
        if (lines.size <= SHELL_KEEP_LINES) return null

        val head = lines.take(SHELL_KEEP_LINES / 2)
        val tail = lines.takeLast(SHELL_TAIL_LINES)
        val errorLines = lines.filter {
            it.contains("error", ignoreCase = true) ||
                it.contains("ERROR") ||
                it.contains("exception", ignoreCase = true)
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
        return if (output.length > 2000) {
            val head = output.take(1000)
            val tail = output.takeLast(500)
            val total = output.length
            "$head\n\n... ($total total chars, ${total - 1500} omitted) ...\n\n$tail"
        } else null
    }

    private fun compressScrapedContent(output: String): String? {
        val cleaned = output.replace(Regex("\\s{3,}"), "\n\n").trim()
        if (cleaned.length < output.length * 0.7) return cleaned
        return compressTextWithLabel(output, "scraped content")
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

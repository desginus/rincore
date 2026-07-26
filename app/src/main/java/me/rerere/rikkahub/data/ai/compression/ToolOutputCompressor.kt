package me.rerere.rikkahub.data.ai.compression

/**
 * 工具输出专用压缩器。
 *
 * 根据工具名选择最优压缩策略:
 * - search_web / scrape_web → JSON 压缩 (搜索结果)
 * - workspace_shell → Shell 输出压缩 (首尾 + 错误行)
 * - workspace_read_file → 保留文件类型标注, 长文件截断
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

        return when {
            toolName in SEARCH_TOOLS -> compressSearchOutput(output)
            toolName in SHELL_TOOLS -> compressShellOutput(output)
            toolName in READ_FILE_TOOLS -> compressFileOutput(output)
            toolName in SCRAPE_TOOLS -> compressScrapedContent(output)
            else -> genericCompress(output)
        }
    }

    private fun compressSearchOutput(output: String): String? {
        // 尝试 JSON 压缩
        val jsonResult = JsonCompressor.compress(output)
        if (jsonResult != null) return "[Compressed search results]\n$jsonResult"

        // 非 JSON: 用文本压缩
        return compressTextWithLabel(output, "search results")
    }

    private fun compressShellOutput(output: String): String? {
        val lines = output.lines()
        if (lines.size <= SHELL_KEEP_LINES) return null

        val head = lines.take(SHELL_KEEP_LINES / 2)
        val tail = lines.takeLast(SHELL_TAIL_LINES)
        val errorLines = lines.filter {
            it.contains("error", ignoreCase = true) ||
                it.contains("Error") ||
                it.contains("ERROR") ||
                it.contains("exception", ignoreCase = true)
        }.take(20)
        val middleCount = lines.size - head.size - tail.size

        return buildString {
            appendLine("--- Command Output (${lines.size} lines, ${output.length} chars) ---")
            head.forEach { appendLine(it) }
            appendLine("... ($middleCount lines omitted)")
            if (errorLines.isNotEmpty()) {
                appendLine("--- Errors Detected ---")
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
        // 网页抓取: 去掉大量空白, 提取核心段落
        val cleaned = output.replace(Regex("\\s{3,}"), "\n\n").trim()
        if (cleaned.length < output.length * 0.7) return cleaned
        return compressTextWithLabel(output, "scraped content")
    }

    private fun genericCompress(output: String): String? {
        // 先试 JSON
        JsonCompressor.compress(output)?.let { return it }
        // 再试文本
        return TextCompressor.compress(output)
    }

    private fun compressTextWithLabel(text: String, label: String): String? {
        val compressed = TextCompressor.compress(text) ?: return null
        return "[Compressed $label]\n$compressed"
    }

    private val SEARCH_TOOLS = setOf("search_web", "search_files", "search_code")
    private val SHELL_TOOLS = setOf("workspace_shell", "execute_command", "shell")
    private val READ_FILE_TOOLS = setOf(
        "workspace_read_file", "read_file", "read_text_file",
        "read_document", "workspace_read_text"
    )
    private val SCRAPE_TOOLS = setOf("scrape_web", "fetch_url", "web_fetch")
}

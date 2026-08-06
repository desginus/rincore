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
        }

        if (result != null) Log.d(TAG, "$toolName: ${output.length} -> ${result.length} chars")
        return result
    }

    fun isSearchTool(name: String): Boolean {
        val lower = name.lowercase()
        // ── 通用搜索关键词 ──
        val generic = listOf(
            "search", "find", "query", "browse", "internet",
            "google", "bing", "duckduckgo", "brave", "serp", "searx",
            "wiki",
            "搜索", "检索", "查找", "搜寻", "联网", "上网", "查"
        )
        if (generic.any { it in lower }) return true

        // ── 域级别匹配 (MCP 工具域名) ──
        val domains = listOf(
            // 搜索引擎域
            "searchoptimization", "trustedsearch", "wikipedia",
            // 商品搜索域
            "productinquiry",
            // 趋势/热榜/新闻/资讯域
            "trendshub",
            // 网页抓取域
            "fetch",
            // 其他搜索相关域名
            "scraper", "crawler", "spider"
        )
        if (domains.any { it in lower }) return true

        // ── 功能关键词 (趋势/排行/新闻/商品/抓取等) ──
        val functional = listOf(
            "trend", "trending", "trends", "rank", "ranking",
            "news", "hot", "popular", "headline",
            "product", "price", "compare", "shopping",
            "热榜", "排行", "趋势", "热搜",
            "新闻", "资讯", "快讯", "头条",
            "商品", "价格", "比价", "值得买",
            "抓取", "爬虫", "解析", "提取",
            "知乎", "微博", "抖音", "豆瓣", "哔哩", "bilibili",
            "维基", "百科", "小红书", "百度",
            "smzdm", "gcores", "sspai", "juejin", "36kr",
            "ifanr", "infoq", "theverge", "9to5mac",
            "nytimes", "bbc", "netease", "tencent", "toutiao",
            "zhihu", "weibo", "douyin", "douban", "weread",
        )
        return functional.any { it in lower }
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

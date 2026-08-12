package me.rerere.rikkahub.data.ai.compression

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

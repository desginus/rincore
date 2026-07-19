package me.rerere.rikkahub.data.ai.tools.routing

import me.rerere.ai.core.Tool

/**
 * 工具域定义——树状层级场景分类。
 * 一级场景对应文档中的顶层分类。
 * 子场景通过路径表示（如 "搜索/网页"、"计算/物理/运动学"）。
 * entries 列表中的域被视为"内置预设"，用户可完全编辑或删除。
 */
enum class ToolDomain(
    val label: String,
    val triggerDescription: String,
    val matchKeywords: List<String>,
    val parent: String? = null, // 父域路径，null=一级场景
) {
    // === 搜索 ===
    SEARCH("搜索", "搜索网页、查资料、查新闻、商品搜索", listOf("搜索", "search", "查找", "搜", "查", "查询", "比价", "商品"), null),
    SEARCH_WEB("搜索/网页", "搜索网页、查资料、查新闻", listOf("搜一下", "search", "查查", "网上找", "网页搜索", "搜狗", "夸克", "维基", "wikipedia"), "搜索"),
    SEARCH_SHOP("搜索/购物", "搜索商品、比价", listOf("多少钱", "比价", "找商品", "购物", "商品搜索", "价格"), "搜索"),

    // === 文件 ===
    FILE("文件", "读写管理文件、压缩解压", listOf("文件", "file", "读文件", "保存", "压缩", "解压", "zip", "目录", "list_files"), null),
    FILE_BROWSE("文件/浏览", "列出目录、查看文件信息", listOf("列出", "列表", "list", "目录", "文件信息", "ls", "dir"), "文件"),
    FILE_RW("文件/读写", "读取、写入文件", listOf("读取", "写入", "read", "write", "创建文件", "保存内容", "删除", "移动", "复制"), "文件"),
    FILE_ARCHIVE("文件/压缩", "创建ZIP、解压", listOf("压缩", "解压", "zip", "archive", "打包", "tar"), "文件"),

    // === 工作区 ===
    WORKSPACE("工作区", "沙箱读写执行Shell命令", listOf("工作区", "workspace", "shell", "执行命令", "写脚本", "终端", "bash"), null),

    // === 浏览器 ===
    BROWSER("浏览器", "打开网页、点击填表、截图提取", listOf("浏览器", "browser", "打开网页", "截图", "填表", "点击", "登录", "web"), null),
    BROWSER_NAV("浏览器/导航", "打开网页、前进后退", listOf("打开网页", "打开网站", "导航", "前进", "后退", "当前", "url"), "浏览器"),
    BROWSER_INTERACT("浏览器/交互", "点击、输入、提交、滚动", listOf("点击", "输入", "填表", "提交", "滚动", "按键", "选择下拉"), "浏览器"),
    BROWSER_EXTRACT("浏览器/提取", "获取文本、DOM、截图", listOf("提取", "获取文本", "dom", "html", "截图", "screen"), "浏览器"),

    // === 计算 ===
    COMPUTE("计算", "JS代码执行和物理力学计算", listOf("计算", "compute", "算", "eval", "javascript", "物理", "physics"), null),
    COMPUTE_JS("计算/JS", "执行JavaScript代码", listOf("eval", "javascript", "js", "代码计算", "运行代码", "跑个代码"), "计算"),
    COMPUTE_PHYSICS("计算/物理", "物理力学仿真计算", listOf("物理", "physics", "抛体", "浮力", "碰撞", "力学", "运动", "仿真", "模拟"), "计算"),

    // === 图表 ===
    CHART("图表", "生成各类图表", listOf("图表", "chart", "画图", "柱状图", "饼图", "折线图", "散点图", "流程图", "思维导图", "雷达图"), null),

    // === 推理 ===
    REASON("推理", "深度推理、思维模型、序列思考", listOf("推理", "思考", "分析", "reason", "think", "sequential", "深度"), null),

    // === 调度 ===
    SCHEDULE("调度", "定时任务、日历事件", listOf("调度", "定时", "scheduled", "提醒", "日历", "calendar", "闹钟"), null),

    // === 媒体 ===
    MEDIA("媒体", "音频视频播放控制", listOf("媒体", "media", "播放", "暂停", "音乐", "音频", "视频", "扫描媒体"), null),

    // === 设备 ===
    DEVICE("设备", "剪贴板、通知、定位、屏幕时间", listOf("设备", "device", "剪贴板", "clipboard", "通知", "定位", "位置", "电量", "分享", "屏幕时间"), null),

    // === 生成 ===
    GENERATE("生成", "二维码、文字转语音", listOf("生成", "generate", "二维码", "qrcode", "朗读", "tts", "语音"), null),

    // === 部署 ===
    DEPLOY("部署", "部署HTML网页", listOf("部署", "deploy", "发布", "上线", "html", "网页链接"), null),

    // === 对话 ===
    CONVERSATION("对话", "子代理、记忆、时间", listOf("对话", "conversation", "子代理", "后台", "记住", "记忆", "时间", "token"), null),
    ;

    companion object {
        fun classify(tool: Tool): ToolDomain? {
            val text = "${tool.name} ${tool.description}".lowercase()
            // 优先匹配深层的叶子节点
            return entries.sortedByDescending { it.label.count { c -> c == '/' } }
                .firstOrNull { dom ->
                    dom.matchKeywords.any { text.contains(it) }
                }
        }
    }
}

package me.rerere.rikkahub.data.ai.tools.routing

import me.rerere.ai.core.Tool

/**
 * 工具域定义——9 大顶层分类，树状层级。
 * 顶层域：搜索、物理引擎、设备状态、文件控制、浏览工具、生成部署、对话工具、辅助推理、方法域。
 * 子域通过路径表示（如 "搜索/搜索引擎"）。
 */
enum class ToolDomain(
    val label: String,
    val triggerDescription: String,
    val matchKeywords: List<String>,
    val parent: String? = null,
) {
    // ============================================================
    // 1. 搜索
    // ============================================================
    SEARCH("搜索", "搜索网页、查资料、查新闻", listOf("搜索", "查找", "搜", "查", "查询"), null),

    SEARCH_ENGINE("搜索/搜索引擎", "通用网页搜索引擎", listOf(
        "search", "搜一下", "搜狗", "夸克", "维基", "wikipedia", "webSearch", "scrape",
        "搜素引擎", "查查", "网上找", "网页搜索", "网页", "search_web", "web_search", "query", "Quark"
    ), "搜索"),

    SEARCH_SHOP("搜索/商品搜索", "商品搜索、比价", listOf(
        "多少钱", "比价", "找商品", "购物", "商品搜索", "价格", "商品"
    ), "搜索"),

    SEARCH_POLICY("搜索/政策搜索", "法律法规政策查询", listOf(
        "政策", "法规", "法律", "trustedsearch", "政策文件", "政府"
    ), "搜索"),

    // ============================================================
    // 2. 物理引擎
    // ============================================================
    PHYSICS("物理引擎", "物理力学仿真计算", listOf("物理", "physics", "力学", "运动"), null),

    PHYSICS_SIM("物理引擎/动力学仿真", "抛体、碰撞、浮力等仿真", listOf(
        "抛体", "浮力", "碰撞", "力学", "运动", "仿真", "simulate"
    ), "物理引擎"),

    PHYSICS_FLUID("物理引擎/流体力学", "流体力学计算", listOf(
        "流体", "空气动力学", "湍流", "水力学"
    ), "物理引擎"),

    // ============================================================
    // 3. 设备状态
    // ============================================================
    DEVICE("设备状态", "剪贴板、通知、定位、调度", listOf("设备", "device", "剪贴板", "clipboard", "通知", "定位", "位置", "电量", "分享", "屏幕时间", "camera", "拍照", "传感器", "蓝牙", "wifi", "toast"), null),

    DEVICE_SCHEDULE("设备状态/调度", "定时任务、日历事件", listOf(
        "调度", "定时", "scheduled", "提醒", "日历", "calendar", "闹钟",
        "cron", "任务", "job", "schedule", "event", "定时任务"
    ), "设备状态"),

    // ============================================================
    // 4. 文件控制
    // ============================================================
    FILE("文件控制", "读写管理文件、压缩解压、工作区Shell", listOf("文件", "file", "读文件", "保存", "压缩", "解压", "zip", "目录", "workspace", "shell", "工作区", "bash", "mkdir", "delete", "move", "copy", "下载"), null),

    FILE_BROWSE("文件控制/浏览", "列出目录、查看文件信息", listOf(
        "列出", "列表", "list", "目录", "文件信息", "ls", "dir", "list_files", "read_file"
    ), "文件控制"),

    FILE_RW("文件控制/读写", "读取、写入、编辑文件", listOf(
        "读取", "写入", "read", "write", "创建文件", "保存内容", "编辑文件",
        "edit_file", "write_file", "workspace_read", "workspace_write", "workspace_edit"
    ), "文件控制"),

    FILE_ARCHIVE("文件控制/压缩", "创建ZIP、解压", listOf(
        "压缩", "解压", "zip", "archive", "打包", "tar"
    ), "文件控制"),

    // ============================================================
    // 5. 浏览工具
    // ============================================================
    BROWSER("浏览工具", "打开网页、点击填表、截图提取", listOf("浏览器", "browser", "打开网页", "截图", "填表", "点击", "登录", "web", "webBody", "WebBody", "playwright", "navigate", "click", "type"), null),

    BROWSER_NAV("浏览工具/导航", "打开网页、前进后退", listOf(
        "打开网页", "打开网站", "导航", "前进", "后退", "当前", "url"
    ), "浏览工具"),

    BROWSER_INTERACT("浏览工具/交互", "点击、输入、提交、滚动", listOf(
        "点击", "输入", "填表", "提交", "滚动", "按键", "选择下拉", "type", "fill"
    ), "浏览工具"),

    BROWSER_EXTRACT("浏览工具/提取", "获取文本、DOM、截图", listOf(
        "提取", "获取文本", "dom", "html", "截图", "screen", "screenshot", "extract"
    ), "浏览工具"),

    // ============================================================
    // 6. 生成部署
    // ============================================================
    GEN_DEPLOY("生成部署", "图像视频生成、网页部署、二维码", listOf("生成", "generate", "部署", "deploy", "发布"), null),

    GEN_IMAGE("生成部署/图像生成", "AI 图片生成", listOf(
        "画图", "生成图片", "AI绘画", "绘画", "image", "图片生成", "插图", "海报"
    ), "生成部署"),

    GEN_VIDEO("生成部署/视频生成", "AI 视频生成", listOf(
        "视频生成", "生成视频", "video", "video_generation"
    ), "生成部署"),

    GEN_DEPLOY_WEB("生成部署/网页部署", "部署 HTML 网页", listOf(
        "部署", "deploy", "发布", "上线", "html", "网页链接", "网页部署", "edgeone"
    ), "生成部署"),

    GEN_QR("生成部署/二维码", "生成二维码", listOf(
        "二维码", "qrcode", "QR", "条码"
    ), "生成部署"),

    GEN_CHART("生成部署/图表", "生成数据图表", listOf(
        "图表", "chart", "柱状图", "饼图", "折线图", "散点图", "流程图", "思维导图",
        "雷达图", "dashscope", "charting", "antv", "visualization", "热力图",
        "面积图", "气泡图", "漏斗图", "桑基图", "韦恩图"
    ), "生成部署"),

    // ============================================================
    // 7. 对话工具
    // ============================================================
    CONVERSATION("对话工具", "子代理、记忆、时间、高频率小工具", listOf("对话", "conversation", "子代理", "后台", "记住", "记忆", "时间", "token"), null),

    CONV_MEMORY("对话工具/记忆", "记忆读写管理", listOf(
        "memory", "memory_tool", "记忆", "记住", "备忘"
    ), "对话工具"),

    CONV_SUBAGENT("对话工具/子代理", "子代理调度", listOf(
        "子代理", "subagent", "后台", "子对话", "代理"
    ), "对话工具"),

    CONV_TIME("对话工具/时间", "时间信息获取", listOf(
        "当前时间", "时间", "get_time_info", "time", "时钟"
    ), "对话工具"),

    CONV_UTIL("对话工具/小工具", "JS沙箱、Ask User、剪贴板、技能等高频率工具", listOf(
        "eval", "javascript", "js", "代码计算", "ask_user", "询问用户",
        "use_skill", "技能", "clipboard", "复制", "TTS", "tts", "朗读",
        "text_to_speech", "语音合成", "屏幕时间", "screen_time",
        "子对话", "conversation", "skill", "shutdown", "restart",
        "toast", "notification", "通知", "分享", "share", "下载", "download"
    ), "对话工具"),

    // ============================================================
    // 8. 辅助推理
    // ============================================================
    REASON("辅助推理", "深度推理、序列思考、方法论分析", listOf("推理", "思考", "分析", "reason", "think", "深度"), null),

    REASON_SEQ("辅助推理/序列思考", "序列化深度推理", listOf(
        "sequentialthinking", "sequential", "序列", "逐步思考", "step by step"
    ), "辅助推理"),

    REASON_METHOD("辅助推理/方法论", "有用方法论MCP工具", listOf(
        "methodology", "方法论", "分析", "研究", "框架", "思维模型"
    ), "辅助推理"),

    // ============================================================
    // 9. 方法域（兜底）
    // ============================================================
    METHOD("方法域", "无法归入其他域的通用方法论工具", listOf("method", "方法", "流程", "策略", "框架", "分析器"), null),
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

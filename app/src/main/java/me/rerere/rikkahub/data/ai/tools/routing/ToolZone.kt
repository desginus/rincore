/**
 * 工具区（ToolZone）— 工具矩阵的唯一分类单元。
 *
 * v4.8.83 彻底重写（取代旧「工具域」体系，含 ToolDomain.kt 的枚举宇宙）:
 *  1. **不再分内置域/自定义域** —— 全部工具区都是同一种声明式数据，同等可增、可删、可改、
 *     可重命名、可隐藏。出厂模板只在首次启动播种一次（seed once），之后用户完全拥有它。
 *  2. **id 即身份**：id = 完整路径（如 `搜索/搜索引擎`），创建时确定、此后不变。
 *     层级直接编码在 id 里 —— 不再有独立的 parent 字段，从根上消灭
 *     「name 有时存短名、有时存全路径」这类归一化不一致（旧体系 delete 假成功的根因）。
 *  3. **声明即存在**：工具区只要被声明就一定在矩阵里（哪怕 0 个工具），绝不自动蒸发。
 *  4. **兜底区 `未分类`** 永远存在（缺失即隐式补上）：保证「各工具区计数之和 == 工具池总数」
 *     这条不变量恒成立，任何工具都不会因为区被删除而凭空消失。
 */
/* 【域 C·工具系统】 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools.routing

import kotlinx.serialization.Serializable

@Serializable
data class ToolZone(
    /** 完整路径，唯一身份。创建后不变（重命名 = 改 title，不动 id）。 */
    val id: String = "",
    /** 显示名。空 = 取 id 末段。 */
    val title: String = "",
    /** 触发描述 —— 功能解释（自然语言），呈现给模型。 */
    val description: String = "",
    /** 触发条件 —— 关键词（可被 search_zones 反查，也用于自动归类）。 */
    val keywords: List<String> = emptyList(),
)

/** 兜底工具区 id —— 未命中任何规则的工具一律落这里。永远存在，不可删除。 */
const val FALLBACK_ZONE_ID = "未分类"

val ToolZone.parentId: String?
    get() = id.substringBeforeLast('/', "").takeIf { it.isNotBlank() }

val ToolZone.shortName: String
    get() = id.substringAfterLast('/')

/** 显示名：title 为空时回落到 id 末段 */
val ToolZone.displayName: String
    get() = title.ifBlank { shortName }

/** 统一标签 —— 全链路（工具矩阵地图/帮助/列表/invoke_tools）唯一格式：
 *  显示名与末段不同时输出 `路径（显示名）`，否则仅路径。 */
val ToolZone.label: String
    get() = if (displayName != shortName) "$id（$displayName）" else id

/** 拼接工具区路径 —— 唯一入口，杜绝「父路径 + 子路径」双重叠加。 */
fun zonePathOf(parent: String?, name: String): String {
    val child = name.trim().trim('/')
    val par = parent?.trim()?.trim('/').orEmpty().trimEnd('/')
    return if (par.isBlank()) child else "$par/$child"
}

/**
 * 出厂工具区模板 —— 首次启动播种一次（seed once）。
 * 播种之后它们就是普通工具区：用户删了就真没了（不再有 removedBuiltinDomains 墓碑），
 * 需要时可用「恢复出厂工具区」把缺失的模板区补回来（只增不删，不动用户自己的区）。
 */
val DEFAULT_TOOL_ZONES: List<ToolZone> = listOf(
    // 1. 搜索
    ToolZone("搜索", "搜索", "搜索网页、查资料、查新闻", listOf("搜索", "查找", "搜")),
    ToolZone(
        "搜索/搜索引擎", "搜索引擎", "通用网页搜索引擎", listOf(
            "search", "搜一下", "搜狗", "夸克", "维基", "wikipedia", "webSearch", "scrape",
            "scrape_web", "搜素引擎", "查查", "网上找", "网页搜索", "网页", "search_web", "web_search",
        )
    ),
    ToolZone("搜索/商品搜索", "商品搜索", "商品搜索、比价", listOf("多少钱", "比价", "找商品", "购物", "商品搜索", "价格", "商品")),
    ToolZone("搜索/政策搜索", "政策搜索", "法律法规政策查询", listOf("政策", "法规", "法律", "trustedsearch", "政策文件", "政府")),

    // 2. 物理引擎
    ToolZone("物理引擎", "物理引擎", "物理力学仿真计算", listOf("物理", "physics", "力学", "运动")),
    ToolZone("物理引擎/动力学仿真", "动力学仿真", "抛体、碰撞、浮力等仿真", listOf("抛体", "浮力", "碰撞", "力学", "运动", "仿真", "simulate")),
    ToolZone("物理引擎/流体力学", "流体力学", "流体力学计算", listOf("流体", "空气动力学", "湍流", "水力学")),

    // 3. 设备状态
    ToolZone(
        "设备状态", "设备状态", "剪贴板、通知、定位、调度", listOf(
            "设备", "device", "剪贴板", "clipboard", "通知", "定位", "位置", "电量", "分享",
            "屏幕时间", "camera", "拍照", "传感器", "蓝牙", "wifi", "toast",
        )
    ),
    ToolZone(
        "设备状态/调度", "调度", "定时任务、日历事件", listOf(
            "调度", "定时", "scheduled", "提醒", "日历", "calendar", "闹钟",
            "cron", "任务", "job", "schedule", "event", "定时任务",
        )
    ),

    // 4. 文件控制
    ToolZone(
        "文件控制", "文件控制", "读写管理文件、压缩解压、工作区Shell", listOf(
            "文件", "file", "读文件", "保存", "压缩", "解压", "zip", "目录", "workspace",
            "shell", "工作区", "bash", "mkdir", "delete", "move", "copy", "下载",
        )
    ),
    ToolZone("文件控制/浏览", "浏览", "列出目录、查看文件信息", listOf("列出", "列表", "list", "目录", "文件信息", "ls", "dir", "list_files", "read_file")),
    ToolZone(
        "文件控制/读写", "读写", "读取、写入、编辑文件", listOf(
            "读取", "写入", "read", "write", "创建文件", "保存内容", "编辑文件",
            "edit_file", "write_file", "workspace_read", "workspace_write", "workspace_edit",
        )
    ),
    ToolZone("文件控制/压缩", "压缩", "创建ZIP、解压", listOf("压缩", "解压", "zip", "archive", "打包", "tar")),

    // 5. 浏览工具
    ToolZone(
        "浏览工具", "浏览工具", "打开网页、点击填表、截图提取", listOf(
            "浏览器", "browser", "打开网页", "截图", "填表", "点击", "登录", "web", "webBody",
            "WebBody", "playwright", "navigate", "click", "type",
        )
    ),
    ToolZone("浏览工具/导航", "导航", "打开网页、前进后退", listOf("打开网页", "打开网站", "导航", "前进", "后退", "当前", "url")),
    ToolZone("浏览工具/交互", "交互", "点击、输入、提交、滚动", listOf("点击", "输入", "填表", "提交", "滚动", "按键", "选择下拉", "type", "fill")),
    ToolZone("浏览工具/提取", "提取", "获取文本、DOM、截图", listOf("提取", "获取文本", "dom", "html", "截图", "screen", "screenshot", "extract")),

    // 6. 生成部署
    ToolZone("生成部署", "生成部署", "图像视频生成、网页部署、二维码", listOf("生成", "generate", "部署", "deploy", "发布")),
    ToolZone("生成部署/图像生成", "图像生成", "AI 图片生成", listOf("画图", "生成图片", "AI绘画", "绘画", "image", "图片生成", "插图", "海报")),
    ToolZone("生成部署/视频生成", "视频生成", "AI 视频生成", listOf("视频生成", "生成视频", "video", "video_generation")),
    ToolZone("生成部署/网页部署", "网页部署", "部署 HTML 网页", listOf("部署", "deploy", "发布", "上线", "html", "网页链接", "网页部署", "edgeone")),
    ToolZone("生成部署/二维码", "二维码", "生成二维码", listOf("二维码", "qrcode", "QR", "条码")),
    ToolZone(
        "生成部署/图表", "图表", "生成数据图表", listOf(
            "图表", "chart", "柱状图", "饼图", "折线图", "散点图", "流程图", "思维导图",
            "雷达图", "dashscope", "charting", "antv", "visualization", "热力图",
            "面积图", "气泡图", "漏斗图", "桑基图", "韦恩图",
        )
    ),

    // 7. 对话工具
    ToolZone("对话工具", "对话工具", "子代理、记忆、时间、高频率小工具", listOf("对话", "conversation", "子代理", "后台", "记住", "记忆", "时间", "token")),
    ToolZone(
        "对话工具/记忆", "记忆", "记忆读写管理、对话搜索、最近对话", listOf(
            "memory", "memory_tool", "记忆", "记住", "备忘",
            "conversation_search", "recent_chats", "对话搜索", "最近对话", "历史搜索",
        )
    ),
    ToolZone("对话工具/子代理", "子代理", "子代理调度", listOf("子代理", "subagent", "后台", "子对话", "代理")),
    ToolZone("对话工具/时间", "时间", "时间信息获取", listOf("当前时间", "时间", "get_time_info", "time", "时钟")),
    ToolZone(
        "对话工具/小工具", "小工具", "JS沙箱、Ask User、剪贴板等高频率工具", listOf(
            "eval", "javascript", "js", "代码计算", "ask_user", "询问用户",
            "clipboard", "复制", "TTS", "tts", "朗读",
            "text_to_speech", "语音合成", "屏幕时间", "screen_time",
            "子对话", "conversation", "shutdown", "restart",
            "toast", "notification", "通知", "分享", "share", "下载", "download",
        )
    ),

    // 8. 辅助推理
    ToolZone("辅助推理", "辅助推理", "深度推理、方法论分析", listOf("推理", "思考", "分析", "reason", "think", "深度")),
    ToolZone("辅助推理/方法论", "方法论", "有用方法论MCP工具", listOf("methodology", "方法论", "分析", "研究", "框架", "思维模型")),

    // 9. 兜底区（永远存在，不可删除；承接无法归入其他区的工具）
    ToolZone(FALLBACK_ZONE_ID, FALLBACK_ZONE_ID, "无法归入其他工具区的工具", listOf("method", "方法", "流程", "策略", "框架", "分析器", "未分类", "uncategorized")),

    // 10. 技能
    ToolZone("技能", "技能", "Skill 能力模块，按功能场景组织", listOf("skill_", "技能", "skill", "能力模块")),

    // 11. 插件
    ToolZone("插件", "插件", "插件能力模块（技能 + workspace 桥接工具）", listOf("plugin__", "插件", "plugin")),

    // 12. 系统（工具矩阵自身的元工具）
    ToolZone(
        "系统", "系统", "内部系统工具（仅用户明确要求管理工具区/MCP/插件时使用）", listOf(
            "manage_zone", "manage_mcp_servers", "list_zones", "move_tool", "clawhub", "plugin_install",
        )
    ),
)

/** 出厂顶层工具集（始终注入请求体、不参与工具区归类的工具）—— 用户可增（提升）可减（降级）。 */
val DEFAULT_TOP_LEVEL_TOOLS: Set<String> = setOf(
    "invoke_tools",
    "search_zones",
    "workspace_shell", "workspace_read_file", "workspace_write_file", "workspace_edit_file", "workspace_show_file",
    "workspace_job",
    "workspace_grep", "workspace_glob",
    "manage_zone", "list_zones", "move_tool_to_zone",
    "manage_mcp_servers", "plugin_install",
    "read_image",
    "task_tool",
)

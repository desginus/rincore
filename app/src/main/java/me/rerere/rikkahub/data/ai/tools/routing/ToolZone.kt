/**
 * 工具区（ToolZone）— 工具矩阵的唯一分类单元。
 *
 * ## v4.8.87 架构重写：身份与路径彻底分离
 *
 * v4.8.83 的模型让 **id = 完整路径**，于是"路径"同时承担了三种角色：
 * 身份、显示、父子关系。一份数据背三种含义，必然互相拉扯 —— 上一版遗留的四个 bug
 * 全是这一条的直接后果：
 *  · 改不了路径 / 显示名与路径对不上（路径即身份 ⇒ 改名 = 换身份）
 *  · 根区显示名被"短化"（显示名只能从路径末段截）
 *  · 孤儿区（父路径不存在）凭空出现、删了又疑似复活（父子关系是字符串前缀推导）
 *  · create 偶发假成功（增删校验建立在字符串拼接上）
 *
 * 现在的模型：
 * ```
 *   id        不透明稳定身份（创建即固定，任何代码都不得从 id 反推语义）
 *   name      段名（同级唯一）；改它 = 改名，路径自动跟随
 *   parentId  父区**引用**；改它 = 移动，无任何字符串推导
 *   title     显示名（可为空，空则用 name）
 *   description / keywords   触发描述 / 触发条件
 * ```
 * 铁律：
 *  1. **路径是派生的**（[ZoneTree.pathOf]）—— 永远与 name 链一致，不可能对不上、不可能短化。
 *  2. **父子是引用** —— 不存在"路径父区"这回事，因此不存在孤儿；删除时子区显式上移。
 *  3. **id 只用于相等比较** —— 出现任何 `id.substringXxx` / `startsWith` 都是 bug。
 *  4. **修改只走 [ZoneOps]** —— UI 与模型共用同一套操作与校验，不存在第二套写规则。
 */
/* 【域 C·工具系统】 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools.routing

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ToolZone(
    /** 不透明稳定身份。创建即固定（改名/移动都不动它），任何代码不得从中反推语义。 */
    val id: String = "",
    /** 段名：同级唯一，不含 '/'。改它 = 改名（路径自动跟随）。 */
    val name: String = "",
    /** 父区 id 引用（null = 根区）。改它 = 移动。 */
    val parentId: String? = null,
    /** 显示名（可空 —— 空则用 name）。 */
    val title: String = "",
    /** 触发描述 —— 功能解释（自然语言），呈现给模型。 */
    val description: String = "",
    /** 触发条件 —— 关键词（自动归类 + `invoke_tools(关键词)` 反查）。 */
    val keywords: List<String> = emptyList(),
) {
    /** 显示名：显示名 = title ?: name（绝不再从路径截取）。 */
    val displayName: String get() = title.ifBlank { name }
}

/** 兜底工具区 —— 未命中任何归类规则的工具落这里。永远存在，不可删除。 */
const val FALLBACK_ZONE_ID = "未分类"

/** 生成一个不透明的新区 id（工厂区沿用固定常量 id，用户新建区用随机短 id）。 */
fun newZoneId(existing: Set<String>): String {
    repeat(8) {
        val candidate = "z" + UUID.randomUUID().toString().replace("-", "").take(10)
        if (candidate !in existing) return candidate
    }
    return "z" + UUID.randomUUID().toString().replace("-", "")
}

// ═══════════ 区树：全部父子/路径派生的唯一实现 ═══════════

/**
 * 工具区树视图 —— 由 `List<ToolZone>` 一次性构建。
 * 路径、子区、子树、祖先判定全部在这里，别处一律不得做字符串推导。
 * 对坏数据（悬空 parentId、成环）**只修不炸**：不可达的区按根区处理。
 */
class ZoneTree(zones: List<ToolZone>) {
    val byId: Map<String, ToolZone> = zones.filter { it.id.isNotBlank() }.distinctBy { it.id }.associateBy { it.id }
    private val order: List<String> = zones.filter { it.id.isNotBlank() }.distinctBy { it.id }.map { it.id }
    private val childrenMap: Map<String, List<String>> = run {
        val m = LinkedHashMap<String, MutableList<String>>()
        for (id in order) {
            val pid = byId[id]?.parentId
            if (pid != null && pid in byId && pid != id) m.getOrPut(pid) { mutableListOf() }.add(id)
        }
        m
    }
    private val pathCache = HashMap<String, String>()

    val ids: Set<String> get() = byId.keys

    /** 声明顺序（根与子统一按声明序） */
    val allIds: List<String> get() = order

    fun get(id: String): ToolZone? = byId[id]

    fun childrenOf(id: String?): List<String> = if (id == null) roots() else childrenMap[id].orEmpty()

    fun roots(): List<String> = order.filter { id ->
        val pid = byId[id]?.parentId
        pid == null || pid !in byId || pid == id
    }

    /** 派生路径：`父/父/名`。父链不可达（坏数据/成环）时按根处理，绝不递归爆栈。 */
    fun pathOf(id: String): String = pathCache.getOrPut(id) {
        val zone = byId[id] ?: return@getOrPut id
        val pid = zone.parentId
        if (pid == null || pid !in byId || pid == id) zone.name.ifBlank { id }
        else pathOf(pid) + "/" + zone.name.ifBlank { id }
    }

    /** 展示标签：`路径（显示名）`（显示名与段名一致时只给路径）。 */
    fun label(id: String): String {
        val z = byId[id] ?: return id
        val path = pathOf(id)
        return if (z.displayName.isNotBlank() && z.displayName != z.name) "$path（${z.displayName}）" else path
    }

    /** 子树全部 id（含自身）。 */
    fun subtreeIds(id: String): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(cur: String, depth: Int) {
            if (depth > 64 || !out.add(cur)) return
            childrenOf(cur).forEach { walk(it, depth + 1) }
        }
        walk(id, 0)
        return out
    }

    /** candidate 是否为 of 的后代（防移动成环）。 */
    fun isDescendant(candidate: String, of: String): Boolean =
        candidate != of && candidate in subtreeIds(of)

    /** 同级重名检查（排除 selfId） */
    fun nameTaken(parentId: String?, name: String, selfId: String? = null): Boolean =
        childrenOf(parentId).any { it != selfId && byId[it]?.name == name }

    /**
     * 寻址（严格唯一）：id → 段名 → 路径 → 显示名。
     * 多义一律不猜（返回全部候选由调用方给出提示）。
     */
    fun resolveCandidates(addr: String?): List<String> {
        val raw = addr?.substringBefore("（")?.substringBefore("(")?.trim()?.trim('/') ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        if (raw in byId) return listOf(raw)
        val byName = order.filter { byId[it]?.name == raw }
        if (byName.isNotEmpty()) return byName
        val byPath = order.filter { pathOf(it) == raw }
        if (byPath.isNotEmpty()) return byPath
        return order.filter { byId[it]?.displayName == raw }
    }

    fun resolve(addr: String?): String? = resolveCandidates(addr).singleOrNull()
}

// ═══════════ 出厂模板 ═══════════

/**
 * 出厂工具区模板 —— 首次启动播种一次（seed once）。
 * 工厂区用**固定 id**（见每条注释），因此「恢复出厂工具区」可按 id 幂等补齐。
 * 播种后它们就是普通区：用户删了就真没了；需要时用「恢复出厂」把缺失的补回来（只增不删）。
 */
val DEFAULT_TOOL_ZONES: List<ToolZone> = listOf(
    // 兜底区（永远存在，不可删除）
    ToolZone(FALLBACK_ZONE_ID, "未分类", null, "", "无法归入其他工具区的工具", listOf("未分类", "uncategorized", "method", "方法", "流程", "策略", "框架")),

    // 1. 搜索
    ToolZone("搜索", "搜索", null, "", "搜索网页、查资料、查新闻", listOf("搜索", "查找", "搜")),
    ToolZone("搜索/搜索引擎", "搜索引擎", "搜索", "", "通用网页搜索引擎", listOf("search", "搜一下", "搜狗", "夸克", "维基", "wikipedia", "webSearch", "scrape", "网页搜索", "search_web", "web_search")),
    ToolZone("搜索/商品搜索", "商品搜索", "搜索", "", "商品搜索、比价", listOf("多少钱", "比价", "找商品", "购物", "商品搜索", "价格", "商品")),
    ToolZone("搜索/政策搜索", "政策搜索", "搜索", "", "法律法规政策查询", listOf("政策", "法规", "法律", "trustedsearch", "政策文件", "政府")),

    // 2. 物理引擎
    ToolZone("物理引擎", "物理引擎", null, "", "物理力学仿真计算", listOf("物理", "physics", "力学", "运动")),
    ToolZone("物理引擎/动力学仿真", "动力学仿真", "物理引擎", "", "抛体、碰撞、浮力等仿真", listOf("抛体", "浮力", "碰撞", "力学", "仿真", "simulate")),
    ToolZone("物理引擎/流体力学", "流体力学", "物理引擎", "", "流体力学计算", listOf("流体", "空气动力学", "湍流", "水力学")),

    // 3. 设备状态
    ToolZone("设备状态", "设备状态", null, "", "剪贴板、通知、定位、调度", listOf("设备", "device", "剪贴板", "clipboard", "通知", "定位", "位置", "电量", "分享", "屏幕时间", "camera", "拍照", "传感器", "蓝牙", "wifi", "toast")),
    ToolZone("设备状态/调度", "调度", "设备状态", "", "定时任务、日历事件", listOf("调度", "定时", "scheduled", "提醒", "日历", "calendar", "闹钟", "cron", "任务", "job", "schedule", "event")),

    // 4. 文件控制
    ToolZone("文件控制", "文件控制", null, "", "读写管理文件、压缩解压、工作区 Shell", listOf("文件", "file", "读文件", "保存", "压缩", "解压", "zip", "目录", "workspace", "shell", "工作区", "bash", "下载")),
    ToolZone("文件控制/浏览", "浏览", "文件控制", "", "列出目录、查看文件信息", listOf("列出", "列表", "list", "目录", "文件信息", "ls", "dir", "list_files", "read_file")),
    ToolZone("文件控制/读写", "读写", "文件控制", "", "读取、写入、编辑文件", listOf("读取", "写入", "read", "write", "创建文件", "保存内容", "编辑文件", "edit_file", "write_file", "workspace_read", "workspace_write", "workspace_edit")),
    ToolZone("文件控制/压缩", "压缩", "文件控制", "", "创建 ZIP、解压", listOf("压缩", "解压", "zip", "archive", "打包", "tar")),

    // 5. 浏览工具
    ToolZone("浏览工具", "浏览工具", null, "", "打开网页、点击填表、截图提取", listOf("浏览器", "browser", "打开网页", "截图", "填表", "点击", "登录", "web", "playwright", "navigate", "click", "type")),
    ToolZone("浏览工具/导航", "导航", "浏览工具", "", "打开网页、前进后退", listOf("打开网页", "打开网站", "导航", "前进", "后退", "url")),
    ToolZone("浏览工具/交互", "交互", "浏览工具", "", "点击、输入、提交、滚动", listOf("输入", "提交", "滚动", "按键", "选择下拉", "fill")),
    ToolZone("浏览工具/提取", "提取", "浏览工具", "", "获取文本、DOM、截图", listOf("提取", "获取文本", "dom", "html", "screen", "screenshot", "extract")),

    // 6. 生成部署
    ToolZone("生成部署", "生成部署", null, "", "图像视频生成、网页部署、二维码", listOf("生成", "generate", "部署", "deploy", "发布")),
    ToolZone("生成部署/图像生成", "图像生成", "生成部署", "", "AI 图片生成", listOf("画图", "生成图片", "AI绘画", "绘画", "image", "图片生成", "插图", "海报")),
    ToolZone("生成部署/视频生成", "视频生成", "生成部署", "", "AI 视频生成", listOf("视频生成", "生成视频", "video", "video_generation")),
    ToolZone("生成部署/网页部署", "网页部署", "生成部署", "", "部署 HTML 网页", listOf("上线", "网页链接", "网页部署", "edgeone")),
    ToolZone("生成部署/二维码", "二维码", "生成部署", "", "生成二维码", listOf("二维码", "qrcode", "QR", "条码")),
    ToolZone("生成部署/图表", "图表", "生成部署", "", "生成数据图表", listOf("图表", "chart", "柱状图", "饼图", "折线图", "散点图", "流程图", "思维导图", "雷达图", "dashscope", "charting", "antv", "visualization", "热力图", "面积图", "气泡图", "漏斗图", "桑基图", "韦恩图")),

    // 7. 对话工具
    ToolZone("对话工具", "对话工具", null, "", "子代理、记忆、时间、高频率小工具", listOf("对话", "conversation", "子代理", "后台", "记住", "记忆", "时间", "token")),
    ToolZone("对话工具/记忆", "记忆", "对话工具", "", "记忆读写管理、对话搜索、最近对话", listOf("memory", "memory_tool", "记忆", "记住", "备忘", "conversation_search", "recent_chats", "对话搜索", "最近对话", "历史搜索")),
    ToolZone("对话工具/子代理", "子代理", "对话工具", "", "子代理调度", listOf("子代理", "subagent", "后台", "子对话", "代理")),
    ToolZone("对话工具/时间", "时间", "对话工具", "", "时间信息获取", listOf("当前时间", "时间", "get_time_info", "time", "时钟")),
    ToolZone("对话工具/小工具", "小工具", "对话工具", "", "JS 沙箱、Ask User、剪贴板等高频率工具", listOf("eval", "javascript", "js", "代码计算", "ask_user", "询问用户", "clipboard", "复制", "TTS", "tts", "朗读", "text_to_speech", "语音合成", "screen_time", "shutdown", "restart", "toast", "notification", "通知", "分享", "share", "download")),

    // 8. 辅助推理
    ToolZone("辅助推理", "辅助推理", null, "", "深度推理、方法论分析", listOf("推理", "思考", "分析", "reason", "think", "深度")),
    ToolZone("辅助推理/方法论", "方法论", "辅助推理", "", "有用方法论 MCP 工具", listOf("methodology", "方法论", "研究", "思维模型")),

    // 9. 技能 / 插件 / 系统
    ToolZone("技能", "技能", null, "", "Skill 能力模块，按功能场景组织", listOf("skill_", "技能", "skill", "能力模块")),
    ToolZone("插件", "插件", null, "", "插件能力模块（技能 + workspace 桥接工具）", listOf("plugin__", "插件", "plugin")),
    ToolZone("系统", "系统", null, "", "内部系统工具（仅用户明确要求管理工具区/MCP/插件时使用）", listOf("manage_zone", "manage_mcp_servers", "invoke_tools", "clawhub", "plugin_install")),
)

/**
 * 工具矩阵核心件 —— 永远留在顶层，不可移出（移出 = 整个矩阵失联，模型再也加载不到任何工具区）。
 * 这是结构性不变式：即使配置被写坏，[me.rerere.rikkahub.data.ai.tools.topLevelToolSetOf] 也会兜回来。
 */
val CORE_MATRIX_TOOLS: Set<String> = setOf("invoke_tools", "manage_zone")

/**
 * 出厂顶层工具集（始终注入请求体、不参与工具区归类）。
 * 工具矩阵自身只保留两个入口 —— `invoke_tools`（加载/反查）与 `manage_zone`（管理）。
 */
val DEFAULT_TOP_LEVEL_TOOLS: Set<String> = setOf(
    "invoke_tools",
    "manage_zone",
    "workspace_shell", "workspace_read_file", "workspace_write_file", "workspace_edit_file", "workspace_show_file",
    "workspace_job",
    "workspace_grep", "workspace_glob",
    "manage_mcp_servers", "plugin_install",
    "read_image",
    "upload_fetch", // v4.8.90: 上传码直取 —— 顶层常驻，任何对话都能直接定位用户上传的文件
    "task_tool",
)

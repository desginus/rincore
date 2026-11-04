package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import java.security.MessageDigest

/**
 * 记忆库里的一个 Markdown 文件。
 *
 * [version] 由内容算出，写操作用它做乐观锁：内容没变版本就不变。
 */
data class MemoryFile(
    val path: String,
    val content: String,
    val updatedAt: Long,
    // v5.3.7 ★统一记忆模型：来源与归属（此前只在另一张条目表里有，导致模型写的记忆
    // 无法按「主动/做梦 × 助手级/项目包级/单对话级」二维检索）
    val source: String = "active",
    val folderId: String? = null,
    val conversationId: String? = null,
    val invalidatedAt: Long? = null,
)  {
    /** 是否做梦整理产物 */
    val isDream: Boolean get() = source == "dream"

    /** 归属层级：对话级 > 项目包级 > 助手级（三 tag） */
    val layer: MemoryLayer
        get() = when {
            !conversationId.isNullOrBlank() -> MemoryLayer.CONVERSATION
            !folderId.isNullOrBlank() -> MemoryLayer.FOLDER
            else -> MemoryLayer.ASSISTANT
        }

    val version: String by lazy { memoryVersionOf(content) }

    private val frontmatter by lazy { SkillFrontmatterParser.parse(content) }

    /** frontmatter 里的一句话描述，用于清单 */
    /**
     * 清单里的一句话描述。
     *
     * ★v5.3.22 关键修复：frontmatter 没写 description 时**用正文首行兜底**。
     *
     * 旧实现只读 frontmatter —— 而 v5.3.7 的 `MIGRATION_37_38` 把历史条目搬进
     * `memory_file` 时**只写 content 原文、不生成 frontmatter**，于是这批记忆的
     * description 恒为空 → 注入的 `<memory_listing>` 里全变成
     *     `/memory_261020175231.md | (no description) | updated: 2026-10-20`
     * → 模型**完全不知道这份记忆讲什么** → 不会去 `memory_read` → 记忆系统
     * 对用户实际失效（这正是「改成索引式以后模型好像不记得我了」的机制）。
     */
    val description: String
        get() = frontmatter["description"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: autoDescription

    /** 正文首行作为兜底描述（去掉列表符、`[stated]` 标记、`【】` 包裹），截 120 字。 */
    private val autoDescription: String
        get() = body.lineSequence()
            .map { raw ->
                raw.trim()
                    .removePrefix("- ").removePrefix("* ").removePrefix("+ ")
                    .removePrefix("[stated]").trim()
                    .removePrefix("【").removeSuffix("】")
                    .trim()
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .take(120)

    /**
     * 是否「新架构写入的结构化记忆」。
     *
     * ★v5.3.23：注入优先级的分层依据。
     *
     * 新架构（模型按 `<memory_format>` 调 `memory_write`）写出的文件**一定带 frontmatter**
     * （name / title / description / aliases）；而 v5.3.7 的 `MIGRATION_37_38` 把历史条目
     * 搬进 `memory_file` 时**只写 content 原文、不生成 frontmatter** —— 那批就是「旧记忆」，
     * 内容多为当时的版本发布流水账，数量大、时效差。
     *
     * 两者混在同一份清单里时，旧记忆会按「最近更新」把结构化记忆挤出注入预算，
     * 于是模型看到的全是过时的版本记录，反而看不到「你是谁、在做什么」。
     */
    val isStructured: Boolean get() = frontmatter.toMap().isNotEmpty()

    /**
     * 注入优先级：0=核心档案（profile/preferences，全文注入）
     * 1=结构化记忆（新架构）2=旧记忆（迁移产物，仅作低置信背景）。
     */
    val tier: Int get() = when {
        path == PROFILE_PATH || path == PREFERENCES_PATH -> 0
        isStructured -> 1
        else -> 2
    }

    val aliases: List<String> get() = frontmatter.getList("aliases")

    /**
     * ═══ v5.3.44 ★★记忆的「用途类型」—— 决定注入权重与淘汰顺序 ═══
     *
     * 这是本轮语义转向的核心：**记忆不是用来分析用户的，是用来让下一次任务更省事的**。
     *
     * 旧的 `/profile.md`（你是谁、住哪、做什么工作）在用户明确否决后**不再是特殊层**：
     * 它要么不存在，要么降级成一条普通 [FACT]。
     *
     * 权重从高到低：**WORK > PROJECT > FACT > PREFERENCE**
     * - [WORK]：这类任务怎么做 —— 命令、路径、步骤、坑。**唯一能直接降低下次沟通成本的东西。**
     * - [PROJECT]：某个项目的结构与操作方式 —— 构建、发布、文件在哪。
     * - [FACT]：具体事实（环境版本、某个配置值）。**会过时**，所以低于前两者。
     * - [PREFERENCE]：回答格式/语气。**最少用、最先淘汰**，不单独占层。
     *
     * 写在 frontmatter 的 `kind:` 里 → **不需要数据库迁移**（content 本来就是文本）。
     * 缺省 = [FACT]：历史记忆绝大多数是"某版本发布了什么"这类事实，归 FACT 是准确的。
     */
    val kind: MemoryKind
        get() = when (frontmatter["kind"]?.trim()?.lowercase()) {
            "work", "procedure", "playbook" -> MemoryKind.WORK
            "project" -> MemoryKind.PROJECT
            "preference", "pref" -> MemoryKind.PREFERENCE
            else -> MemoryKind.FACT
        }

    /** 注入权重：数值越大越优先出现、越晚被淘汰。 */
    val weight: Int get() = kind.weight

    /** 去掉 frontmatter 后的正文 */
    val body: String get() = SkillFrontmatterParser.extractBody(content)

    /** 给用户看的正文：`[stated]` 是写给模型的标记，界面上不显示 */
    val displayBody: String get() = body.replace(statedMarkerRegex, "$1")

    /**
     * 编辑页里的正文。frontmatter 读不出来时（比如某个值里有没加引号的冒号）整段原文都算正文：
     * 那一段在各个输入框里都看不到，不留在正文里的话一保存就被悄悄换掉了。
     */
    val editableBody: String get() = if (frontmatter.toMap().isEmpty()) content else body

    /** 所在目录，根目录下的文件是 "/" */
    val directory: String get() = path.substringBeforeLast('/').ifEmpty { "/" }

    /** 去掉目录和 .md 后缀的文件名 */
    val name: String get() = path.substringAfterLast('/').removeSuffix(".md")

    /** 界面上显示的标题：frontmatter 里的 title，没写时从文件名推出来（frontend-tooling 显示成 Frontend Tooling） */
    val title: String get() = frontmatter["title"]?.trim()?.ifEmpty { null } ?: memoryTitleOf(name)

    /**
     * 用编辑页分开填的各项拼出新的文件内容。
     *
     * name 跟着路径走，不由用户填；留空的项不写；frontmatter 里其他的键原样保留。
     */
    fun contentWith(title: String, description: String, aliases: List<String>, body: String): String {
        val values = linkedMapOf<String, Any?>("name" to name)
        title.toSingleLine().takeIf { it.isNotEmpty() }?.let { values["title"] = it }
        description.toSingleLine().takeIf { it.isNotEmpty() }?.let { values["description"] = it }
        aliases.takeIf { it.isNotEmpty() }?.let { values["aliases"] = it }
        frontmatter.toMap().forEach { (key, value) -> if (key !in editableKeys) values[key] = value }
        return SkillFrontmatterParser.compose(values, body)
    }

    companion object {
        const val PROFILE_PATH = "/profile.md"
        const val PREFERENCES_PATH = "/preferences.md"
        const val MAX_BYTES = 48 * 1024
        private const val MAX_PATH_LENGTH = 128

        /** 规范化模型或用户给出的路径，不合法时返回 null */
        fun normalizePath(raw: String): String? {
            val path = raw.trim().let { if (it.startsWith("/")) it else "/$it" }
            if (path.length > MAX_PATH_LENGTH || !path.endsWith(".md")) return null
            if (path.any { it.isISOControl() || it == '\\' }) return null
            val segments = path.removePrefix("/").split("/")
            if (segments.any { it.isBlank() || it != it.trim() || it.startsWith(".") }) return null
            return path
        }
    }
}

/**
 * 记忆用途类型。顺序即优先级（见 [MemoryFile.weight]）。
 *
 * ★为什么是这四个而不是 MIKA 的三个：MIKA 是「操作手机」的 agent，用户画像对它有价值
 * （称呼、口味、日程）；我们是「办公/工作区」工具，**用户的个人画像对完成任务没有帮助**，
 * 真正有价值的是「这类活怎么干」。故新增 [PROJECT]（项目结构），降级 [FACT]/[PREFERENCE]。
 */
enum class MemoryKind(val weight: Int) {
    /** 做法：以后遇到同类任务照着做。权重最高。 */
    WORK(4),
    /** 项目：这个项目怎么构建/发布/找文件。 */
    PROJECT(3),
    /** 事实：环境版本、配置值、某个具体状态。会过时。 */
    FACT(2),
    /** 偏好：回答格式与语气。最少用、最先淘汰。 */
    PREFERENCE(1),
}

// 编辑页有对应输入框的键，加上跟着路径走的 name
private val editableKeys = setOf("name", "title", "description", "aliases")

// 清单里一个文件占一行，标题和描述里不能有换行
private fun String.toSingleLine(): String = replace(Regex("\\s+"), " ").trim()

// 列表项开头的 [stated] 标记，保留前面的列表符号
private val statedMarkerRegex = Regex("""(?m)^(\s*[-*+]\s+)\[stated\]\s*""", RegexOption.IGNORE_CASE)

// 每次都全文注入的两个文件排在最前，其余目录按记忆格式约定的顺序
private val pinnedPathOrder = listOf(MemoryFile.PROFILE_PATH, MemoryFile.PREFERENCES_PATH)
private val directoryOrder = listOf("/", "/topics", "/areas", "/people")

private fun <T> List<T>.orderOf(item: T) = indexOf(item).let { if (it < 0) size else it }

/** 把路径里用连字符或下划线拼的名字转成界面上的标题 */
fun memoryTitleOf(name: String): String =
    name.split('-', '_', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }

/** 按目录分组，用于在界面上展示 */
fun List<MemoryFile>.groupByDirectory(): List<Pair<String, List<MemoryFile>>> =
    sortedWith(compareBy({ pinnedPathOrder.orderOf(it.path) }, { it.path }))
        .groupBy { it.directory }
        .toList()
        .sortedWith(compareBy({ directoryOrder.orderOf(it.first) }, { it.first }))

private fun memoryVersionOf(content: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(12)

/**
 * v5.3.7：记忆归属层级（用户定案的三个 tag）。
 * 与 [me.rerere.rikkahub.data.model.MemoryFile.layer] 一一对应。
 */
enum class MemoryLayer { ASSISTANT, FOLDER, CONVERSATION }

/**
 * v5.3.7：把**统一记忆**（[MemoryFile]，唯一存储表 memory_file）适配成注入 prompt
 * 使用的旧模型 [me.rerere.rikkahub.data.model.AssistantMemory]。
 *
 * 存储与判据都已统一（v5.3.22 起 `buildMemoryPrompt(List<AssistantMemory>)` 重载已物理删除，
 * 唯一入口是 `prompts.buildMemoryPrompt(List<MemoryFile>)`），这里只是让旧 UI 调用点
 * 无需连锁改动 —— 数据来自同一张表，不存在第二份真相。
 */
fun MemoryFile.toAssistantMemory(): AssistantMemory = AssistantMemory(
    id = path.hashCode().toLong(),
    content = displayBody.ifBlank { content },
    conversationId = conversationId,
    folderId = folderId,
    source = source,
    path = path,
    isLegacy = !isStructured,
)

/** v5.3.7：由正文首行生成一个可读的记忆路径（UI 新建记忆时用）。 */
fun pathFromContent(content: String, fallbackId: Long = 0L): String {
    val firstLine = content.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
        .removePrefix("#")
        .removePrefix("【")
        .removeSuffix("】")
        .trim()
    val cleaned = firstLine
        .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(40)
    return if (cleaned.isBlank()) "memory_$fallbackId.md" else "$cleaned.md"
}

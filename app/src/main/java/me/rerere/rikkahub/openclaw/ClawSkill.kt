package me.rerere.rikkahub.openclaw


/* ───【自研】ClawSkill.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
/**
 * OpenClaw 技能数据模型。
 * 对应 ~/.openclaw/workspace/skills/<name>/SKILL.md 解析结果。
 */
data class ClawSkill(
    val name: String,                    // YAML frontmatter: name (匹配目录名)
    val description: String,             // YAML frontmatter: description (用于意图匹配)
    val version: String = "0.0.0",       // 语义化版本
    val body: String,                    // Markdown 正文 — 核心指令
    val emoji: String? = null,           // 可选表情符号
    val homepage: String? = null,
    val requiresEnv: List<EnvRequirement> = emptyList(),
    val requiresBins: List<String> = emptyList(),
    val sourcePath: String = "",         // SKILL.md 所在目录绝对路径
)

data class EnvRequirement(
    val name: String,
    val required: Boolean = true,
    val description: String = "",
)

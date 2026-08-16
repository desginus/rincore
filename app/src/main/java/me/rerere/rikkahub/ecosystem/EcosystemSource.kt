package me.rerere.rikkahub.ecosystem


/* ───【自研】EcosystemSource.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
/**
 * 生态系统标识 — 所有受支持的 AI 指令生态的枚举。
 * 每个生态对应一组可发现的文件和内容格式。
 */
enum class EcosystemSource(val displayName: String, val description: String) {
    OPENCLAW("OpenClaw", "OpenClaw 个人 AI 助手生态 — AGENTS.md, SOUL.md, SKILL.md"),
    CLAUDE_CODE("Claude Code", "Anthropic Claude Code CLI — CLAUDE.md, .claude/settings.json, .claude/commands/"),
    CURSOR("Cursor", "Cursor IDE AI — .cursorrules, .cursor/instructions/"),
    COPILOT("GitHub Copilot", "GitHub Copilot — .github/copilot-instructions.md"),
    WINDSURF("Windsurf", "Windsurf IDE — .windsurfrules"),
    CUSTOM("自定义", "用户自定义指令文件"),
}

/**
 * 发现的生态系统指令文件。
 */
data class EcosystemInstruction(
    val source: EcosystemSource,
    val fileName: String,          // 文件名 (AGENTS.md, SKILL.md, CLAUDE.md etc.)
    val displayPath: String,       // 显示路径
    val content: String,           // 文件内容
    val role: InstructionRole,     // 在 system prompt 中的角色
    val metadata: Map<String, String> = emptyMap(),
)

enum class InstructionRole {
    /** 不可变系统规则 — 最高优先级, 始终注入, 模型不可修改 */
    SYSTEM_RULES,
    /** 人格/语气指导 — 控制回答风格 */
    PERSONALITY,
    /** 技能手册 — 工具使用指导, 按需注入 */
    SKILL_MANUAL,
    /** 项目上下文 — 当前项目的规则/约束 */
    PROJECT_CONTEXT,
    /** 用户自定义 — 用户个人的偏好/指令 */
    USER_DEFINED,
}

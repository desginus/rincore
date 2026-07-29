package me.rerere.rikkahub.openclaw

import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * OpenClaw SKILL.md → RikkaHub Tool 桥接器。
 * Tool 是 data class, 通过构造函数直接创建。
 */
object ClawSkillBridge {

    private const val SKILL_PREFIX = "skill_"

    fun toTool(skill: ClawSkill, enabled: Boolean = true): Tool {
        val toolName = SKILL_PREFIX + skill.name.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")

        val body = skill.body
        val description = skill.description
        val name = skill.name

        return Tool(
            name = toolName,
            description = description,
            systemPrompt = { model: Model, messages: List<UIMessage> ->
                if (!enabled) return@Tool ""
                buildString {
                    appendLine("## 可用技能: $name")
                    appendLine("描述: $description")
                    if (skill.emoji != null) appendLine("图标: ${skill.emoji}")
                    appendLine()
                    appendLine("### 指令")
                    appendLine(body)
                    appendLine()
                    appendLine("---")
                    appendLine("当需要使用此技能时, 请按照上述指令操作。")
                    appendLine("使用现有工具 (bash/workspace_shell/search/read/write) 完成技能描述的任务。")
                }
            },
            needsApproval = { false },
            execute = { input: JsonElement ->
                // 技能工具不直接执行 — 它是纯指令文档。
                // 模型的 systemPrompt 中已包含完整指令,
                // 此返回值作为备份上下文。
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            appendLine("[技能已激活: $name]")
                            appendLine("该技能提供以下指导:")
                            appendLine()
                            appendLine(body.take(4000))
                            if (body.length > 4000) {
                                appendLine("...(已截断, 完整指令请查看 system prompt)")
                            }
                        }
                    )
                )
            },
        )
    }

    fun toTools(skills: List<ClawSkill>, enabledFilter: (ClawSkill) -> Boolean = { true }): List<Tool> {
        return skills
            .filter(enabledFilter)
            .map { toTool(it) }
    }
}

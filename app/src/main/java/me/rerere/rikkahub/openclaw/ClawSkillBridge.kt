package me.rerere.rikkahub.openclaw

import me.rerere.ai.core.Tool

/**
 * OpenClaw 技能 → RikkaHub Tool 桥接器。
 * 每个 SKILL.md 生成一个 Tool 实例, 其 systemPrompt 即为技能指令正文。
 * 模型调用 `skill_<name>` 时, 将收到完整的技能手册作为上下文。
 */
object ClawSkillBridge {

    /**
     * 将单个技能转换为 Tool。
     * @param skill 解析后的技能
     * @param enabled 如果 false, 仅生成描述供展示, 不注册为可用工具
     */
    fun toTool(skill: ClawSkill, enabled: Boolean = true): Tool {
        val toolName = "skill_${skill.name.lowercase().replace("[^a-z0-9-]".toRegex(), "-")}"

        return object : Tool {
            override val name: String = toolName

            override val description: String = skill.description

            override val needsApproval: (kotlinx.serialization.json.JsonElement) -> Boolean = { false }

            override suspend fun execute(args: String): List<me.rerere.ai.ui.UIMessagePart> {
                // 技能工具不直接执行 — 它是纯指令文档。
                // 模型"调用"该工具时, systemPrompt 会被注入到上下文中,
                // 模型使用其中的指令指导后续的标准工具调用 (bash/fs/search等)。
                return listOf(
                    me.rerere.ai.ui.UIMessagePart.Text(
                        buildString {
                            appendLine("[技能已激活: ${skill.name}]")
                            appendLine("该技能提供以下指导:")
                            appendLine()
                            appendLine(skill.body.take(4000))
                            if (skill.body.length > 4000) {
                                appendLine("...(已截断, 完整指令请在系统提示中查看)")
                            }
                        }
                    )
                )
            }

            override fun systemPrompt(
                model: me.rerere.ai.provider.Model,
                messages: List<me.rerere.ai.ui.UIMessage>,
            ): String {
                if (!enabled) return ""
                return buildString {
                    appendLine("## 可用技能: ${skill.name}")
                    appendLine("描述: ${skill.description}")
                    if (skill.emoji != null) appendLine("图标: ${skill.emoji}")
                    appendLine()
                    appendLine("### 指令")
                    appendLine(skill.body)
                    appendLine()
                    appendLine("---")
                    appendLine("当需要使用此技能时, 请按照上述指令操作。")
                    appendLine("使用现有工具 (bash/workspace_shell/search/read/write) 完成技能描述的任务。")
                }
            }
        }
    }

    /**
     * 批量转换技能列表。
     */
    fun toTools(skills: List<ClawSkill>, enabledFilter: (ClawSkill) -> Boolean = { true }): List<Tool> {
        return skills
            .filter { enabledFilter(it) }
            .map { toTool(it) }
    }
}

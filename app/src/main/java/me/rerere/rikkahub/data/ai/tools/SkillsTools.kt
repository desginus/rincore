package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata

/**
 * 将每个启用的 Skill 创建为独立工具。
 * 工具名: skill_<sanitized_name>
 * 工具描述: Skill 的原始 description
 * 参数: path (可选) — Skill 目录内的相对路径
 */
fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    skillManager: SkillManager,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return available.map { skill ->
        val toolName = sanitizeSkillToolName(skill.name)
        Tool(
            name = toolName,
            description = skill.description.take(300),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf<String>()
                )
            },
            execute = {
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    skillManager.readSkillBody(skill.name)
                        ?: error("Skill '${skill.name}' not found")
                } else {
                    val target = skillManager.resolveSkillFile(skill.name, path)
                        ?: error("Path '$path' is outside the skill directory")
                    require(target.exists()) { "File '$path' not found in skill '${skill.name}'" }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    }
}

/** 将 Skill 名称转换为合法的工具名: skill_<lowercase_with_underscores> */
fun sanitizeSkillToolName(skillName: String): String {
    val sanitized = skillName.lowercase()
        .replace(" ", "_")
        .replace(Regex("[^a-z0-9_-]"), "")
        .trim('_')
    return "skill_$sanitized"
}

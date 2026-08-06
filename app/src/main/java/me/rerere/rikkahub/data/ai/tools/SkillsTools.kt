package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillPaths

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    skillProvider: () -> List<SkillMetadata> = { allSkills }, // 实时查询(可选) — 修复列表快照滞后: 新增 Skill 无需重启
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    // 每个已启用 Skill 生成独立工具 skill_<name> — 直接可用, 无需先 invoke_tools 查列表
    // 描述取自 SKILL.md frontmatter (tools 数组可见), 模型拿到即可调用
    val skillTools = available.map { skill ->
        Tool(
            name = sanitizeSkillToolName(skill.name), // skill_<清洗名> — 含空格/特殊字符的 skill 名需清洗为合法工具名
            description = skill.description.ifBlank { "Load and apply the '${skill.name}' skill's instructions." },
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
                val liveAvailable = skillProvider().filter { s -> s.name in enabledSkills }
                val live = liveAvailable.firstOrNull { s -> s.name == skill.name }
                    ?: error("Skill '${skill.name}' is not available. Available skills: ${liveAvailable.joinToString { it.name }}")
                if (path.isNullOrBlank()) {
                    require(live.skillFile.exists()) { "Skill '${skill.name}' not found" }
                    SkillFrontmatterParser.extractBody(live.skillFile.readText())
                } else {
                    val target = SkillPaths.resolveSkillFile(live.skillDir, path)
                        ?: error("Path '$path' is outside the skill directory")
                    target.readText()
                }
            },
        )
    }

    return skillTools + listOf(
        Tool(
            name = "use_skill",
            description = "Load and apply a skill to get specialized instructions or capabilities. Skill tools (skill_<name>) are directly callable; use this to fetch additional files inside a skill directory by path.",
            // 注意: 不再通过 systemPrompt 注入 skills 列表 —
            // 分层模式下 systemPrompt 不注入, 且动态列表破坏缓存。
            // skills 元数据统一由 invoke_tools("技能") 在消息层返回。
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                // 实时查询 (修复: 新增 Skill 无需重启, 立即可用)
                val liveAvailable = skillProvider().filter { s -> s.name in enabledSkills }
                val skill = liveAvailable.firstOrNull { s -> s.name == name }
                    ?: error("Skill '$name' is not available. Available skills: ${liveAvailable.joinToString { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    require(skill.skillFile.exists()) { "Skill '$name' not found" }
                    SkillFrontmatterParser.extractBody(skill.skillFile.readText())
                } else {
                    val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                        ?: error("Path '$path' is outside the skill directory")
                    require(target.exists()) { "File '$path' not found in skill '$name'" }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}

fun sanitizeSkillToolName(skillName: String): String {
    val sanitized = skillName.lowercase()
        .replace(" ", "_")
        .replace(Regex("[^a-z0-9_-]"), "")
        .trim('_')
    return "skill_$sanitized"
}

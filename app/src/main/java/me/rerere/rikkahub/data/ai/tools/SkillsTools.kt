package me.rerere.rikkahub.data.ai.tools


/* ───【原版对齐】SkillsTools | 差异 +86 行
 * 来源: 原版移植 + 自研 (技能工具生成)
 * 差异: 技能全量生成 (v3.5.45) 与执行去 enabledSkills 过滤
 *       (v3.6.92 — 修复技能全灭)
 * ───────────────────────────────────────────────────────────────*/
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
    allSkills: List<SkillMetadata>,
    enabledSkills: Set<String>? = null, // v3.10.4: 恢复助手级过滤 — null=全量(存量兼容), 非null=按名单
    skillProvider: () -> List<SkillMetadata> = { allSkills }, // 实时查询(可选) — 修复列表快照滞后: 新增 Skill 无需重启
): List<Tool> {
    // 信源统一 (v3.5.45): 全部已安装 Skill 生成独立工具 — 不按启用过滤。
    // v3.6.92: enabledSkills 参数删除 — 生成/执行口径统一为"存在即可用"
    //   (默认 enabledSkills 为空 → 所有技能报 not available 的矛盾遗留)。
    // v3.10.4: 恢复过滤 — 由调用方 (ToolsBuilder) 按助手 filterSkills 决定:
    //   新助手 (filterSkills=true) 传 enabledSkills → 未勾选技能不生成工具;
    //   存量助手 (filterSkills=false) 传 null → 全量兼容, 不破坏现有可用性。
    // 技能工具经 invoke_tools("技能") 分层加载, 不增加冷启动体积。
    val base = skillProvider().ifEmpty { allSkills }
    val available = if (enabledSkills != null) base.filter { it.name in enabledSkills } else base
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
                // v3.6.92: 去掉 enabledSkills 过滤 — 工具生成全量 (v3.5.45) 而执行按
                // enabledSkills 过滤是矛盾遗留: 默认 enabledSkills 为空 → 所有技能
                // (含系统预置) 报 not available。技能存在即可执行, 与生成口径一致。
                val liveAvailable = skillProvider()
                val live = liveAvailable.firstOrNull { s -> s.name == skill.name }
                    ?: error("Skill '${skill.name}' is not available. Available skills: ${liveAvailable.joinToString { it.name }}")
                if (path.isNullOrBlank()) {
                    require(live.skillFile.exists()) { "Skill '${skill.name}' not found" }
                    listOf(UIMessagePart.Text(SkillFrontmatterParser.extractBody(live.skillFile.readText())))
                } else {
                    val target = SkillPaths.resolveSkillFile(live.skillDir, path)
                        ?: error("Path '$path' is outside the skill directory")
                    listOf(UIMessagePart.Text(target.readText()))
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
                // v3.6.92: 同样去掉 enabledSkills 过滤 — 存在即可用
                val liveAvailable = skillProvider()
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
    // skill__<名> — 第一字段类别(skill), 第二字段分类字段(skill 名), 与 mcp__服务器__工具 同构
    return "skill__$sanitized"
}

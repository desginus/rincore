package me.rerere.rikkahub.ecosystem

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 生态系统桥接器 — 将任意生态的指令文件转换为 RikkaHub Tool。
 *
 * 所有生态统一模式:
 *   指令文本 → Tool(name, description=摘要, systemPrompt=正文, execute=返回引用)
 *
 * 模型在 tools 列表中看到, 调用后获得完整指令上下文。
 */
object EcosystemBridge {

    /** 前缀映射 */
    private val PREFIX_MAP = mapOf(
        EcosystemSource.OPENCLAW to "claw_",
        EcosystemSource.CLAUDE_CODE to "claude_",
        EcosystemSource.CURSOR to "cursor_",
        EcosystemSource.COPILOT to "copilot_",
        EcosystemSource.WINDSURF to "windsurf_",
        EcosystemSource.CUSTOM to "custom_",
    )

    /**
     * 生态系统指令 → Tool。
     */
    fun toTool(instruction: EcosystemInstruction): Tool {
        val prefix = PREFIX_MAP[instruction.source] ?: "eco_"
        val toolName = prefix + instruction.fileName
            .lowercase()
            .replace(Regex("[^a-z0-9_\\-.]"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')

        val body = instruction.content
        val desc = buildSummary(instruction)

        return Tool(
            name = toolName,
            description = desc,
            systemPrompt = { _, _ -> buildSystemPromptFor(instruction) },
            needsApproval = { false },
            execute = { _: JsonElement ->
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            appendLine("[${instruction.source.displayName}] ${instruction.fileName}")
                            appendLine()
                            appendLine(body.take(4000))
                            if (body.length > 4000) appendLine("...(已截断 ${body.length}c 中的前 4000c)")
                        }
                    )
                )
            },
        )
    }

    private fun buildSummary(inst: EcosystemInstruction): String {
        val src = inst.source.displayName
        val firstLine = inst.content.lines().firstOrNull { it.isNotBlank() && !it.startsWith("---") }
            ?.take(80) ?: inst.fileName
        return "[$src] $firstLine"
    }

    private fun buildSystemPromptFor(inst: EcosystemInstruction): String {
        return buildString {
            appendLine("## [${inst.source.displayName}] ${inst.fileName}")
            appendLine("来源: ${inst.displayPath}")
            appendLine()
            appendLine(inst.content)
        }
    }

    /**
     * 创建技能发现工具 — 让模型可以列出所有可用的生态系统指令。
     */
    fun createDiscoveryTool(instructions: List<EcosystemInstruction>): Tool {
        val listing = instructions.joinToString("\n") { inst ->
            val prefix = PREFIX_MAP[inst.source] ?: "eco_"
            val toolName = prefix + inst.fileName
                .lowercase()
                .replace(Regex("[^a-z0-9_\\-.]"), "-")
                .trim('-')
            "  $toolName — [${inst.source.displayName}] ${inst.fileName}"
        }

        return Tool(
            name = "list_ecosystem_tools",
            description = "列出当前可用的所有生态系统指令和技能 (OpenClaw/Claude Code/Cursor 等)",
            systemPrompt = { _, _ -> "" },
            needsApproval = { false },
            execute = { _: JsonElement ->
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            appendLine("当前可用生态系统指令 (${instructions.size} 个):")
                            appendLine()
                            if (instructions.isEmpty()) {
                                appendLine("  (无)")
                            } else {
                                appendLine(listing)
                            }
                            appendLine()
                            appendLine("调用方式: 在 system prompt 中查找对应工具名, 调用后获得完整指令。")
                        }
                    )
                )
            },
        )
    }
}

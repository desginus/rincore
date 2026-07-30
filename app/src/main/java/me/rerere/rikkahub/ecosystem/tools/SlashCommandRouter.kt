package me.rerere.rikkahub.ecosystem.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.ecosystem.EcosystemInstruction
import me.rerere.rikkahub.ecosystem.EcosystemManager

object SlashCommandRouter : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { msg ->
            if (msg.role != MessageRole.USER) return@map msg

            val textParts = msg.parts.filterIsInstance<UIMessagePart.Text>()
            if (textParts.isEmpty()) return@map msg

            val text = textParts.joinToString("") { it.text }
            val commandMatch = text.lines()
                .firstOrNull { it.trimStart().startsWith("/") }
                ?.trimStart()
                ?.removePrefix("/")
                ?.trim()
                ?: return@map msg

            val allInstructions = EcosystemManager.instructions.value
            val matched = findMatchingInstruction(allInstructions, commandMatch)
                ?: return@map msg

            val newText = text + "\n\n--- Slash Command: /$commandMatch ---\n" +
                "Source: [${matched.source.displayName}] ${matched.fileName}\n\n" +
                matched.content.take(3000)

            val newParts = msg.parts.map { part ->
                if (part is UIMessagePart.Text) UIMessagePart.Text(newText) else part
            }
            msg.copy(parts = newParts)
        }
    }

    private fun findMatchingInstruction(
        instructions: List<EcosystemInstruction>,
        command: String,
    ): EcosystemInstruction? {
        if (instructions.isEmpty()) return null

        // 1. 精确匹配 metadata.slash_commands 列表
        instructions.firstOrNull { inst ->
            val slashCmds = inst.metadata["slash_commands"]
            slashCmds != null && slashCmds.split(",").any { it.trim().lowercase() == command.lowercase() }
        }?.let { return it }

        // 2. 精确匹配文件名 (去扩展名)
        instructions.firstOrNull { inst ->
            val name = inst.fileName
                .removeSuffix(".md").removeSuffix(".json")
                .lowercase()
            name == command.lowercase()
        }?.let { return it }

        // 3. 模糊匹配: command 包含在文件名中
        instructions.firstOrNull { inst ->
            inst.fileName.lowercase().contains(command.lowercase())
        }?.let { return it }

        // 4. skill name 匹配 (来自 OpenClaw SKILL.md metadata)
        instructions.firstOrNull { inst ->
            inst.metadata["skillName"]?.lowercase() == command.lowercase()
        }?.let { return it }

        return null
    }
}

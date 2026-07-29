package me.rerere.rikkahub.ecosystem.tools

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.ecosystem.EcosystemInstruction
import me.rerere.rikkahub.ecosystem.EcosystemManager

/**
 * Slash Command 路由器 — InputMessageTransformer 实现。
 *
 * 拦截用户消息中以 / 开头的行, 匹配已启用的生态系统指令
 * (尤其是 Claude Code 的 .claude/commands/*.md),
 * 将其内容注入到消息上下文中。
 */
object SlashCommandRouter : InputMessageTransformer {

    override suspend fun transform(messages: List<UIMessage>): List<UIMessage> {
        return messages.map { msg ->
            if (msg.from != UIMessage.From.User) return@map msg

            val text = msg.getMessageText()
            val commandMatch = text.lines()
                .firstOrNull { it.trimStart().startsWith("/") }
                ?.trimStart()
                ?.removePrefix("/")
                ?.trim()
                ?: return@map msg

            val allInstructions = EcosystemManager.instructions.value
            val matched = findMatchingInstruction(allInstructions, commandMatch)
                ?: return@map msg

            // 注入匹配的指令内容到消息末尾
            val newText = buildString {
                appendLine(text)
                appendLine()
                appendLine("--- Slash 命令: /$commandMatch ---")
                appendLine("来源: [${matched.source.displayName}] ${matched.fileName}")
                appendLine()
                appendLine(matched.content.take(3000))
            }

            msg.copy(parts = msg.parts.map { part ->
                if (part is UIMessagePart.Text) {
                    UIMessagePart.Text(newText)
                } else part
            })
        }
    }

    private fun findMatchingInstruction(
        instructions: List<EcosystemInstruction>,
        command: String,
    ): EcosystemInstruction? {
        if (instructions.isEmpty()) return null

        // 精确匹配: 文件名去除扩展名 == command
        instructions.firstOrNull { inst ->
            val name = inst.fileName
                .removeSuffix(".md")
                .removeSuffix(".json")
                .lowercase()
            name == command.lowercase()
        }?.let { return it }

        // 模糊匹配: command 包含在文件名中
        instructions.firstOrNull { inst ->
            inst.fileName.lowercase().contains(command.lowercase())
        }?.let { return it }

        // OpenClaw skill name 匹配
        instructions.firstOrNull { inst ->
            inst.metadata["skillName"]?.lowercase() == command.lowercase()
        }?.let { return it }

        return null
    }
}

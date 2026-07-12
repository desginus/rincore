package me.rerere.rikkahub.costguards

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/**
 * Phase 15 — Cost & loop guards, v1 surface.
 *
 * One LLM tool: [checkTokenUsageTool]. Returns the running token totals for a given
 * conversation plus the assistant's soft/hard token caps and a budget classification.
 */

private fun errEnv(error: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", error); put("detail", detail) }.toString()))

fun checkTokenUsageTool(
    settingsStore: SettingsStore,
    conversationRepo: ConversationRepository,
): Tool = Tool(
    name = "检查用量",
    description = "读取当前会话的输入+输出 token 累计用量，与助手的软/硬上限对比。UNDER_SOFT=安全、WARN=接近上限应减速、OVER_HARD=已超限应停止。如未设置上限返回 NO_BUDGET。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("conversation_id", buildJsonObject {
                    put("type", "string")
                    put("description", "会话 UUID; 省略则使用助手当前聊天")
                })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val rawConvId = params["conversation_id"]?.jsonPrimitive?.contentOrNull
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getCurrentAssistant()
        val convId = rawConvId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val conv = if (convId != null) {
            conversationRepo.getConversationById(convId)
        } else {
            conversationRepo.getRecentConversations(assistant.id, 1).firstOrNull()
        } ?: return@Tool errEnv("no_conversation", "未找到可计算用量的会话")
        val snapshot = TokenBudgetTracker.snapshot(
            conversation = conv,
            softCap = assistant.tokenBudgetSoftCap,
            hardCap = assistant.tokenBudgetHardCap,
        )
        val payload = buildJsonObject {
            put("conversation_id", conv.id.toString())
            put("input_tokens", snapshot.totals.inputTokens)
            put("output_tokens", snapshot.totals.outputTokens)
            put("total_tokens", snapshot.totals.totalTokens)
            put("per_message_max", snapshot.totals.perMessageMax)
            put("message_count", snapshot.totals.messageCount)
            if (snapshot.softCap != null) put("soft_cap", snapshot.softCap)
            if (snapshot.hardCap != null) put("hard_cap", snapshot.hardCap)
            put("status", snapshot.status.name)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

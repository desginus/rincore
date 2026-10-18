/* 【域 I·数据存储】 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.event

/* ───【原版对齐】AppEvent.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** 聊天生成过程中的流式更新，由 ChatNotificationManager 消费用于 Live Update 通知。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    ) : AppEvent()

    /**
     * 聊天生成结束（完成、失败或取消）。
     * [contentPreview] 为 null 时仅取消 Live Update 通知，不发送完成通知。
     */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
        /** v4.8.88: 是否子代理会话 —— 通知系统据此分流（子代理的提示归子代理）。 */
        val isSubAgent: Boolean = false,
    ) : AppEvent()

    /**
     * v4.8.88: 子代理终态（完成/失败/超时/预算熔断）。
     * 由 [me.rerere.rikkahub.subagent.SubAgentEngine] 发射，ChatNotificationManager 消费 ——
     * 与主模型的「聊天完成」通知**分频道、分文案、分跳转**。
     */
    data class SubAgentFinished(
        val runId: String,
        val parentConversationId: String?,
        val label: String,
        val status: String,
        val error: String?,
        val resultPreview: String?,
        val tokensIn: Long,
        val tokensOut: Long,
    ) : AppEvent()
}

/* 【域 B·AI 传输】GenerationChunk.kt — 从 GenerationHandler 拆出 (4.8.12 重构)
 * 职责: 生成流事件的类型定义 (Messages 消息快照 / LoadedDomains 域加载通知)
 * 消费方: ChatService (流收集), TranslatorVM 链路
 * 基线: 自研 | 地图: docs/APP_MAP.md §B
 * ───────────────────────────────────────────────────────────────*/

package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessage

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk

    data class LoadedDomains(
        val domains: List<String> // v3.6.10: 保序 (加载顺序 — tools 前缀稳定)
    ) : GenerationChunk
}

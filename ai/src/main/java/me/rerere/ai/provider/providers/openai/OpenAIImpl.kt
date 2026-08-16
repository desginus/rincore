/**
 * OpenAI Provider 实现 — 模块: A. 传输链 / ai
 *
 * 职责: ChatCompletions / Responses API 分发 + 参数组装 + 流式/非流式。
 * 基线: 回滚自 3.2.2 (v3.5.0)。
 *
 * 问题定位: 请求被拒/参数错误/流式中断 → 查本文件 + ChatCompletionsAPI + ResponseAPI
 */
package me.rerere.ai.provider.providers.openai


/* ───【原版对齐】OpenAIImpl.kt | 差异 ±15 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.flow.Flow
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage

interface OpenAIImpl {
    suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>
}

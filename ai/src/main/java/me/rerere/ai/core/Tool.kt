package me.rerere.ai.core

/* ───【原版对齐】Tool.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}

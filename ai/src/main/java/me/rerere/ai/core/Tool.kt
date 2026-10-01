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
        // v4.8.80: MCP 原始 schema 透传 — $defs/$schema 关键字。
        // 修复悬空 $ref: 旧转换丢弃 $defs, 出站 schema 的 "$ref": "#/$defs/X"
        // 指向不存在目标 → 严格网关 400 "Pointer '/$defs/X' does not exist"。
        @SerialName("\$defs") val defs: JsonObject? = null,
        @SerialName("\$schema") val schema: String? = null,
    ) : InputSchema()
}

/** v4.8.80: 出站 schema 兜底 — parameters 缺省 (null) 时给最小合法对象 schema;
 *  修复严格网关 zod 校验 "Invalid input: expected record, received null"
 *  (此前默认 lambda { null } 的工具直接把 "parameters": null 发出去)。 */
fun Tool.effectiveParameters(): InputSchema =
    parameters() ?: InputSchema.Obj(properties = JsonObject(emptyMap()))

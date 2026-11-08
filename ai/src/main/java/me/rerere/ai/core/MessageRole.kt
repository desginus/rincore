package me.rerere.ai.core

/* ───【原版对齐】MessageRole.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MessageRole {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("tool")
    TOOL,
    ;

    companion object {
        /**
         * ★v5.3.61（对齐上游 3.1.2）宽松解析 role 字符串。
         *
         * 部分中转/网关会返回空字符串或 `"AI"`、`"bot"` 这类非标准值，而 `valueOf()` 遇到
         * 不认识的名字会抛 `IllegalArgumentException` —— 直接**崩溃在解析响应**这一步
         *（用户报的「部分中转接口解析崩溃」）。匹配不到返回 null，由调用方回落 ASSISTANT。
         */
        fun fromValueOrNull(value: String?): MessageRole? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/* ───【原版对齐】UIMessageAnnotation.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.7 StreamChunk 体系移植 — 架构代差清除)
 * ───────────────────────────────────────────────────────────────*/

package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()
}

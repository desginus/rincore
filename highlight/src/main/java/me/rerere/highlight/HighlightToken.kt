package me.rerere.highlight


/* ───【原版对齐】HighlightToken.kt | 差异 ±0 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
sealed interface HighlightToken {
    val content: String

    data class Plain(
        override val content: String,
    ) : HighlightToken

    data class Styled(
        override val content: String,
        val type: String,
    ) : HighlightToken
}

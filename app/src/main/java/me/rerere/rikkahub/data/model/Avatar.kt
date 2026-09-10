package me.rerere.rikkahub.data.model

/* ───【原版对齐】Avatar.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.serialization.Serializable

@Serializable
sealed class Avatar {
    @Serializable
    data object Dummy : Avatar()

    @Serializable
    data class Emoji(val content: String) : Avatar()

    @Serializable
    data class Image(val url: String) : Avatar()
}

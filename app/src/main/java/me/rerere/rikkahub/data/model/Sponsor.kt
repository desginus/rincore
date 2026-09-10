package me.rerere.rikkahub.data.model

/* ───【原版对齐】Sponsor.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.serialization.Serializable

@Serializable
data class Sponsor(
    val userName: String,
    val avatar: String,
    val amount: String,
)

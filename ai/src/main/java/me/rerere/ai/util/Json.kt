package me.rerere.ai.util

/* ───【原版对齐】Json.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.serialization.json.Json

@PublishedApi
internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

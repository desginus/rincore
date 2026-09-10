package me.rerere.rikkahub.utils

/* ───【原版对齐】Json.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

val JsonInstant by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

val JsonInstantPretty by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}

val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

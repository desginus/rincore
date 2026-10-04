/* 【域 L·基础设施】 | 地图: docs/APP_MAP.md §L */
package me.rerere.rikkahub.utils

/* ───【自研】KeyMask.kt — API Key 掩码统一实现（v4.8.97 和谐化）
 * 原有两处私有实现 (ApiKeyQuickSwitcher / UsagePage) 展示口径不一：
 * ≤8 位一处显示 "****"、一处原样暴露；长键一处 4+4、一处 6+4。
 * 统一为单一实现：≤8 → "****"；更长 → 前 6 + "****" + 后 4。
 * ───────────────────────────────────────────────────────────────*/

/** API Key 掩码（展示用，任何日志/UI 输出密钥一律走本函数） */
fun maskApiKey(key: String): String =
    if (key.length <= 8) "****" else key.take(6) + "****" + key.takeLast(4)

/* 【域 L·基础设施】 | 地图: docs/APP_MAP.md §L */
package me.rerere.rikkahub.data.firebase


/* ───【自研】StubAnalytics.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.os.Bundle

/**
 * No-op stub replacing FirebaseAnalytics for RinCore (Firebase removed).
 */
object StubAnalytics {
    fun logEvent(name: String, params: Bundle?) {}
}

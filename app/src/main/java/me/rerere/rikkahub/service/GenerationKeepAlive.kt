/* 【域 A·对话核心】GenerationKeepAlive.kt — 从 ChatService 拆出 (4.8.12 重构)
 * 职责: 生成期间的系统级保活 — PARTIAL WakeLock (CPU, v3.6.15) +
 *       WifiLock (射频, v4.7.18; 防息屏 WiFi 休眠致断流)。
 * 常用改动: 保活时长/模式 → acquire 函数; 新增保活维度 → 同模式加一对
 * 问题定位: 切后台断流/息屏断流 → 本文件 + 调用点 (ChatService 生成链)
 * 基线: 自研 (v3.6.15 + v4.7.18 轮次) | 地图: docs/APP_MAP.md §A
 * ───────────────────────────────────────────────────────────────*/

package me.rerere.rikkahub.service

import android.content.Context

/**
 * v3.6.15: 生成时 PARTIAL WakeLock — 切后台/锁屏时 CPU 保持,
 * 网络读不因 Doze 挂起 (SSE 流式稳定); 15min 超时兜底防泄漏。
 */
internal fun acquireGenWakeLock(context: Context): android.os.PowerManager.WakeLock? {
    return runCatching {
        val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return null
        val wl = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "rincore:generation"
        )
        wl.setReferenceCounted(false)
        wl.acquire(15 * 60 * 1000L)
        wl
    }.getOrNull()
}

internal fun releaseGenWakeLock(wl: android.os.PowerManager.WakeLock?) {
    if (wl == null) return
    runCatching { if (wl.isHeld) wl.release() }
}

/**
 * v4.7.18: 生成时 WifiLock — 防息屏后 WiFi 射频休眠导致的中途断流
 * (用户: 电脑上同类断流与网卡有关; 手机对应物 = WiFi 省电射频休眠。
 *  生成期间保持 WiFi 高性能模式, 流式读取不被打断)。
 */
@Suppress("DEPRECATION")
internal fun acquireGenWifiLock(context: Context): android.net.wifi.WifiManager.WifiLock? {
    return runCatching {
        val wm = context.getSystemService(android.net.wifi.WifiManager::class.java) ?: return null
        val wl = wm.createWifiLock(
            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "rincore:generation"
        )
        wl.setReferenceCounted(false)
        wl.acquire()
        wl
    }.getOrNull()
}

internal fun releaseGenWifiLock(wl: android.net.wifi.WifiManager.WifiLock?) {
    if (wl == null) return
    runCatching { if (wl.isHeld) wl.release() }
}

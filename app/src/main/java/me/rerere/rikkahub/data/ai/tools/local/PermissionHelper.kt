package me.rerere.rikkahub.data.ai.tools.local

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 运行时权限辅助工具 */
object PermissionHelper {
    fun hasRuntime(ctx: Context, perms: List<String>): Boolean =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /** 是否拥有"所有文件访问"权限 (Android 11+ MANAGE_EXTERNAL_STORAGE) */
    fun hasAllFilesAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /** 打开系统设置中"所有文件访问"页面 */
    fun allFilesAccessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 使用情况访问 (App usage stats) — 屏幕使用时间等工具依赖 */
    fun hasUsageStatsAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, ctx.packageManager.getApplicationInfo(ctx.packageName, 0).uid, ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, ctx.packageManager.getApplicationInfo(ctx.packageName, 0).uid, ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 打开"使用情况访问"设置页 */
    fun usageAccessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 精确闹钟权限 (Android 12+) — 定时任务 AlarmManager 通道的保障 */
    fun hasExactAlarmAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else {
            true
        }

    /** 打开精确闹钟权限设置页 */
    fun exactAlarmIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 电池优化豁免 — 后台任务可靠性的关键 (小米省电策略会延迟后台执行) */
    fun hasBatteryOptimizationExemption(ctx: Context): Boolean {
        val pwm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pwm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** 打开电池优化豁免请求 (系统弹窗直接请求, 无需进长列表) */
    fun batteryOptimizationIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

package me.rerere.rikkahub.data.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper

/**
 * 权限自动发现清单 — 运行时扫描 <uses-permission> 声明 + 关键特殊权限,
 * 按授予方式分组并给出引导动作。保活关键权限 (精确闹钟/电池优化) 置顶。
 *
 * 自包含版 (裁剪 rikkahub-agent 的 CapabilityCatalog 依赖)。
 */
object PermissionInventory {

    enum class Group { SpecialAccess, Runtime, AutoGranted }

    enum class Status { GRANTED, DENIED, AUTO_GRANTED }

    sealed class GrantAction {
        /** 无需操作 — 安装时自动授予 */
        object None : GrantAction()
        /** 运行时权限请求 */
        data class Runtime(val permission: String) : GrantAction()
        /** 跳转系统设置页 */
        data class SystemSettings(val intent: Intent) : GrantAction()
    }

    data class Row(
        val id: String,
        val label: String,
        val description: String,
        val status: Status,
        val group: Group,
        val grant: GrantAction,
        val statusLabel: String? = null,
    )

    fun build(context: Context): List<Row> {
        val rows = mutableListOf<Row>()
        // 保活关键权限 — 后台任务可靠性保障链 (置顶)
        rows += exactAlarmRow(context)
        rows += batteryOptimizationRow(context)
        rows += notificationRow(context)
        rows += usageStatsRow(context)
        // 深度权限: 后台定位 (工作流地理围栏/位置工具后台使用)
        rows += backgroundLocationRow(context)
        // 其他特殊权限
        rows += overlayRow(context)
        rows += writeSettingsRow(context)
        rows += allFilesRow(context)
        // 声明权限扫描 (运行时权限自动发现)
        for (perm in readDeclaredPermissions(context)) {
            rows += classify(context, perm) ?: continue
        }
        return rows.distinctBy { it.id }.sortedWith(
            compareBy({ it.group.ordinal }, { if (it.status == Status.DENIED) 0 else 1 }, { it.label })
        )
    }

    private fun row(
        id: String, label: String, description: String, granted: Boolean,
        group: Group, grant: GrantAction,
    ) = Row(id, label, description, if (granted) Status.GRANTED else Status.DENIED, group, grant)

    private fun exactAlarmRow(context: Context) = row(
        "exact_alarm", "精确闹钟", "定时任务/闹钟的 AlarmManager 精确触发通道 (被杀后台后仍准时执行)",
        PermissionHelper.hasExactAlarmAccess(context), Group.SpecialAccess,
        GrantAction.SystemSettings(PermissionHelper.exactAlarmIntent(context))
    )

    private fun batteryOptimizationRow(context: Context) = row(
        "battery_optimization", "电池优化豁免", "后台任务不被省电策略延迟 (小米澎湃需关闭智能限制)",
        PermissionHelper.hasBatteryOptimizationExemption(context), Group.SpecialAccess,
        GrantAction.SystemSettings(PermissionHelper.batteryOptimizationIntent(context))
    )

    private fun notificationRow(context: Context) = row(
        Manifest.permission.POST_NOTIFICATIONS, "通知", "闹钟提醒/后台任务结果通知",
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        Group.Runtime, GrantAction.Runtime(Manifest.permission.POST_NOTIFICATIONS)
    )

    private fun usageStatsRow(context: Context) = row(
        "usage_stats", "使用情况访问", "屏幕使用时间等工具依赖",
        PermissionHelper.hasUsageStatsAccess(context), Group.SpecialAccess,
        GrantAction.SystemSettings(PermissionHelper.usageAccessIntent(context))
    )

    private fun backgroundLocationRow(context: Context): Row {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        return row(
            "background_location", "后台定位", "工作流地理围栏/位置工具在后台运行时使用 (Android 11+ 需系统设置开启)",
            granted, Group.SpecialAccess,
            GrantAction.SystemSettings(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent("android.settings.APP_LOCATION_SETTINGS", Uri.parse("package:${context.packageName}"))
                } else {
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                }
            )
        )
    }

    private fun overlayRow(context: Context) = row(
        Manifest.permission.SYSTEM_ALERT_WINDOW, "悬浮窗", "AI 工作状态悬浮提示",
        Settings.canDrawOverlays(context), Group.SpecialAccess,
        GrantAction.SystemSettings(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    )

    private fun writeSettingsRow(context: Context) = row(
        Manifest.permission.WRITE_SETTINGS, "修改系统设置", "亮度调节等系统工具",
        Settings.System.canWrite(context), Group.SpecialAccess,
        GrantAction.SystemSettings(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    )

    private fun allFilesRow(context: Context) = row(
        Manifest.permission.MANAGE_EXTERNAL_STORAGE, "所有文件访问", "管理共享存储文件",
        PermissionHelper.hasAllFilesAccess(context), Group.SpecialAccess,
        GrantAction.SystemSettings(PermissionHelper.allFilesAccessIntent(context))
    )

    private fun readDeclaredPermissions(context: Context): List<String> {
        val pm = context.packageManager
        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        return info.requestedPermissions?.toList() ?: emptyList()
    }

    private fun classify(context: Context, perm: String): Row? {
        // 已在显式行处理的权限跳过
        if (perm in listOf(
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                Manifest.permission.WRITE_SETTINGS,
                Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Manifest.permission.SCHEDULE_EXACT_ALARM,
                Manifest.permission.USE_EXACT_ALARM,
            )
        ) return null

        // 运行时权限 — 按 SDK 版本过滤
        val pm = context.packageManager
        val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        val label = labelOrHumanize(perm)
        val desc = descriptionOrDefault(perm)
        return Row(
            id = perm,
            label = label,
            description = desc,
            status = if (granted) Status.GRANTED else Status.DENIED,
            group = Group.Runtime,
            grant = GrantAction.Runtime(perm),
        )
    }

    private fun labelOrHumanize(perm: String): String {
        val known = mapOf(
            Manifest.permission.READ_CALENDAR to "读取日历",
            Manifest.permission.WRITE_CALENDAR to "写入日历",
            Manifest.permission.ACCESS_FINE_LOCATION to "精确位置",
            Manifest.permission.RECORD_AUDIO to "麦克风",
            Manifest.permission.CAMERA to "相机",
            Manifest.permission.READ_CONTACTS to "读取联系人",
        )
        known[perm]?.let { return it }
        return perm.substringAfterLast(".").replace("_", " ").lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    private fun descriptionOrDefault(perm: String): String = when (perm) {
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> "日历查询/创建工具"
        Manifest.permission.ACCESS_FINE_LOCATION -> "位置相关工具"
        else -> "运行时权限"
    }
}

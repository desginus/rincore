package me.rerere.rikkahub.data.appmode

import android.content.Context

/**
 * ═══ 双模式（v5.3.47）═══
 *
 * 同一个 APK 里装着两个**完全独立**的应用，只共用一个外壳与一套 Linux 沙箱：
 *
 * - [WORK]（工作模式）= RinCore —— 现有的办公/工作区能力，代码在 `me.rerere.rikkahub.*`
 * - [CHAT]（聊天模式）= Mika —— 个人助理，代码在 `com.wangzan101.mika.*`
 *
 * **两者不共享任何运行时状态**：各自的依赖容器、数据库、DataStore、偏好设置、
 * 技能、对话、MCP 服务器全部独立。唯一共享的是：
 *  1. 这个 APK（同一份安装、同一个图标、同一个应用进程）；
 *  2. 工作区目录（`files/workspaces/`，即 proot 的 Linux 沙箱）。
 *
 * 状态存在**独立的 SharedPreferences**（`rincore_shell`），**不放进 Settings** ——
 * Settings 是 RinCore 的 DataStore，属于工作模式自己的数据；壳层状态如果寄存在里面，
 * 就等于两个应用又开始纠缠了。
 */
enum class AppMode {
    WORK,
    CHAT,
    ;

    companion object {
        /** 从存储解析；未知值一律回落到 [WORK]（宁可回工作模式，也不要把用户丢进没就绪的界面）。 */
        fun parse(raw: String?): AppMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: WORK
    }
}

/**
 * 壳层状态的唯一入口。
 *
 * 刻意用 `SharedPreferences` 而不是 DataStore：这里只有一个字符串，且要在
 * `RouteActivity.onCreate` 之前同步读到（DataStore 是异步的，首帧拿不到值会导致
 * 先渲染错模式再闪一下）。**同步读、极低频写**，正是 SharedPreferences 的合适场景。
 */
object AppModeStore {
    private const val PREFS = "rincore_shell"
    private const val KEY_MODE = "app_mode"

    fun read(context: Context): AppMode {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppMode.parse(prefs.getString(KEY_MODE, null))
    }

    fun write(context: Context, mode: AppMode) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    /** 切换并返回新模式。 */
    fun toggle(context: Context): AppMode {
        val next = if (read(context) == AppMode.WORK) AppMode.CHAT else AppMode.WORK
        write(context, next)
        return next
    }
}

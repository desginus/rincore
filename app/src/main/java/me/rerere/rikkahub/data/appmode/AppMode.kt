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

    /**
     * ★★「上次在聊天模式没起来」的哨兵。
     *
     * 场景：用户切到聊天模式 → 应用关闭/被杀/崩溃 → 下次启动仍读回 CHAT →
     * 如果 CHAT 这一侧起不来（Mika 初始化失败、渲染崩溃……），用户就**再也进不去应用**。
     * 这种「一个模式挂了导致整个软件打不开」是绝不可接受的。
     *
     * 机制：进入 CHAT 时置位 → CHAT 成功组合后清除 →
     * 下次启动若发现它**还在**，说明上一次 CHAT 没能跑起来 → **自动回退工作模式**。
     *
     * 宁可回工作模式（至少能用），也不要把用户关在一个起不来的界面里。
     */
    private const val KEY_CHAT_PENDING = "chat_mode_pending"

    fun read(context: Context): AppMode {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppMode.parse(prefs.getString(KEY_MODE, null))
    }

    /**
     * v5.3.48 ★改用 `commit()` 而不是 `apply()`。
     *
     * `apply()` 是异步落盘：用户刚切完模式就被系统杀进程（后台清理很常见），
     * 写入可能没落盘 → 下次打开还是旧模式 —— 正是用户说的
     * 「上次在一个模式关闭的，开了应用又到另一个模式」。
     * 这里一次切换只写一个字符串，同步写代价可忽略（毫秒级），换来确定性。
     */
    fun write(context: Context, mode: AppMode): Boolean {
        val ok = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .putBoolean(KEY_CHAT_PENDING, mode == AppMode.CHAT)
            .commit()
        // v5.3.49：`commit()` 返回 false 表示落盘失败（磁盘满/权限）。旧实现忽略返回值 ——
        // 用户切了模式、界面上像是切了，重启却回到旧模式，且没有任何线索。
        // 失败不会破坏已有数据（SharedPreferences 写入是原子的），所以只记录、不中断。
        if (!ok) {
            android.util.Log.w("AppModeStore", "写入模式失败（$mode）—— 本次切换可能在重启后失效")
        }
        return ok
    }

    /** CHAT 首次成功渲染后调用 —— 清除「没起来」哨兵。 */
    fun markChatHealthy(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CHAT_PENDING, false)
            .apply()
    }

    /** 上次进入 CHAT 后是否没能正常渲染（= 应该自动回退工作模式）。 */
    fun isChatPending(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CHAT_PENDING, false)

    /** 切换并返回新模式。 */
    fun toggle(context: Context): AppMode {
        val next = if (read(context) == AppMode.WORK) AppMode.CHAT else AppMode.WORK
        write(context, next)
        return next
    }
}

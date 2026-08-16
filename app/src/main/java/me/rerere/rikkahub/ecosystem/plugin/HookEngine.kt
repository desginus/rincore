package me.rerere.rikkahub.ecosystem.plugin


/* ───【自研】HookEngine.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ecosystem.plugin.ClaudePluginParser.HookDef
import java.io.File

/**
 * Hooks 执行引擎 — 事件驱动的插件生命周期管理器。
 *
 * 支持事件:
 * - onMessage: 用户每次发送消息时触发
 * - onFileChange: workspace_shell 写入文件后触发
 * - onSessionStart: 新对话开始时触发
 * - onToolExec: 工具执行前后触发
 * - onPluginInstall: 插件安装/卸载时触发
 */
object HookEngine {
    private const val TAG = "HookEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class HookEvent(
        val type: String,
        val payload: Map<String, String> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val _events = MutableSharedFlow<HookEvent>(replay = 0, extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private var registeredHooks: List<HookDef> = emptyList()

    fun refresh(pluginDirs: List<File>) {
        registeredHooks = pluginDirs.flatMap { dir ->
            ClaudePluginParser.parsePluginDir(dir).hooks
        }
        Log.i(TAG, "Registered ${registeredHooks.size} hooks from ${pluginDirs.size} plugins")
    }

    fun dispatch(event: HookEvent) {
        scope.launch {
            _events.emit(event)
            val matching = registeredHooks.filter { it.event == event.type }
            matching.forEach { hook ->
                Log.i(TAG, "Hook triggered: ${hook.event} → ${hook.action}")
                // action 可以是: shell:command、skill:name、mcp:connect、log:message
                executeHookAction(hook.action, event)
            }
        }
    }

    private fun executeHookAction(action: String, event: HookEvent) {
        try {
            when {
                action.startsWith("shell:") -> {
                    val cmd = action.removePrefix("shell:")
                    // 通过 Runtime.exec 或标记为待处理
                    Log.i(TAG, "Hook shell: $cmd (event: ${event.type})")
                    // 子进程执行留给 workspace_shell
                }
                action.startsWith("skill:") -> {
                    val skillName = action.removePrefix("skill:")
                    Log.i(TAG, "Hook skill: $skillName (event: ${event.type})")
                }
                action.startsWith("log:") -> {
                    val msg = action.removePrefix("log:")
                    Log.i(TAG, "Hook log: $msg")
                }
                else -> Log.w(TAG, "Unknown hook action: $action")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hook action failed: $action — ${e.message}")
        }
    }
}

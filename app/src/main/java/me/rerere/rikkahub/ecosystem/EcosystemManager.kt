package me.rerere.rikkahub.ecosystem

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.core.Tool
import java.io.File

/**
 * 生态系统管理器 — 跨生态指令桥接中心。
 *
 * 职责:
 * - 从多个根目录扫描所有生态的指令文件
 * - 维护已启用/已禁用的指令集合
 * - 为 ChatService 提供统一的 Tool 列表
 */
object EcosystemManager {
    private const val TAG = "EcosystemManager"
    private const val ENABLED_PREFS = "ecosystem_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var context: Context? = null
    private var rootDirs: List<File> = emptyList()

    private val _instructions = MutableStateFlow<List<EcosystemInstruction>>(emptyList())
    val instructions: StateFlow<List<EcosystemInstruction>> = _instructions.asStateFlow()

    private val _enabledIds = MutableStateFlow<Set<String>>(emptySet())
    val enabledIds: StateFlow<Set<String>> = _enabledIds.asStateFlow()

    private val _scannedDirs = MutableStateFlow<List<String>>(emptyList())
    val scannedDirs: StateFlow<List<String>> = _scannedDirs.asStateFlow()

    /**
     * 初始化。
     * @param ctx Application context
     * @param extraDirs 额外扫描目录 (如 ~/.openclaw/workspace, 项目根目录等)
     */
    fun initialize(ctx: Context, extraDirs: List<String> = emptyList()) {
        context = ctx.applicationContext
        val dirs = mutableListOf<File>()

        // 默认扫描目录
        dirs.add(File(ctx.filesDir, "openclaw/workspace"))
        dirs.add(File(ctx.filesDir, "ecosystem"))

        // 用户配置的额外目录
        extraDirs.forEach { dirs.add(File(it)) }

        rootDirs = dirs
        _scannedDirs.value = dirs.map { it.absolutePath }
        loadEnabledSet()
        // 首次扫描 (同步, 确保第一个对话就能用)
        refreshBlocking()
    }

    /**
     * 重新扫描所有根目录 (后台线程, UI 触发)。
     */
    fun refresh() {
        scope.launch {
            try {
                refreshBlocking()
            } catch (e: Exception) {
                Log.e(TAG, "Refresh failed: ${e.message}", e)
            }
        }
    }

    private fun refreshBlocking() {
        val all = EcosystemScanner.scanAll(rootDirs)
        _instructions.value = all
        Log.i(TAG, "Scanned ${all.size} instructions from ${rootDirs.size} roots")
    }

    /**
     * 获取已启用的 Tool 列表 (含发现工具)。
     * 调用方: ChatService
     */
    fun getEnabledTools(): List<Tool> {
        val enabledInstrs = _instructions.value.filter { isEnabled(idOf(it)) }
        val tools = enabledInstrs.map { EcosystemBridge.toTool(it) }

        // 追加发现工具
        return tools + EcosystemBridge.createDiscoveryTool(enabledInstrs)
    }

    /**
     * 启用/禁用某个指令。
     */
    fun setEnabled(id: String, enabled: Boolean) {
        val current = _enabledIds.value.toMutableSet()
        if (enabled) current.add(id) else current.remove(id)
        _enabledIds.value = current
        saveEnabledSet()
    }

    fun isEnabled(id: String): Boolean = _enabledIds.value.contains(id)

    /**
     * 生成指令唯一 ID。
     */
    fun idOf(inst: EcosystemInstruction): String {
        return "${inst.source.name}_${inst.fileName}_${inst.displayPath.hashCode()}"
    }

    private fun loadEnabledSet() {
        val prefs = context?.getSharedPreferences(ENABLED_PREFS, Context.MODE_PRIVATE)
        _enabledIds.value = prefs?.getStringSet("ids", emptySet()) ?: emptySet()
    }

    private fun saveEnabledSet() {
        val prefs = context?.getSharedPreferences(ENABLED_PREFS, Context.MODE_PRIVATE)
        prefs?.edit()?.putStringSet("ids", _enabledIds.value)?.apply()
    }

    // ═══ GitHub Token ═══

    fun setGitHubToken(token: String) {
        context?.getSharedPreferences("eco_tokens", Context.MODE_PRIVATE)
            ?.edit()?.putString("github_token", token)?.apply()
    }

    fun getGitHubToken(): String {
        return context?.getSharedPreferences("eco_tokens", Context.MODE_PRIVATE)
            ?.getString("github_token", "") ?: ""
    }
}


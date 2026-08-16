package me.rerere.rikkahub.openclaw


/* ───【自研】ClawSkillManager.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.core.Tool
import java.io.File

/**
 * OpenClaw 技能管理器 — 单例。
 *
 * 职责:
 * - 扫描 workspace/skills/ 目录
 * - 维护已启用/已禁用的技能集合
 * - 为 ChatService 提供 Tool 列表
 */
object ClawSkillManager {
    private const val TAG = "ClawSkillManager"
    private const val ENABLED_PREFS = "claw_skills_enabled"

    private var skillsDir: File? = null
    private var context: Context? = null

    private val _skills = MutableStateFlow<List<ClawSkill>>(emptyList())
    val skills: StateFlow<List<ClawSkill>> = _skills.asStateFlow()

    private val _enabledNames = MutableStateFlow<Set<String>>(emptySet())
    val enabledNames: StateFlow<Set<String>> = _enabledNames.asStateFlow()

    private val _workspaceRoot = MutableStateFlow<String>("")
    val workspaceRoot: StateFlow<String> = _workspaceRoot.asStateFlow()

    /**
     * 初始化: 必须在 Application.onCreate 中调用。
     */
    fun initialize(ctx: Context, workspacePath: String?) {
        context = ctx.applicationContext
        val path = workspacePath ?: defaultWorkspacePath(ctx)
        _workspaceRoot.value = path
        skillsDir = File(path, "skills")
        loadEnabledSet()
        refresh()
    }

    private fun defaultWorkspacePath(ctx: Context): String {
        return File(ctx.filesDir, "openclaw/workspace").absolutePath
    }

    /**
     * 重新扫描技能目录。
     */
    fun refresh() {
        val dir = skillsDir
        if (dir == null || !dir.isDirectory) {
            Log.w(TAG, "Skills dir not found: ${dir?.absolutePath}, creating...")
            dir?.mkdirs()
            _skills.value = emptyList()
            return
        }
        val loaded = ClawSkillLoader.scanDirectory(dir)
        _skills.value = loaded
        Log.i(TAG, "Scanned ${loaded.size} skills from ${dir.absolutePath}")
    }

    /**
     * 启用/禁用技能。
     */
    fun setEnabled(name: String, enabled: Boolean) {
        val current = _enabledNames.value.toMutableSet()
        if (enabled) current.add(name) else current.remove(name)
        _enabledNames.value = current
        saveEnabledSet()
    }

    fun isEnabled(name: String): Boolean = _enabledNames.value.contains(name)

    /**
     * 获取已启用的技能 → Tool 列表。
     */
    fun getEnabledTools(): List<Tool> {
        return _skills.value
            .filter { isEnabled(it.name) }
            .map { ClawSkillBridge.toTool(it) }
    }

    /**
     * 获取所有技能 → Tool 列表 (用于展示)。
     */
    fun getAllTools(): List<Tool> {
        return _skills.value.map { ClawSkillBridge.toTool(it, enabled = isEnabled(it.name)) }
    }

    /**
     * 检查指定名称的技能是否存在且已启用。
     */
    fun hasSkill(name: String): Boolean {
        return _skills.value.any { it.name == name } && isEnabled(name)
    }

    private fun loadEnabledSet() {
        val prefs = context?.getSharedPreferences(ENABLED_PREFS, Context.MODE_PRIVATE)
        val set = prefs?.getStringSet("names", emptySet()) ?: emptySet()
        _enabledNames.value = set
    }

    private fun saveEnabledSet() {
        val prefs = context?.getSharedPreferences(ENABLED_PREFS, Context.MODE_PRIVATE)
        prefs?.edit()?.putStringSet("names", _enabledNames.value)?.apply()
    }
}

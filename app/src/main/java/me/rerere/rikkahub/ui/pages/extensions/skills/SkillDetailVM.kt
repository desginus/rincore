/* 【域 A·对话核心】 — 页面 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.extensions.skills
/* ───【原版对齐】SkillDetailVM.kt | 基线 2.5.5 (v4.8.64 移植: readOnly 只读判定 + 文件树由 walkTopDown 扁平清单构建; SkillFile 改为 relativePath+size)
 * 来源: 原版 2.5.5 适配移植 | 差异: 仅工程标注
 * ───────────────────────────────────────────────────────────────*/

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillPaths

data class SkillFile(
    val relativePath: String,
    val size: Long,
) {
    val name: String get() = relativePath.substringAfterLast('/')
}

sealed class SkillFileNode {
    data class FileNode(val skillFile: SkillFile) : SkillFileNode()
    data class DirNode(
        val name: String,
        val relativePath: String,
        val children: List<SkillFileNode>,
    ) : SkillFileNode()
}

class SkillDetailVM(
    private val skillManager: SkillManager,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    private val _readOnly = MutableStateFlow(false)
    val readOnly = _readOnly.asStateFlow()

    private var skillName = ""
    private var skill: SkillMetadata? = null

    fun init(name: String) {
        if (skillName == name) return
        skillName = name
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val skill = skillManager.findSkill(skillName) ?: return@launch
            this@SkillDetailVM.skill = skill
            _readOnly.value = skill.builtin
            val files = skill.skillDir.walkTopDown()
                .filter { it.isFile }
                .map { SkillFile(it.relativeTo(skill.skillDir).invariantSeparatorsPath, it.length()) }
                .toList()
            _tree.value = buildTree(files, prefix = "")
        }
    }

    private fun buildTree(files: List<SkillFile>, prefix: String): List<SkillFileNode> {
        val (direct, nested) = files.partition { !it.relativePath.removePrefix(prefix).contains('/') }
        val dirs = nested
            .groupBy { it.relativePath.removePrefix(prefix).substringBefore('/') }
            .toSortedMap()
            .map { (dirName, children) ->
                val dirPath = prefix + dirName
                SkillFileNode.DirNode(dirName, dirPath, buildTree(children, "$dirPath/"))
            }
        val fileNodes = direct
            .sortedWith(compareBy({ it.relativePath != "SKILL.md" }, { it.name }))
            .map { SkillFileNode.FileNode(it) }
        return dirs + fileNodes
    }

    fun readFile(skillFile: SkillFile): String {
        val skillDir = skill?.skillDir ?: return ""
        return SkillPaths.resolveSkillFile(skillDir, skillFile.relativePath)
            ?.takeIf { it.exists() }
            ?.readText()
            .orEmpty()
    }

    // Returns null on success, error message on failure
    fun saveFile(relativePath: String, content: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_readOnly.value) {
                withContext(Dispatchers.Main) { onResult("内置技能不可修改") }
                return@launch
            }
            if (relativePath == "SKILL.md") {
                val name = SkillFrontmatterParser.parse(content)["name"]
                if (name != skillName) {
                    withContext(Dispatchers.Main) { onResult("不允许修改技能名称（name 字段必须为 \"$skillName\"）") }
                    return@launch
                }
            }
            val success = skillManager.saveSkillFile(skillName, relativePath, content)
            loadFiles()
            withContext(Dispatchers.Main) { onResult(if (success) null else "保存失败") }
        }
    }

    fun deleteFile(skillFile: SkillFile, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = !_readOnly.value && skillManager.deleteSkillFile(skillName, skillFile.relativePath)
            if (success) loadFiles()
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }
}

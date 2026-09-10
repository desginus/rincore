package me.rerere.rikkahub.data.files

/* ───【原版对齐】SkillPaths.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import java.io.File

internal object SkillPaths {
    fun resolveSkillDir(skillsRoot: File, skillName: String): File? {
        if (skillName.isBlank()) return null
        if (skillName == "." || skillName == "..") return null
        if (skillName.contains('/') || skillName.contains('\\')) return null

        val canonicalRoot = skillsRoot.canonicalFile
        val canonicalDir = canonicalRoot.resolve(skillName).canonicalFile
        val parent = canonicalDir.parentFile ?: return null

        if (parent != canonicalRoot) return null
        if (!canonicalDir.isSameOrInside(canonicalRoot)) return null

        return canonicalDir
    }

    fun resolveSkillFile(skillDir: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null

        val canonicalSkillDir = skillDir.canonicalFile
        val canonicalTarget = canonicalSkillDir.resolve(relativePath).canonicalFile

        return canonicalTarget.takeIf { it.isSameOrInside(canonicalSkillDir) }
    }

    private fun File.isSameOrInside(root: File): Boolean {
        val rootPath = root.canonicalFile.path
        val currentPath = canonicalFile.path
        return currentPath == rootPath || currentPath.startsWith(rootPath + File.separator)
    }
}

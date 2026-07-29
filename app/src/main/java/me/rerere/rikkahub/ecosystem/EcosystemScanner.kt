package me.rerere.rikkahub.ecosystem

import android.util.Log
import java.io.File

/**
 * 多生态系统文件扫描器。
 * 从一个根目录出发, 发现所有已知生态的指令文件。
 */
object EcosystemScanner {
    private const val TAG = "EcosystemScanner"

    /**
     * 已知的文件发现规则。
     * Pair(first, second): first=相对路径模式, second=角色
     */
    private val DISCOVERY_RULES: Map<EcosystemSource, List<Pair<String, InstructionRole>>> = mapOf(
        EcosystemSource.OPENCLAW to listOf(
            "AGENTS.md" to InstructionRole.SYSTEM_RULES,
            "SOUL.md" to InstructionRole.PERSONALITY,
            "TOOLS.md" to InstructionRole.SKILL_MANUAL,
            "MEMORY.md" to InstructionRole.USER_DEFINED,
        ),
        EcosystemSource.CLAUDE_CODE to listOf(
            "CLAUDE.md" to InstructionRole.SYSTEM_RULES,
            ".claude/settings.json" to InstructionRole.PROJECT_CONTEXT,
        ),
        EcosystemSource.CURSOR to listOf(
            ".cursorrules" to InstructionRole.PROJECT_CONTEXT,
            ".cursor/instructions.md" to InstructionRole.PROJECT_CONTEXT,
        ),
        EcosystemSource.COPILOT to listOf(
            ".github/copilot-instructions.md" to InstructionRole.PROJECT_CONTEXT,
        ),
        EcosystemSource.WINDSURF to listOf(
            ".windsurfrules" to InstructionRole.PROJECT_CONTEXT,
        ),
    )

    /**
     * 扫描根目录下所有生态文件。
     */
    fun scan(rootDir: File): List<EcosystemInstruction> {
        if (!rootDir.isDirectory) return emptyList()

        val results = mutableListOf<EcosystemInstruction>()

        for ((source, rules) in DISCOVERY_RULES) {
            for ((relativePath, role) in rules) {
                val file = File(rootDir, relativePath)
                if (file.isFile && file.canRead()) {
                    try {
                        val content = file.readText()
                        if (content.isNotBlank()) {
                            results.add(
                                EcosystemInstruction(
                                    source = source,
                                    fileName = file.name,
                                    displayPath = file.absolutePath,
                                    content = content,
                                    role = role,
                                )
                            )
                            Log.d(TAG, "Found: ${source.displayName} / ${file.name} (${content.length}c)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read ${file.absolutePath}: ${e.message}")
                    }
                }
            }
        }

        // 扫描 OpenClaw 技能子目录 (使用 ClawSkillLoader 解析元数据)
        val skillsDir = File(rootDir, "skills")
        if (skillsDir.isDirectory) {
            me.rerere.rikkahub.openclaw.ClawSkillLoader.scanDirectory(skillsDir).forEach { skill ->
                val instruction = EcosystemInstruction(
                    source = EcosystemSource.OPENCLAW,
                    fileName = "SKILL.md (${skill.name})",
                    displayPath = skill.sourcePath + "/SKILL.md",
                    content = buildString {
                        appendLine("# ${skill.name} v${skill.version}")
                        if (skill.emoji != null) appendLine("图标: ${skill.emoji}")
                        appendLine("描述: ${skill.description}")
                        appendLine()
                        appendLine(skill.body)
                    },
                    role = InstructionRole.SKILL_MANUAL,
                    metadata = mapOf(
                        "skillName" to skill.name,
                        "version" to skill.version,
                    ),
                )
                results.add(instruction)
            }
        }

        // 扫描 .claude/commands/ 目录
        val claudeCommandsDir = File(rootDir, ".claude/commands")
        if (claudeCommandsDir.isDirectory) {
            claudeCommandsDir.listFiles()?.filter { it.isFile && it.extension == "md" }?.forEach { cmdFile ->
                try {
                    val content = cmdFile.readText()
                    if (content.isNotBlank()) {
                        results.add(
                            EcosystemInstruction(
                                source = EcosystemSource.CLAUDE_CODE,
                                fileName = "command: ${cmdFile.nameWithoutExtension}",
                                displayPath = cmdFile.absolutePath,
                                content = content,
                                role = InstructionRole.SKILL_MANUAL,
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        Log.i(TAG, "Scanned ${results.size} ecosystem files from ${rootDir.absolutePath}")
        return results
    }

    /**
     * 扫描多个根目录。
     */
    fun scanAll(rootDirs: List<File>): List<EcosystemInstruction> {
        return rootDirs.flatMap { scan(it) }
    }
}

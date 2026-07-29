package me.rerere.rikkahub.openclaw

import android.util.Log
import java.io.File

/**
 * SKILL.md 解析器。
 * 读取目录下的 SKILL.md, 解析 YAML frontmatter + Markdown body。
 */
object ClawSkillLoader {
    private const val TAG = "ClawSkillLoader"
    private const val FRONTMATTER_DELIM = "---"

    /**
     * 扫描目录, 加载所有包含 SKILL.md 的子目录作为技能。
     */
    fun scanDirectory(root: File): List<ClawSkill> {
        if (!root.isDirectory) return emptyList()
        val results = mutableListOf<ClawSkill>()

        root.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val skillMd = File(child, "SKILL.md")
                if (skillMd.isFile) {
                    parse(skillMd, child.absolutePath)?.let { results.add(it) }
                }
                results.addAll(scanDirectory(child))
            }
        }
        return results
    }

    /**
     * 解析单个 SKILL.md 文件。
     */
    fun parse(file: File, rootPath: String = file.parent ?: ""): ClawSkill? {
        return try {
            val raw = file.readText()
            parseContent(raw, rootPath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ${file.absolutePath}: ${e.message}")
            null
        }
    }

    /**
     * 从文本内容解析。
     */
    fun parseContent(content: String, sourcePath: String = ""): ClawSkill? {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith(FRONTMATTER_DELIM)) {
            Log.w(TAG, "No YAML frontmatter found in $sourcePath")
            return null
        }

        val endIdx = trimmed.indexOf(FRONTMATTER_DELIM, FRONTMATTER_DELIM.length)
        if (endIdx < 0) {
            Log.w(TAG, "Unclosed frontmatter in $sourcePath")
            return null
        }

        val frontmatter = trimmed.substring(FRONTMATTER_DELIM.length, endIdx).trim()
        val body = trimmed.substring(endIdx + FRONTMATTER_DELIM.length).trim()

        val parsed = parseFrontmatter(frontmatter)

        val name = parsed.topFields["name"] ?: run {
            Log.w(TAG, "SKILL.md missing required 'name' field")
            return null
        }
        val description = parsed.topFields["description"] ?: ""

        return ClawSkill(
            name = name,
            description = description,
            version = parsed.topFields["version"] ?: "0.0.0",
            body = body,
            emoji = parsed.topFields["emoji"],
            homepage = parsed.topFields["homepage"],
            requiresEnv = parsed.envRequirements,
            requiresBins = parsed.binRequirements,
            sourcePath = sourcePath,
        )
    }

    data class ParsedFrontmatter(
        val topFields: Map<String, String>,
        val envRequirements: List<EnvRequirement>,
        val binRequirements: List<String>,
    )

    /**
     * 缩进感知的 YAML frontmatter 解析。
     * 只解析顶层字段和 metadata.requires 子树。
     */
    private fun parseFrontmatter(fm: String): ParsedFrontmatter {
        val topFields = mutableMapOf<String, String>()
        val envs = mutableListOf<EnvRequirement>()
        val bins = mutableListOf<String>()

        val lines = fm.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("#")) { i++; continue }

            val indent = line.length - line.trimStart().length

            when {
                // 顶层字段 (indent 0, key: value)
                indent == 0 && trimmed.contains(":") && !trimmed.startsWith("metadata:") -> {
                    val colonIdx = trimmed.indexOf(':')
                    val key = trimmed.substring(0, colonIdx).trim()
                    val value = trimmed.substring(colonIdx + 1).trim().trim('"').trim('\'')
                    if (key.isNotBlank() && value.isNotBlank()) {
                        topFields[key] = value
                    }
                }
                // metadata: 进入 metadata 块
                indent == 0 && trimmed == "metadata:" -> {
                    i = parseMetadataBlock(lines, i + 1, envs, bins)
                }
            }
            i++
        }

        return ParsedFrontmatter(topFields, envs, bins)
    }

    /**
     * 解析 metadata 块 (indent ≥ 2)。
     * 返回处理到的行索引。
     */
    private fun parseMetadataBlock(
        lines: List<String>,
        startIdx: Int,
        envs: MutableList<EnvRequirement>,
        bins: MutableList<String>,
    ): Int {
        var i = startIdx
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { i++; continue }

            val indent = line.length - line.trimStart().length
            // 退出 metadata 块 (indent ≤ 1 且非空)
            if (indent <= 1 && trimmed.isNotEmpty()) return i - 1

            when {
                trimmed == "requires:" -> {
                    i = parseRequiresBlock(lines, i + 1, envs, bins)
                }
            }
            i++
        }
        return lines.size
    }

    /**
     * 解析 metadata.requires 块 (indent ≥ 4)。
     */
    private fun parseRequiresBlock(
        lines: List<String>,
        startIdx: Int,
        envs: MutableList<EnvRequirement>,
        bins: MutableList<String>,
    ): Int {
        var i = startIdx
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { i++; continue }

            val indent = line.length - line.trimStart().length
            if (indent <= 3 && trimmed.isNotEmpty()) return i - 1

            when {
                trimmed == "env:" -> {
                    i = parseEnvBlock(lines, i + 1, envs)
                }
                trimmed == "bins:" -> {
                    i = parseBinsBlock(lines, i + 1, bins)
                }
            }
            i++
        }
        return lines.size
    }

    /**
     * 解析 env 列表 (indent ≥ 6)。
     * 格式:
     *   - name: TODOIST_API_KEY
     *     required: true
     *     description: ...
     */
    private fun parseEnvBlock(
        lines: List<String>,
        startIdx: Int,
        envs: MutableList<EnvRequirement>,
    ): Int {
        var i = startIdx
        var currentName = ""
        var currentRequired = "true"
        var currentDesc = ""

        fun flush() {
            if (currentName.isNotBlank()) {
                envs.add(EnvRequirement(currentName, currentRequired == "true", currentDesc))
            }
            currentName = ""
            currentRequired = "true"
            currentDesc = ""
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { i++; continue }

            val indent = line.length - line.trimStart().length
            if (indent <= 5 && trimmed.isNotEmpty() && !trimmed.startsWith("-")) {
                flush()
                return i - 1
            }

            when {
                trimmed.startsWith("- name:") -> {
                    flush()
                    currentName = trimmed.substringAfter("- name:").trim()
                }
                trimmed.startsWith("required:") -> {
                    currentRequired = trimmed.substringAfter("required:").trim()
                }
                trimmed.startsWith("description:") -> {
                    currentDesc = trimmed.substringAfter("description:").trim().trim('"').trim('\'')
                }
                trimmed.startsWith("- ") && !trimmed.startsWith("- name:") -> {
                    // new list entry but not env name — flush and exit env block
                    flush()
                    return i - 1
                }
            }
            i++
        }
        flush()
        return lines.size
    }

    /**
     * 解析 bins 列表 (indent ≥ 6)。
     * 格式:
     *   - curl
     *   - jq
     */
    private fun parseBinsBlock(
        lines: List<String>,
        startIdx: Int,
        bins: MutableList<String>,
    ): Int {
        var i = startIdx
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) { i++; continue }

            val indent = line.length - line.trimStart().length
            if (indent <= 5 && trimmed.isNotEmpty() && !trimmed.startsWith("-")) {
                return i - 1
            }

            if (trimmed.startsWith("- ")) {
                val bin = trimmed.substringAfter("- ").trim()
                if (bin.isNotBlank()) bins.add(bin)
            }
            i++
        }
        return lines.size
    }
}

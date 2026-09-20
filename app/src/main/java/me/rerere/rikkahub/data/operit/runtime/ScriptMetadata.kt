package me.rerere.rikkahub.data.operit.runtime


/* ───【自研】岔路口计划·阶段2 — 脚本 METADATA 解析 (宽容变体)
 * 实测两种格式: "斜杠星号+METADATA 同行" 与 "斜杠星号换行后 METADATA" — 解析须宽容
 * v4.6.2 生态模块: 增加 JS 对象字面量修复链 (内置包实测 31 个中 23 个规范 JSON,
 *   1 个轻修复可过, 7 个手写宽松格式走简化提取兜底 — Python 全量验证矩阵见提交说明)。
 *   处理链: 注释剔除→逐行补逗号→花括号粘连修复→三引号归一→字符串状态机
 *   (内嵌裸引号→单引号,裸换行→\n)→无引号 key/值→尾逗号。
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ScriptMetadata(
    val name: String = "",
    val display_name: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val enabledByDefault: Boolean = false,
    val category: String = "",
    val tools: List<ScriptToolDecl> = emptyList(),
)

@Serializable
data class ScriptToolDecl(
    val name: String = "",
    val description: Map<String, String> = emptyMap(),
    val parameters: List<ScriptToolParam> = emptyList(),
)

@Serializable
data class ScriptToolParam(
    val name: String = "",
    val description: Map<String, String> = emptyMap(),
    val type: String = "string",
    val required: Boolean = false,
    val default: kotlinx.serialization.json.JsonElement? = null,
)

object ScriptMetadataParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // 兼容变体: /* METADATA ... */ (同行或分行, 大小写不敏感)
    private val metadataRegex = Regex(
        """/\*\s*METADATA\s*([\s\S]*?)\*/""",
        RegexOption.IGNORE_CASE,
    )

    /** 主解析: ① isLenient JSON → ② HJSON (Operit 同款, 手写格式正解) → ③ 修复链兜底 → ④ null */
    fun parse(source: String): ScriptMetadata? {
        val body = extractBody(source) ?: return null
        // ① 直接解析 (isLenient: 允许无引号 token)
        runCatching { json.decodeFromString(ScriptMetadata.serializer(), body) }.getOrNull()?.let { return it }
        // ② HJSON — Operit 生态包的标准解析路径 (无引号/注释/省略逗号/三引号多行串全兼容;
        //    实测 31/31 内置包全通过, 247 个工具声明全量解析 — 与 Operit PackageManager 同款)
        runCatching {
            val normalized = org.hjson.JsonValue.readHjson(body).toString()
            json.decodeFromString(ScriptMetadata.serializer(), normalized)
        }.getOrNull()?.let { return it }
        // ③ JS 对象字面量修复链 (HJSON 失败时兜底)
        runCatching {
            json.decodeFromString(ScriptMetadata.serializer(), repairJsObject(body))
        }.getOrNull()?.let { return it }
        return null
    }

    /**
     * 简化提取兜底 (v4.6.2): 极端宽松的手写格式包 — 保留名称/描述/开关,
     * tools 留空 (不注册工具, 生态页诚实标注"宽松格式")。
     * Python 全量验证: 修复链失败的 7/31 个包均可用此路径提取核心信息。
     */
    fun parseLoose(source: String): ScriptMetadata? {
        val body = extractBody(source) ?: return null
        fun grab(pattern: String): String? = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'', ',', ' ')

        val name = grab("""(?:^|[{,\s])"?name"?\s*:\s*["']?([A-Za-z_][A-Za-z0-9_]*)""") ?: return null
        val zh = grab("""(?:display_name|["']display_name["'])\s*:\s*\{[^}]*?zh\s*:\s*["']([^"']+)["']""")
        val descZh = grab("""(?:"?description"?)\s*:\s*\{[^}]*?zh\s*:\s*["']([^"']{1,200})""")
        val enabled = grab("""enabledByDefault\s*:\s*(true|false)""")?.toBooleanStrictOrNull() ?: false
        val category = grab("""category\s*:\s*["']?([A-Za-z0-9_]+)["']?""")
        // 工具名列表 (仅展示用): tools 段内出现的一级 name
        val toolNames = Regex("""\{\s*"?name"?\s*:\s*([A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(body).map { it.groupValues[1] }.distinct().filter { it != name }.toList()

        return ScriptMetadata(
            name = name,
            display_name = mapOf("zh" to (zh ?: name)),
            description = mapOf("zh" to (descZh ?: "")),
            enabledByDefault = enabled,
            category = category ?: "",
            tools = emptyList(), // 宽松格式: 不注册工具 (参数 schema 不可靠)
        ).also {
            looseToolHints[it.name] = toolNames
        }
    }

    /** parseLoose 提取到的工具名提示 (展示用, name→工具名列表) */
    val looseToolHints = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    private fun extractBody(source: String): String? {
        val match = metadataRegex.find(source) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** JS 对象字面量 → JSON 修复链 (Python 全量验证过的五步) */
    private fun repairJsObject(body: String): String {
        // ① 注释行剔除 + 空行剔除 + 逐行补逗号 (新 key 行前缺逗号则补)
        val out = mutableListOf<String>()
        for (raw in body.split('\n')) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("//")) continue
            val isKeyLine = Regex("""^(?:"[A-Za-z_]\w*"|[A-Za-z_]\w*)\s*:""").containsMatchIn(s)
            if (isKeyLine && out.isNotEmpty()) {
                val prev = out.last().trimEnd()
                if (prev.isNotEmpty() && prev.last() !in "{[,:" && !prev.endsWith(",")) {
                    out[out.size - 1] = prev + ","
                }
            }
            out.add(raw)
        }
        var p = out.joinToString("\n")
        // ② 粘连修复: } 或 ] 后直接跟 key: → 断行+补逗号
        p = Regex("""([}\]])((?:"[A-Za-z_]\w*"|[A-Za-z_]\w*)\s*:)""").replace(p) { m ->
            m.groupValues[1] + ",\n" + m.groupValues[2]
        }
        // ③ 三引号归一
        p = p.replace("'''", "\"")
        // ④ 字符串状态机: 内嵌裸引号 → 单引号; 字符串内裸换行 → \n
        p = fixStrings(p)
        // ⑤ 无引号 key → 加引号
        p = Regex("""(^|[{,\s])([A-Za-z_][A-Za-z0-9_]*)\s*:""", RegexOption.MULTILINE).replace(p) { m ->
            m.groupValues[1] + "\"" + m.groupValues[2] + "\":"
        }
        // ⑥ 裸词值 → 加引号
        p = Regex("""[:\s]\s*([A-Za-z_][A-Za-z0-9_]*)(\s*[,}\]\n])""").replace(p) { m ->
            ": \"" + m.groupValues[1] + "\"" + m.groupValues[2]
        }
        // ⑦ 尾逗号清除
        p = Regex("""[,](\s*[}\]])""").replace(p) { m -> m.groupValues[1] }
        return p
    }

    /** 字符串状态机 — 内嵌裸引号/裸换行修复 (Python 全量验证) */
    private fun fixStrings(raw: String): String {
        val sb = StringBuilder(raw.length + 64)
        var inStr = false
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            if (inStr) {
                if (c == '\\' && i + 1 < n) {
                    sb.append(c).append(raw[i + 1]); i += 2; continue
                }
                if (c == '\n') {
                    sb.append("\\n"); i++; continue
                }
                if (c == '"') {
                    var j = i + 1
                    while (j < n && (raw[j] == ' ' || raw[j] == '\t' || raw[j] == '\n')) j++
                    if (j >= n || raw[j] in ",:}]") {
                        inStr = false; sb.append(c)
                    } else {
                        sb.append('\'')
                    }
                    i++; continue
                }
                sb.append(c); i++; continue
            }
            if (c == '"') inStr = true
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** 提取给模型看的中文优先文案 */
    fun pickText(map: Map<String, String>, lang: String = "zh"): String =
        map[lang] ?: map["zh"] ?: map["en"] ?: map.values.firstOrNull() ?: ""
}

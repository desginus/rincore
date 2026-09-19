package me.rerere.rikkahub.data.operit.runtime


// ───【自研】岔路口计划·阶段2 — 脚本 METADATA 解析 (宽容变体)
// 实测两种格式: "/* METADATA 同行" 与 "/*\nMETADATA 分行" — 解析须宽容
// ───────────────────────────────────────────────────────────────
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

    fun parse(source: String): ScriptMetadata? {
        val match = metadataRegex.find(source) ?: return null
        val body = match.groupValues.getOrNull(1)?.trim() ?: return null
        return runCatching { json.decodeFromString(ScriptMetadata.serializer(), body) }.getOrNull()
    }

    /** 提取给模型看的中文优先文案 */
    fun pickText(map: Map<String, String>, lang: String = "zh"): String =
        map[lang] ?: map["zh"] ?: map["en"] ?: map.values.firstOrNull() ?: ""
}

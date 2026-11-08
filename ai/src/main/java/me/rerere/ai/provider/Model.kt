package me.rerere.ai.provider

/* ───【原版对齐】Model.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // 5.0.0 并树：上下文窗口大小(token)，上游 3.0.0 字段（默认 1M，序列化向后兼容）
    val contextLength: Int = 1_000_000,
)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
    // v4.8.103: 视频生成模型 (对齐图像生成流程 — Google Veo / 阿里云 HappyHorse)
    VIDEO,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}

/**
 * ★v5.3.61（对齐上游 3.1.2）：按空格分隔的多关键词筛选模型。
 *
 * 每个关键词都要命中 **模型 ID 或显示名称** 的一部分，关键词之间不要求顺序，忽略大小写；
 * 没有关键词时返回原列表。
 *
 * 旧实现是 `displayName.contains(整串, true)` —— 输入「gpt 4」这种带空格的多个词时
 * 永远匹配不到（等于把空格当成了一个必须存在的字符），且完全不搜 modelId。
 */
fun List<Model>.filterByKeywords(query: String): List<Model> {
    val keywords = query.split(" ").filter { it.isNotBlank() }
    if (keywords.isEmpty()) return this
    return filter { model ->
        keywords.all { keyword ->
            model.modelId.contains(keyword, ignoreCase = true) ||
                model.displayName.contains(keyword, ignoreCase = true)
        }
    }
}

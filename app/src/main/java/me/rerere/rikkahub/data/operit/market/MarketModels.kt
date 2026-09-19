package me.rerere.rikkahub.data.operit.market


/* ───【自研】岔路口计划·阶段1 — Operit 云商城数据模型
 * 按实测的真实市场 API schema (static.operit.app/market/v2/...) 对齐
 * 来源: RinCore 自研新增 (LGPL 下独立实现, 不复制 Operit 代码)
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 市场总清单 — static.operit.app/market/v2/manifest.json */
@Serializable
data class MarketManifest(
    val ok: Boolean = false,
    val marketVersion: Int = 2,
    val generatedAt: String? = null,
    val types: List<MarketType> = emptyList(),
    val formatVersions: List<MarketFormatVersion> = emptyList(),
    val categories: List<MarketCategory> = emptyList(),
)

@Serializable
data class MarketType(
    val id: String = "",
    val name: String = "",
    val description: String = "",
)

@Serializable
data class MarketFormatVersion(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val publishable: Boolean = false,
)

@Serializable
data class MarketCategory(
    val id: String = "",
    val name: String = "",
    val description: String = "",
)

/** 条目列表响应 — static.operit.app/market/v2/lists/.../page-N.json */
@Serializable
data class MarketListResponse(
    val ok: Boolean = false,
    val marketVersion: Int = 2,
    val generatedAt: String? = null,
    val list: String? = null,
    val sort: String? = null,
    val page: Int = 1,
    val pageSize: Int = 100,
    val total: Int = 0,
    val items: List<MarketEntry> = emptyList(),
)

/** 市场条目 (条目 shard 里 entriesById 的 value; 列表里 items 的元素) */
@Serializable
data class MarketEntry(
    val type: String = "",
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val detail: String = "",
    val logoUrl: String? = null,
    val authorId: String = "",
    val publisherId: String = "",
    val allowPublicUpdates: Boolean = true,
    val featured: Boolean = false,
    val categoryId: String = "",
    val stateCode: String = "approved",
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val publishedAt: String? = null,
    val source: MarketSource? = null,
    val artifact: MarketArtifact? = null,
    val assets: List<MarketAsset> = emptyList(),
    val versions: List<MarketVersion> = emptyList(),
    val latestVersion: MarketVersion? = null,
    val contributors: List<MarketContributor> = emptyList(),
    val downloads: Int = 0,
    val downloadCount: Int = 0,
    val stats: MarketStats? = null,
    val author: MarketContributor? = null,
    val publisher: MarketContributor? = null,
    val reactions: List<JsonElement> = emptyList(),
) {
    val effectiveDownloadCount: Int get() = if (downloadCount > 0) downloadCount else downloads
}

@Serializable
data class MarketSource(
    val kind: String = "",
    val url: String = "",
)

@Serializable
data class MarketArtifact(
    val projectId: String = "",
    val runtimePkg: String? = null,
)

@Serializable
data class MarketAsset(
    val id: String = "",
    val versionId: String = "",
    val kind: String = "",
    val url: String = "",
    val sha256: String = "",
    val assetName: String = "",
)

@Serializable
data class MarketVersion(
    val id: String = "",
    val version: String = "",
    val formatVer: String = "",
    val minAppVer: String? = null,
    val maxAppVer: String? = null,
    val runtimePackageId: String? = null,
    val publisherId: String = "",
    val publisher: MarketContributor? = null,
    val publishedAt: String? = null,
    // v4.5.30 阶段3: mcp 类型的 installConfig (mcpServers JSON)
    val installConfig: String? = null,
)

@Serializable
data class MarketContributor(
    val id: String = "",
    val login: String = "",
    val avatar: String = "",
)

@Serializable
data class MarketStats(
    val downloads: Int = 0,
    val likes: Int = 0,
    val lastDownloadAt: String? = null,
    val updatedAt: String? = null,
)

/** 条目 shard 响应 — static.operit.app/market/v2/entries/<shard>.json */
@Serializable
data class MarketEntriesShardResponse(
    val ok: Boolean = false,
    val marketVersion: Int = 2,
    val generatedAt: String? = null,
    val shard: String = "",
    val entriesById: Map<String, MarketEntry> = emptyMap(),
)

/** 列表 sort 选项 */
enum class MarketSort(val wireValue: String) {
    DOWNLOADS("downloads"),
    LIKES("likes"),
    UPDATED("updated");

    companion object {
        fun from(s: String?): MarketSort = entries.firstOrNull { it.wireValue == s } ?: DOWNLOADS
    }
}

/** 列表筛选: 全部 / 按类型 / 按类型+分类 */
sealed class MarketListFilter {
    abstract val sort: MarketSort

    data class All(override val sort: MarketSort = MarketSort.DOWNLOADS) : MarketListFilter()
    data class ByType(val type: String, override val sort: MarketSort = MarketSort.DOWNLOADS) : MarketListFilter()
    data class ByTypeCategory(
        val type: String,
        val categoryId: String,
        override val sort: MarketSort = MarketSort.DOWNLOADS,
    ) : MarketListFilter()
    data class ByCategory(val categoryId: String, override val sort: MarketSort = MarketSort.DOWNLOADS) : MarketListFilter()
}

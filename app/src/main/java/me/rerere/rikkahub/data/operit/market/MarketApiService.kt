/* 【域 K·生态扩展】 | 地图: docs/APP_MAP.md §K */
package me.rerere.rikkahub.data.operit.market


/* ───【自研】岔路口计划·阶段1 — Operit 云商城 API 客户端
 * 端点实测: static.operit.app (匿名 JSON) + api.operit.app (下载 302)
 * 来源: RinCore 自研新增 (LGPL 下独立实现)
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Operit 云商城只读 API。
 *
 * 两层服务:
 * - 静态层 static.operit.app: manifest / lists / entries / comments — 纯 JSON 匿名可访问
 * - 动态层 api.operit.app: assets/{id}/download — 302 跳转到 GitHub Release (自带 sha256 响应头)
 *
 * 本服务只做"取数据"与"下载 URL 解析"; 实际下载与校验由 PackageDownloader 负责。
 */
class MarketApiService(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun getManifest(): Result<MarketManifest> = withContext(Dispatchers.IO) {
        runCatching {
            requestJson(
                url = "$STATIC_BASE/market/v2/manifest.json",
                label = "manifest",
            ) { body -> json.decodeFromString(MarketManifest.serializer(), body) }
        }
    }

    suspend fun getListPage(
        filter: MarketListFilter,
        page: Int = 1,
    ): Result<MarketListResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val sort = filter.sort.wireValue
            val segments = buildList {
                addAll(listOf("market", "v2", "lists"))
                when (filter) {
                    is MarketListFilter.All -> addAll(listOf("all", sort))
                    is MarketListFilter.ByType -> addAll(listOf("type", filter.type.lowercase(), sort))
                    is MarketListFilter.ByCategory -> addAll(listOf("category", filter.categoryId.lowercase(), sort))
                    is MarketListFilter.ByTypeCategory -> addAll(
                        listOf("type", filter.type.lowercase(), "category", filter.categoryId.lowercase(), sort),
                    )
                }
                add("page-$page.json")
            }
            val url = segments.joinToString("/", prefix = "$STATIC_BASE/")
            requestJson(url = url, label = "listPage filter=$filter page=$page") { body ->
                json.decodeFromString(MarketListResponse.serializer(), body)
            }
        }
    }

    suspend fun getEntry(entryId: String): Result<MarketEntry?> = withContext(Dispatchers.IO) {
        runCatching {
            val shard = marketShard(entryId)
            val url = "$STATIC_BASE/market/v2/entries/$shard.json"
            val shardResponse: MarketEntriesShardResponse = requestJson(url, label = "getEntry id=$entryId shard=$shard") { body ->
                json.decodeFromString(MarketEntriesShardResponse.serializer(), body)
            }
            shardResponse.entriesById[entryId]
        }
    }

    /** 下载端点 URL (api.operit.app/market/v2/assets/{id}/download, 302→GitHub) */
    fun downloadUrlForAsset(assetId: String): String {
        val id = assetId.trim()
        if (id.isBlank()) return ""
        return "$DYNAMIC_BASE/market/v2/assets/$id/download"
    }

    /** 取下载实际最终 URL (跟随 302, 用于显示/诊断) — 不下载文件 */
    suspend fun resolveFinalDownloadUrl(assetId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val startUrl = downloadUrlForAsset(assetId)
            val request = Request.Builder().url(startUrl).head().get().build()
            // 不跟随重定向, 只看 302 的 Location (OkHttp 默认跟随; 用自定义 client 禁跟随)
            // 这里简化: 用一个不跟随重定向的副本
            val noFollowClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            noFollowClient.newCall(request).execute().use { response ->
                val loc = response.header("location")
                if (!loc.isNullOrBlank()) loc else startUrl
            }
        }
    }

    private inline fun <T> requestJson(
        url: String,
        label: String,
        decode: (String) -> T,
    ): T {
        val request = Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Market API $label failed: HTTP ${response.code}, url=$url")
            }
            return decode(body)
        }
    }

    /** FNV-1a 32 位哈希 (与 Operit 一致), 取前 2 个 hex 字符作为 entryId 的分片 */
    private fun marketShard(entryId: String): String {
        var hash = FNV1A_32_OFFSET_BASIS
        for (b in entryId.toByteArray()) {
            hash = hash xor (b.toLong() and 0xffL)
            hash = (hash and 0xffffffffL) // 截断 32 位
            hash = (hash * FNV1A_32_PRIME) and 0xffffffffL
        }
        return hash.toString(16).padStart(8, '0').take(2)
    }

    private companion object {
        const val STATIC_BASE = "https://static.operit.app"
        const val DYNAMIC_BASE = "https://api.operit.app"
        const val USER_AGENT = "RinCore/Market (compatible; Operit market client)"

        const val FNV1A_32_OFFSET_BASIS = 0x811c9dc5L
        const val FNV1A_32_PRIME = 0x01000193L
    }
}

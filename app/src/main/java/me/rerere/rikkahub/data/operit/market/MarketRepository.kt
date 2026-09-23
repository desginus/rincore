/* 【域 K·生态扩展】 | 地图: docs/APP_MAP.md §K */
package me.rerere.rikkahub.data.operit.market


/* ───【自研】岔路口计划·阶段1 — 云商城仓储层 (带内存缓存)
 * 来源: RinCore 自研新增
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 市场数据仓储: API 调用 + 短期内存缓存 (TTL 5 分钟, 避免重复请求) */
class MarketRepository(
    private val apiService: MarketApiService,
) {
    private val cacheTtlMs = 5L * 60 * 1000
    private val manifestCache = AtomicRef<CacheEntry<MarketManifest>>()
    private val listCache = mutableMapOf<String, CacheEntry<MarketListResponse>>()
    private val entryCache = mutableMapOf<String, CacheEntry<MarketEntry?>>()

    suspend fun getManifest(forceRefresh: Boolean = false): Result<MarketManifest> = withContext(Dispatchers.IO) {
        if (!forceRefresh) manifestCache.value?.takeIf { it.isFresh() }?.let { return@withContext Result.success(it.data) }
        apiService.getManifest().onSuccess { manifestCache.value = CacheEntry(it) }
    }

    suspend fun getListPage(
        filter: MarketListFilter,
        page: Int = 1,
        forceRefresh: Boolean = false,
    ): Result<MarketListResponse> = withContext(Dispatchers.IO) {
        val key = cacheKey(filter, page)
        if (!forceRefresh) listCache[key]?.takeIf { it.isFresh() }?.let { return@withContext Result.success(it.data) }
        apiService.getListPage(filter, page).onSuccess { listCache[key] = CacheEntry(it) }
    }

    suspend fun getEntry(entryId: String, forceRefresh: Boolean = false): Result<MarketEntry?> = withContext(Dispatchers.IO) {
        if (!forceRefresh) entryCache[entryId]?.takeIf { it.isFresh() }?.let { return@withContext Result.success(it.data) }
        apiService.getEntry(entryId).onSuccess { entryCache[entryId] = CacheEntry(it) }
    }

    fun invalidateAll() {
        manifestCache.value = null
        synchronized(listCache) { listCache.clear() }
        synchronized(entryCache) { entryCache.clear() }
    }

    private fun cacheKey(filter: MarketListFilter, page: Int): String = when (filter) {
        is MarketListFilter.All -> "all:${filter.sort.wireValue}:$page"
        is MarketListFilter.ByType -> "type:${filter.type}:${filter.sort.wireValue}:$page"
        is MarketListFilter.ByCategory -> "category:${filter.categoryId}:${filter.sort.wireValue}:$page"
        is MarketListFilter.ByTypeCategory -> "type:${filter.type}:cat:${filter.categoryId}:${filter.sort.wireValue}:$page"
    }

    private class CacheEntry<T>(val data: T) {
        private val createdAt = System.currentTimeMillis()
        fun isFresh(): Boolean = System.currentTimeMillis() - createdAt < 5L * 60 * 1000
    }

    /** 简易原子引用 (避免引入 AtomicReference 模板噪声) */
    private class AtomicRef<T> {
        @Volatile private var _value: T? = null
        var value: T?
            get() = _value
            set(v) { _value = v }
    }
}

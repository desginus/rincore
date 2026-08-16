package me.rerere.common.cache


/* ───【原版对齐】CacheStore.kt | 差异 ±0 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
interface CacheStore<K, V> {
    fun loadEntry(key: K): CacheEntry<V>?
    fun saveEntry(key: K, entry: CacheEntry<V>)
    fun remove(key: K)
    fun clear()
    fun loadAllEntries(): Map<K, CacheEntry<V>>
    fun keys(): Set<K>
}


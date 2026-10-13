package com.maodouchat.server.service

/**
 * Per-cache observability snapshot returned by [CacheService.cacheStats].
 */
data class CacheStat(
    val name: String,
    val size: Int,
    val maxSize: Int,
    val ttlMs: Long,
    val hits: Long,
    val misses: Long,
    val evictions: Long
)

fun CacheService.getHits(): Long = globalHits.get()
fun CacheService.getMisses(): Long = globalMisses.get()
fun CacheService.getEvictions(): Long = globalEvictions.get()

fun CacheService.resetStats() {
    globalHits.set(0)
    globalMisses.set(0)
    globalEvictions.set(0)
    userProfiles.resetStats()
    chatMetadata.resetStats()
    publicStatus.resetStats()
    customCaches.values.forEach { it.resetStats() }
}

/**
 * 聚合统计（向后兼容旧调用方）。包含每缓存明细在 `caches` 字段下。
 */
fun CacheService.getStats(): Map<String, Any> = mapOf(
    "hits" to globalHits.get(),
    "misses" to globalMisses.get(),
    "evictions" to globalEvictions.get(),
    "hitRate" to if (globalHits.get() + globalMisses.get() > 0) {
        globalHits.get().toDouble() / (globalHits.get() + globalMisses.get())
    } else 0.0,
    "userProfiles" to userProfiles.size(),
    "chatMetadata" to chatMetadata.size(),
    "publicStatus" to publicStatus.size(),
    "customCaches" to customCaches.size,
    "caches" to cacheStats()
)

/**
 * 每个缓存的独立统计：大小、命中、未命中、淘汰数。供 /health/metrics 暴露。
 */
fun CacheService.cacheStats(): Map<String, CacheStat> {
    val result = LinkedHashMap<String, CacheStat>()
    result[userProfiles.name] = userProfiles.stat()
    result[chatMetadata.name] = chatMetadata.stat()
    result[publicStatus.name] = publicStatus.stat()
    customCaches.values.forEach { cache -> result[cache.name] = cache.stat() }
    return result
}

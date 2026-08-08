package com.maodouchat.server.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

/**
 * 通用内存 LRU 缓存服务，支持 TTL 和自动清理。
 *
 * 所有缓存均有容量上限（LinkedHashMap.removeEldestEntry）和 TTL；后台协程定期清理过期条目，
 * 读取时也会复检 TTL。命名缓存数量同样有上限，防止误用导致 OOM。
 */
class CacheService {
    private val logger = LoggerFactory.getLogger("CacheService")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val activeLifecycleIds = mutableSetOf<Long>()
    private var lastLifecycleId = 0L

    internal fun start(): Long = synchronized(this) {
        check(!closed.get()) { "Cache service is shut down" }
        val lifecycleId = nextLifecycleId()
        activeLifecycleIds += lifecycleId
        lifecycleId
    }

    internal fun shutdown(lifecycleId: Long) {
        val shouldShutdown = synchronized(this) {
            activeLifecycleIds.remove(lifecycleId) &&
                activeLifecycleIds.isEmpty() &&
                closed.compareAndSet(false, true)
        }
        if (shouldShutdown) closeResources()
    }

    fun shutdown() {
        val shouldShutdown = synchronized(this) {
            if (!closed.compareAndSet(false, true)) return@synchronized false
            activeLifecycleIds.clear()
            true
        }
        if (shouldShutdown) closeResources()
    }

    private fun closeResources() {
        scope.cancel()
        userProfiles.clear()
        chatMetadata.clear()
        publicStatus.clear()
        synchronized(customCaches) {
            customCaches.values.forEach { it.clear() }
            customCaches.clear()
        }
        clearInstance(this)
    }

    private fun nextLifecycleId(): Long {
        do {
            lastLifecycleId = if (lastLifecycleId == Long.MAX_VALUE) 1L else lastLifecycleId + 1L
        } while (lastLifecycleId in activeLifecycleIds)
        return lastLifecycleId
    }

    private val userProfiles = LRUCache<String, Any>(1000, 5 * 60 * 1000L, "userProfiles")
    private val chatMetadata = LRUCache<String, Any>(2000, 2 * 60 * 1000L, "chatMetadata")
    private val publicStatus = LRUCache<String, Any>(10, 30 * 1000L, "publicStatus")
    private val customCaches = ConcurrentHashMap<String, LRUCache<String, Any>>()

    /** Cap on the number of distinct named caches to prevent unbounded map growth. */
    private val maxNamedCaches: Int = envInt("CACHE_MAX_NAMED_CACHES", 64)
    /** Env-tunable default TTL for custom caches created via [getOrCreateCache]. */
    private val customDefaultTtlMs: Long = envLong("CACHE_DEFAULT_TTL_MS", 60_000L)

    private val globalHits = AtomicLong(0)
    private val globalMisses = AtomicLong(0)
    private val globalEvictions = AtomicLong(0)

    init {
        scope.launch {
            while (isActive) {
                delay(5 * 60 * 1000)
                runCatching { cleanup() }.onFailure { e -> logger.warn("Cache cleanup failed", e) }
            }
        }
    }

    // ── 用户资料缓存 ──

    fun getUserProfile(key: String): Any? {
        val value = userProfiles.get(key)
        if (value != null) globalHits.incrementAndGet() else globalMisses.incrementAndGet()
        return value
    }

    fun putUserProfile(key: String, value: Any) {
        userProfiles.put(key, value)
    }

    fun invalidateUserProfile(key: String) {
        userProfiles.remove(key)
    }

    // ── 群聊元数据缓存 ──

    fun getChatMetadata(key: String): Any? {
        val value = chatMetadata.get(key)
        if (value != null) globalHits.incrementAndGet() else globalMisses.incrementAndGet()
        return value
    }

    fun putChatMetadata(key: String, value: Any) {
        chatMetadata.put(key, value)
    }

    fun invalidateChatMetadata(key: String) {
        chatMetadata.remove(key)
    }

    // ── 公共状态缓存 ──

    fun getPublicStatus(key: String): Any? {
        val value = publicStatus.get(key)
        if (value != null) globalHits.incrementAndGet() else globalMisses.incrementAndGet()
        return value
    }

    fun putPublicStatus(key: String, value: Any) {
        publicStatus.put(key, value)
    }

    // ── 自定义缓存 ──

    fun getOrCreateCache(
        name: String,
        maxEntries: Int = 500,
        ttlMs: Long = customDefaultTtlMs
    ): LRUCache<String, Any> {
        check(!closed.get()) { "Cache service is shut down" }
        customCaches[name]?.let { return it }
        return synchronized(customCaches) {
            check(!closed.get()) { "Cache service is shut down" }
            customCaches[name]?.let { return@synchronized it }
            if (customCaches.size >= maxNamedCaches) {
                logger.warn("Named cache cap reached ({}) for '{}'", maxNamedCaches, name)
                throw IllegalStateException("Named cache limit reached")
            }
            LRUCache<String, Any>(maxEntries.coerceAtLeast(1), ttlMs.coerceAtLeast(1L), name).also {
                customCaches[name] = it
            }
        }
    }

    // ── 统计 ──

    fun getHits(): Long = globalHits.get()
    fun getMisses(): Long = globalMisses.get()
    fun getEvictions(): Long = globalEvictions.get()

    fun resetStats() {
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
    fun getStats(): Map<String, Any> = mapOf(
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
    fun cacheStats(): Map<String, CacheStat> {
        val result = LinkedHashMap<String, CacheStat>()
        result[userProfiles.name] = userProfiles.stat()
        result[chatMetadata.name] = chatMetadata.stat()
        result[publicStatus.name] = publicStatus.stat()
        customCaches.values.forEach { cache -> result[cache.name] = cache.stat() }
        return result
    }

    // ── 清理 ──

    fun cleanup() {
        if (closed.get()) return
        globalEvictions.addAndGet(userProfiles.evictExpired().toLong())
        globalEvictions.addAndGet(chatMetadata.evictExpired().toLong())
        globalEvictions.addAndGet(publicStatus.evictExpired().toLong())
        customCaches.values.forEach { globalEvictions.addAndGet(it.evictExpired().toLong()) }
    }

    companion object {
        @Volatile
        private var instance: CacheService? = null

        fun getInstance(): CacheService {
            return instance ?: synchronized(this) {
                instance ?: CacheService().also { instance = it }
            }
        }

        internal fun acquireLifecycle(): Pair<CacheService, Long> = synchronized(this) {
            val service = instance?.takeUnless { it.closed.get() }
                ?: CacheService().also { instance = it }
            service to service.start()
        }

        private fun clearInstance(service: CacheService) {
            synchronized(this) {
                if (instance === service) instance = null
            }
        }
    }
}

/**
 * 基于 LinkedHashMap 的 LRU 缓存，支持 TTL 和并发访问。
 *
 * 容量由 [maxEntries] 硬性限制（removeEldestEntry 同时淘汰 accessOrder 与 [map]）；
 * TTL 在读取和后台清理时双重复检。命中/未命中/淘汰计数供可观测性使用。
 */
class LRUCache<K, V>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    val name: String = "cache"
) {
    private val map = ConcurrentHashMap<K, CacheEntry<V>>()
    private val accessOrder = object : LinkedHashMap<K, CacheEntry<V>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CacheEntry<V>>?): Boolean {
            return if (size > maxEntries) {
                eldest?.key?.let { map.remove(it) }
                evictions.incrementAndGet()
                true
            } else false
        }
    }
    private val accessOrderLock = Any()

    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val evictions = AtomicLong(0)

    fun get(key: K): V? = synchronized(accessOrderLock) {
        val entry = map[key]
        if (entry == null) {
            misses.incrementAndGet()
            return@synchronized null
        }
        if (System.currentTimeMillis() - entry.createdAt > ttlMs) {
            misses.incrementAndGet()
            if (map.remove(key, entry)) {
                accessOrder.remove(key)
            }
            return@synchronized null
        }
        hits.incrementAndGet()
        accessOrder.remove(key)
        accessOrder[key] = entry
        entry.value
    }

    fun put(key: K, value: V) {
        val entry = CacheEntry(value, System.currentTimeMillis())
        synchronized(accessOrderLock) {
            map[key] = entry
            accessOrder[key] = entry
        }
    }

    fun remove(key: K) {
        synchronized(accessOrderLock) {
            map.remove(key)
            accessOrder.remove(key)
        }
    }

    fun clear() {
        synchronized(accessOrderLock) {
            map.clear()
            accessOrder.clear()
        }
    }

    fun size(): Int = synchronized(accessOrderLock) { map.size }

    /** Remove expired entries; returns the count removed. */
    fun evictExpired(): Int = synchronized(accessOrderLock) {
        val now = System.currentTimeMillis()
        val expired = map.entries.filter { now - it.value.createdAt > ttlMs }.map { it.key to it.value }
        var removed = 0
        expired.forEach { (key, entry) ->
            if (map.remove(key, entry)) {
                removed++
                accessOrder.remove(key)
            }
        }
        removed
    }

    fun resetStats() {
        hits.set(0)
        misses.set(0)
        evictions.set(0)
    }

    fun stat(): CacheStat = CacheStat(
        name = name,
        size = size(),
        maxSize = maxEntries,
        ttlMs = ttlMs,
        hits = hits.get(),
        misses = misses.get(),
        evictions = evictions.get()
    )

    private data class CacheEntry<V>(
        val value: V,
        val createdAt: Long
    )
}

// Env helpers mirror ServerConfig.env(): real env first, system property second, default last.
private fun env(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: System.getProperty(name)?.takeIf(String::isNotBlank)
        ?: default

private fun envInt(name: String, default: Int): Int =
    env(name, default.toString()).toIntOrNull()?.coerceAtLeast(1) ?: default

private fun envLong(name: String, default: Long): Long =
    env(name, default.toString()).toLongOrNull()?.coerceAtLeast(1L) ?: default

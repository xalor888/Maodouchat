package com.maodouchat.server.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 通用内存 LRU 缓存服务，支持 TTL 和自动清理。
 *
 * 所有缓存均有容量上限（LinkedHashMap.removeEldestEntry）和 TTL；后台协程定期清理过期条目，
 * 读取时也会复检 TTL。命名缓存数量同样有上限，防止误用导致 OOM。
 */
class CacheService {
    internal val logger = LoggerFactory.getLogger("CacheService")
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val closed = AtomicBoolean(false)
    internal val activeLifecycleIds = mutableSetOf<Long>()
    internal var lastLifecycleId = 0L

    internal val userProfiles = LRUCache<String, Any>(1000, 5 * 60 * 1000L, "userProfiles")
    internal val chatMetadata = LRUCache<String, Any>(2000, 2 * 60 * 1000L, "chatMetadata")
    internal val publicStatus = LRUCache<String, Any>(10, 30 * 1000L, "publicStatus")
    internal val customCaches = ConcurrentHashMap<String, LRUCache<String, Any>>()

    /** Cap on the number of distinct named caches to prevent unbounded map growth. */
    internal val maxNamedCaches: Int = envInt("CACHE_MAX_NAMED_CACHES", 64)
    /** Env-tunable default TTL for custom caches created via [getOrCreateCache]. */
    internal val customDefaultTtlMs: Long = envLong("CACHE_DEFAULT_TTL_MS", 60_000L)

    internal val globalHits = AtomicLong(0)
    internal val globalMisses = AtomicLong(0)
    internal val globalEvictions = AtomicLong(0)

    init {
        startCleanupLoop()
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

        internal fun clearInstance(service: CacheService) {
            synchronized(this) {
                if (instance === service) instance = null
            }
        }
    }
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

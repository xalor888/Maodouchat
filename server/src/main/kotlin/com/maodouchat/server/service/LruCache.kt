package com.maodouchat.server.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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

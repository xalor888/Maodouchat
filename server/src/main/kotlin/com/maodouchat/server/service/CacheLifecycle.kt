package com.maodouchat.server.service

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun CacheService.start(): Long = synchronized(this) {
    check(!closed.get()) { "Cache service is shut down" }
    val lifecycleId = nextLifecycleId()
    activeLifecycleIds += lifecycleId
    lifecycleId
}

internal fun CacheService.shutdown(lifecycleId: Long) {
    val shouldShutdown = synchronized(this) {
        activeLifecycleIds.remove(lifecycleId) &&
            activeLifecycleIds.isEmpty() &&
            closed.compareAndSet(false, true)
    }
    if (shouldShutdown) closeResources()
}

fun CacheService.shutdown() {
    val shouldShutdown = synchronized(this) {
        if (!closed.compareAndSet(false, true)) return@synchronized false
        activeLifecycleIds.clear()
        true
    }
    if (shouldShutdown) closeResources()
}

internal fun CacheService.closeResources() {
    scope.cancel()
    userProfiles.clear()
    chatMetadata.clear()
    publicStatus.clear()
    synchronized(customCaches) {
        customCaches.values.forEach { it.clear() }
        customCaches.clear()
    }
    CacheService.clearInstance(this)
}

internal fun CacheService.nextLifecycleId(): Long {
    do {
        lastLifecycleId = if (lastLifecycleId == Long.MAX_VALUE) 1L else lastLifecycleId + 1L
    } while (lastLifecycleId in activeLifecycleIds)
    return lastLifecycleId
}

internal fun CacheService.startCleanupLoop() {
    scope.launch {
        while (isActive) {
            delay(5 * 60 * 1000)
            runCatching { cleanup() }.onFailure { e -> logger.warn("Cache cleanup failed", e) }
        }
    }
}

fun CacheService.cleanup() {
    if (closed.get()) return
    globalEvictions.addAndGet(userProfiles.evictExpired().toLong())
    globalEvictions.addAndGet(chatMetadata.evictExpired().toLong())
    globalEvictions.addAndGet(publicStatus.evictExpired().toLong())
    customCaches.values.forEach { globalEvictions.addAndGet(it.evictExpired().toLong()) }
}

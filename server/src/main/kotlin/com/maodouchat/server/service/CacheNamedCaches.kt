package com.maodouchat.server.service

fun CacheService.getUserProfile(key: String): Any? {
    val value = userProfiles.get(key)
    if (value != null) globalHits.incrementAndGet() else globalMisses.incrementAndGet()
    return value
}

fun CacheService.putUserProfile(key: String, value: Any) {
    userProfiles.put(key, value)
}

fun CacheService.invalidateUserProfile(key: String) {
    userProfiles.remove(key)
}

fun CacheService.getChatMetadata(key: String): Any? {
    val value = chatMetadata.get(key)
    if (value != null) globalHits.incrementAndGet() else globalMisses.incrementAndGet()
    return value
}

fun CacheService.putChatMetadata(key: String, value: Any) {
    chatMetadata.put(key, value)
}

fun CacheService.invalidateChatMetadata(key: String) {
    chatMetadata.remove(key)
}

fun CacheService.getPublicStatus(key: String): Any? {
    val value = publicStatus.get(key)
    if (value != null) globalHits.incrementAndGet() else globalMisses.incrementAndGet()
    return value
}

fun CacheService.putPublicStatus(key: String, value: Any) {
    publicStatus.put(key, value)
}

fun CacheService.getOrCreateCache(
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

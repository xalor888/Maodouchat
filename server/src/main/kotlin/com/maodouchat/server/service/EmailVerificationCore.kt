package com.maodouchat.server.service

import jakarta.mail.internet.InternetAddress

internal class CacheKeyLock(
    val monitor: Any = Any(),
    var users: Int = 0,
)

internal fun EmailService.normalizePurpose(purpose: String): String {
    val p = purpose.trim().lowercase()
    return if (p == PURPOSE_RESET) PURPOSE_RESET else PURPOSE_REGISTER
}

internal fun EmailService.cacheKey(purpose: String, email: String): String = "$purpose|$email"

internal fun EmailService.generateCode(): String {
    return secureRandom.nextInt(900_000).plus(100_000).toString()
}

/**
 * 常量时间字符串比较，避免 [String.equals] 的早退时序侧信道。
 * 验证码均为固定 6 位，长度相等分支不泄露长度信息；差异仅在内容。
 */
internal fun EmailService.constantTimeEquals(a: String, b: String): Boolean {
    val ab = a.toByteArray(Charsets.UTF_8)
    val bb = b.toByteArray(Charsets.UTF_8)
    var diff = if (ab.size == bb.size) 0 else 1
    val len = minOf(ab.size, bb.size)
    for (i in 0 until len) {
        diff = diff or (ab[i].toInt() xor bb[i].toInt())
    }
    return diff == 0
}

internal fun EmailService.storeCode(cacheKey: String, code: String) {
    val cacheEntry = Pair(code, System.currentTimeMillis() + CODE_EXPIRE_MS)
    codeCache[cacheKey] = cacheEntry
    attemptCounter.remove(cacheKey)
}

internal fun EmailService.reserveCacheSlot(cacheKey: String): Boolean = synchronized(cacheCapacityLock) {
    if (codeCache.containsKey(cacheKey)) return@synchronized false
    if (pendingCacheKeys.contains(cacheKey)) return@synchronized false
    if (codeCache.size + pendingCacheKeys.size >= MAX_CACHED_CODES) {
        throw IllegalStateException("验证码服务繁忙，请稍后再试")
    }
    check(pendingCacheKeys.add(cacheKey)) { "验证码缓存容量预留冲突" }
    true
}

internal fun EmailService.releaseCacheReservation(cacheKey: String) {
    synchronized(cacheCapacityLock) {
        pendingCacheKeys.remove(cacheKey)
    }
}

internal fun String.normalizedEmailStrict(): String {
    val normalized = trim().lowercase()
    // 纵深防御：显式拒绝换行符，杜绝任何通过邮箱字段注入额外邮件头(Header Injection)的可能。
    require('\r' !in normalized && '\n' !in normalized) { "邮箱地址无效" }
    val addresses = runCatching { InternetAddress.parse(normalized, true) }
        .getOrElse { throw IllegalArgumentException("邮箱地址无效", it) }
    require(addresses.size == 1 && addresses.single().address.equals(normalized, ignoreCase = true)) {
        "邮箱地址无效"
    }
    return normalized
}

internal inline fun <T> EmailService.withCacheKeyLock(cacheKey: String, action: () -> T): T {
    val cacheKeyLock = cacheKeyLocks.compute(cacheKey) { _, existing ->
        (existing ?: CacheKeyLock()).also { it.users++ }
    }!!
    try {
        return synchronized(cacheKeyLock.monitor, action)
    } finally {
        cacheKeyLocks.computeIfPresent(cacheKey) { _, current ->
            current.users--
            if (current.users == 0) null else current
        }
    }
}

/**
 * 清理过期的验证码和错误计数器 — 防内存泄漏。
 * 每分钟最多全量 sweep 一次（高频请求下不每次遍历）。
 */
@Synchronized
internal fun EmailService.sweepExpired() {
    val now = System.currentTimeMillis()
    if (now - lastSweepTime < SWEEP_INTERVAL_MS) return
    lastSweepTime = now
    codeCache.entries.removeIf { (_, value) -> now > value.second }
    // attemptCounter 没有时间信息，按"已经超过 CODE_EXPIRE_MS 没有对应 codeCache 条目"来清理
    attemptCounter.keys.removeAll { email -> !codeCache.containsKey(email) }
}

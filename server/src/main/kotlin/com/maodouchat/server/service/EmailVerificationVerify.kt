package com.maodouchat.server.service

/**
 * 验证验证码 — 原子比较并消费（单次有效）
 *
 * @param email 邮箱
 * @param code 用户输入
 * @param purpose 与发送时相同
 * @return 是否验证成功
 */
fun EmailService.verifyCode(email: String, code: String, purpose: String = PURPOSE_REGISTER): Boolean {
    val normalizedEmail = email.normalizedEmailStrict()
    val purposeKey = normalizePurpose(purpose)
    val cacheKey = cacheKey(purposeKey, normalizedEmail)
    return withCacheKeyLock(cacheKey) {
        val cached = codeCache[cacheKey] ?: return@withCacheKeyLock false
        if (attemptCounter.getOrDefault(cacheKey, 0) >= MAX_ATTEMPTS) {
            codeCache.remove(cacheKey, cached)
            attemptCounter.remove(cacheKey)
            logger.warn("Too many verification attempts for {} ({})", normalizedEmail, purposeKey)
            return@withCacheKeyLock false
        }

        val (storedCode, expireTime) = cached
        if (System.currentTimeMillis() > expireTime) {
            codeCache.remove(cacheKey, cached)
            attemptCounter.remove(cacheKey)
            return@withCacheKeyLock false
        }

        // 常量时间比较，防时序攻击（原本 storedCode == code 的 String.equals 非恒定时间）。
        // 只去掉首尾空白：输入框/粘贴常带空格或换行，不 trim 会把正确验证码判失败。
        if (constantTimeEquals(storedCode, code.trim())) {
            attemptCounter.remove(cacheKey)
            return@withCacheKeyLock codeCache.remove(cacheKey, cached)
        }

        val attempts = attemptCounter.merge(cacheKey, 1) { current, _ -> current + 1 } ?: 1
        if (attempts >= MAX_ATTEMPTS) {
            codeCache.remove(cacheKey, cached)
            attemptCounter.remove(cacheKey)
            logger.warn("Too many verification attempts for {} ({})", normalizedEmail, purposeKey)
        }
        false
    }
}

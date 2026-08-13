package com.maodouchat.server.service

import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import java.security.SecureRandom

/**
 * 邮件服务 — 发送验证码
 *
 * 使用 JavaMail 通过 SMTP 发送。配置从环境变量读取；
 * 未配置 SMTP_HOST 时进入开发模式：不发送邮件，仅在 server log 输出验证码。
 */
object EmailService {

    private val logger = LoggerFactory.getLogger(EmailService::class.java)
    private val secureRandom = SecureRandom()
    // codeCache 条目在 verifyCode 成功或达到 MAX_ATTEMPTS 时被移除，但"用户收不到邮件/放弃注册"的条目
    // 只能等自然过期。这里用定期 sweep 防止高流量下内存泄漏。
    /** cache key = purpose|email → (code, expireAt) */
    private val codeCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val attemptCounter = ConcurrentHashMap<String, Int>()
    private val cacheKeyLocks = ConcurrentHashMap<String, CacheKeyLock>()
    private val pendingCacheKeys = HashSet<String>()
    private val cacheCapacityLock = Any()
    private const val CODE_EXPIRE_MS = 5 * 60 * 1000L // 5 分钟过期
    private const val MAX_ATTEMPTS = 5 // 单邮箱最大错误尝试次数
    private const val MAX_CACHED_CODES = 50_000
    private var lastSweepTime = 0L
    private const val SWEEP_INTERVAL_MS = 60_000L // 每分钟最多 sweep 一次

    const val PURPOSE_REGISTER = "register"
    const val PURPOSE_RESET = "reset"

    // SMTP 配置（从环境变量读取；smtpHost 未配置时进入开发模式）
    private val smtpHost: String = System.getenv("SMTP_HOST").orEmpty()
    private val smtpPort = System.getenv("SMTP_PORT").orEmpty().ifBlank { "465" }
    private val smtpUser = System.getenv("SMTP_USER").orEmpty().ifBlank { "your-email@qq.com" }
    private val smtpPass = System.getenv("SMTP_PASS").orEmpty().ifBlank { "your-smtp-password" }
    private val fromName = System.getenv("SMTP_FROM_NAME").orEmpty().ifBlank { "毛豆聊天" }
    private val smtpTimeoutMs = System.getenv("SMTP_TIMEOUT_MS").orEmpty().toIntOrNull()
        ?.coerceIn(1_000, 120_000) ?: 15_000
    // 默认走标准 PKIX 证书校验；仅当显式设置 SMTP_SSL_TRUST=1 时才信任任意该主机证书
    //（自签名/内部 CA 场景）。默认不关闭校验，避免 SMTP 被中间人劫持泄露验证码与凭据。
    private val smtpSslTrust: Boolean =
        System.getenv("SMTP_SSL_TRUST").orEmpty().lowercase() in setOf("1", "true", "yes")

    private val isDevMode: Boolean
        get() = smtpHost.isBlank()

    // 仅当显式开启时才把明文验证码打印到日志，避免误配置（未设 SMTP）的生产环境泄露账号。
    private val devLogCodes: Boolean =
        System.getenv("DEV_LOG_CODES").orEmpty().lowercase() in setOf("1", "true", "yes")

    /**
     * 发送验证码 — 开发模式下不发送邮件，只在 log 输出验证码
     *
     * @param toEmail 收件人邮箱
     * @param purpose 用途：register / reset（互不影响）
     * @return 验证码（同时缓存）
     */
    fun sendVerificationCode(toEmail: String, purpose: String = PURPOSE_REGISTER): String {
        val email = toEmail.normalizedEmail()
        val purposeKey = normalizePurpose(purpose)
        val cacheKey = cacheKey(purposeKey, email)
        val code = generateCode()
        // 8.50 修复 M5：SMTP 发送移出 cacheKeyLock——原在锁内 Transport.send（最长
        // 15s×3 超时）会阻塞同邮箱所有注册/重置请求；锁只保护「预留槽位 + 写码」的原子性
        var codeToSend = code
        try {
            withCacheKeyLock(cacheKey) {
                sweepExpired()
                val reserved = reserveCacheSlot(cacheKey)
                if (!reserved) {
                    // 9.139：已有同键验证码（在途或有效期内）——复用旧码重发，避免并发发送时
                    // 后写覆盖前写导致先到邮件的验证码失效；无旧码 = 正在发送中，拒绝并发发送
                    val existing = codeCache[cacheKey]
                        ?: throw IllegalStateException("验证码发送中，请稍后再试")
                    if (System.currentTimeMillis() > existing.second) {
                        codeCache.remove(cacheKey)
                        check(reserveCacheSlot(cacheKey)) { "验证码缓存容量预留冲突" }
                        // 旧码已过期：沿用本次新生成的 code
                    } else {
                        codeToSend = existing.first
                    }
                }
            }
            val purposeLabel = if (purposeKey == PURPOSE_RESET) "重置密码" else "注册"
            if (isDevMode) {
                withCacheKeyLock(cacheKey) { storeCode(cacheKey, codeToSend) }
                // 开发模式：不发送邮件。默认绝不把明文验证码写进日志（避免误配置的生产环境泄露账号）；
                // 仅当显式设置 DEV_LOG_CODES=true 时才打印，方便本地调试。
                if (devLogCodes) {
                    logger.warn("Development verification code ({}) for {}: {}", purposeKey, email, codeToSend)
                } else {
                    logger.warn("Development mode: verification code generated for {} (set DEV_LOG_CODES=true to log it)", email)
                }
                return codeToSend
            }

            try {
                val props = Properties().apply {
                    put("mail.smtp.host", smtpHost)
                    put("mail.smtp.port", smtpPort)
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.ssl.enable", "true")
                    // 默认不设置 ssl.trust，由 JVM 信任库做标准证书校验；仅在显式开启时才跳过校验。
                    if (smtpSslTrust) put("mail.smtp.ssl.trust", smtpHost)
                    put("mail.smtp.connectiontimeout", smtpTimeoutMs.toString())
                    put("mail.smtp.timeout", smtpTimeoutMs.toString())
                    put("mail.smtp.writetimeout", smtpTimeoutMs.toString())
                }

                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(smtpUser, smtpPass)
                    }
                })

                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(smtpUser, fromName, "UTF-8"))
                    setRecipient(Message.RecipientType.TO, InternetAddress(email, true))
                    subject = "毛豆聊天 $purposeLabel 验证码"
                    setText("您的${purposeLabel}验证码是：$codeToSend\n\n该验证码 5 分钟内有效。\n\n如非本人操作，请忽略此邮件。", "UTF-8")
                }

                Transport.send(message)
                withCacheKeyLock(cacheKey) { storeCode(cacheKey, codeToSend) }
                logger.info("Verification code email ({}) sent to {}", purposeKey, email)
                return codeToSend
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to send verification code: {}", e.message)
                throw IllegalStateException("验证码邮件发送失败", e)
            }
        } finally {
            // releaseCacheReservation 幂等（未预留时 remove 不存在的 key 无副作用）
            withCacheKeyLock(cacheKey) { releaseCacheReservation(cacheKey) }
        }
    }

    /**
     * 验证验证码 — 原子比较并消费（单次有效）
     *
     * @param email 邮箱
     * @param code 用户输入
     * @param purpose 与发送时相同
     * @return 是否验证成功
     */
    fun verifyCode(email: String, code: String, purpose: String = PURPOSE_REGISTER): Boolean {
        val normalizedEmail = email.normalizedEmail()
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

            // 常量时间比较，防时序攻击（原本 storedCode == code 的 String.equals 非恒定时间）
            if (constantTimeEquals(storedCode, code)) {
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

    private fun normalizePurpose(purpose: String): String {
        val p = purpose.trim().lowercase()
        return if (p == PURPOSE_RESET) PURPOSE_RESET else PURPOSE_REGISTER
    }

    private fun cacheKey(purpose: String, email: String): String = "$purpose|$email"

    private fun generateCode(): String {
        return secureRandom.nextInt(900_000).plus(100_000).toString()
    }

    /**
     * 常量时间字符串比较，避免 [String.equals] 的早退时序侧信道。
     * 验证码均为固定 6 位，长度相等分支不泄露长度信息；差异仅在内容。
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        var diff = if (ab.size == bb.size) 0 else 1
        val len = minOf(ab.size, bb.size)
        for (i in 0 until len) {
            diff = diff or (ab[i].toInt() xor bb[i].toInt())
        }
        return diff == 0
    }

    private fun storeCode(cacheKey: String, code: String) {
        val cacheEntry = Pair(code, System.currentTimeMillis() + CODE_EXPIRE_MS)
        codeCache[cacheKey] = cacheEntry
        attemptCounter.remove(cacheKey)
    }

    private fun reserveCacheSlot(cacheKey: String): Boolean = synchronized(cacheCapacityLock) {
        if (codeCache.containsKey(cacheKey)) return@synchronized false
        if (codeCache.size + pendingCacheKeys.size >= MAX_CACHED_CODES) {
            throw IllegalStateException("验证码服务繁忙，请稍后再试")
        }
        check(pendingCacheKeys.add(cacheKey)) { "验证码缓存容量预留冲突" }
        true
    }

    private fun releaseCacheReservation(cacheKey: String) {
        synchronized(cacheCapacityLock) {
            pendingCacheKeys.remove(cacheKey)
        }
    }

    private fun String.normalizedEmail(): String {
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

    private inline fun <T> withCacheKeyLock(cacheKey: String, action: () -> T): T {
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

    private class CacheKeyLock(
        val monitor: Any = Any(),
        var users: Int = 0,
    )

    /**
     * 清理过期的验证码和错误计数器 — 防内存泄漏。
     * 每分钟最多全量 sweep 一次（高频请求下不每次遍历）。
     */
    @Synchronized
    private fun sweepExpired() {
        val now = System.currentTimeMillis()
        if (now - lastSweepTime < SWEEP_INTERVAL_MS) return
        lastSweepTime = now
        codeCache.entries.removeIf { (_, value) -> now > value.second }
        // attemptCounter 没有时间信息，按"已经超过 CODE_EXPIRE_MS 没有对应 codeCache 条目"来清理
        attemptCounter.keys.removeAll { email -> !codeCache.containsKey(email) }
    }
}

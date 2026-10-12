package com.maodouchat.server.service

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import java.security.SecureRandom

/**
 * 邮件服务 — 发送验证码
 *
 * 使用 JavaMail 通过 SMTP 发送。配置从环境变量读取；
 * 未配置 SMTP_HOST 时进入开发模式：不发送邮件，仅在 server log 输出验证码。
 */
object EmailService {

    internal val logger = LoggerFactory.getLogger(EmailService::class.java)
    internal val secureRandom = SecureRandom()
    // codeCache 条目在 verifyCode 成功或达到 MAX_ATTEMPTS 时被移除，但"用户收不到邮件/放弃注册"的条目
    // 只能等自然过期。这里用定期 sweep 防止高流量下内存泄漏。
    /** cache key = purpose|email → (code, expireAt) */
    internal val codeCache = ConcurrentHashMap<String, Pair<String, Long>>()
    internal val attemptCounter = ConcurrentHashMap<String, Int>()
    internal val cacheKeyLocks = ConcurrentHashMap<String, CacheKeyLock>()
    internal val pendingCacheKeys = HashSet<String>()
    internal val cacheCapacityLock = Any()
    internal const val CODE_EXPIRE_MS = 5 * 60 * 1000L // 5 分钟过期
    internal const val MAX_ATTEMPTS = 5 // 单邮箱最大错误尝试次数
    internal const val MAX_CACHED_CODES = 50_000
    internal var lastSweepTime = 0L
    internal const val SWEEP_INTERVAL_MS = 60_000L // 每分钟最多 sweep 一次

    const val PURPOSE_REGISTER = "register"
    const val PURPOSE_RESET = "reset"

    // SMTP 配置（从环境变量读取；smtpHost 未配置时进入开发模式）
    internal val smtpHost: String = System.getenv("SMTP_HOST").orEmpty()
    internal val smtpPort = System.getenv("SMTP_PORT").orEmpty().ifBlank { "465" }
    internal val smtpUser = System.getenv("SMTP_USER").orEmpty().ifBlank { "your-email@qq.com" }
    internal val smtpPass = System.getenv("SMTP_PASS").orEmpty().ifBlank { "your-smtp-password" }
    internal val fromName = System.getenv("SMTP_FROM_NAME").orEmpty().ifBlank { "毛豆聊天" }
    internal val smtpTimeoutMs = System.getenv("SMTP_TIMEOUT_MS").orEmpty().toIntOrNull()
        ?.coerceIn(1_000, 120_000) ?: 15_000
    // 默认走标准 PKIX 证书校验；仅当显式设置 SMTP_SSL_TRUST=1 时才信任任意该主机证书
    //（自签名/内部 CA 场景）。默认不关闭校验，避免 SMTP 被中间人劫持泄露验证码与凭据。
    internal val smtpSslTrust: Boolean =
        System.getenv("SMTP_SSL_TRUST").orEmpty().lowercase() in setOf("1", "true", "yes")

    internal val isDevMode: Boolean
        get() = smtpHost.isBlank()

    // 仅当显式开启时才把明文验证码打印到日志，避免误配置（未设 SMTP）的生产环境泄露账号。
    internal val devLogCodes: Boolean =
        System.getenv("DEV_LOG_CODES").orEmpty().lowercase() in setOf("1", "true", "yes")
}

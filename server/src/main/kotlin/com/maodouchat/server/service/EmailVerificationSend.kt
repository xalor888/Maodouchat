package com.maodouchat.server.service

import java.util.Properties
import java.util.concurrent.CancellationException
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

/**
 * 发送验证码 — 开发模式下不发送邮件，只在 log 输出验证码
 *
 * @param toEmail 收件人邮箱
 * @param purpose 用途：register / reset（互不影响）
 * @return 验证码（同时缓存）
 */
fun EmailService.sendVerificationCode(toEmail: String, purpose: String = PURPOSE_REGISTER): String {
    val email = toEmail.normalizedEmailStrict()
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

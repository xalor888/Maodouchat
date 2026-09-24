package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.service.SealedSenderCertificateService
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 密钥分离（G328c）：`JWT_SECRET` 此前同时是 access token 签名密钥、sealed-sender 证书
 * 的 HMAC 密钥、dev_session 的签名密钥。一把密钥服务三个协议意味着：无法单独轮换
 * （换掉它等于把所有人踢下线），且任何一处协议的弱点都波及全部。
 *
 * 这里只测**派生与覆盖**这一类纯配置行为，不碰 JwtConfig/JwtVerifier——那些对象把
 * 密钥缓存在 val 里（进程内首次访问即固定），与本类里反复改系统属性的做法会互相污染。
 * 需要起服务的隔离测试在同包的 `DeveloperSessionKeyIsolationTest`（forkEvery=1 按类分 JVM）。
 */
class PurposeKeyDerivationTest {

    private inline fun <T> withProperty(name: String, value: String?, block: () -> T): T {
        val old = System.getProperty(name)
        try {
            if (value != null) System.setProperty(name, value) else System.clearProperty(name)
            return block()
        } finally {
            if (old != null) System.setProperty(name, old) else System.clearProperty(name)
        }
    }

    /** env() 优先读真实环境变量；真的在环境里设了就没法在进程内覆盖，跳过而不是误报。 */
    private fun assumeNoEnvVar(name: String) {
        assumeTrue(System.getenv(name).isNullOrBlank()) { "$name 已在环境变量中设置，无法在进程内覆盖" }
    }

    /** 用给定密钥按 sealed-sender 的格式签一张证书（模拟「主密钥泄露后可伪造」）。 */
    private fun forgeSealedSenderCert(secret: String, userId: String, deviceId: Int, expiresAt: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val payload = "v1|$userId|$deviceId|$expiresAt"
        val sig = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val raw = "v1.$userId.$deviceId.$expiresAt.$sig"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    fun `purpose keys differ from the master secret and from each other`() {
        assumeNoEnvVar("SEALED_SENDER_SECRET")
        assumeNoEnvVar("DEVELOPER_SESSION_SECRET")
        withProperty("JWT_SECRET", "master-secret-123456789012345678901234567890") {
            val master = ServerConfig.jwtSecret
            val sealed = ServerConfig.sealedSenderSecret
            val dev = ServerConfig.developerSessionSecret
            assertNotEquals(master, sealed, "sealed-sender 子密钥不得等于主密钥")
            assertNotEquals(master, dev, "dev_session 子密钥不得等于主密钥")
            assertNotEquals(sealed, dev, "两个用途之间也必须分离")
            assertTrue(sealed.length >= 32, "派生密钥应足够长，实际 ${sealed.length}")
            assertTrue(dev.length >= 32)
        }
    }

    @Test
    fun `purpose keys are stable for the same master secret`() {
        assumeNoEnvVar("SEALED_SENDER_SECRET")
        withProperty("JWT_SECRET", "master-secret-123456789012345678901234567890") {
            assertEquals(
                ServerConfig.sealedSenderSecret,
                ServerConfig.sealedSenderSecret,
                "派生必须是确定性的——否则每次重启旧证书全部失效",
            )
        }
    }

    @Test
    fun `explicit purpose secret overrides the derived one and leaves the master alone`() {
        assumeNoEnvVar("SEALED_SENDER_SECRET")
        withProperty("JWT_SECRET", "master-secret-123456789012345678901234567890") {
            val derived = ServerConfig.sealedSenderSecret
            withProperty("SEALED_SENDER_SECRET", "independent-sealed-sender-key-32-chars") {
                assertEquals("independent-sealed-sender-key-32-chars", ServerConfig.sealedSenderSecret)
                assertNotEquals(derived, ServerConfig.sealedSenderSecret)
                assertEquals("master-secret-123456789012345678901234567890", ServerConfig.jwtSecret)
            }
        }
    }

    @Test
    fun `certificate signed with the master secret is rejected`() {
        assumeNoEnvVar("SEALED_SENDER_SECRET")
        withProperty("JWT_SECRET", "master-secret-123456789012345678901234567890") {
            val expiresAt = System.currentTimeMillis() + 3600_000L
            assertNull(
                SealedSenderCertificateService.verify(
                    forgeSealedSenderCert(ServerConfig.jwtSecret, "u1", 1, expiresAt),
                ),
                "主密钥不该还能签出被接受的 sealed-sender 证书——否则密钥分离是假的",
            )
            // 正向对照：用派生的用途密钥按同格式签名必须被接受。
            // 没有这一条，上面那条用「格式写错了」也能通过。
            assertNotNull(
                SealedSenderCertificateService.verify(
                    forgeSealedSenderCert(ServerConfig.sealedSenderSecret, "u1", 1, expiresAt),
                ),
            )
        }
    }

    @Test
    fun `rotating the purpose secret invalidates old certificates`() {
        assumeNoEnvVar("SEALED_SENDER_SECRET")
        withProperty("JWT_SECRET", "master-secret-123456789012345678901234567890") {
            val issued = SealedSenderCertificateService.issue("u1", 1, ttlMs = 3600_000L)
            assertNotNull(issued)
            // 显式换成独立密钥：这就是「单独轮换」的可操作性——只影响这一个用途，
            // 主密钥与登录态毫发无损（sealed-sender 证书 24 小时内自然换新）。
            withProperty("SEALED_SENDER_SECRET", "independent-sealed-sender-key-32-chars") {
                assertNull(SealedSenderCertificateService.verify(issued.certificate))
                val reIssued = SealedSenderCertificateService.issue("u1", 1, ttlMs = 3600_000L)
                assertNotNull(reIssued)
                assertNotNull(SealedSenderCertificateService.verify(reIssued.certificate))
            }
        }
    }
}

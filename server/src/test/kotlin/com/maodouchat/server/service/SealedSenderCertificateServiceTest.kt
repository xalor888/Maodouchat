package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SealedSenderCertificateServiceTest {

    private inline fun <T> withJwtSecret(secret: String?, block: () -> T): T {
        val old = System.getProperty("JWT_SECRET")
        try {
            if (secret != null) {
                System.setProperty("JWT_SECRET", secret)
            } else {
                System.clearProperty("JWT_SECRET")
            }
            return block()
        } finally {
            if (old != null) {
                System.setProperty("JWT_SECRET", old)
            } else {
                System.clearProperty("JWT_SECRET")
            }
        }
    }

    @Test
    fun `issue and verify certificate successfully`() {
        withJwtSecret("test-jwt-secret-at-least-32-chars-long-abc") {
            val issued = SealedSenderCertificateService.issue("u1001", 2, ttlMs = 3600_000L)
            assertNotNull(issued)
            assertEquals("u1001", issued.userId)
            assertEquals(2, issued.deviceId)

            val verified = SealedSenderCertificateService.verify(issued.certificate)
            assertNotNull(verified)
            assertEquals("u1001", verified.userId)
            assertEquals(2, verified.deviceId)
            assertEquals(issued.expiresAt, verified.expiresAt)
        }
    }

    @Test
    fun `reject expired or tampered certificate`() {
        val issued = withJwtSecret("test-jwt-secret-at-least-32-chars-long-abc") {
            val cert = SealedSenderCertificateService.issue("u1001", 1, ttlMs = 60_000L)
            assertNotNull(cert)
            cert
        }

        // Tamper secret
        withJwtSecret("different-secret-different-secret-32ch") {
            assertNull(SealedSenderCertificateService.verify(issued.certificate))
        }

        // Tamper certificate payload
        withJwtSecret("test-jwt-secret-at-least-32-chars-long-abc") {
            assertNull(SealedSenderCertificateService.verify(issued.certificate + "bad"))
            assertNull(SealedSenderCertificateService.verify("invalid.cert.format"))
        }
    }

    @Test
    fun `issue returns null when userId is blank or secret is blank`() {
        withJwtSecret("") {
            assertNull(SealedSenderCertificateService.issue("u1001", 1))
        }
        withJwtSecret("test-jwt-secret-at-least-32-chars-long-abc") {
            assertNull(SealedSenderCertificateService.issue("", 1))
            assertNull(SealedSenderCertificateService.issue("   ", 1))
        }
    }
}

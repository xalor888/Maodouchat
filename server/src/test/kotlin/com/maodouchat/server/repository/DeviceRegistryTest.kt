package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.signal.libsignal.protocol.ecc.Curve
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B03 [DeviceRegistry] 状态机测试（对照重构清单 B03 Gate：设备批准防重放、
 * 被撤销设备不可再用；状态明确为 pending/confirmed/revoked 语义）。
 */
class DeviceRegistryTest {

    private var database: Database? = null

    @org.junit.jupiter.api.AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `first device auto-confirms, second stays pending`() {
        setupDb()
        val registry = DeviceRegistry()
        registry.touchDevice(USER_ID, 1, "primary")
        registry.touchDevice(USER_ID, 2, "secondary")
        assertTrue(registry.isDeviceConfirmed(USER_ID, 1))
        assertFalse(registry.isDeviceConfirmed(USER_ID, 2))
        assertEquals(DEVICE_STATUS_PENDING, deviceStatus(2))
    }

    @Test
    fun `confirm happy path then replay is already-confirmed`() {
        setupDb()
        val approver = newIdentity()
        val target = newIdentity()
        val registry = DeviceRegistry()
        // 先落设备行再插密钥：initialDeviceStatus 依据已有机端密钥判断，
        // 提前插 key 会让首设备误判为 PENDING。
        registry.touchDevice(USER_ID, 1)
        registry.touchDevice(USER_ID, 2)
        insertIdentityKey(1, approver.publicKey)
        insertIdentityKey(2, target.publicKey)

        assertEquals(
            ConfirmDeviceResult.CONFIRMED,
            registry.confirmDevice(USER_ID, 2, 1, validProof(approver.privateKey, 1, 2, target.publicKey)),
        )
        assertTrue(registry.isDeviceConfirmed(USER_ID, 2))
        assertEquals(
            ConfirmDeviceResult.ALREADY_CONFIRMED,
            registry.confirmDevice(USER_ID, 2, 1, validProof(approver.privateKey, 1, 2, target.publicKey)),
        )
    }

    @Test
    fun `confirm rejects bad proofs and untrusted approvers`() {
        setupDb()
        val approver = newIdentity()
        val target = newIdentity()
        val registry = DeviceRegistry()
        registry.touchDevice(USER_ID, 1)
        registry.touchDevice(USER_ID, 2)
        insertIdentityKey(1, approver.publicKey)
        insertIdentityKey(2, target.publicKey)

        // Unknown target device.
        assertEquals(ConfirmDeviceResult.NOT_FOUND, registry.confirmDevice(USER_ID, 9, 1, "e30="))
        // Garbage signature.
        assertEquals(
            ConfirmDeviceResult.INVALID_PROOF,
            registry.confirmDevice(USER_ID, 2, 1, Base64.getEncoder().encodeToString("nope".toByteArray())),
        )
        // Signature from the wrong key.
        val stranger = newIdentity()
        assertEquals(
            ConfirmDeviceResult.INVALID_PROOF,
            registry.confirmDevice(USER_ID, 2, 1, validProof(stranger.privateKey, 1, 2, target.publicKey)),
        )
        // Self-approval and pending approver are untrusted.
        assertEquals(
            ConfirmDeviceResult.APPROVER_NOT_TRUSTED,
            registry.confirmDevice(USER_ID, 2, 2, validProof(target.privateKey, 2, 2, target.publicKey)),
        )
        registry.touchDevice(USER_ID, 3)
        insertIdentityKey(3, newIdentity().publicKey)
        assertEquals(
            ConfirmDeviceResult.APPROVER_NOT_TRUSTED,
            registry.confirmDevice(USER_ID, 2, 3, validProof(approver.privateKey, 3, 2, target.publicKey)),
        )
        // Out-of-range ids and blank proof.
        assertEquals(ConfirmDeviceResult.INVALID, registry.confirmDevice(USER_ID, 0, 1, "e30="))
        assertEquals(ConfirmDeviceResult.INVALID_PROOF, registry.confirmDevice(USER_ID, 2, 1, "   "))
        // Target still pending after all rejections.
        assertFalse(registry.isDeviceConfirmed(USER_ID, 2))
    }

    @Test
    fun `delete last confirmed is blocked, pending delete succeeds`() {
        setupDb()
        val registry = DeviceRegistry()
        registry.touchDevice(USER_ID, 1)
        registry.touchDevice(USER_ID, 2)

        assertEquals(
            DeleteDeviceResult.LAST_CONFIRMED,
            registry.deleteDeviceAndRevokeSessionsGuarded(USER_ID, 1).result,
        )
        assertTrue(registry.isDeviceConfirmed(USER_ID, 1))

        assertEquals(
            DeleteDeviceResult.DELETED,
            registry.deleteDeviceAndRevokeSessionsGuarded(USER_ID, 2).result,
        )
        assertEquals(DeleteDeviceResult.NOT_FOUND, registry.deleteDeviceGuarded(USER_ID, 2))
    }

    // ---- fixtures ----

    private fun setupDb() {
        val dbUrl =
            "jdbc:h2:mem:device-registry-${kotlin.random.Random.nextInt(1_000_000)}-${counter.incrementAndGet()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            Users.insert {
                it[Users.id] = USER_ID
                it[Users.name] = "device-test"
                it[Users.email] = "device-test@example.test"
                it[Users.passwordHash] = "x"
            }
        }
    }

    private fun insertIdentityKey(deviceId: Int, publicKeyBase64: String) {
        transaction {
            SignalKeys.insert {
                it[SignalKeys.id] = "ik_${UUID.randomUUID()}"
                it[SignalKeys.userId] = USER_ID
                it[SignalKeys.deviceId] = deviceId
                it[SignalKeys.keyType] = "identity_key"
                it[SignalKeys.keyData] = publicKeyBase64
                it[SignalKeys.createdAt] = System.currentTimeMillis()
            }
        }
    }

    private fun deviceStatus(deviceId: Int): String? = transaction {
        SignalDevices.selectAll().where {
            (SignalDevices.userId eq USER_ID) and (SignalDevices.deviceId eq deviceId)
        }.firstOrNull()?.get(SignalDevices.status)
    }

    private fun validProof(
        approverPrivate: org.signal.libsignal.protocol.ecc.ECPrivateKey,
        approverDeviceId: Int,
        targetDeviceId: Int,
        targetIdentityKeyBase64: String,
    ): String {
        val payload = buildString {
            append("maodouchat-device-confirm:v1\n")
            append(USER_ID)
            append('\n')
            append(approverDeviceId)
            append('\n')
            append(targetDeviceId)
            append('\n')
            append(targetIdentityKeyBase64)
        }.toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(Curve.calculateSignature(approverPrivate, payload))
    }

    private fun newIdentity(): IdentityMaterial {
        val pair = Curve.generateKeyPair()
        return IdentityMaterial(
            publicKey = Base64.getEncoder().encodeToString(pair.publicKey.serialize()),
            privateKey = pair.privateKey,
        )
    }

    private data class IdentityMaterial(
        val publicKey: String,
        val privateKey: org.signal.libsignal.protocol.ecc.ECPrivateKey,
    )

    private companion object {
        const val USER_ID = "device-user"
        val counter = AtomicInteger()
    }
}

package com.maodouchat.server.repository

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalKeyUploadRepositoryTest {

    private var database: Database? = null

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `identity mismatch wins over another active session occupying device id`() {
        setupDb()
        insertSession(id = "occupying-session", deviceId = 7)
        val repository = SignalKeyRepository()
        val publishedIdentity = newIdentity()

        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "occupying-session", 7, publishedIdentity),
        )
        insertSession(id = "contending-session", deviceId = null)
        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.DEVICE_IDENTITY_MISMATCH,
            upload(repository, "contending-session", 7, newIdentity()),
        )
    }

    @Test
    fun `same identity on device occupied by another session remains retryable conflict`() {
        setupDb()
        insertSession(id = "occupying-session", deviceId = 7)
        val repository = SignalKeyRepository()
        val publishedIdentity = newIdentity()
        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "occupying-session", 7, publishedIdentity),
        )
        insertSession(id = "contending-session", deviceId = null)

        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.DEVICE_ID_CONFLICT,
            upload(repository, "contending-session", 7, publishedIdentity),
        )
    }

    @Test
    fun `bound session with a different identity is a session conflict`() {
        setupDb()
        insertSession(id = "bound-session", deviceId = null)
        val repository = SignalKeyRepository()
        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "bound-session", 7, newIdentity()),
        )

        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.SESSION_CONFLICT,
            upload(repository, "bound-session", 7, newIdentity()),
        )
    }

    @Test
    fun `new device is pending and excluded from confirmed fanout`() {
        setupDb()
        val repository = SignalKeyRepository()
        insertSession(id = "first-session", deviceId = null)
        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "first-session", 1, newIdentity()),
        )

        insertSession(id = "new-session", deviceId = null)
        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "new-session", 7, newIdentity()),
        )

        val allDevices = repository.getDeviceInfos(USER_ID, currentDeviceId = 7, includePending = true)
        assertEquals(
            SignalKeyRepository.DEVICE_STATUS_PENDING,
            allDevices.single { it.deviceId == 7 }.status,
        )
        assertTrue(repository.getDeviceIds(USER_ID).contains(1))
        assertFalse(repository.getDeviceIds(USER_ID).contains(7))
        assertTrue(repository.getDeviceInfos(USER_ID, includePending = false).none { it.deviceId == 7 })
        assertEquals(setOf(USER_ID to 1), repository.getConfirmedDeviceTargets(listOf(USER_ID)))
    }

    @Test
    fun `unconsumed prekey with reused id is replaced and same key is idempotent`() {
        setupDb()
        insertSession(id = "prekey-session", deviceId = null)
        val repository = SignalKeyRepository()
        val identity = newIdentity()
        val first = Curve.generateKeyPair()
        val replacement = Curve.generateKeyPair()

        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "prekey-session", 7, identity, listOf(preKey(42, first))),
        )
        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "prekey-session", 7, identity, listOf(preKey(42, replacement))),
        )

        val replacementData = encodedPublicKey(replacement)
        transaction {
            val rows = SignalKeys.selectAll().where {
                (SignalKeys.userId eq USER_ID) and
                    (SignalKeys.deviceId eq 7) and
                    (SignalKeys.keyType eq "pre_key") and
                    (SignalKeys.keyId eq 42)
            }.toList()
            assertEquals(1, rows.size)
            assertEquals(replacementData, rows.single()[SignalKeys.keyData])
        }

        // Retrying the exact same key package must not create another row or rotate the data.
        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "prekey-session", 7, identity, listOf(preKey(42, replacement))),
        )
        transaction {
            val rows = SignalKeys.selectAll().where {
                (SignalKeys.userId eq USER_ID) and
                    (SignalKeys.deviceId eq 7) and
                    (SignalKeys.keyId eq 42) and
                    (SignalKeys.keyType eq "pre_key")
            }.toList()
            assertEquals(1, rows.size)
            assertEquals(replacementData, rows.single()[SignalKeys.keyData])
        }
    }

    @Test
    fun `consumed prekey is never revived or overwritten by a reused id`() {
        setupDb()
        insertSession(id = "consumed-session", deviceId = null)
        val repository = SignalKeyRepository()
        val identity = newIdentity()
        val original = Curve.generateKeyPair()
        val regenerated = Curve.generateKeyPair()

        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "consumed-session", 7, identity, listOf(preKey(43, original))),
        )
        val bundle = repository.getBundle(USER_ID, 7, consumeOneTimePreKey = true)
        assertEquals(43, bundle?.preKeyId)

        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "consumed-session", 7, identity, listOf(preKey(43, regenerated))),
        )

        transaction {
            val rows = SignalKeys.selectAll().where {
                (SignalKeys.userId eq USER_ID) and
                    (SignalKeys.deviceId eq 7) and
                    (SignalKeys.keyId eq 43)
            }.toList()
            assertEquals(1, rows.size)
            assertEquals("consumed_pre_key", rows.single()[SignalKeys.keyType])
            assertEquals(encodedPublicKey(original), rows.single()[SignalKeys.keyData])
        }
    }

    @Test
    fun `consumed prekey retention starts when it is consumed`() {
        setupDb()
        insertSession(id = "retention-session", deviceId = null)
        val repository = SignalKeyRepository()
        val identity = newIdentity()

        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            upload(repository, "retention-session", 7, identity, listOf(preKey(46, Curve.generateKeyPair()))),
        )
        // Simulate a key uploaded before the retention period, then consume it now.
        transaction {
            SignalKeys.update({
                (SignalKeys.userId eq USER_ID) and
                    (SignalKeys.deviceId eq 7) and
                    (SignalKeys.keyId eq 46) and
                    (SignalKeys.keyType eq "pre_key")
            }) {
                it[SignalKeys.createdAt] = System.currentTimeMillis() - 31L * 86_400_000L
            }
        }
        assertEquals(46, repository.getBundle(USER_ID, 7, consumeOneTimePreKey = true)?.preKeyId)
        assertEquals(0, repository.purgeConsumedPreKeys(retentionDays = 30))

        // Once the refreshed retention timestamp is made old, normal cleanup can remove it.
        transaction {
            SignalKeys.update({
                (SignalKeys.userId eq USER_ID) and
                    (SignalKeys.deviceId eq 7) and
                    (SignalKeys.keyId eq 46) and
                    (SignalKeys.keyType eq "consumed_pre_key")
            }) {
                it[SignalKeys.createdAt] = System.currentTimeMillis() - 31L * 86_400_000L
            }
        }
        assertEquals(1, repository.purgeConsumedPreKeys(retentionDays = 30))
    }

    @Test
    fun `malformed prekey public key is rejected before publishing bundle`() {
        setupDb()
        insertSession(id = "invalid-prekey-session", deviceId = null)
        val repository = SignalKeyRepository()
        val identity = newIdentity()

        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.INVALID_PRE_KEY,
            upload(
                repository,
                "invalid-prekey-session",
                7,
                identity,
                listOf(SignalKeyRepository.PreKeyUpload(44, "not-a-curve-point")),
            ),
        )
        transaction {
            assertEquals(0L, SignalKeys.selectAll().count())
            val session = AuthSessions.selectAll().where { AuthSessions.id eq "invalid-prekey-session" }.single()
            assertEquals(null, session[AuthSessions.signalDeviceId])
        }
    }

    @Test
    fun `duplicate prekey id with different public keys is rejected`() {
        setupDb()
        insertSession(id = "duplicate-prekey-session", deviceId = null)
        val repository = SignalKeyRepository()
        val identity = newIdentity()

        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.INVALID_PRE_KEY,
            upload(
                repository,
                "duplicate-prekey-session",
                7,
                identity,
                listOf(
                    preKey(45, Curve.generateKeyPair()),
                    preKey(45, Curve.generateKeyPair()),
                ),
            ),
        )
        transaction {
            assertEquals(0L, SignalKeys.selectAll().count())
        }
    }

    private fun setupDb() {
        val dbUrl =
            "jdbc:h2:mem:signal-key-upload-${kotlin.random.Random.nextInt(1_000_000)}-${counter.incrementAndGet()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            Users.insert {
                it[Users.id] = USER_ID
                it[Users.name] = "signal-test"
                it[Users.email] = "signal-test@example.test"
                it[Users.passwordHash] = "x"
            }
        }
    }

    private fun insertSession(id: String, deviceId: Int?) {
        val now = System.currentTimeMillis()
        transaction {
            AuthSessions.insert {
                it[AuthSessions.id] = id
                it[AuthSessions.userId] = USER_ID
                it[AuthSessions.signalDeviceId] = deviceId
                it[AuthSessions.createdAt] = now
                it[AuthSessions.updatedAt] = now
            }
        }
    }

    private fun upload(
        repository: SignalKeyRepository,
        sessionId: String,
        deviceId: Int,
        identity: IdentityMaterial,
        preKeys: List<SignalKeyRepository.PreKeyUpload> = emptyList(),
    ): SignalKeyRepository.UploadKeyPackageResult {
        val signedPreKeyPair = Curve.generateKeyPair()
        val signature = Curve.calculateSignature(
            identity.privateKey,
            signedPreKeyPair.publicKey.serialize(),
        )
        return repository.uploadKeyPackage(
            userId = USER_ID,
            authSessionId = sessionId,
            deviceId = deviceId,
            identityKey = identity.publicKey,
            registrationId = 12_345,
            signedPreKeyId = 1,
            signedPreKey = Base64.getEncoder().encodeToString(signedPreKeyPair.publicKey.serialize()),
            signedPreKeySignature = Base64.getEncoder().encodeToString(signature),
            preKeys = preKeys,
        )
    }

    private fun preKey(id: Int, pair: org.signal.libsignal.protocol.ecc.ECKeyPair): SignalKeyRepository.PreKeyUpload =
        SignalKeyRepository.PreKeyUpload(id, encodedPublicKey(pair))

    private fun encodedPublicKey(pair: org.signal.libsignal.protocol.ecc.ECKeyPair): String =
        Base64.getEncoder().encodeToString(pair.publicKey.serialize())

    private fun newIdentity(): IdentityMaterial {
        val pair = Curve.generateKeyPair()
        return IdentityMaterial(
            publicKey = Base64.getEncoder().encodeToString(pair.publicKey.serialize()),
            privateKey = pair.privateKey,
        )
    }

    private data class IdentityMaterial(
        val publicKey: String,
        val privateKey: ECPrivateKey,
    )

    private companion object {
        const val USER_ID = "signal-key-user"
        val counter = AtomicInteger()
    }
}

package com.maodouchat.crypto

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.dao.IdentityTrustDao
import com.maodouchat.data.local.dao.SignalKeyDao
import com.maodouchat.data.local.entity.IdentityTrustEntity
import com.maodouchat.data.local.entity.SignalKeyEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.UUID

/**
 * E2EE 证据的第二层（G18）：**本仓库生产 store** + **跨重启存活**。
 *
 * G17 的 `SignalE2eeRoundTripTest` 用 libsignal 自带的内存 store，只证明密码学可用；
 * 真正会藏 bug 的是本仓库的 [PersistentSignalProtocolStore]：它的 `loadPreKey`/`loadSession`
 * **只读内存 map**，写操作经 `daoWrite` 写穿到 Room，重启靠 `loadPersistedState()` 回填。
 * 本文件就是在这一层取证。
 *
 * 能证明什么：
 * - 两个参与方都用**生产** `PersistentSignalProtocolStore`（Room 支撑）完成真 X3DH 往返，
 *   棘轮双向；
 * - 写穿是真的：DAO 里确实有 identity / session 行，且被消费的一次性 PreKey 行已删除；
 * - **重启存活**：新 store 实例 + `loadPersistedState()` 后仍能解开对端后续消息；
 * - **反过来**：不调用 `loadPersistedState()` 的新实例必须解不出来——证明「能继续」真的
 *   来自持久化回填，而不是内存残留或别的巧合；
 * - 行损坏被丢弃并计数，随后解密**大声失败**（不返回垃圾）；
 * - 持久化失败被记录（写路径）/ 抛出（读路径），不静默；
 * - 账号隔离：另一个 accountId 的 store 解同一会话必须失败。
 *
 * 仍**不能**证明什么：`SignalDirectCipher`/`SignalProtocol.initialize` 的完整装配路径、
 * 群 SenderKey 分发、真实跨进程/跨网络双设备投递、日志/导出/备份、生产 PostgreSQL。
 */
@RunWith(AndroidJUnit4::class)
class PersistentSignalStoreRoundTripTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 一个真实账号在本机的真实生产 store。 */
    private inner class Party(val accountId: String, val deviceId: Int) {
        val identity: IdentityKeyPair = IdentityKeyPair.generate()
        val registrationId: Int = KeyHelper.generateRegistrationId(false)
        val address = SignalProtocolAddress(accountId, deviceId)

        val preKeyId = 31337 + deviceId
        private val preKeyPair: ECKeyPair = Curve.generateKeyPair()
        val signedPreKeyId = 22222 + deviceId
        private val signedPreKeyPair: ECKeyPair = Curve.generateKeyPair()
        private val signedPreKeySignature: ByteArray =
            Curve.calculateSignature(identity.privateKey, signedPreKeyPair.publicKey.serialize())

        var store: PersistentSignalProtocolStore = newStore()

        init {
            store.putPreKey(PreKeyRecord(preKeyId, preKeyPair))
            store.putSignedPreKey(
                SignedPreKeyRecord(signedPreKeyId, 1L, signedPreKeyPair, signedPreKeySignature),
            )
        }

        /** 与 [store] 同库同账号同身份的新实例（模拟进程重启）。 */
        fun newStore(): PersistentSignalProtocolStore = PersistentSignalProtocolStore(
            db.signalKeyDao(),
            db.identityTrustDao(),
            accountId,
            identity,
            registrationId,
        )

        fun bundle(): PreKeyBundle = PreKeyBundle(
            registrationId,
            deviceId,
            preKeyId,
            preKeyPair.publicKey,
            signedPreKeyId,
            signedPreKeyPair.publicKey,
            signedPreKeySignature,
            identity.publicKey,
        )

        fun daoRows(): List<String> = runBlocking {
            db.signalKeyDao().getKeysWithPrefix("user:$accountId:").map { it.keyType }
        }
    }

    private fun plaintext(label: String) = "$label-${UUID.randomUUID()}"

    /**
     * 解析**放在 runCatching 之外**：否则「解析失败」会被当成「解密失败」，
     * 负断言就会因为完全无关的原因变绿（本轮真实踩到过这个坑）。
     */
    private fun parse(wire: CiphertextMessage) = when (wire.type) {
        CiphertextMessage.PREKEY_TYPE -> PreKeySignalMessage(wire.serialize())
        else -> SignalMessage(wire.serialize())
    }

    private fun decryptParsed(cipher: SessionCipher, parsed: Any): ByteArray = when (parsed) {
        is PreKeySignalMessage -> cipher.decrypt(parsed)
        is SignalMessage -> cipher.decrypt(parsed)
        else -> error("unexpected parsed message: ${parsed.javaClass}")
    }

    private fun decrypt(
        store: PersistentSignalProtocolStore,
        from: SignalProtocolAddress,
        wire: CiphertextMessage,
    ): String = String(decryptParsed(SessionCipher(store, from), parse(wire)), Charsets.UTF_8)

    private fun assertProtocolLevelFailure(what: String, error: Throwable?) {
        assertNotNull("$what 必须失败，而不是静默通过", error)
        assertTrue(
            "$what 应当在 libsignal 协议层失败，实际：${error!!.javaClass.name}: ${error.message}",
            error.javaClass.name.startsWith("org.signal.libsignal.protocol"),
        )
    }

    /** 建立会话、A 发一条、B 用生产 store 解开；返回双方。 */
    private fun establishedPair(): Pair<Party, Party> {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        SessionBuilder(alice.store, bob.address).process(bob.bundle())
        val seed = plaintext("seed")
        val wire = SessionCipher(alice.store, bob.address).encrypt(seed.toByteArray(Charsets.UTF_8))
        assertEquals(CiphertextMessage.PREKEY_TYPE, wire.type)
        assertEquals(seed, decrypt(bob.store, alice.address, wire))
        return alice to bob
    }

    @Test
    fun realX3dhRoundTripThroughProductionStore() {
        val (alice, bob) = establishedPair()

        // 棘轮双向：bob 已收到 PreKeySignalMessage，回复应当是普通 SignalMessage。
        val reply = plaintext("store-reply")
        val wire = SessionCipher(bob.store, alice.address).encrypt(reply.toByteArray(Charsets.UTF_8))
        assertEquals(CiphertextMessage.WHISPER_TYPE, wire.type)
        assertEquals(reply, decrypt(alice.store, bob.address, wire))

        // 写穿是真的：DAO 里必须有 session 与 identity 行，而不是只在内存里。
        val bobRows = bob.daoRows()
        assertTrue("bob 的 DAO 里应当有与 alice 的 session 行，实际：$bobRows", bobRows.any { it.endsWith("session:alice|1") })
        assertTrue("bob 的 DAO 里应当有 alice 的身份行，实际：$bobRows", bobRows.any { it.endsWith("identity:alice|1") })
        val aliceRows = alice.daoRows()
        assertTrue("alice 的 DAO 里应当有与 bob 的 session 行，实际：$aliceRows", aliceRows.any { it.endsWith("session:bob|1") })
        assertTrue("签名预密钥行应当仍在（它不是一次性的）", bobRows.any { it.endsWith("signed_pre_key:${bob.signedPreKeyId}") })

        // 一次性 PreKey 被消费后必须从 DAO 消失（否则会被重复使用，破坏前向安全）。
        assertNull(
            "被消费的一次性 PreKey 行仍然留在 DAO 里：$bobRows",
            runBlocking { db.signalKeyDao().getKey("user:bob:pre_key:${bob.preKeyId}") },
        )
    }

    @Test
    fun ratchetStateSurvivesStoreReloadAfterLoadPersistedState() {
        val (alice, bob) = establishedPair()

        // 让会话双向确立，这样 alice 后续发的就是普通 SignalMessage。
        val reply = plaintext("reply-before-restart")
        val wireReply = SessionCipher(bob.store, alice.address).encrypt(reply.toByteArray(Charsets.UTF_8))
        assertEquals(reply, decrypt(alice.store, bob.address, wireReply))

        // 「重启」：全新 store 实例 + 从 DAO 回填。
        val reloaded = bob.newStore()
        val dropped = runBlocking { reloaded.loadPersistedState() }
        assertEquals("健康数据不该被当成损坏行丢弃", 0, dropped)

        val after = plaintext("after-restart")
        val wire = SessionCipher(alice.store, bob.address).encrypt(after.toByteArray(Charsets.UTF_8))
        assertEquals(CiphertextMessage.WHISPER_TYPE, wire.type)
        assertEquals(
            "重启后必须还能解开对端消息（否则棘轮状态没有真正持久化）",
            after,
            decrypt(reloaded, alice.address, wire),
        )
    }

    @Test
    fun aReloadedStoreWithoutHydrationCannotDecrypt() {
        val (alice, bob) = establishedPair()

        // 刻意**不**调用 loadPersistedState()：内存是全空的，必须解不出来。
        val coldStore = bob.newStore()
        val after = plaintext("cold")
        val wire = SessionCipher(alice.store, bob.address).encrypt(after.toByteArray(Charsets.UTF_8))
        val parsed = parse(wire) // 解析失败不该被当成「解密失败」而让本条误绿

        val error = runCatching {
            decryptParsed(SessionCipher(coldStore, alice.address), parsed)
        }.exceptionOrNull()
        assertProtocolLevelFailure(
            "没有回填就能解密（G18 的关键反证：说明「重启存活」并非来自持久化）",
            error,
        )

        // 回填之后同一个实例必须能解——证明失败原因确实是缺少回填。
        runBlocking { coldStore.loadPersistedState() }
        assertEquals(after, String(decryptParsed(SessionCipher(coldStore, alice.address), parsed), Charsets.UTF_8))
    }

    @Test
    fun aCorruptSessionRowIsDroppedAndDecryptionFailsLoudly() {
        val (alice, bob) = establishedPair()

        // 正对照先行：健康的重启 store 能解开一条新消息（证明本用例的「解不开」不是环境问题）。
        val healthy = bob.newStore()
        assertEquals(0, runBlocking { healthy.loadPersistedState() })
        val control = plaintext("healthy-control")
        val controlWire = SessionCipher(alice.store, bob.address).encrypt(control.toByteArray(Charsets.UTF_8))
        assertEquals(control, decrypt(healthy, alice.address, controlWire))

        val sessionKey = "user:bob:session:alice|1"
        val original = runBlocking { db.signalKeyDao().getKey(sessionKey) }
        assertNotNull("前置条件：session 行应当存在", original)

        // 写坏 session 行（不可解析的负载），模拟磁盘/迁移损坏。
        runBlocking { db.signalKeyDao().insertKey(original!!.copy(keyData = "!!not-a-valid-record!!")) }

        val reloaded = bob.newStore()
        assertEquals("损坏的 session 行应当被丢弃并计数", 1, runBlocking { reloaded.loadPersistedState() })
        assertNull("损坏行应当已从 DAO 删除", runBlocking { db.signalKeyDao().getKey(sessionKey) })

        // 而且必须**大声失败**，绝不能返回垃圾字符串。
        val after = plaintext("after-corruption")
        val wire = SessionCipher(alice.store, bob.address).encrypt(after.toByteArray(Charsets.UTF_8))
        val parsed = parse(wire)
        val error = runCatching {
            decryptParsed(SessionCipher(reloaded, alice.address), parsed)
        }.exceptionOrNull()
        assertProtocolLevelFailure("session 行损坏后", error)
        assertFalse("绝不能在失败时把密文当成原文返回", after == error!!.message)
    }

    @Test
    fun anotherAccountCannotDecryptTheSameConversation() {
        val (alice, _) = establishedPair()

        // 同库、同设备号，但 accountId 不同（mallory）：作用域前缀不同，必须看不到任何会话。
        val mallory = PersistentSignalProtocolStore(
            db.signalKeyDao(),
            db.identityTrustDao(),
            "mallory",
            IdentityKeyPair.generate(),
            KeyHelper.generateRegistrationId(false),
        )
        runBlocking { mallory.loadPersistedState() }

        val onlyForBob = plaintext("for-bob-only")
        val wire = SessionCipher(alice.store, SignalProtocolAddress("bob", 1))
            .encrypt(onlyForBob.toByteArray(Charsets.UTF_8))
        val parsed = parse(wire)
        val error = runCatching {
            decryptParsed(SessionCipher(mallory, alice.address), parsed)
        }.exceptionOrNull()
        assertProtocolLevelFailure("另一个 accountId 的 store", error)
    }

    /**
     * 注入式失败 DAO：用 Kotlin 接口委托只覆写需要失败的少数方法。
     * 不用「关闭 Room 库」来制造失败——实测那样**不会**失败：in-memory 库被 close 后再写会
     * 静默重开一个空库，写入照旧成功（本轮踩到过）。
     */
    private class FailingSignalKeyDao(
        private val delegate: SignalKeyDao,
    ) : SignalKeyDao by delegate {
        override fun insertKeyBlocking(key: SignalKeyEntity): Unit =
            throw IllegalStateException("injected signal_keys write failure")
    }

    private class FailingIdentityTrustDao(
        private val delegate: IdentityTrustDao,
    ) : IdentityTrustDao by delegate {
        override fun getTrustBlocking(accountId: String, remoteUserId: String, deviceId: Int): IdentityTrustEntity? =
            throw IllegalStateException("injected identity_trust read failure")
    }

    @Test
    fun aPersistenceFailureIsRecordedOnWriteAndThrownOnRead() {
        val realKeys = db.signalKeyDao()
        val realTrust = db.identityTrustDao()

        // 写路径：内存照旧更新，失败被**记录**而不是抛出
        //（上层靠 persistenceFailure()/isSignalStoreHealthy() 判断）。
        val writeStore = PersistentSignalProtocolStore(
            FailingSignalKeyDao(realKeys),
            realTrust,
            "failure-probe-write",
            IdentityKeyPair.generate(),
            KeyHelper.generateRegistrationId(false),
        )
        assertNull("前置条件：健康 store 不应有失败记录", writeStore.persistenceFailure())
        writeStore.putPreKey(PreKeyRecord(7, Curve.generateKeyPair()))
        assertNotNull(
            "写穿透失败必须被记录，否则 isSignalStoreHealthy() 会误报健康，损坏会一路静默",
            writeStore.persistenceFailure(),
        )
        assertEquals("内存 map 仍会更新，但失败已经记录下来——这正是需要上层查健康的原因", 1, writeStore.preKeyCount())

        // 读路径（daoWrite）：必须**抛出**，不能悄悄返回一个「看起来没问题」的结果。
        val readStore = PersistentSignalProtocolStore(
            realKeys,
            FailingIdentityTrustDao(realTrust),
            "failure-probe-read",
            IdentityKeyPair.generate(),
            KeyHelper.generateRegistrationId(false),
        )
        val error = runCatching {
            readStore.isTrustedIdentity(
                SignalProtocolAddress("peer", 1),
                IdentityKeyPair.generate().publicKey,
                IdentityKeyStore.Direction.SENDING,
            )
        }.exceptionOrNull()
        assertTrue(
            "读穿透失败必须抛 SignalStorePersistenceException，实际：${error?.javaClass?.name}",
            error is SignalStorePersistenceException,
        )
        assertNotNull("读路径失败同样必须被记录", readStore.persistenceFailure())
    }
}

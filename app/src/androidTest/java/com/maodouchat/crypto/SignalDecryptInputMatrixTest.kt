package com.maodouchat.crypto

import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maodouchat.core.crypto.DecryptResult
import com.maodouchat.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * 解密契约的**畸形输入矩阵**（G20）。
 *
 * 契约：凡是返回 `DecryptResult` 的解密入口，就必须**只能**返回 `DecryptResult`——
 * 任何 `Throwable` 从里面逃出来都是缺陷。G19 已经因为 `AssertionError`（libsignal 的
 * `FilterExceptions` 会把意外的 checked exception 包成它，而它 `extends Error`）踩过一次；
 * 本文件的作用是**把这一类漏洞一次性钉死**，而不是再等着某个输入去撞它。
 *
 * 断言刻意比「允许抛出调用方会处理的协议异常」更严：这些入口自己就带分类链，
 * 所以**任何**逃逸都算红。用例会把所有逃逸**收集起来一次性报出**，避免一次只看到一条。
 *
 * 另外两点也很重要：
 * 1. 同一段密文会在**多个偏移**各翻一个 bit——只测一个位置会漏掉「改动落在非密码学字段上
 *    因此仍能正常解密」这种情况；
 * 2. 每次调用前清理重试状态：`decryptRetryTracker` 是按发送方累计失败的，矩阵连打同一个
 *    发送方会让后续用例提前走「跳过密码学尝试」的短路，从而**把逃逸掩盖掉**（矩阵假绿）。
 */
@RunWith(AndroidJUnit4::class)
class SignalDecryptInputMatrixTest {

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

    private inner class Party(val accountId: String, val deviceId: Int) {
        val context = SignalProtocolContext(db.signalKeyDao(), db.identityTrustDao())
        val codec = SignalEnvelopeCodec()
        val cipher: SignalGroupCipher
        val direct: SignalDirectCipher
        val senderKeys: SignalGroupSenderKeyManager
        val address get() = SignalProtocolAddress(accountId, deviceId)

        val preKeyId = 31337 + deviceId
        private val preKeyPair = Curve.generateKeyPair()
        private val signedPreKeyId = 22222 + deviceId
        private val signedPreKeyPair = Curve.generateKeyPair()
        private lateinit var signedPreKeySignature: ByteArray

        init {
            context.currentUserId = accountId
            context.generateIdentityKeys() // 带上账号重做一次，让 store 作用域前缀正确
            context.localDeviceId = deviceId
            context.localCryptoReady = true
            senderKeys = SignalGroupSenderKeyManager(context)
            val deviceIdCoordinator = SignalDeviceIdCoordinator(context)
            val sessionManager = SignalSessionManager(context, deviceIdCoordinator)
            direct = SignalDirectCipher(context, sessionManager, deviceIdCoordinator, codec)
            cipher = SignalGroupCipher(context, senderKeys, direct)
            signedPreKeySignature = Curve.calculateSignature(
                context.identityKeyPair.privateKey,
                signedPreKeyPair.publicKey.serialize(),
            )
            val store = context.protocolStore as PersistentSignalProtocolStore
            store.putPreKey(PreKeyRecord(preKeyId, preKeyPair))
            store.putSignedPreKey(SignedPreKeyRecord(signedPreKeyId, 1L, signedPreKeyPair, signedPreKeySignature))
        }

        fun bundle(): PreKeyBundle = PreKeyBundle(
            context.registrationId, deviceId, preKeyId, preKeyPair.publicKey,
            signedPreKeyId, signedPreKeyPair.publicKey, signedPreKeySignature,
            context.identityKeyPair.publicKey,
        )

        fun resetRetryState(senderId: String) = direct.clearDecryptRetryStateForSender(senderId)
    }

    /** 收集逃逸，而不是第一个就断言失败——否则一轮只能看到一条。 */
    private val escapes = mutableListOf<String>()

    private fun expectNoEscape(label: String, block: () -> DecryptResult) {
        try {
            block()
        } catch (t: Throwable) {
            escapes += "$label → ${t.javaClass.name}: ${t.message}"
        }
    }

    private fun assertNoEscapes() {
        assertEquals(
            "这些调用让 Throwable 逃出了 DecryptResult 契约（期望全部被分类成 DecryptResult）：",
            emptyList<String>(),
            escapes,
        )
    }

    private fun flipAt(value: String, offset: Int): String {
        val c = value[offset]
        val f = if (c == 'A') 'B' else 'A'
        return value.substring(0, offset) + f + value.substring(offset + 1)
    }

    /** 统一的偏移集合：只测一个位置会漏掉「改动落在非密码学字段上」这类情况。 */
    private fun offsetsFor(value: String) =
        listOf(0, value.length / 4, value.length / 2, (value.length * 3) / 4, value.length - 1).distinct()

    private fun ciphertextValueOf(envelope: String): Pair<Int, Int> {
        val marker = "\"ciphertext\":\""
        val start = envelope.indexOf(marker)
        assertTrue("前置条件：信封里应当有 ciphertext 字段：$envelope", start >= 0)
        val valueStart = start + marker.length
        return valueStart to envelope.indexOf('"', valueStart)
    }

    private fun replaceCiphertext(envelope: String, newValue: String): String {
        val (vs, ve) = ciphertextValueOf(envelope)
        return envelope.substring(0, vs) + newValue + envelope.substring(ve)
    }

    @Test
    fun directEnvelopeEntryPointNeverLetsAThrowableEscape() {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        SessionBuilder(alice.context.protocolStore as PersistentSignalProtocolStore, bob.address)
            .process(bob.bundle())

        val text = "matrix-direct-${java.util.UUID.randomUUID()}"
        val wire = SessionCipher(alice.context.protocolStore as PersistentSignalProtocolStore, bob.address)
            .encrypt(text.toByteArray(Charsets.UTF_8))
        val type = if (wire.type == CiphertextMessage.PREKEY_TYPE) "prekey" else "signal"
        val b64 = Base64.encodeToString(wire.serialize(), Base64.NO_WRAP)
        val validEnvelope = alice.codec.buildEncryptedMessageEnvelope(1, 1, type, "TEXT", b64)

        // 正对照：合法信封必须解得出来（否则下面的「没有逃逸」可能只是因为这条链路根本没工作）。
        bob.resetRetryState("alice")
        val control = bob.direct.decryptContentEnvelope("alice", validEnvelope)
        assertEquals(DecryptResult.Success(text), control)

        // 同一段密文在多个偏移各翻一个 bit。
        val tamperedDirect = mutableListOf<String>()
        offsetsFor(b64).forEach { offset ->
            val tampered = replaceCiphertext(validEnvelope, flipAt(b64, offset))
            tamperedDirect += tampered
            bob.resetRetryState("alice")
            expectNoEscape("decryptContentEnvelope/bitflip@$offset") {
                bob.direct.decryptContentEnvelope("alice", tampered)
            }
        }
        // 证明这些「坏输入」真的各不相同、也真的不同于合法输入——否则矩阵可能已经静默地
        // 不再篡改任何东西却依然全绿（这正是「探针漏一个偏移」要防的那种假绿）。
        assertEquals("篡改后的输入必须两两不同", tamperedDirect.size, tamperedDirect.distinct().size)
        assertTrue("篡改后的输入必须不同于合法输入", tamperedDirect.none { it == validEnvelope })

        listOf(
            "truncated" to replaceCiphertext(validEnvelope, b64.take(b64.length / 2)),
            "empty-ciphertext" to replaceCiphertext(validEnvelope, ""),
            "not-base64" to replaceCiphertext(validEnvelope, "!!!!not-base64!!!!"),
            "bad-version" to validEnvelope.replace("\"version\":2", "\"version\":99"),
            "bad-algorithm" to validEnvelope.replace("\"algorithm\":\"signal-v2\"", "\"algorithm\":\"bogus\""),
            "empty-body" to "",
            "truncated-json" to validEnvelope.take(validEnvelope.length / 2),
            "garbage" to "not json at all",
        ).forEach { (label, content) ->
            bob.resetRetryState("alice")
            expectNoEscape("decryptContentEnvelope/$label") {
                bob.direct.decryptContentEnvelope("alice", content)
            }
        }

        assertNoEscapes()
    }

    @Test
    fun deviceCiphertextEntryPointNeverLetsAThrowableEscape() {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        SessionBuilder(alice.context.protocolStore as PersistentSignalProtocolStore, bob.address)
            .process(bob.bundle())

        val text = "matrix-device-${java.util.UUID.randomUUID()}"
        val wire = SessionCipher(alice.context.protocolStore as PersistentSignalProtocolStore, bob.address)
            .encrypt(text.toByteArray(Charsets.UTF_8))
        val type = if (wire.type == CiphertextMessage.PREKEY_TYPE) "prekey" else "signal"
        val b64 = Base64.encodeToString(wire.serialize(), Base64.NO_WRAP)

        bob.resetRetryState("alice")
        assertEquals(DecryptResult.Success(text), bob.direct.decryptDeviceCiphertext("alice", 1, type, b64))

        offsetsFor(b64).forEach { offset ->
            bob.resetRetryState("alice")
            expectNoEscape("decryptDeviceCiphertext/bitflip@$offset") {
                bob.direct.decryptDeviceCiphertext("alice", 1, type, flipAt(b64, offset))
            }
        }
        listOf(
            "truncated" to b64.take(b64.length / 2),
            "empty" to "",
            "not-base64" to "!!!!not-base64!!!!",
            "wrong-type" to b64,
        ).forEachIndexed { index, (label, value) ->
            bob.resetRetryState("alice")
            val t = if (label == "wrong-type") "bogus-type" else type
            expectNoEscape("decryptDeviceCiphertext/$label") {
                bob.direct.decryptDeviceCiphertext("alice", 1, t, value)
            }
        }

        assertNoEscapes()
    }

    @Test
    fun multiDeviceAndCrossTypeInputsNeverLetAThrowableEscape() {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        SessionBuilder(alice.context.protocolStore as PersistentSignalProtocolStore, bob.address)
            .process(bob.bundle())

        val text = "matrix-multi-${java.util.UUID.randomUUID()}"
        val wire = SessionCipher(alice.context.protocolStore as PersistentSignalProtocolStore, bob.address)
            .encrypt(text.toByteArray(Charsets.UTF_8))
        val type = if (wire.type == CiphertextMessage.PREKEY_TYPE) "prekey" else "signal"
        val b64 = Base64.encodeToString(wire.serialize(), Base64.NO_WRAP)

        // 多设备信封：一条给本机(device 1，密文有效)，一条给不存在的 device 9。
        val multiOk = alice.codec.buildMultiDeviceMessageEnvelope(
            senderDeviceId = 1,
            payloadType = "TEXT",
            entries = listOf(
                MultiDeviceMessageEntry(recipientUserId = "bob", recipientDeviceId = 1, ciphertextType = type, ciphertext = b64),
                MultiDeviceMessageEntry(recipientUserId = "bob", recipientDeviceId = 9, ciphertextType = type, ciphertext = b64),
            ),
        )
        bob.resetRetryState("alice")
        assertEquals(DecryptResult.Success(text), bob.direct.decryptContentEnvelope("alice", multiOk))

        // 只有指向不存在的本机设备 → 应当是 NotForThisDevice，而不是抛异常。
        val multiOtherDevice = alice.codec.buildMultiDeviceMessageEnvelope(
            senderDeviceId = 1,
            payloadType = "TEXT",
            entries = listOf(
                MultiDeviceMessageEntry(recipientUserId = "bob", recipientDeviceId = 9, ciphertextType = type, ciphertext = b64),
            ),
        )
        bob.resetRetryState("alice")
        expectNoEscape("decryptContentEnvelope/multi-device-for-other-device") {
            bob.direct.decryptContentEnvelope("alice", multiOtherDevice)
        }

        // 多设备信封里指向本机的密文被篡改：同样跑多个偏移（这条路径的入口此前连
        // try/catch 都没有，单点取样不足以说明问题）。
        val tamperedMulti = replaceCiphertext(multiOk, flipAt(b64, offsetsFor(b64).first()))
        offsetsFor(b64).forEach { offset ->
            val tamperedM = replaceCiphertext(multiOk, flipAt(b64, offset))
            bob.resetRetryState("alice")
            expectNoEscape("decryptContentEnvelope/multi-device-tampered@$offset") {
                bob.direct.decryptContentEnvelope("alice", tamperedM)
            }
        }

        // 把群信封喂给直发入口（跨类型）。必须**先建分发**，否则拿到的不是群信封而是
        // 一个失败的 Result——那会变成「测试自己抛异常」，看起来像逃逸，其实不是（本轮踩过）。
        alice.senderKeys.createGroupSenderKeyDistribution("g-matrix", 1)
        val groupEnvelope = alice.cipher.encryptGroupTextEnvelope("g-matrix", "x", "text", 1).getOrThrow()
        bob.resetRetryState("alice")
        expectNoEscape("decryptContentEnvelope/given-group-envelope") {
            bob.direct.decryptContentEnvelope("alice", groupEnvelope)
        }

        // 直接把多设备信封喂给那个**没有 try/catch** 的公开入口（静态普查发现它没有任何分类链）。
        val parsed = MultiDeviceEnvelopePolicy.parse(tamperedMulti)
        if (parsed != null) {
            bob.resetRetryState("alice")
            expectNoEscape("decryptParsedMultiDeviceEnvelope/tampered") {
                bob.direct.decryptParsedMultiDeviceEnvelope("alice", parsed)
            }
        }

        // 把直发信封喂给群入口（反向跨类型），并同样跑多个偏移。
        offsetsFor(b64).forEach { offset ->
            val direct = alice.codec.buildEncryptedMessageEnvelope(1, 1, type, "TEXT", flipAt(b64, offset))
            bob.resetRetryState("alice")
            expectNoEscape("decryptGroupContentEnvelope/given-direct-envelope@$offset") {
                bob.cipher.decryptGroupContentEnvelope("alice", direct, "g-matrix", 1)
            }
        }

        assertNoEscapes()
    }
}

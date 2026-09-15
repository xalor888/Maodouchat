package com.maodouchat.crypto

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.UUID

/**
 * E2EE 的**真实加密往返**证据（G17）。
 *
 * 为什么必须是 instrumented 测试：`org.signal:libsignal-android:0.41.0` 的 AAR 只带
 * Android JNI（arm64-v8a / armeabi-v7a / x86 / x86_64），**没有 host 动态库**，
 * 所以 `app/src/test` 的 JVM 单测根本无法加载真密码学。G13/G14 只能测边界与策略，
 * 那些用例**不能**证明密文真的能被对方解回原文——本文件才是那段证据。
 *
 * 这里能证明什么：
 * - 真实 X3DH 会话建立（首条消息是 `PreKeySignalMessage`）；
 * - A 加密 → B 解出**逐字节相同**的原文；
 * - 棘轮**双向**推进（B 回复 A 也能解），排除单向偶然成功；
 * - 篡改一个字节 / 用第三方会话 / 身份与签名不匹配 → 都必须在协议层失败；
 * - 生产类 `SignalEnvelopeCodec` 产出的**上线信封**里不含原文。
 *
 * 这里**不能**证明什么（不要引用它去支撑这些结论）：
 * - 真实双设备**跨进程/跨网络**投递（本测试在同一进程内成对建立会话）；
 * - 服务端 PostgreSQL / 日志 / 导出 / 备份 中不含明文（那些是服务端证据面，见
 *   `ServerPlaintextSweepTest` 与其「证据边界」段）；
 * - `SignalDirectCipher` 等生产 cipher 类的完整装配路径——它们依赖 `MaodouchatApp`
 *   单例与 Room/SQLCipher store，本文件走的是「真实 libsignal 原语 + 真实生产信封编码器」，
 *   这一层差异在台账里如实记录，不得含糊成「生产 cipher 类已验证」。
 */
@RunWith(AndroidJUnit4::class)
class SignalE2eeRoundTripTest {

    /** 一个真实设备：真实身份密钥、真实预密钥、内存 store（libsignal 自带实现）。 */
    private class Party(val userId: String, val deviceId: Int) {
        val identity: IdentityKeyPair = IdentityKeyPair.generate()
        val registrationId: Int = KeyHelper.generateRegistrationId(false)
        val store = InMemorySignalProtocolStore(identity, registrationId)
        val address = SignalProtocolAddress(userId, deviceId)

        private val preKeyId = 31337 + deviceId
        private val preKeyPair: ECKeyPair = Curve.generateKeyPair()
        private val signedPreKeyId = 22222 + deviceId
        private val signedPreKeyPair: ECKeyPair = Curve.generateKeyPair()

        /** 签名由**本设备身份私钥**产生——这正是身份不匹配时必须失败的依据。 */
        private val signedPreKeySignature: ByteArray =
            Curve.calculateSignature(identity.privateKey, signedPreKeyPair.publicKey.serialize())

        init {
            store.storePreKey(preKeyId, PreKeyRecord(preKeyId, preKeyPair))
            store.storeSignedPreKey(
                signedPreKeyId,
                SignedPreKeyRecord(signedPreKeyId, 1L, signedPreKeyPair, signedPreKeySignature),
            )
        }

        /** 服务端会派发给对端的 bundle。 */
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
    }

    private fun newPlaintext(label: String) = "$label-${UUID.randomUUID()}"

    /** 只接受协议层异常：避免「失败」其实是 NPE/断言错位造成的假阳性。 */
    private fun assertProtocolLevelFailure(what: String, error: Throwable?) {
        assertNotNull("$what 必须失败，而不是静默通过", error)
        assertTrue(
            "$what 应当在 libsignal 协议层失败，实际异常是 ${error!!.javaClass.name}: ${error.message}",
            error.javaClass.name.startsWith("org.signal.libsignal.protocol"),
        )
    }

    @Test
    fun realX3dhSessionCarriesExactPlaintextBothDirections() {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        val plaintext = newPlaintext("round-trip")

        SessionBuilder(alice.store, bob.address).process(bob.bundle())

        val first = SessionCipher(alice.store, bob.address).encrypt(plaintext.toByteArray(Charsets.UTF_8))
        assertEquals(
            "首条消息必须是 PreKeySignalMessage（X3DH），否则这次并没有真的建立新会话",
            CiphertextMessage.PREKEY_TYPE,
            first.type,
        )
        // 密文不等于原文（同长度巧合不算证据，但完全相同必须红）。
        assertFalse(
            "密文与原文逐字节相同，说明根本没有加密",
            plaintext.toByteArray(Charsets.UTF_8).contentEquals(first.serialize()),
        )

        val decryptedByBob = SessionCipher(bob.store, alice.address)
            .decrypt(PreKeySignalMessage(first.serialize()))
        assertEquals(plaintext, String(decryptedByBob, Charsets.UTF_8))

        // 反向：Double Ratchet 必须双向推进，排除「单向偶然成功」。
        val replyPlaintext = newPlaintext("reply")
        val reply = SessionCipher(bob.store, alice.address).encrypt(replyPlaintext.toByteArray(Charsets.UTF_8))
        assertEquals(
            "会话建立后应当走普通 SignalMessage（棘轮），而不是再来一次 PreKey",
            CiphertextMessage.WHISPER_TYPE,
            reply.type,
        )
        val decryptedByAlice = SessionCipher(alice.store, bob.address)
            .decrypt(SignalMessage(reply.serialize()))
        assertEquals(replyPlaintext, String(decryptedByAlice, Charsets.UTF_8))
    }

    @Test
    fun tamperedCiphertextFailsInsteadOfYieldingAnything() {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        SessionBuilder(alice.store, bob.address).process(bob.bundle())

        val plaintext = newPlaintext("tamper")
        val serialized = SessionCipher(alice.store, bob.address)
            .encrypt(plaintext.toByteArray(Charsets.UTF_8))
            .serialize()

        // 正对照：未篡改的这串字节确实能解回原文——否则「篡改后失败」可能只是因为
        // 这段字节本来就没有任何会话与之对应，那样断言失败就没有意义。
        val ok = SessionCipher(bob.store, alice.address).decrypt(PreKeySignalMessage(serialized))
        assertEquals(plaintext, String(ok, Charsets.UTF_8))

        // 篡改：翻掉最后一个字节（落在 MAC 上），必须失败。
        val freshAlice = Party("alice", 1)
        val freshBob = Party("bob", 1)
        SessionBuilder(freshAlice.store, freshBob.address).process(freshBob.bundle())
        val target = SessionCipher(freshAlice.store, freshBob.address)
            .encrypt(newPlaintext("tamper-target").toByteArray(Charsets.UTF_8))
            .serialize()
        val tampered = target.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        val error = runCatching {
            SessionCipher(freshBob.store, freshAlice.address).decrypt(PreKeySignalMessage(tampered))
        }.exceptionOrNull()
        assertProtocolLevelFailure("被篡改一个字节的密文", error)
    }

    @Test
    fun thirdPartyWithNoSessionCannotDecrypt() {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        SessionBuilder(alice.store, bob.address).process(bob.bundle())

        val serialized = SessionCipher(alice.store, bob.address)
            .encrypt(newPlaintext("third-party").toByteArray(Charsets.UTF_8))
            .serialize()

        val carol = Party("carol", 1)
        val error = runCatching {
            SessionCipher(carol.store, alice.address).decrypt(PreKeySignalMessage(serialized))
        }.exceptionOrNull()
        assertProtocolLevelFailure("没有会话的第三方", error)

        // 合法的收件人仍然能解——证明失败来自「没有会话」而不是密文本身坏了。
        val ok = SessionCipher(bob.store, alice.address).decrypt(PreKeySignalMessage(serialized))
        assertTrue(String(ok, Charsets.UTF_8).startsWith("third-party-"))
    }

    @Test
    fun wireEnvelopeNeverContainsPlaintext() {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        SessionBuilder(alice.store, bob.address).process(bob.bundle())

        val plaintext = "PLAINTEXT-MUST-NOT-LEAK-${UUID.randomUUID()}"
        val message = SessionCipher(alice.store, bob.address)
            .encrypt(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertextBase64 = Base64.encodeToString(message.serialize(), Base64.NO_WRAP)

        // 用**生产类**（不是测试替身）构造真正会上行的信封。
        val codec = SignalEnvelopeCodec()
        val envelope = codec.buildEncryptedMessageEnvelope(
            senderDeviceId = alice.deviceId,
            recipientDeviceId = bob.deviceId,
            ciphertextType = "prekey",
            payloadType = "TEXT",
            ciphertext = ciphertextBase64,
        )

        assertFalse("信封里出现了原文：$envelope", envelope.contains(plaintext))
        assertFalse("信封里出现了原文片段", envelope.contains("PLAINTEXT-MUST-NOT-LEAK"))
        // 否则「不含原文」可能只是因为这个信封什么都没装：
        assertTrue("信封必须真的承载密文", envelope.contains(ciphertextBase64))
        assertTrue("信封必须被生产编解码器认成加密信封", codec.isEncryptedEnvelope(envelope))

        // 并且这串密文确实能解回原文——证明它不是一段无关的随机串。
        val decrypted = SessionCipher(bob.store, alice.address)
            .decrypt(PreKeySignalMessage(message.serialize()))
        assertEquals(plaintext, String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun signedPreKeyFromDifferentIdentityIsRejected() {
        val bob = Party("bob", 1)
        val mallory = Party("mallory", 1)

        // Bob 的预密钥与签名，但对外宣称的身份是 Mallory —— 必须验签失败。
        val forged = PreKeyBundle(
            bob.registrationId,
            bob.deviceId,
            bob.bundle().preKeyId,
            bob.bundle().preKey,
            bob.bundle().signedPreKeyId,
            bob.bundle().signedPreKey,
            bob.bundle().signedPreKeySignature,
            mallory.identity.publicKey,
        )
        val attacked = Party("alice", 1)
        val error = runCatching {
            SessionBuilder(attacked.store, bob.address).process(forged)
        }.exceptionOrNull()
        assertProtocolLevelFailure("身份与签名不匹配的 bundle", error)

        // 正对照：同一套预密钥、身份正确时，会话可以建立。用**全新的** alice 设备，
        // 避免上面那次失败留下的半成品状态污染结论。
        val honest = Party("alice", 1)
        SessionBuilder(honest.store, bob.address).process(bob.bundle())
        val text = newPlaintext("honest")
        val wire = SessionCipher(honest.store, bob.address).encrypt(text.toByteArray(Charsets.UTF_8))
        val back = SessionCipher(bob.store, honest.address).decrypt(PreKeySignalMessage(wire.serialize()))
        assertEquals(text, String(back, Charsets.UTF_8))
    }
}

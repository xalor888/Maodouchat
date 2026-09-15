package com.maodouchat.crypto

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maodouchat.core.crypto.DecryptResult
import com.maodouchat.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 群消息 **SenderKey 真实加密往返**证据（G19）。
 *
 * G17/G18 覆盖的是 1:1 直发；群侧此前没有任何 on-device 真证据。本文件用**生产类**
 * （`SignalGroupSenderKeyManager` / `SignalGroupCipher` / `SignalEnvelopeCodec`）跑通：
 * 建 distribution → 打成上线信封 → 对端安装 → 发群消息 → 解回逐字节相同原文。
 *
 * 这条路是**本地**的：`GroupSessionBuilder` 只在 store 里读写 sender key，不需要网络。
 *
 * 能证明什么：
 * - 生产分发/加密/解密三段都真的工作，且 sender key 可连续使用（不是一次性巧合）；
 * - 生产编解码器认得群信封与分发信封；
 * - 未安装 distribution 的第三方解不出来；跨群重放被拒；
 * - epoch 语义：**未来 epoch** 的信封被拒（`FutureEpoch`）；key 失效后**不能再按旧 epoch 加密**；
 * - 篡改密文失败，且两个信封里都不含原文。
 *
 * 刻意**不**断言的东西：拿旧 epoch 的密文去解**可能仍然成功**——接收端保留旧 distribution 的
 * sender key 属于正常的历史可解，不是缺陷。不变量 10 管的是**发送端不得复用已被失效的 key**，
 * 所以这里钉的是发送端 `Result.isFailure`，而不是接收端必失败。（这类「断言看起来更严但其实是错的」
 * 正是要先想清楚再写的东西。）
 *
 * 仍**不能**证明什么：`SignalProtocol.initialize` 的完整装配、群分发走网络与多设备扇出、
 * 真实跨进程/跨网络双设备投递、日志/导出/备份、生产 PostgreSQL。
 */
@RunWith(AndroidJUnit4::class)
class SignalGroupSenderKeyRoundTripTest {

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

    /**
     * 一个真实参与方：生产 `SignalProtocolContext` + 生产 `PersistentSignalProtocolStore`（Room 支撑）
     * + 生产群密码学组件。accountId 隔离，所以两个参与方可以共用同一个库。
     */
    private inner class GroupParty(val accountId: String, val deviceId: Int) {
        val context = SignalProtocolContext(db.signalKeyDao(), db.identityTrustDao())
        val codec = SignalEnvelopeCodec()
        val senderKeys: SignalGroupSenderKeyManager
        val cipher: SignalGroupCipher
        val directCipher: SignalDirectCipher
        val address get() = org.signal.libsignal.protocol.SignalProtocolAddress(accountId, deviceId)

        val preKeyId = 31337 + deviceId
        private val preKeyPair = org.signal.libsignal.protocol.ecc.Curve.generateKeyPair()
        val signedPreKeyId = 22222 + deviceId
        private val signedPreKeyPair = org.signal.libsignal.protocol.ecc.Curve.generateKeyPair()
        // 必须在 init 里 generateIdentityKeys() 之后才算签名：那一步会重新生成身份密钥，
        // 提前算出来的签名会和 bundle 里公布的身份公钥对不上（本轮实测 InvalidKeyException）。
        private lateinit var signedPreKeySignature: ByteArray

        init {
            context.currentUserId = accountId
            // 构造器里的 init 已经生成过一次（那时 currentUserId 还是 null，store 会落在
            // "anonymous" 作用域），这里带上账号重做一次，让 store 的作用域前缀正确。
            context.generateIdentityKeys()
            context.localDeviceId = deviceId
            context.localCryptoReady = true
            senderKeys = SignalGroupSenderKeyManager(context)
            val deviceIdCoordinator = SignalDeviceIdCoordinator(context)
            val sessionManager = SignalSessionManager(context, deviceIdCoordinator)
            directCipher = SignalDirectCipher(context, sessionManager, deviceIdCoordinator, codec)
            cipher = SignalGroupCipher(context, senderKeys, directCipher)

            // 直发路径需要本机预密钥，对端才能建会话。
            signedPreKeySignature = org.signal.libsignal.protocol.ecc.Curve.calculateSignature(
                context.identityKeyPair.privateKey,
                signedPreKeyPair.publicKey.serialize(),
            )
            val store = context.protocolStore as PersistentSignalProtocolStore
            store.putPreKey(org.signal.libsignal.protocol.state.PreKeyRecord(preKeyId, preKeyPair))
            store.putSignedPreKey(
                org.signal.libsignal.protocol.state.SignedPreKeyRecord(
                    signedPreKeyId, 1L, signedPreKeyPair, signedPreKeySignature,
                ),
            )
        }

        fun bundle() = org.signal.libsignal.protocol.state.PreKeyBundle(
            context.registrationId,
            deviceId,
            preKeyId,
            preKeyPair.publicKey,
            signedPreKeyId,
            signedPreKeyPair.publicKey,
            signedPreKeySignature,
            context.identityKeyPair.publicKey,
        )

        /** 生成 distribution 并打成**生产**上线信封；返回 (distributionId, envelope)。 */
        fun distribute(groupId: String, epoch: Long): Pair<String, String> {
            val payload = senderKeys.createGroupSenderKeyDistribution(groupId, epoch)
            val envelope = codec.buildSenderKeyDistributionEnvelope(
                groupId = groupId,
                distributionId = payload.distributionId,
                message = payload.message,
                epoch = epoch,
                senderDeviceId = deviceId,
            )
            return payload.distributionId to envelope
        }

        fun send(groupId: String, text: String, epoch: Long): String =
            cipher.encryptGroupTextEnvelope(groupId, text, "text", epoch).getOrThrow()

        fun sendResult(groupId: String, text: String, epoch: Long): Result<String> =
            cipher.encryptGroupTextEnvelope(groupId, text, "text", epoch)

        fun install(
            senderId: String,
            envelope: String,
            groupId: String? = null,
            epoch: Long? = null,
        ): SenderKeyDistOutcome =
            cipher.processSenderKeyDistributionEnvelope(senderId, envelope, groupId, epoch)

        fun receive(
            senderId: String,
            envelope: String,
            groupId: String? = null,
            epoch: Long? = null,
        ): DecryptResult = cipher.decryptGroupContentEnvelope(senderId, envelope, groupId, epoch)
    }

    private fun plaintext(label: String) = "$label-${java.util.UUID.randomUUID()}"

    private fun assertNotSuccess(what: String, result: DecryptResult) {
        assertFalse(
            "$what 必须解不出来，但拿到了 Success(${ (result as? DecryptResult.Success)?.plaintext })",
            result is DecryptResult.Success,
        )
        assertTrue(
            "$what 应当以明确的失败/不支持状态结束，实际：$result",
            result is DecryptResult.NoSession ||
                result is DecryptResult.Failed ||
                result is DecryptResult.UnsupportedEnvelope ||
                result is DecryptResult.FutureEpoch ||
                result is DecryptResult.Duplicate,
        )
    }

    @Test
    fun groupSenderKeyRoundTripThroughProductionCipher() {
        val alice = GroupParty("alice", 1)
        val bob = GroupParty("bob", 1)
        val group = "group-round-trip"

        val (_, distribution) = alice.distribute(group, epoch = 1)
        assertEquals(SenderKeyDistOutcome.Installed, bob.install("alice", distribution, group, 1))

        val first = plaintext("group-first")
        val envelope1 = alice.send(group, first, epoch = 1)
        assertEquals(first, (bob.receive("alice", envelope1, group, 1) as DecryptResult.Success).plaintext)

        // 连发第二条：sender key 必须可连续使用，不是一次性巧合。
        val second = plaintext("group-second")
        val envelope2 = alice.send(group, second, epoch = 1)
        assertEquals(second, (bob.receive("alice", envelope2, group, 1) as DecryptResult.Success).plaintext)

        // 生产编解码器必须认得这两种信封，否则「不含原文」可能只是因为信封没装对东西。
        assertTrue("群消息信封应当被认成 sender-key 信封", alice.codec.isSenderKeyEnvelope(envelope1))
        assertTrue(
            "分发信封应当被认成分发信封",
            alice.codec.isSenderKeyDistributionEnvelope(distribution),
        )

        // 两个信封里都不许出现原文（分发信封里是密钥材料，不是正文）。
        listOf(first, second).forEach { text ->
            assertFalse("群信封里出现了原文：$envelope1", envelope1.contains(text))
            assertFalse("群信封里出现了原文：$envelope2", envelope2.contains(text))
            assertFalse("分发信封里出现了原文：$distribution", distribution.contains(text))
        }
    }

    @Test
    fun aThirdPartyThatNeverInstalledTheDistributionCannotDecrypt() {
        val alice = GroupParty("alice", 1)
        val bob = GroupParty("bob", 1)
        val carol = GroupParty("carol", 1)
        val group = "group-third-party"

        val (_, distribution) = alice.distribute(group, 1)
        bob.install("alice", distribution, group, 1)
        // carol 刻意不安装。

        val secret = plaintext("group-secret")
        val envelope = alice.send(group, secret, 1)

        assertNotSuccess("未安装 distribution 的第三方", carol.receive("alice", envelope, group, 1))
        // 正对照：装了的人必须解得出来，证明失败来自「没装」而不是密文坏了。
        assertEquals(secret, (bob.receive("alice", envelope, group, 1) as DecryptResult.Success).plaintext)
    }

    @Test
    fun aGroupEnvelopeReplayedIntoAnotherGroupIsRejected() {
        val alice = GroupParty("alice", 1)
        val bob = GroupParty("bob", 1)
        val group = "group-original"
        val other = "group-other"

        val (_, distribution) = alice.distribute(group, 1)
        bob.install("alice", distribution, group, 1)
        // 正对照先用**自己的**信封做（群 id 正确必须解得出来）……
        val control = plaintext("group-control")
        val controlEnvelope = alice.send(group, control, 1)
        assertEquals(control, (bob.receive("alice", controlEnvelope, group, 1) as DecryptResult.Success).plaintext)

        // ……再拿一个**新的**信封做跨群重放。顺序很重要：group id 不匹配会让该信封的指纹
        // 进入终态记录（这是生产行为，合理的），复用同一个信封会让后面的正对照也变成
        // UnsupportedEnvelope——本轮真实踩到过，所以控制组必须用另一个信封。
        val text = plaintext("group-replay")
        val envelope = alice.send(group, text, 1)
        assertNotSuccess("跨群重放", bob.receive("alice", envelope, other, 1))

        // 分发信封同样受 expectedGroupId 约束。
        val (_, second) = alice.distribute(other, 1)
        assertEquals(
            "分发信封投到别的群必须被拒",
            SenderKeyDistOutcome.Skipped,
            bob.install("alice", second, group, 1),
        )
    }

    @Test
    fun staleEpochEncryptionIsRefusedAfterInvalidation() {
        val alice = GroupParty("alice", 1)
        val bob = GroupParty("bob", 1)
        val group = "group-epoch"

        val (_, e1) = alice.distribute(group, 1)
        bob.install("alice", e1, group, 1)
        val before = plaintext("before-rotation")
        assertEquals(before, (bob.receive("alice", alice.send(group, before, 1), group, 1) as DecryptResult.Success).plaintext)

        // 轮换：失效旧 key，按新 epoch 重新分发。
        assertTrue("失效 group sender key 应当成功", alice.senderKeys.invalidateGroupSenderKey(group))
        val (_, e2) = alice.distribute(group, 2)
        assertEquals(SenderKeyDistOutcome.Installed, bob.install("alice", e2, group, 2))

        val after = plaintext("after-rotation")
        val newEnvelope = alice.send(group, after, 2)
        assertEquals(after, (bob.receive("alice", newEnvelope, group, 2) as DecryptResult.Success).plaintext)

        // 不变量 10 的核心：key 失效后**不得**再按旧 epoch 加密（否则就是复用已被失效的 key）。
        val stale = alice.sendResult(group, plaintext("stale"), epoch = 1)
        assertTrue("失效后仍能按旧 epoch 加密：$stale", stale.isFailure)
        // 只断言「失败」不够：探针实测去掉这条 epoch 守卫后，加密仍会因「distribution id 未知」
        // 而失败，于是断言照样绿——那样测试并没有真正钉住这个守卫。所以这里钉住失败原因。
        val reason = stale.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "失败原因应当明确指出该 epoch 没有已分发的 sender key，实际：$reason",
            reason.contains("group_sender_key_not_distributed"),
        )
    }

    @Test
    fun aFutureEpochEnvelopeIsRejected() {
        val alice = GroupParty("alice", 1)
        val bob = GroupParty("bob", 1)
        val group = "group-future-epoch"

        val (_, distribution) = alice.distribute(group, 2)
        bob.install("alice", distribution, group, 2)
        val text = plaintext("future-epoch")
        val envelope = alice.send(group, text, 2)

        // 对端还停在 epoch 1 时，epoch 2 的信封必须被明确拒为 FutureEpoch。
        val result = bob.receive("alice", envelope, group, epoch = 1)
        assertEquals(DecryptResult.FutureEpoch, result)
        // 正对照：epoch 跟上之后必须解得出来。
        assertEquals(text, (bob.receive("alice", envelope, group, 2) as DecryptResult.Success).plaintext)
    }

    /** 直发路径的同一类怀疑：生产 wrapper 必须也只能返回 DecryptResult，不能让 Error 逃出去。 */
    @Test
    fun aTamperedDirectCiphertextAlsoReturnsADecryptResult() {
        val alice = GroupParty("alice", 1)
        val bob = GroupParty("bob", 1)
        org.signal.libsignal.protocol.SessionBuilder(
            (alice.context.protocolStore as PersistentSignalProtocolStore),
            bob.address,
        ).process(bob.bundle())

        val text = plaintext("direct-tamper")
        val wire = org.signal.libsignal.protocol.SessionCipher(
            (alice.context.protocolStore as PersistentSignalProtocolStore),
            bob.address,
        ).encrypt(text.toByteArray(Charsets.UTF_8))
        val b64 = android.util.Base64.encodeToString(wire.serialize(), android.util.Base64.NO_WRAP)
        val type = if (wire.type == org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE) "prekey" else "signal"

        // 正对照：未篡改的必须解得出。
        assertEquals(
            DecryptResult.Success(text),
            bob.directCipher.decryptDeviceCiphertext("alice", 1, type, b64),
        )

        // 第二段密文用于篡改（避免污染上面那段的终态记录）。
        val wire2 = org.signal.libsignal.protocol.SessionCipher(
            (alice.context.protocolStore as PersistentSignalProtocolStore),
            bob.address,
        ).encrypt(plaintext("direct-tamper-2").toByteArray(Charsets.UTF_8))
        val b64b = android.util.Base64.encodeToString(wire2.serialize(), android.util.Base64.NO_WRAP)
        val mid = b64b.length / 2
        val flipped = if (b64b[mid] == 'A') 'B' else 'A'
        val tampered = b64b.substring(0, mid) + flipped + b64b.substring(mid + 1)

        val result = bob.directCipher.decryptDeviceCiphertext("alice", 1, type, tampered)
        assertNotSuccess("被篡改一个字符的直发密文", result)
    }

    @Test
    fun aTamperedGroupEnvelopeFailsAndNeverReturnsPlaintext() {
        val alice = GroupParty("alice", 1)
        val bob = GroupParty("bob", 1)
        val group = "group-tamper"

        val (_, distribution) = alice.distribute(group, 1)
        bob.install("alice", distribution, group, 1)
        val text = plaintext("tamper-target")
        val envelope = alice.send(group, text, 1)

        // 正对照：未篡改的能解。
        assertEquals(text, (bob.receive("alice", envelope, group, 1) as DecryptResult.Success).plaintext)

        // 篡改 base64 密文里的一个字符（保持 JSON 结构不变）。
        val marker = "\"ciphertext\":\""
        val start = envelope.indexOf(marker)
        assertTrue("前置条件：信封里应当有 ciphertext 字段", start >= 0)
        val valueStart = start + marker.length
        val valueEnd = envelope.indexOf('"', valueStart)
        val body = envelope.substring(valueStart, valueEnd)
        assertTrue("前置条件：密文应当有足够长度", body.length > 10)
        val mid = body.length / 2
        val flipped = if (body[mid] == 'A') 'B' else 'A'
        val tampered = envelope.substring(0, valueStart) + body.substring(0, mid) + flipped + body.substring(mid + 1) + envelope.substring(valueEnd)

        val result = bob.receive("alice", tampered, group, 1)
        // 精确断言 Failed（而不是泛泛的「不是 Success」）：libsignal 会把「意外的 checked
        // exception」包成 AssertionError（extends Error），若生产 wrapper 只 catch Exception，
        // 这个 Error 会直接穿出 decryptGroupContentEnvelope——这条断言就是那个缺陷的守卫。
        assertEquals(
            "被篡改的群信封必须收敛成 DecryptResult.Failed，而不是让 Error 穿出去",
            DecryptResult.Failed,
            result,
        )
        assertFalse("绝不能把密文当成原文返回", (result as? DecryptResult.Success)?.plaintext == text)
    }
}

package com.maodouchat.messaging.v2

import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maodouchat.core.crypto.DecryptResult
import com.maodouchat.crypto.PersistentSignalProtocolStore
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessagingV2InboxState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * **客户端装配层**的第一份端到端证据（G22）。
 *
 * G17–G20 测的都是**组件**（cipher / store / 畸形输入矩阵）。本文件测的是这些组件被
 * **真正接起来**之后的行为：驱动生产类 [SignalMessagingV2EnvelopeProcessor] 的 `process(envelope)`，
 * 即「服务端中转过来的信封 → 解密 → 内容策略 → 落库 sink」这条真实路径。
 * 装配是漏洞最容易藏的地方：某个分支短路、某个策略被跳过、失败却仍然提交。
 *
 * 用生产 `SignalProtocol(signalKeyDao, identityTrustDao)`（内部自建 context/编解码/session/
 * direct+group cipher）加生产 `PersistentSignalProtocolStore`；会话用 `SessionBuilder` 建立，
 * **不需要网络**。
 *
 * 关于「失败」的契约——这里刻意**不**按「process 不得抛异常」写断言：这个类的真实设计是按结果
 * 抛**具名** `IllegalStateException`（`messaging_v2_decrypt_failed` / `messaging_v2_no_session` /
 * `messaging_v2_future_group_revision` …），由调用方（收件箱同步器）据此分类成重试或死信。
 * 所以本文件钉的是：**失败时一行都不许提交**，且**抛出的名字必须准确**——名字错了，调用方就会把
 * 「以后还能重试」判成「永久失败」或反之。
 *
 * 关于群 epoch：`SignalMessagingV2Adapter` 用 `snapshot.memberRevision` 作为 sender key 的 epoch
 * （同一编排里 `groupRevisionProvider` 也返回 memberRevision），所以**生产的 epoch 与 revision 相等**。
 * 本文件的群用例因此用一致的数字，并**另外**钉住「epoch 比当前 revision 旧的分发必须被跳过」。
 */
@RunWith(AndroidJUnit4::class)
class SignalMessagingV2EnvelopeProcessorAssemblyTest {

    private lateinit var db: AppDatabase
    private val json = Json { encodeDefaults = true }

    /** 生产里 epoch == memberRevision；这里用同一个常量，避免造出生产不会出现的组合。 */
    private val groupRevision = 7L
    private val groupEpoch = 7L

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
        val protocol = SignalProtocol(db.signalKeyDao(), db.identityTrustDao())
        val context get() = protocol.context
        val address get() = SignalProtocolAddress(accountId, deviceId)
        val store get() = context.protocolStore as PersistentSignalProtocolStore

        private val preKeyId = 31337 + deviceId
        private val preKeyPair = Curve.generateKeyPair()
        private val signedPreKeyId = 22222 + deviceId
        private val signedPreKeyPair = Curve.generateKeyPair()
        private lateinit var signedPreKeySignature: ByteArray

        init {
            context.currentUserId = accountId
            context.generateIdentityKeys() // 带上账号重做一次，让 store 作用域正确
            context.localDeviceId = deviceId
            context.localCryptoReady = true
            signedPreKeySignature = Curve.calculateSignature(
                context.identityKeyPair.privateKey,
                signedPreKeyPair.publicKey.serialize(),
            )
            store.putPreKey(PreKeyRecord(preKeyId, preKeyPair))
            store.putSignedPreKey(
                SignedPreKeyRecord(signedPreKeyId, 1L, signedPreKeyPair, signedPreKeySignature),
            )
        }

        fun bundle(): PreKeyBundle = PreKeyBundle(
            context.registrationId, deviceId, preKeyId, preKeyPair.publicKey,
            signedPreKeyId, signedPreKeyPair.publicKey, signedPreKeySignature,
            context.identityKeyPair.publicKey,
        )

        fun encrypt(peer: Party, plaintext: String): CiphertextMessage =
            SessionCipher(store, peer.address).encrypt(plaintext.toByteArray(Charsets.UTF_8))

        fun decryptFrom(peer: Party, wire: CiphertextMessage): String {
            val cipher = SessionCipher(store, peer.address)
            val bytes = if (wire.type == CiphertextMessage.PREKEY_TYPE) {
                cipher.decrypt(PreKeySignalMessage(wire.serialize()))
            } else {
                cipher.decrypt(SignalMessage(wire.serialize()))
            }
            return String(bytes, Charsets.UTF_8)
        }
    }

    private class RecordingSink : MessagingV2DomainSink {
        val commits = mutableListOf<Pair<MessagingV2InboxEntity, MessagingV2Content>>()
        override suspend fun commit(envelope: MessagingV2InboxEntity, content: MessagingV2Content) {
            commits += envelope to content
        }
    }

    private fun dataJson(body: String, type: String = "TEXT", version: Int = 2): String =
        json.encodeToString(
            MessagingV2Content.serializer(),
            MessagingV2Content(version = version, type = type, body = body),
        )

    private fun b64(wire: CiphertextMessage) = Base64.encodeToString(wire.serialize(), Base64.NO_WRAP)

    private fun typeOf(wire: CiphertextMessage) =
        if (wire.type == CiphertextMessage.PREKEY_TYPE) "prekey" else "signal"

    private fun envelope(
        envelopeId: String,
        messageId: String,
        conversationId: String,
        senderUserId: String,
        senderDeviceId: Int,
        kind: String,
        ciphertextType: String,
        ciphertext: String,
        groupRevision: Long? = null,
    ) = MessagingV2InboxEntity(
        envelopeId = envelopeId,
        ownerUserId = "bob",
        deviceId = 1,
        sequence = 1L,
        messageId = messageId,
        conversationId = conversationId,
        senderUserId = senderUserId,
        senderDeviceId = senderDeviceId,
        kind = kind,
        groupRevision = groupRevision,
        clientTimestamp = 9_000L,
        serverTimestamp = 9_000L,
        ciphertextType = ciphertextType,
        ciphertext = ciphertext,
    )

    /** alice 侧建立会话；bob 侧在第一条被解开前没有会话状态。 */
    private fun directPair(): Pair<Party, Party> {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        runBlocking {
            SessionBuilder(alice.store, bob.address).process(bob.bundle())
        }
        return alice to bob
    }

    private fun processor(
        protocol: SignalProtocol,
        sink: MessagingV2DomainSink,
        revision: Long? = null,
        onSenderKeyMissing: (MessagingV2InboxEntity, Long) -> Unit = { _, _ -> },
        dao: MessagingV2Dao? = null,
    ) = SignalMessagingV2EnvelopeProcessor(
        signalProtocol = protocol,
        domainSink = sink,
        groupRevisionProvider = { revision },
        onSenderKeyMissing = { env, epoch -> onSenderKeyMissing(env, epoch) },
        inboxDao = dao,
    )

    @Test
    fun directEnvelopeIsDecryptedAndCommittedExactlyOnce() = runBlocking {
        val (alice, bob) = directPair()
        val sink = RecordingSink()
        val body = "assembly-direct-${UUID.randomUUID()}"
        val wire = alice.encrypt(bob, dataJson(body))

        processor(bob.protocol, sink).process(
            envelope("e-direct", "m-direct", "chat-1", "alice", 1, "DATA", typeOf(wire), b64(wire)),
        )

        assertEquals("必须恰好提交一次", 1, sink.commits.size)
        val (committedEnvelope, content) = sink.commits.single()
        assertEquals(body, content.body)
        assertEquals("m-direct", committedEnvelope.messageId)
        assertEquals("alice", committedEnvelope.senderUserId)
        assertEquals("chat-1", committedEnvelope.conversationId)
    }

    @Test
    fun senderKeyDistributionInstallsWithoutCommitting() = runBlocking {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        val sink = RecordingSink()
        SessionBuilder(alice.store, bob.address).process(bob.bundle())

        // 生产时序：epoch == memberRevision。
        val distribution = alice.protocol.createGroupSenderKeyDistribution("group-1", groupEpoch)
        val distributionJson = alice.protocol.envelopeCodec.buildSenderKeyDistributionEnvelope(
            groupId = "group-1",
            distributionId = distribution.distributionId,
            message = distribution.message,
            epoch = groupEpoch,
            senderDeviceId = 1,
        )
        val wire = alice.encrypt(bob, distributionJson)

        processor(bob.protocol, sink, revision = groupRevision).process(
            envelope(
                "e-dist", "m-dist", "group-1", "alice", 1, "SENDER_KEY", typeOf(wire), b64(wire),
                groupRevision = groupRevision,
            ),
        )

        assertEquals("分发信封只安装 sender key，不产生消息提交", 0, sink.commits.size)

        // 真正的可观察结果是「bob 现在能解开 alice 的群消息」。
        // 注意 `hasGroupDistributionId` 说的是**自己**用于发送的 sender key 元数据，
        // 与「是否安装了别人的 sender key」无关——用它断言会得到假失败（本轮踩过）。
        val groupText = "group-after-install-${UUID.randomUUID()}"
        val groupEnvelope = alice.protocol
            .encryptGroupTextEnvelope("group-1", groupText, "text", groupEpoch)
            .getOrThrow()
        assertEquals(
            groupText,
            (bob.protocol.decryptGroupContentEnvelope("alice", groupEnvelope, "group-1", groupEpoch)
                as DecryptResult.Success).plaintext,
        )
    }

    @Test
    fun aStaleDistributionIsSkippedAndTheGroupMessageStaysUndecryptable() = runBlocking {
        val alice = Party("alice", 1)
        val bob = Party("bob", 1)
        val sink = RecordingSink()
        SessionBuilder(alice.store, bob.address).process(bob.bundle())

        // alice 按**旧** epoch（1）分发，而当前 revision 是 7 → 安装必须被跳过（不变量 10 的陈旧性保护）。
        val stale = alice.protocol.createGroupSenderKeyDistribution("group-stale", 1L)
        val staleJson = alice.protocol.envelopeCodec.buildSenderKeyDistributionEnvelope(
            groupId = "group-stale",
            distributionId = stale.distributionId,
            message = stale.message,
            epoch = 1L,
            senderDeviceId = 1,
        )
        val staleWire = alice.encrypt(bob, staleJson)
        processor(bob.protocol, sink, revision = groupRevision).process(
            envelope(
                "e-stale", "m-stale", "group-stale", "alice", 1, "SENDER_KEY", typeOf(staleWire), b64(staleWire),
                groupRevision = 1L,
            ),
        )
        assertEquals("陈旧分发不得产生提交", 0, sink.commits.size)

        // 旧 epoch 的群消息因此解不开：必须走 NoSession + 修复回调，且一行都不提交。
        val groupEnvelope = alice.protocol
            .encryptGroupTextEnvelope("group-stale", "stale-text", "text", 1L)
            .getOrThrow()
        val missing = mutableListOf<Long>()
        val failure = runCatching {
            processor(
                bob.protocol, sink,
                revision = groupRevision,
                onSenderKeyMissing = { _, epoch -> missing += epoch },
            ).process(
                envelope(
                    "e-stale-msg", "m-stale-msg", "group-stale", "alice", 1, "DATA", "SENDER_KEY",
                    groupEnvelope, groupRevision,
                ),
            )
        }.exceptionOrNull()

        assertEquals("缺 sender key 时一行都不许提交", 0, sink.commits.size)
        assertEquals("必须触发一次 sender key 修复（用当前 revision 作为 epoch）", listOf(groupRevision), missing)
        assertEquals("messaging_v2_no_session", failure?.message)
    }

    @Test
    fun serviceEnvelopeIsCommittedAsPlaintextButRejectedWhenSenderPolicyFails() = runBlocking {
        val bob = Party("bob", 1)
        val sink = RecordingSink()
        val processor = processor(bob.protocol, sink)
        val body = "bot-plaintext-${UUID.randomUUID()}"

        // 合法：bot 发送者 + device 0 + SERVICE_PLAINTEXT。
        processor.process(
            envelope("e-svc", "m-svc", "chat-1", "bot_helper", 0, "SERVICE", "SERVICE_PLAINTEXT",
                dataJson(body, version = 1)),
        )
        assertEquals(1, sink.commits.size)
        assertEquals(body, sink.commits.single().second.body)

        val after = sink.commits.size
        // 三者各自违反一条策略：发送者不是 bot/system、设备号不是 0、密文类型不对。
        processor.process(
            envelope("e-svc-bad", "m-svc-bad", "chat-1", "alice", 0, "SERVICE", "SERVICE_PLAINTEXT",
                dataJson("spoofed", version = 1)),
        )
        processor.process(
            envelope("e-svc-dev", "m-svc-dev", "chat-1", "bot_helper", 1, "SERVICE", "SERVICE_PLAINTEXT",
                dataJson("wrong device", version = 1)),
        )
        processor.process(
            envelope("e-svc-type", "m-svc-type", "chat-1", "bot_helper", 0, "SERVICE", "signal",
                dataJson("wrong type", version = 1)),
        )
        assertEquals("三类不合法 service 信封都不得提交", after, sink.commits.size)
    }

    @Test
    fun undecryptableEnvelopesCommitNothingAndFailWithTheirOwnName() = runBlocking {
        val (alice, bob) = directPair()
        val sink = RecordingSink()
        val processor = processor(bob.protocol, sink)

        // 先让会话**双向确立**：bob 解第一条并回一条，这样 alice 后续发的才是普通 SignalMessage。
        val first = alice.encrypt(bob, dataJson("first"))
        processor.process(
            envelope("e-first", "m-first", "chat-1", "alice", 1, "DATA", typeOf(first), b64(first)),
        )
        assertEquals(1, sink.commits.size)
        val reply = bob.encrypt(alice, "ack")
        alice.decryptFrom(bob, reply)

        val second = alice.encrypt(bob, dataJson("second"))
        assertEquals(
            "前置条件：会话确立后应当是普通 SignalMessage" +
                "（首条 PreKey 自带建会话材料，不能用来测「没有会话」）",
            CiphertextMessage.WHISPER_TYPE,
            second.type,
        )
        val secondB64 = b64(second)

        val problems = mutableListOf<String>()
        fun check(label: String, env: MessagingV2InboxEntity, expectedName: String) {
            val before = sink.commits.size
            val failure = runCatching { runBlocking { processor.process(env) } }.exceptionOrNull()
            if (sink.commits.size != before) problems += "$label 竟然提交了内容"
            if (failure?.message != expectedName) {
                problems += "$label 期望失败名 $expectedName，实际 ${failure?.message}"
            }
        }

        // 篡改一个字符 → 解密失败
        val mid = secondB64.length / 2
        val flipped = if (secondB64[mid] == 'A') 'B' else 'A'
        val tampered = secondB64.substring(0, mid) + flipped + secondB64.substring(mid + 1)
        check(
            "篡改密文",
            envelope("e-t", "m-t", "chat-1", "alice", 1, "DATA", "signal", tampered),
            "messaging_v2_decrypt_failed",
        )

        // 非 base64 → 不支持的密文
        check(
            "非 base64",
            envelope("e-g", "m-g", "chat-1", "alice", 1, "DATA", "signal", "!!!not-base64!!!"),
            "messaging_v2_unsupported_ciphertext",
        )

        // 没有会话的陌生发送者。**实测**：libsignal 0.41 对「地址没有会话」的普通 SignalMessage
        // 抛的是 InvalidMessageException（不是 NoSessionException），于是组件层落到
        // DecryptResult.Failed、处理器报 `messaging_v2_decrypt_failed`。
        // 先断言组件层的真实结果，再断言处理器据此给出的名字——这样如果哪天 libsignal 改了分类，
        // 这条会明确指出是哪一层变了。
        val strangerResult = bob.protocol.decryptDeviceCiphertext("stranger", 1, "signal", secondB64)
        assertEquals(
            "组件层对未知发送者的直发消息给出的是 Failed（不是 NoSession）：$strangerResult",
            DecryptResult.Failed,
            strangerResult,
        )
        check(
            "陌生发送者",
            envelope("e-n", "m-n", "chat-1", "stranger", 1, "DATA", "signal", secondB64),
            "messaging_v2_decrypt_failed",
        )

        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun aPolicyRejectedPayloadIsNotCommittedAndDoesNotThrow() = runBlocking {
        val (alice, bob) = directPair()
        val sink = RecordingSink()
        val processor = processor(bob.protocol, sink)

        // kind=DATA 但 content.type 是保留控制类型 EVENT → 策略必须拒绝（防「把 DATA 伪装成事件」）。
        val smuggled = json.encodeToString(
            MessagingV2Content.serializer(),
            MessagingV2Content(version = 2, type = "EVENT", body = "smuggled"),
        )
        val wire = alice.encrypt(bob, smuggled)
        processor.process(
            envelope("e-smuggle", "m-smuggle", "chat-1", "alice", 1, "DATA", typeOf(wire), b64(wire)),
        )

        assertEquals("策略拒绝的载荷不得提交，也不得把异常抛给调用方", 0, sink.commits.size)
    }

    @Test
    fun duplicateWithoutJournalIsNotCommittedTwiceAndFailsNamed() = runBlocking {
        val (alice, bob) = directPair()
        val sink = RecordingSink()
        val processor = processor(bob.protocol, sink) // inboxDao = null → 没有 journal 可恢复
        val wire = alice.encrypt(bob, dataJson("dup-${UUID.randomUUID()}"))
        val env = envelope("e-dup", "m-dup", "chat-1", "alice", 1, "DATA", typeOf(wire), b64(wire))

        processor.process(env)
        assertEquals(1, sink.commits.size)

        val failure = runCatching { processor.process(env) }.exceptionOrNull()
        assertEquals("重复信封不得二次提交", 1, sink.commits.size)
        assertEquals("messaging_v2_duplicate_uncommitted", failure?.message)
    }

    @Test
    fun duplicateWithJournalRecoversTheProjectionFromTheJournal() = runBlocking {
        val (alice, bob) = directPair()
        val sink = RecordingSink()
        val dao = db.messagingV2Dao()
        val processor = processor(bob.protocol, sink, dao = dao)
        val body = "assembly-journal-${UUID.randomUUID()}"
        val wire = alice.encrypt(bob, dataJson(body))
        val env = envelope("e-journal", "m-journal", "chat-1", "alice", 1, "DATA", typeOf(wire), b64(wire))

        // journal 的写入是 `UPDATE ... WHERE envelopeId = ? AND state = 'PROCESSING'`：
        // 行不仅要先存在，还必须处在 PROCESSING（真实时序是「先 claim 再 process」）。
        // 状态不对时 journal 会**静默写不进去**（0 行）——这正是本条要盯住的时序契约。
        dao.insertInbox(listOf(env.copy(state = MessagingV2InboxState.PROCESSING)))

        processor.process(env)
        assertEquals(1, sink.commits.size)
        assertTrue(
            "成功解密后必须留下 journal，供进程被杀后恢复",
            !dao.plaintextJournal("e-journal").isNullOrBlank(),
        )

        // 进程被杀在「解密后、投影前」：重放同一信封 → Duplicate → 从 journal 恢复并提交，
        // 且 journal 随后被清空（免得永远重放）。
        processor.process(env)
        assertEquals("第二次应当从 journal 恢复出投影", 2, sink.commits.size)
        assertEquals(body, sink.commits[1].second.body)
        assertTrue("恢复后 journal 必须清空", dao.plaintextJournal("e-journal").isNullOrBlank())
    }
}

package com.maodouchat.messaging.v2

import com.maodouchat.core.crypto.DecryptResult
import com.maodouchat.crypto.SenderKeyDistOutcome
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * G14：解密路径 `SignalMessagingV2EnvelopeProcessor` 的第一批证据。
 *
 * 这段代码守着整个 App 最隐蔽的一类失败：**「消息收到了，但是解不开」**。
 * 如果它把解不开的信封当成已提交（或静默丢掉正文），用户看到的是「消息没了」，
 * 而日志里一切正常——没有任何测试挡得住。
 *
 * 因此本文件的重点不是「解密成功会怎样」，而是**每条失败路径都不许静默**：
 * 必须抛错（进入重试/死信），且 `domainSink.commit` 一次都不能被调用。
 */
class SignalMessagingV2EnvelopeProcessorTest {

    private val protocol = mockk<SignalProtocol>()
    private val commits = mutableListOf<MessagingV2Content>()
    private val sink = MessagingV2DomainSink { _, content -> commits += content }

    private val validDataPayload = """{"version":1,"type":"TEXT","body":"hello"}"""

    private fun envelope(
        kind: String = "DATA",
        ciphertextType: String = "TEXT",
        ciphertext: String = "CIPHER",
        senderUserId: String = "u2",
        senderDeviceId: Int = 1,
        groupRevision: Long? = null,
    ) = MessagingV2InboxEntity(
        envelopeId = "e1",
        ownerUserId = "u1",
        deviceId = 1,
        sequence = 1L,
        messageId = "m1",
        conversationId = "c1",
        senderUserId = senderUserId,
        senderDeviceId = senderDeviceId,
        kind = kind,
        groupRevision = groupRevision,
        clientTimestamp = 1_000L,
        serverTimestamp = 1_000L,
        ciphertextType = ciphertextType,
        ciphertext = ciphertext,
    )

    private fun processor(
        dao: MessagingV2Dao? = null,
        revision: Long? = null,
        onSenderKeyMissing: suspend (MessagingV2InboxEntity, Long) -> Unit = { _, _ -> },
    ) = SignalMessagingV2EnvelopeProcessor(
        signalProtocol = protocol,
        domainSink = sink,
        groupRevisionProvider = { revision },
        onSenderKeyMissing = onSenderKeyMissing,
        inboxDao = dao,
    )

    @Test
    fun `every decrypt failure is reported and nothing is committed`() = runTest {
        val cases = listOf(
            DecryptResult.Failed to "messaging_v2_decrypt_failed",
            DecryptResult.UntrustedIdentity to "messaging_v2_untrusted_identity",
            DecryptResult.FutureEpoch to "messaging_v2_future_group_revision",
            DecryptResult.NotForThisDevice to "messaging_v2_wrong_device",
            DecryptResult.UnsupportedEnvelope to "messaging_v2_unsupported_ciphertext",
            DecryptResult.NoSession to "messaging_v2_no_session",
        )
        val seen = mutableListOf<String>()
        cases.forEach { (result, expectedCode) ->
            every {
                protocol.decryptDeviceCiphertext(any(), any(), any(), any())
            } returns result

            val error = assertFailsWith<IllegalStateException> { processor().process(envelope()) }
            val message = error.message ?: ""
            assertTrue(message.contains(expectedCode), "失败码应当可区分：期望 $expectedCode，实际 $message")
            seen += expectedCode
        }
        assertEquals(seen.distinct().size, seen.size, "各失败码必须互不相同（否则无法诊断）：$seen")
        assertTrue(commits.isEmpty(), "解不开的信封绝不能被提交：$commits")
    }

    @Test
    fun `a sender key envelope with no session asks for a repair and still fails`() = runTest {
        every { protocol.decryptGroupContentEnvelope(any(), any(), any(), any()) } returns DecryptResult.NoSession
        val asked = mutableListOf<Pair<String, Long>>()

        val error = assertFailsWith<IllegalStateException> {
            processor(onSenderKeyMissing = { e, epoch -> asked += e.envelopeId to epoch })
                .process(envelope(kind = "DATA", ciphertextType = "SENDER_KEY", groupRevision = 9L))
        }

        assertTrue(error.message!!.contains("messaging_v2_no_session"), error.message!!)
        assertEquals(
            listOf("e1" to 9L),
            asked,
            "sender key 缺失必须触发修复请求，否则群消息永远解不开：$asked",
        )
        assertTrue(commits.isEmpty(), "没解开就不能提交")
    }

    @Test
    fun `a sender key envelope with an unknown epoch does not ask for a repair`() = runTest {
        every { protocol.decryptGroupContentEnvelope(any(), any(), any(), any()) } returns DecryptResult.NoSession
        var asked = 0

        assertFailsWith<IllegalStateException> {
            processor(onSenderKeyMissing = { _, _ -> asked++ })
                .process(envelope(kind = "DATA", ciphertextType = "SENDER_KEY", groupRevision = null))
        }

        assertEquals(0, asked, "拿不到 epoch 就不该发起修复（否则会用错误的 epoch 请求 sender key）")
    }

    @Test
    fun `a duplicate with neither journal nor projection is not silently dropped`() = runTest {
        every { protocol.decryptDeviceCiphertext(any(), any(), any(), any()) } returns DecryptResult.Duplicate
        val dao = mockk<MessagingV2Dao>()
        coEvery { dao.plaintextJournal(any()) } returns null
        coEvery { dao.isMessageProjected(any(), any()) } returns false

        val error = assertFailsWith<IllegalStateException> { processor(dao).process(envelope()) }

        assertTrue(error.message!!.contains("messaging_v2_duplicate_uncommitted"), error.message!!)
        assertTrue(commits.isEmpty(), "既没有 journal 也没有投影，就说明正文真的丢了——不能假装成功")
    }

    @Test
    fun `a duplicate recovers the projection from the journal and clears it`() = runTest {
        every { protocol.decryptDeviceCiphertext(any(), any(), any(), any()) } returns DecryptResult.Duplicate
        val dao = mockk<MessagingV2Dao>()
        coEvery { dao.plaintextJournal("e1") } returns validDataPayload
        coEvery { dao.writePlaintextJournal(any(), any(), any()) } returns 1

        processor(dao).process(envelope())

        assertEquals(1, commits.size, "有 journal 就必须把它恢复成投影（这是进程死在提交前的唯一恢复路径）")
        coVerify(exactly = 1) { dao.writePlaintextJournal("e1", "", any()) }
    }

    @Test
    fun `a duplicate sender key distribution is acknowledged without a new commit`() = runTest {
        every { protocol.decryptGroupContentEnvelope(any(), any(), any(), any()) } returns DecryptResult.Duplicate

        // 幂等重放：不能抛错（否则会被推进死信），也不需要再提交一次。
        processor().process(envelope(kind = "SENDER_KEY", ciphertextType = "SENDER_KEY"))

        assertTrue(commits.isEmpty(), "重复的 sender key 分发不该再产生一次投影")
    }

    @Test
    fun `the plaintext journal is written before the projection commits`() = runTest {
        every { protocol.decryptDeviceCiphertext(any(), any(), any(), any()) } returns
            DecryptResult.Success(validDataPayload)
        val order = mutableListOf<String>()
        val dao = mockk<MessagingV2Dao>()
        coEvery { dao.writePlaintextJournal(any(), any(), any()) } answers { order += "journal"; 1 }
        val orderedSink = MessagingV2DomainSink { _, _ -> order += "commit" }

        SignalMessagingV2EnvelopeProcessor(
            signalProtocol = protocol,
            domainSink = orderedSink,
            groupRevisionProvider = { null },
            onSenderKeyMissing = { _, _ -> },
            inboxDao = dao,
        ).process(envelope())

        assertEquals(
            listOf("journal", "commit"),
            order,
            "必须先写 journal 再提交：棘轮步进在解密那一刻就已持久化，journal 是崩溃后唯一的恢复路径",
        )
    }

    @Test
    fun `a service envelope must satisfy the sender policy to be committed`() = runTest {
        val rejected = envelope(kind = "SERVICE", ciphertextType = "SERVICE_PLAINTEXT", senderDeviceId = 1)
        processor().process(rejected)
        assertTrue(commits.isEmpty(), "设备号不是 0 的「service」消息不该被采信：$commits")

        val accepted = envelope(
            kind = "SERVICE",
            ciphertextType = "SERVICE_PLAINTEXT",
            ciphertext = validDataPayload,
            senderUserId = "bot_helper",
            senderDeviceId = 0,
        )
        processor().process(accepted)
        assertEquals(1, commits.size, "合法的 service 消息（bot_ 前缀 + 设备 0 + SERVICE_PLAINTEXT）应当提交")
        // service 走的是明文分支，不该碰 Signal 解密。
        verify(exactly = 0) { protocol.decryptDeviceCiphertext(any(), any(), any(), any()) }
    }

    @Test
    fun `a payload whose kind was disguised is not committed`() = runTest {
        // 发送方控制 kind：把 DATA 伪装成 RECEIPT 试图污染有序收件箱。
        every { protocol.decryptDeviceCiphertext(any(), any(), any(), any()) } returns
            DecryptResult.Success(validDataPayload)

        processor().process(envelope(kind = "RECEIPT"))

        assertTrue(commits.isEmpty(), "kind 与内容不匹配时必须拒绝提交：$commits")
    }

    @Test
    fun `a sender key install failure aborts instead of committing`() = runTest {
        every { protocol.decryptGroupContentEnvelope(any(), any(), any(), any()) } returns
            DecryptResult.Success("sk-dist")
        every {
            protocol.processSenderKeyDistributionEnvelope(any(), any(), any(), any())
        } returns SenderKeyDistOutcome.Failed

        val error = assertFailsWith<IllegalStateException> {
            processor().process(envelope(kind = "SENDER_KEY", ciphertextType = "SENDER_KEY"))
        }

        assertTrue(error.message!!.contains("messaging_v2_sender_key_install_failed"), error.message!!)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun `an already installed sender key distribution is accepted idempotently`() = runTest {
        every { protocol.decryptGroupContentEnvelope(any(), any(), any(), any()) } returns
            DecryptResult.Success("sk-dist")
        every {
            protocol.processSenderKeyDistributionEnvelope(any(), any(), any(), any())
        } returns SenderKeyDistOutcome.Skipped

        processor().process(envelope(kind = "SENDER_KEY", ciphertextType = "SENDER_KEY"))

        assertTrue(commits.isEmpty(), "sender key 分发不产生消息投影")
    }
}

package com.maodouchat.messaging.v2

import com.maodouchat.crypto.DeviceCiphertext
import com.maodouchat.crypto.MultiRecipientEnvelopePayload
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G13：Signal 信封**加密准备器**的第一批证据。
 *
 * 这个类决定「哪些明文、加密给哪些设备」，是客户端侧最要害的一段逻辑，
 * 而它此前**零测试**（`app/src/test/java/com/maodouchat/messaging/v2/` 下 20 个文件里没有它）。
 *
 * 它只依赖三个可注入的东西（`SignalProtocol` + 快照 provider + `ensureGroupReady`），
 * 所以能在 JVM 单测里把加密层替换成替身，专门验「准备器自己的守卫」——
 * 快照归属、群控过期、覆盖集合、以及**明文不出现在信封里**。
 */
class SignalMessagingV2EnvelopePreparerTest {

    private val protocol = mockk<SignalProtocol>()

    private val plaintextSentinel = "PLAINTEXT-SENTINEL-2f9c41d7"

    private fun outbox(
        kind: String = "DATA",
        conversationId: String = "c1",
        ownerUserId: String = "u1",
        groupRevision: Long? = null,
    ) = MessagingV2OutboxEntity(
        messageId = "m1",
        ownerUserId = ownerUserId,
        conversationId = conversationId,
        kind = kind,
        localPayload = """{"type":"TEXT","body":"$plaintextSentinel"}""",
        clientTimestamp = 1_000L,
        groupRevision = groupRevision,
    )

    private fun snapshot(
        conversationId: String = "c1",
        ownerUserId: String = "u1",
        isGroup: Boolean = false,
        memberRevision: Long = 7L,
        targets: Set<MessagingV2DeviceTarget> = setOf(MessagingV2DeviceTarget("u2", 1)),
    ) = MessagingV2ConversationSnapshot(
        conversationId = conversationId,
        ownerUserId = ownerUserId,
        isGroup = isGroup,
        memberRevision = memberRevision,
        participantUserIds = listOf("u1", "u2"),
        targets = targets,
    )

    private fun preparerFor(snapshot: MessagingV2ConversationSnapshot) =
        SignalMessagingV2EnvelopePreparer(
            signalProtocol = protocol,
            snapshotProvider = { _, _ -> snapshot },
        )

    private fun directPayload(vararg targets: MessagingV2DeviceTarget) = MultiRecipientEnvelopePayload(
        envelope = "e",
        targets = emptyList(),
        ciphertexts = targets.map {
            DeviceCiphertext(it.userId, it.deviceId, "TEXT", "CIPHER-${it.userId}-${it.deviceId}")
        },
    )

    @Test
    fun `a snapshot for another conversation is rejected before any encryption`() = runTest {
        val preparer = preparerFor(snapshot(conversationId = "other"))

        val error = assertFailsWith<IllegalArgumentException> { preparer.prepare("t", outbox()) }
        assertTrue(
            error.message!!.contains("messaging_v2_snapshot_mismatch"),
            "必须明确报出快照不匹配：${error.message}",
        )
        coVerify(exactly = 0) {
            protocol.encryptMultiRecipientContentEnvelopeWithTargets(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a snapshot owned by another user is rejected before any encryption`() = runTest {
        val preparer = preparerFor(snapshot(ownerUserId = "someone-else"))

        val error = assertFailsWith<IllegalArgumentException> { preparer.prepare("t", outbox()) }
        assertTrue(
            error.message!!.contains("messaging_v2_snapshot_owner_mismatch"),
            "必须明确报出归属不匹配：${error.message}",
        )
        coVerify(exactly = 0) {
            protocol.encryptMultiRecipientContentEnvelopeWithTargets(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a stale group control is rejected before any encryption`() = runTest {
        val preparer = preparerFor(
            snapshot(isGroup = true, memberRevision = 7L, targets = setOf(MessagingV2DeviceTarget("u2", 1))),
        )
        // 群控消息携带的是旧修订（5 < 7）：此时加密出去只会让成员拿到失效的 sender key。
        val stale = outbox(kind = "SENDER_KEY", groupRevision = 5L)

        assertFailsWith<MessagingV2StaleGroupControlException> { preparer.prepare("t", stale) }
        coVerify(exactly = 0) { protocol.encryptGroupContentEnvelope(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            protocol.encryptMultiRecipientContentEnvelopeWithTargets(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `group control whose envelopes do not match the snapshot are rejected`() = runTest {
        val targets = setOf(MessagingV2DeviceTarget("u2", 1), MessagingV2DeviceTarget("u3", 1))
        val preparer = preparerFor(snapshot(isGroup = true, memberRevision = 7L, targets = targets))
        // 加密层只覆盖了两个目标中的一个：直接发出去会让某个成员收不到群控消息。
        coEvery {
            protocol.encryptMultiRecipientContentEnvelopeWithTargets(any(), any(), any(), any(), any(), any())
        } returns Result.success(directPayload(MessagingV2DeviceTarget("u2", 1)))

        val error = assertFailsWith<IllegalStateException> {
            preparer.prepare("t", outbox(kind = "KEY_REQUEST", groupRevision = 7L))
        }
        assertTrue(
            error.message!!.contains("messaging_v2_group_control_coverage_mismatch"),
            "覆盖不一致必须被拦下：${error.message}",
        )
    }

    @Test
    fun `direct send whose envelopes do not match the snapshot are rejected`() = runTest {
        val targets = setOf(MessagingV2DeviceTarget("u2", 1), MessagingV2DeviceTarget("u3", 1))
        val preparer = preparerFor(snapshot(targets = targets))
        coEvery {
            protocol.encryptMultiRecipientContentEnvelopeWithTargets(any(), any(), any(), any(), any(), any())
        } returns Result.success(directPayload(MessagingV2DeviceTarget("u2", 1)))

        val error = assertFailsWith<IllegalStateException> { preparer.prepare("t", outbox()) }
        assertTrue(
            error.message!!.contains("messaging_v2_crypto_coverage_mismatch"),
            "覆盖不一致必须被拦下：${error.message}",
        )
    }

    @Test
    fun `a direct send with no targets encrypts nothing`() = runTest {
        val preparer = preparerFor(snapshot(targets = emptySet()))

        val prepared = preparer.prepare("t", outbox())

        assertTrue(prepared.envelopes.isEmpty(), "没有目标设备时不该产出信封：${prepared.envelopes}")
        coVerify(exactly = 0) {
            protocol.encryptMultiRecipientContentEnvelopeWithTargets(any(), any(), any(), any(), any(), any())
        }
    }

    /**
     * 明文边界（与已立的不变量 9 呼应）：明文**进**加密层，密文**出**到信封。
     *
     * 断言刻意分三截，缺一不可：
     * 1. 加密层确实收到了明文（证明这次真的加密了，不是「什么都没做」也能过）；
     * 2. 加密层确实产出了信封（同上）；
     * 3. 信封里带的是加密层返回的**密文标记**，而不是明文。
     *
     * 只断言 3 会变成自证——一个什么都不做的实现也能满足「信封里没有明文」。
     */
    @Test
    fun `the plaintext goes into the cipher and never into the envelope`() = runTest {
        val sentPlaintext = slot<String>()
        coEvery {
            protocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = any(),
                recipientIds = any(),
                plaintext = capture(sentPlaintext),
                payloadType = any(),
                includeCurrentUserDevices = any(),
                requiredRecipientIds = any(),
            )
        } returns Result.success(directPayload(MessagingV2DeviceTarget("u2", 1)))
        every { protocol.encryptGroupContentEnvelope(any(), any(), any(), any()) } returns
            Result.success("CIPHER-GROUP")

        val prepared = preparerFor(snapshot()).prepare("t", outbox())

        assertTrue(sentPlaintext.isCaptured, "加密层必须被真正调用")
        assertTrue(
            sentPlaintext.captured.contains(plaintextSentinel),
            "加密层收到的应当就是待加密的明文负载：${sentPlaintext.captured}",
        )
        assertEquals(1, prepared.envelopes.size, "有一个目标设备就应当产出一个信封")

        val envelope = prepared.envelopes.single()
        assertEquals("CIPHER-u2-1", envelope.ciphertext, "信封必须携带加密层返回的密文")
        assertFalse(envelope.ciphertext.contains(plaintextSentinel), "信封里出现了明文：${envelope.ciphertext}")
        assertFalse(envelope.ciphertextType.contains(plaintextSentinel))
        assertFalse(envelope.recipientUserId.contains(plaintextSentinel))
    }
}

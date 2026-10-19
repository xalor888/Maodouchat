package com.maodouchat.messaging.v2

import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxState
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ApiService
import com.maodouchat.network.EncryptedDeviceEnvelopeRequestV2
import com.maodouchat.network.SendMessageRequestV2
import com.maodouchat.network.SendMessageResponseV2
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * messaging-v2 不变量 **第 9 条**：
 * 「Outbox plaintext exists only in the local SQLCipher database.
 *   Network requests contain only per-device ciphertext.」
 *
 * 这条在补测试之前**一个用例都没有**——而它正是 E2EE 的命门：只要有一条路径把
 * `MessagingV2OutboxEntity.localPayload`（本机明文）带进网络请求，端到端加密就名存实亡。
 *
 * 这里守的是**传输边界**（`MessagingV2OutboxCoordinator`）：
 * - 该发出去的只有 `PreparedMessageV2.envelopes`（每设备密文 + 收件人坐标）；
 * - 该落盘留待发送的也只有这些信封；
 * - 本机明文只留在 SQLCipher 的 outbox 行里。
 *
 * 注意断言方式：给 outbox 行塞一个**哨兵明文**，然后检查真正交给线路层/落盘的东西里
 * 从不出现它。这样即使以后有人把 `localPayload` 加进 `SendMessageRequestV2` 或落盘 JSON，
 * 用例也会红——而不是只验证「我构造的密文长这样」。
 */
class MessagingV2OutboxPlaintextBoundaryTest {

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val sentinel = "PLAINTEXT-SENTINEL-2f9c41d7"
    private val ciphertext = "BASE64-PER-DEVICE-CIPHERTEXT-9x"

    @AfterTest
    fun tearDown() {
        unmockkObject(ApiService)
    }

    private fun sendingRow() = MessagingV2OutboxEntity(
        messageId = "m1",
        ownerUserId = "u1",
        conversationId = "c1",
        kind = "TEXT",
        localPayload = """{"body":"$sentinel"}""",
        clientTimestamp = 1L,
        groupRevision = 7L,
        preparedEnvelopesJson = json.encodeToString(
            ListSerializer(EncryptedDeviceEnvelopeRequestV2.serializer()),
            listOf(EncryptedDeviceEnvelopeRequestV2("u2", 1, "TEXT", ciphertext)),
        ),
        state = MessagingV2OutboxState.SENDING,
    )

    @Test
    fun `the wire request carries per-device ciphertext and never the local plaintext`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val captured = slot<SendMessageRequestV2>()
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery { dao.claimNextOutbox("u1", any()) } returnsMany listOf(sendingRow(), null)
        coEvery { dao.completeOutbox("m1", "u1") } returns 1
        mockkObject(ApiService)
        coEvery { ApiService.sendMessageV2(any(), capture(captured)) } returns
            Result.success(SendMessageResponseV2("m1", 2L, 1, false))

        var completed = false
        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> error("SENDING 状态不该再走 prepare") },
            onCompleted = { completed = true },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        assertTrue(completed, "SENDING 行应当被送出并标记完成")
        val body = json.encodeToString(SendMessageRequestV2.serializer(), captured.captured)
        assertFalse(
            body.contains(sentinel),
            "出站请求里出现了本机明文——不变量 9 被破坏了：$body",
        )
        assertTrue(
            body.contains(ciphertext),
            "出站请求必须带上每设备密文：$body",
        )
        assertTrue(
            captured.captured.envelopes.single().recipientUserId == "u2",
            "信封必须带收件人坐标，否则服务端无法按设备投递",
        )
    }

    @Test
    fun `what gets staged for the wire is the prepared envelopes, not the plaintext`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val staged = slot<String>()
        val preparing = sendingRow().copy(
            state = MessagingV2OutboxState.PREPARING,
            preparedEnvelopesJson = null,
        )
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery { dao.claimNextOutbox("u1", any()) } returnsMany listOf(preparing, null)
        coEvery {
            dao.storePreparedOutbox("m1", "u1", capture(staged), any(), any())
        } returns 1

        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ ->
                PreparedMessageV2(
                    groupRevision = 7L,
                    envelopes = listOf(EncryptedDeviceEnvelopeRequestV2("u2", 1, "TEXT", ciphertext)),
                )
            },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        assertFalse(
            staged.captured.contains(sentinel),
            "落盘留待发送的内容里出现了本机明文——不变量 9 被破坏了：${staged.captured}",
        )
        assertTrue(
            staged.captured.contains(ciphertext),
            "落盘的应当是每设备密文信封：${staged.captured}",
        )
        // 反面：真正的本机明文仍然在实体里（这是对的——它只该待在 SQLCipher）。
        assertTrue(preparing.localPayload.contains(sentinel), "本机 outbox 行仍然是明文的唯一居所")
    }

    @Test
    fun `cancelled preparation stages nothing for the wire`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val preparing = sendingRow().copy(
            state = MessagingV2OutboxState.PREPARING,
            preparedEnvelopesJson = null,
        )
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery { dao.claimNextOutbox("u1", any()) } returnsMany listOf(preparing, null)

        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> throw CancellationException("test cancel") },
        )
        assertFailsWith<CancellationException> {
            coordinator.flush(token = "token", ownerUserId = "u1")
        }
        coVerify(exactly = 0) {
            dao.storePreparedOutbox(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `failed send leaves the retry row local-only`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val captured = slot<SendMessageRequestV2>()
        val retryState = slot<String>()
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery { dao.claimNextOutbox("u1", any()) } returnsMany listOf(sendingRow(), null)
        coEvery {
            dao.markOutboxFailed(any(), any(), any(), capture(retryState), any(), any(), any())
        } returns 1
        mockkObject(ApiService)
        coEvery { ApiService.sendMessageV2(any(), capture(captured)) } returns
            Result.failure(ApiException(ApiFailureKind.NETWORK))

        var completed = false
        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> error("SENDING 状态不该再走 prepare") },
            onCompleted = { completed = true },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        assertFalse(completed, "失败的发送不该触发 onCompleted")
        assertEquals(MessagingV2OutboxState.RETRY_SEND, retryState.captured)
        val body = json.encodeToString(SendMessageRequestV2.serializer(), captured.captured)
        assertFalse(body.contains(sentinel), "失败路径上出站请求里也不许出现本机明文：$body")
        assertTrue(body.contains(ciphertext), "失败的出站请求里仍应是每设备密文：$body")
    }

    @Test
    fun `sending row without prepared envelopes never reaches the wire`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery {
            dao.claimNextOutbox("u1", any())
        } returnsMany listOf(sendingRow().copy(preparedEnvelopesJson = null), null)
        coEvery { dao.markOutboxFailed(any(), any(), any(), any(), any(), any(), any()) } returns 1
        mockkObject(ApiService)

        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> error("SENDING 状态不该再走 prepare") },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        coVerify(exactly = 0) { ApiService.sendMessageV2(any(), any()) }
        coVerify(exactly = 1) {
            dao.markOutboxFailed(
                "m1", "u1", MessagingV2OutboxState.SENDING,
                MessagingV2OutboxState.RETRY_SEND, any(), any(), any(),
            )
        }
    }

    @Test
    fun `stale group control message is discarded without leaking plaintext`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val captured = slot<SendMessageRequestV2>()
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery {
            dao.claimNextOutbox("u1", any())
        } returnsMany listOf(sendingRow().copy(kind = "SENDER_KEY"), null)
        coEvery {
            dao.discardOutbox("m1", "u1", MessagingV2OutboxState.SENDING)
        } returns 1
        mockkObject(ApiService)
        coEvery { ApiService.sendMessageV2(any(), capture(captured)) } returns
            Result.failure(
                ApiException(
                    kind = ApiFailureKind.HTTP,
                    statusCode = 409,
                    serverCode = "GROUP_REVISION_MISMATCH",
                ),
            )

        var completed = false
        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> error("SENDING 状态不该再走 prepare") },
            onCompleted = { completed = true },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        assertFalse(completed, "过期的群控消息不该被标记完成")
        coVerify(exactly = 1) {
            dao.discardOutbox("m1", "u1", MessagingV2OutboxState.SENDING)
        }
        val body = json.encodeToString(SendMessageRequestV2.serializer(), captured.captured)
        assertFalse(body.contains(sentinel), "丢弃路径上出站请求里也不许出现本机明文：$body")
    }

    @Test
    fun `device coverage mismatch requeues for fresh encryption with plaintext still local`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val captured = slot<SendMessageRequestV2>()
        val retryState = slot<String>()
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery { dao.claimNextOutbox("u1", any()) } returnsMany listOf(sendingRow(), null)
        coEvery {
            dao.markOutboxFailed(any(), any(), any(), capture(retryState), any(), any(), any())
        } returns 1
        mockkObject(ApiService)
        coEvery { ApiService.sendMessageV2(any(), capture(captured)) } returns
            Result.failure(
                ApiException(
                    kind = ApiFailureKind.HTTP,
                    statusCode = 409,
                    serverCode = "DEVICE_COVERAGE_MISMATCH",
                ),
            )

        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> error("本轮只走 SENDING，不该再走 prepare") },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        assertEquals(MessagingV2OutboxState.RETRY_PREPARE, retryState.captured)
        val body = json.encodeToString(SendMessageRequestV2.serializer(), captured.captured)
        assertFalse(body.contains(sentinel), "重加密排队路径上出站请求里也不许出现本机明文：$body")
        assertTrue(body.contains(ciphertext), "重加密排队的请求里仍应是每设备密文：$body")
    }

    @Test
    fun `preparation failure stages nothing and requeues without leaking plaintext`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val retryState = slot<String>()
        val preparing = sendingRow().copy(
            state = MessagingV2OutboxState.PREPARING,
            preparedEnvelopesJson = null,
        )
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery { dao.claimNextOutbox("u1", any()) } returnsMany listOf(preparing, null)
        coEvery {
            dao.markOutboxFailed(any(), any(), any(), capture(retryState), any(), any(), any())
        } returns 1
        mockkObject(ApiService)

        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> throw IllegalStateException("encryption blew up") },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        assertEquals(MessagingV2OutboxState.RETRY_PREPARE, retryState.captured)
        coVerify(exactly = 0) {
            dao.storePreparedOutbox(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { ApiService.sendMessageV2(any(), any()) }
    }

    @Test
    fun `group revision mismatch on a data message requeues instead of discarding`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val captured = slot<SendMessageRequestV2>()
        val retryState = slot<String>()
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery { dao.claimNextOutbox("u1", any()) } returnsMany listOf(sendingRow(), null)
        coEvery {
            dao.markOutboxFailed(any(), any(), any(), capture(retryState), any(), any(), any())
        } returns 1
        mockkObject(ApiService)
        coEvery { ApiService.sendMessageV2(any(), capture(captured)) } returns
            Result.failure(
                ApiException(
                    kind = ApiFailureKind.HTTP,
                    statusCode = 409,
                    serverCode = "GROUP_REVISION_MISMATCH",
                ),
            )

        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> error("本轮只走 SENDING，不该再走 prepare") },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        assertEquals(MessagingV2OutboxState.RETRY_PREPARE, retryState.captured)
        coVerify(exactly = 0) { dao.discardOutbox(any(), any(), any()) }
        val body = json.encodeToString(SendMessageRequestV2.serializer(), captured.captured)
        assertFalse(body.contains(sentinel), "重加密排队路径上出站请求里也不许出现本机明文：$body")
    }

    @Test
    fun `group fan-out keeps every recipient envelope on the wire`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val captured = slot<SendMessageRequestV2>()
        val envelopes = listOf(
            EncryptedDeviceEnvelopeRequestV2("u2", 1, "TEXT", "CIPH-FOR-u2-77"),
            EncryptedDeviceEnvelopeRequestV2("u3", 2, "TEXT", "CIPH-FOR-u3-88"),
            EncryptedDeviceEnvelopeRequestV2("u4", 1, "TEXT", "CIPH-FOR-u4-99"),
        )
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery { dao.claimNextOutbox("u1", any()) } returnsMany listOf(
            sendingRow().copy(
                preparedEnvelopesJson = json.encodeToString(
                    ListSerializer(EncryptedDeviceEnvelopeRequestV2.serializer()),
                    envelopes,
                ),
            ),
            null,
        )
        coEvery { dao.completeOutbox("m1", "u1") } returns 1
        mockkObject(ApiService)
        coEvery { ApiService.sendMessageV2(any(), capture(captured)) } returns
            Result.success(SendMessageResponseV2("m1", 2L, 3, false))

        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> error("SENDING 状态不该再走 prepare") },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        val sent = captured.captured.envelopes
        assertEquals(listOf("u2", "u3", "u4"), sent.map { it.recipientUserId })
        val body = json.encodeToString(SendMessageRequestV2.serializer(), captured.captured)
        assertFalse(body.contains(sentinel), "群发路径上出站请求里也不许出现本机明文：$body")
        envelopes.forEach { envelope ->
            assertTrue(body.contains(envelope.ciphertext), "设备 ${envelope.recipientUserId} 的密文被丢了")
        }
    }

    @Test
    fun `stale group control discovered during preparation is discarded locally`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val preparing = sendingRow().copy(
            state = MessagingV2OutboxState.PREPARING,
            preparedEnvelopesJson = null,
            kind = "SENDER_KEY",
        )
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery { dao.claimNextOutbox("u1", any()) } returnsMany listOf(preparing, null)
        coEvery {
            dao.discardOutbox("m1", "u1", MessagingV2OutboxState.PREPARING)
        } returns 1
        mockkObject(ApiService)

        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> throw MessagingV2StaleGroupControlException() },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        coVerify(exactly = 1) {
            dao.discardOutbox("m1", "u1", MessagingV2OutboxState.PREPARING)
        }
        coVerify(exactly = 0) {
            dao.storePreparedOutbox(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { ApiService.sendMessageV2(any(), any()) }
    }

    @Test
    fun `flush with nothing claimed touches neither the wire nor the database`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        coEvery { dao.recoverStaleOutboxClaims(any(), any(), any()) } returns 0
        coEvery { dao.claimNextOutbox("u1", any()) } returns null
        mockkObject(ApiService)

        val coordinator = MessagingV2OutboxCoordinator(
            dao = dao,
            preparer = { _, _ -> error("空跑不该走到 prepare") },
        )
        coordinator.flush(token = "token", ownerUserId = "u1")

        coVerify(exactly = 0) { ApiService.sendMessageV2(any(), any()) }
        coVerify(exactly = 0) {
            dao.storePreparedOutbox(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            dao.markOutboxFailed(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { dao.discardOutbox(any(), any(), any()) }
        coVerify(exactly = 0) { dao.completeOutbox(any(), any()) }
    }
}

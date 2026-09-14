package com.maodouchat.messaging.v2

import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxState
import com.maodouchat.network.ApiService
import com.maodouchat.network.EncryptedDeviceEnvelopeRequestV2
import com.maodouchat.network.SendMessageRequestV2
import com.maodouchat.network.SendMessageResponseV2
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
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
}

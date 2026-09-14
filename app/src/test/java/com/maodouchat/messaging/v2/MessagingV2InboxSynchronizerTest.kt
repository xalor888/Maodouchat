package com.maodouchat.messaging.v2

import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.network.AcknowledgeEnvelopesResponseV2
import com.maodouchat.network.ApiService
import com.maodouchat.network.PendingEnvelopeV2Dto
import com.maodouchat.network.PendingInboxResponseV2
import com.maodouchat.network.toEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * messaging-v2 不变量 **5 / 6 / 7** —— 接收侧（inbox）生命周期：
 *
 * 5. 「先把信封落盘、再解密；只能经过唯一一个有序协调器，绝不能由 UI 或 WebSocket 收集器处理」
 * 6. 「解密出的领域数据先提交，inbox 行才转 `ACK_PENDING`」
 * 7. 「`ACK_PENDING` 跨进程存活；服务端 ACK 幂等，所以「远端已 ACK、本地还没删」之间崩溃会在下一轮收敛」
 *
 * 这三条此前**一个用例都没有**（全仓 `grep ACK_PENDING` 在测试里为空，
 * `MessagingV2InboxSynchronizer` 也没有测试文件）。而它们正是接收侧的顺序契约：
 * 顺序反了就会出现「消息没落库却告诉服务端已收到」这类**静默丢消息**。
 *
 * 断言方式是**看调用顺序与在失败路径上不该发生的事**，而不是复述实现。
 */
class MessagingV2InboxSynchronizerTest {

    @AfterTest
    fun tearDown() {
        unmockkObject(ApiService)
    }

    private fun dto(id: String) = PendingEnvelopeV2Dto(
        envelopeId = id,
        sequence = 1L,
        messageId = "m_$id",
        conversationId = "c1",
        senderUserId = "u2",
        senderDeviceId = 1,
        kind = "TEXT",
        groupRevision = null,
        clientTimestamp = 1L,
        serverTimestamp = 2L,
        ciphertextType = "TEXT",
        ciphertext = "ct_$id",
    )

    private fun MessagingV2Dao.stubInboxHappyPath(events: MutableList<String> = mutableListOf()) {
        coEvery { recoverStaleInboxClaims(any(), any(), any(), any()) } returns 0
        coEvery { insertInbox(any()) } answers { events += "insertInbox"; listOf(1L) }
        coEvery { claimNextInbox(any(), any(), any()) } returns null
        coEvery { ackPendingIds(any(), any(), any()) } returns emptyList()
        coEvery { deleteAcknowledgedInbox(any(), any(), any()) } returns 1
        coEvery { markDeadLettersAcknowledged(any(), any(), any(), any()) } returns 0
    }

    @Test
    fun `an envelope is persisted before it is decrypted, by the single coordinator`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val order = mutableListOf<String>()
        val envelope: MessagingV2InboxEntity = dto("e1").toEntity("u1", 1, 0)

        dao.stubInboxHappyPath(order)
        coEvery { dao.claimNextInbox("u1", 1, any()) } returnsMany listOf(envelope, null)
        coEvery { dao.markInboxAckPending("e1", any()) } answers { order += "ackPending"; 1 }
        mockkObject(ApiService)
        coEvery { ApiService.getPendingInboxV2(any(), any()) } returns
            Result.success(PendingInboxResponseV2(listOf(dto("e1")), hasMore = false))

        val coordinator = MessagingV2InboxSynchronizer(
            dao = dao,
            processor = { order += "decrypt" },
        )
        coordinator.sync(token = "token", ownerUserId = "u1", deviceId = 1)

        // 不变量 5：落盘必须在解密之前。
        val persistedAt = order.indexOf("insertInbox")
        val decryptedAt = order.indexOf("decrypt")
        assertTrue(persistedAt >= 0, "信封必须先落盘：$order")
        assertTrue(decryptedAt >= 0, "信封必须被处理：$order")
        assertTrue(
            persistedAt < decryptedAt,
            "顺序错了——先解密后落盘就意味着崩溃会丢消息：$order",
        )
        // 不变量 6：解密完成后才转 ACK_PENDING。
        assertTrue(
            order.indexOf("ackPending") > decryptedAt,
            "领域数据提交之前不得把 inbox 行标记为 ACK_PENDING：$order",
        )
    }

    @Test
    fun `a failed decryption never marks the row ACK_PENDING`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val envelope: MessagingV2InboxEntity = dto("e1").toEntity("u1", 1, 0)

        dao.stubInboxHappyPath()
        coEvery { dao.claimNextInbox("u1", 1, any()) } returnsMany listOf(envelope, null)
        coEvery { dao.markInboxFailed(any(), any(), any(), any()) } returns 1
        coEvery { dao.markInboxAckPending(any(), any()) } returns 1
        mockkObject(ApiService)
        coEvery { ApiService.getPendingInboxV2(any(), any()) } returns
            Result.success(PendingInboxResponseV2(listOf(dto("e1")), hasMore = false))

        val coordinator = MessagingV2InboxSynchronizer(
            dao = dao,
            processor = { error("transient_ratchet_failure") },
        )
        coordinator.sync(token = "token", ownerUserId = "u1", deviceId = 1)

        // 不变量 6 的另一半：解密/提交失败时，绝不能告诉服务端「已收到」。
        coVerify(exactly = 0) { dao.markInboxAckPending(any(), any()) }
        coVerify(exactly = 1) { dao.markInboxFailed("e1", any(), any(), any()) }
    }

    @Test
    fun `an acknowledgement that stays pending is re-sent idempotently`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val acked = mutableListOf<List<String>>()

        dao.stubInboxHappyPath()
        // 模拟「远端已 ACK、本地还没删」：同一批 id 在后续轮次里仍然是 ACK_PENDING。
        coEvery { dao.ackPendingIds("u1", 1, any()) } returnsMany
            listOf(listOf("e1"), listOf("e1"), emptyList())
        mockkObject(ApiService)
        coEvery { ApiService.getPendingInboxV2(any(), any()) } returns
            Result.success(PendingInboxResponseV2(emptyList(), hasMore = false))
        coEvery { ApiService.acknowledgeInboxV2(any(), any()) } answers {
            acked += secondArg<List<String>>()
            Result.success(AcknowledgeEnvelopesResponseV2(secondArg<List<String>>().size))
        }

        MessagingV2InboxSynchronizer(dao = dao, processor = { }).sync(
            token = "token",
            ownerUserId = "u1",
            deviceId = 1,
        )

        // 不变量 7：同一批 id 会被重复 ACK（服务端幂等），最终由本地删除收敛。
        assertEquals(2, acked.size, "仍然 pending 的 id 必须在下一轮被重发：$acked")
        assertEquals(listOf("e1"), acked[0])
        assertEquals(listOf("e1"), acked[1])
        coVerify(exactly = 2) { dao.deleteAcknowledgedInbox("u1", 1, listOf("e1")) }
    }
}

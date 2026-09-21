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
import kotlin.test.assertFalse
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

    // ─── 多页拉取循环（PULL_LIMIT = 200 / MAX_PULL_PAGES = 20） ───
    //
    // 这个循环从 G28 起每一轮都被写进「仍未覆盖」，是本清单里活最久的缺口。
    // 它值得单独测：`repeat(MAX_PULL_PAGES) { … if (!page.hasMore) return }` 有三处会静默出错的地方——
    // ① 漏拉（hasMore 判断写反 / 提前 return）→ 用户永远看不到中间那几页消息；
    // ② 重复拉（分页边界错）→ 同一封信封被 insertInbox 两次；
    // ③ 死循环（服务端一直说 hasMore=true）→ 同步永不返回，UI 一直转圈。
    // 断言方式全部是**外部可观察行为**（拉了几次、落盘了几次、什么顺序），不复述实现。

    /** 让 dao 对每一页都走「落盘 → 无待处理 → 无待 ACK」的空转路径，只记录 insertInbox 的顺序。 */
    private fun MessagingV2Dao.stubMultiPageDao(events: MutableList<String>) {
        coEvery { recoverStaleInboxClaims(any(), any(), any(), any()) } returns 0
        coEvery { insertInbox(any()) } answers {
            events += "insertInbox:" + firstArg<List<MessagingV2InboxEntity>>().joinToString(",") { it.envelopeId }
            listOf(1L)
        }
        coEvery { claimNextInbox(any(), any(), any()) } returns null
        coEvery { ackPendingIds(any(), any(), any()) } returns emptyList()
        coEvery { deleteAcknowledgedInbox(any(), any(), any()) } returns 1
        coEvery { markDeadLettersAcknowledged(any(), any(), any(), any()) } returns 0
    }

    @Test
    fun `multi page pull drains every page exactly once and in order`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val events = mutableListOf<String>()
        val pulls = mutableListOf<Int>()
        dao.stubMultiPageDao(events)
        mockkObject(ApiService)

        // 三页：前两页 hasMore=true，第三页 hasMore=false。
        coEvery { ApiService.getPendingInboxV2(any(), any()) } answers {
            pulls += secondArg<Int>()
            val page = pulls.size
            Result.success(
                PendingInboxResponseV2(
                    envelopes = listOf(dto("p${page}_e1"), dto("p${page}_e2")),
                    hasMore = page < 3,
                ),
            )
        }

        MessagingV2InboxSynchronizer(dao = dao, processor = { }).sync(
            token = "token",
            ownerUserId = "u1",
            deviceId = 1,
        )

        assertEquals(3, pulls.size, "三页必须全部拉完，一页都不能漏：$pulls")
        assertTrue(pulls.all { it == MessagingV2InboxSynchronizer.PULL_LIMIT }, "每页都必须按 PULL_LIMIT 拉：$pulls")
        // 每页的信封恰好落盘一次，且顺序与页面顺序一致。
        val inserts = events.filter { it.startsWith("insertInbox:") }
        assertEquals(3, inserts.size, "每页只应落盘一次，多一次就是重复拉取：$inserts")
        assertEquals(
            listOf("insertInbox:p1_e1,p1_e2", "insertInbox:p2_e1,p2_e2", "insertInbox:p3_e1,p3_e2"),
            inserts,
            "落盘顺序必须等于服务端分页顺序：$inserts",
        )
    }

    @Test
    fun `pull stops as soon as the server reports no more pages`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val events = mutableListOf<String>()
        dao.stubMultiPageDao(events)
        mockkObject(ApiService)

        // 第一页就 hasMore=false：绝不该发起第二次拉取。
        coEvery { ApiService.getPendingInboxV2(any(), any()) } returns
            Result.success(PendingInboxResponseV2(listOf(dto("only_1")), hasMore = false))

        MessagingV2InboxSynchronizer(dao = dao, processor = { }).sync(
            token = "token",
            ownerUserId = "u1",
            deviceId = 1,
        )

        coVerify(exactly = 1) { ApiService.getPendingInboxV2(any(), any()) }
        assertEquals(1, events.count { it.startsWith("insertInbox:") })
    }

    @Test
    fun `a failed batch stops the loop before pulling the next page`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val events = mutableListOf<String>()
        val pulls = mutableListOf<Int>()
        dao.stubMultiPageDao(events)
        // 第一页有一封待处理信封，且处理它必失败 → batchCompleted=false → 必须立刻 return。
        val first = dto("p1_bad").toEntity("u1", 1, 0)
        coEvery { dao.claimNextInbox(any(), any(), any()) } returnsMany listOf(first, null)
        coEvery { dao.markInboxFailed(any(), any(), any(), any()) } returns 1
        mockkObject(ApiService)
        coEvery { ApiService.getPendingInboxV2(any(), any()) } answers {
            pulls += secondArg<Int>()
            Result.success(
                PendingInboxResponseV2(
                    envelopes = if (pulls.size == 1) listOf(dto("p1_bad")) else listOf(dto("p2_never")),
                    hasMore = true,
                ),
            )
        }

        MessagingV2InboxSynchronizer(dao = dao, processor = { error("transient_ratchet_failure") }).sync(
            token = "token",
            ownerUserId = "u1",
            deviceId = 1,
        )

        assertEquals(1, pulls.size, "批处理失败后不得再拉第二页（否则会在坏信封后面继续吞消息）：$pulls")
        assertFalse(
            events.any { it.contains("p2_never") },
            "失败那一页之后的信封绝不允许被落盘：$events",
        )
    }

    @Test
    fun `a server that always claims more pages cannot spin forever`() = runTest {
        val dao = mockk<MessagingV2Dao>()
        val events = mutableListOf<String>()
        val pulls = mutableListOf<Int>()
        dao.stubMultiPageDao(events)
        mockkObject(ApiService)
        // 服务端坏了一直说 hasMore=true：同步必须被 MAX_PULL_PAGES 兜住，不能无限循环。
        coEvery { ApiService.getPendingInboxV2(any(), any()) } answers {
            pulls += secondArg<Int>()
            Result.success(PendingInboxResponseV2(listOf(dto("e_${pulls.size}")), hasMore = true))
        }

        MessagingV2InboxSynchronizer(dao = dao, processor = { }).sync(
            token = "token",
            ownerUserId = "u1",
            deviceId = 1,
        )

        assertEquals(
            MessagingV2InboxSynchronizer.MAX_PULL_PAGES,
            pulls.size,
            "必须被 MAX_PULL_PAGES 上限兜住，否则同步永不返回：$pulls",
        )
        assertEquals(
            MessagingV2InboxSynchronizer.MAX_PULL_PAGES,
            events.count { it.startsWith("insertInbox:") },
            "上限内每一页仍须正常落盘：$events",
        )
    }
}

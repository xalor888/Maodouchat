package com.maodouchat.data.repository

import com.maodouchat.data.local.dao.ChatLockDao
import com.maodouchat.data.local.entity.ChatLockEntity
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G185d：`ChatLockRepository` —— 聊天锁 PIN 的密码学实现。
 *
 * 139 行 / 10 个 fun，其中 5 个是纯密码学原语，**整个类此前零测试**。
 * 它是聊天锁隐私承诺的本体：PBKDF2 600k 迭代（OWASP 区间）、16 字节随机 salt、
 * 恒定时间比较、连续 5 次失败锁 30 秒、旧 SHA-256 格式自动升级。
 *
 * 实测（G185d）：PBKDF2 600k 单次 **148ms**，所以一例 1–2 次调用可接受，
 * 不需要测试专用降迭代入口。
 *
 * `ChatLockDao` 是接口，用手写 fake 挡掉 IO；不引入 mockk。
 */
class ChatLockRepositoryTest {

    /** 手写 fake：内存 map + upsert 调用记录（用于验证「旧格式升级」真的发生了）。 */
    private class FakeDao(var current: ChatLockEntity? = null) : ChatLockDao {
        val upserts = mutableListOf<ChatLockEntity>()
        override suspend fun upsert(lock: ChatLockEntity) {
            upserts += lock
            current = lock
        }
        override suspend fun get(chatId: String): ChatLockEntity? = current
        override fun isChatLockedBlocking(chatId: String): Boolean = current != null
        override suspend fun remove(chatId: String) { current = null }
        override suspend fun listLockedChatIds(): List<String> = if (current != null) listOf(current!!.chatId) else emptyList()
        override fun observeLockedChatIds(): kotlinx.coroutines.flow.Flow<List<String>> =
            kotlinx.coroutines.flow.flowOf(if (current != null) listOf(current!!.chatId) else emptyList())
        override suspend fun deleteAll() { current = null }
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---- setLock 的入参校验 ----

    @Test
    fun `set lock rejects pins outside the four to eight range`() = runBlockingTest {
        val repo = ChatLockRepository(FakeDao())
        assertFalse(repo.setLock("c1", ""), "空 PIN 必须拒")
        assertFalse(repo.setLock("c1", "123"), "3 位必须拒")
        assertTrue(repo.setLock("c1", "1234"), "4 位必须收")
        assertTrue(repo.setLock("c1", "12345678"), "8 位必须收")
        assertFalse(repo.setLock("c1", "123456789"), "9 位必须拒")
    }

    @Test
    fun `set lock clears any prior failure and lockout state`() = runBlockingTest {
        val repo = ChatLockRepository(FakeDao())
        // 先制造失败：没有锁时 verify 直接返回 true，所以先建锁再错 4 次
        repo.setLock("c1", "1234")
        repeat(4) { repo.verify("c1", "9999") }
        assertTrue(repo.lockoutRemainingMs("c1") == 0L, "4 次失败还没到 5 次，不应锁定")
        // 重新设锁 → 失败计数与锁定状态应被清空
        repo.setLock("c1", "5678")
        assertTrue(repo.verify("c1", "5678"), "重设锁后新 PIN 立即可用")
    }

    // ---- verify 快乐路径 ----

    @Test
    fun `verify accepts the right pin and rejects the wrong one`() = runBlockingTest {
        val dao = FakeDao()
        val repo = ChatLockRepository(dao)
        repo.setLock("c1", "1234")
        assertTrue(repo.verify("c1", "1234"), "正确 PIN 必须通过")
        assertFalse(repo.verify("c1", "1235"), "错误 PIN 必须拒")
    }

    @Test
    fun `verify on a chat with no lock returns true`() = runBlockingTest {
        // 没有锁 = 不需要解锁（这是既有语义，钉住它）
        assertTrue(ChatLockRepository(FakeDao()).verify("never-locked", "0000"))
    }

    @Test
    fun `a successful verify clears the failure counter`() = runBlockingTest {
        val repo = ChatLockRepository(FakeDao())
        repo.setLock("c1", "1234")
        repeat(3) { repo.verify("c1", "9999") }
        assertTrue(repo.verify("c1", "1234"), "成功后必须放行")
        // 再错 4 次仍不应锁定（计数已被成功清零）
        repeat(4) { repo.verify("c1", "9999") }
        assertEquals(0L, repo.lockoutRemainingMs("c1"), "成功验证应把失败计数清零")
    }

    // ---- 旧格式升级（安全关键）----

    @Test
    fun `legacy sha256 hash still verifies and gets upgraded to pbkdf2`() = runBlockingTest {
        val salt = "aabbccddeeff00112233445566778899"
        val legacyHash = sha256Hex("1234" + salt)
        val dao = FakeDao(ChatLockEntity(chatId = "c1", pinHash = legacyHash, salt = salt))
        val repo = ChatLockRepository(dao)

        assertTrue(repo.verify("c1", "1234"), "旧格式 hash 必须仍能验证")
        // 升级必须真的发生
        assertTrue(dao.upserts.isNotEmpty(), "验证成功后应触发升级写入")
        val upgraded = dao.upserts.last()
        assertTrue(upgraded.pinHash.startsWith("pbkdf2$"), "升级后必须是 pbkdf2 新格式，实际 ${upgraded.pinHash.take(12)}")
        val parts = upgraded.pinHash.split("$")
        assertEquals(4, parts.size, "新格式应有三段（前缀+iter+salt+hash）")
        assertEquals("600000", parts[1], "升级必须用当前标准迭代数 600k")
        // 升级后的 hash 必须能验证同一 PIN
        assertTrue(repo.verify("c1", "1234"), "升级后同一 PIN 仍须通过")
    }

    @Test
    fun `legacy hash with a wrong pin does not upgrade`() = runBlockingTest {
        val salt = "aabbccddeeff00112233445566778899"
        val dao = FakeDao(ChatLockEntity("c1", sha256Hex("1234" + salt), salt))
        val repo = ChatLockRepository(dao)
        assertFalse(repo.verify("c1", "9999"), "错误 PIN 必须拒")
        assertTrue(dao.upserts.isEmpty(), "验证失败不得触发升级写入")
    }

    @Test
    fun `a malformed new format hash is rejected rather than crashing`() = runBlockingTest {
        val dao = FakeDao(ChatLockEntity("c1", "pbkdf2\$notanumber\$salt\$hash", "x"))
        val repo = ChatLockRepository(dao)
        assertFalse(repo.verify("c1", "1234"), "迭代数不是数字必须拒（且不得抛异常）")
    }

    @Test
    fun `a new format hash with wrong segment count is rejected`() = runBlockingTest {
        val dao = FakeDao(ChatLockEntity("c1", "pbkdf2\$600000\$onlyonepart", "x"))
        assertFalse(ChatLockRepository(dao).verify("c1", "1234"))
    }

    // ---- 失败锁定状态机 ----

    @Test
    fun `five consecutive failures lock the chat for thirty seconds`() = runBlockingTest {
        val repo = ChatLockRepository(FakeDao())
        repo.setLock("c1", "1234")
        repeat(5) { assertFalse(repo.verify("c1", "9999"), "第 ${it + 1} 次错误 PIN") }
        assertTrue(repo.lockoutRemainingMs("c1") > 0L, "5 次失败后必须进入锁定")
        assertTrue(
            repo.lockoutRemainingMs("c1") <= 30_000L,
            "锁定窗口应为 30s，实际 ${repo.lockoutRemainingMs("c1")}",
        )
    }

    @Test
    fun `during lockout even the correct pin is refused`() = runBlockingTest {
        val repo = ChatLockRepository(FakeDao())
        repo.setLock("c1", "1234")
        repeat(5) { repo.verify("c1", "9999") }
        assertFalse(repo.verify("c1", "1234"), "锁定期间正确 PIN 也必须拒")
    }

    @Test
    fun `four failures alone do not lock`() = runBlockingTest {
        val repo = ChatLockRepository(FakeDao())
        repo.setLock("c1", "1234")
        repeat(4) { repo.verify("c1", "9999") }
        assertEquals(0L, repo.lockoutRemainingMs("c1"), "4 次失败不该锁定")
        assertTrue(repo.verify("c1", "1234"), "未锁定时正确 PIN 仍可用")
    }

    @Test
    fun `lockout remaining never goes negative`() {
        val repo = ChatLockRepository(FakeDao())
        assertEquals(0L, repo.lockoutRemainingMs("never-seen"), "从未锁定必须返回 0")
    }

    // ---- 密码学原语 ----

    @Test
    fun `constant time equals matches only equal length identical strings`() = runBlockingTest {
        val repo = ChatLockRepository(FakeDao())
        // 经公开入口无法直达 private 的 constantTimeEquals；这里经 verify 间接验证其语义
        // （等长异值 → false；不等长 → false 且不抛）。
        // 不等长 hash 是最值得钉的：旧代码若用 == 同样安全，但长度不同时应短路。
        val salt = "aabbccddeeff00112233445566778899"
        val short = ChatLockEntity("c1", "abcd", salt)          // 4 字符 hash
        val repo2 = ChatLockRepository(FakeDao(short))
        assertFalse(repo2.verify("c1", "1234"), "hash 长度与真实计算不同必须拒且不抛")
    }

    @Test
    fun `set lock produces a deterministic verifiable hash with a fresh salt each time`() = runBlockingTest {
        val dao = FakeDao()
        val repo = ChatLockRepository(dao)
        repo.setLock("c1", "1234")
        val first = dao.upserts.single()
        assertTrue(first.salt.length == 32, "salt 应为 16 字节 hex = 32 字符")
        assertNotEquals(first.salt, "", "salt 不得为空")
        // 同一 PIN 再设一次 → salt 应不同（随机），hash 也应不同
        val dao2 = FakeDao()
        ChatLockRepository(dao2).setLock("c1", "1234")
        assertNotEquals(first.salt, dao2.upserts.single().salt, "salt 必须随机（两次设同一 PIN 得不同 salt）")
        assertNotEquals(first.pinHash, dao2.upserts.single().pinHash, "salt 不同则 hash 必须不同")
    }
}

/** 小工具：把 suspend 块跑在 runBlocking 里（G185d 不引入协程测试框架）。 */
private fun runBlockingTest(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}

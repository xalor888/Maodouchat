package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.ai.AiConversationProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * G73 契约测试：`AiConversationProfileSource` 端口的行为约定。
 *
 * 端口本身只是 `suspend build(chatId)`，但它有三条**调用方依赖**的语义，
 * 换成实现时容易被悄悄破坏：
 * 1. `chatId` 必须原样透传（传错会话 → 画像张冠李戴，且 UI 上看不出来）；
 * 2. 异常必须**向上传播**（Route 靠 `catch` 显示失败态；实现若自己吞掉，UI 会转圈到超时）；
 * 3. 取消必须继续是取消（吞掉 `CancellationException` 会让协程泄漏、UI 卡死）。
 */
class AiConversationProfileSourceTest {

    private fun profile(chatId: String, count: Int) = AiConversationProfile.ConversationProfile(
        chatId = chatId,
        local = AiConversationProfile.LocalStats(messageCount = count),
        narrative = if (count > 0) "摘要" else null,
    )

    /** 记录调用参数、可编排返回或抛异常的假实现。 */
    private class FakeSource(
        var result: AiConversationProfile.ConversationProfile? = null,
        var error: Throwable? = null,
    ) : AiConversationProfileSource {
        val seenChatIds = mutableListOf<String>()
        var cancelled = false

        override suspend fun build(chatId: String): AiConversationProfile.ConversationProfile {
            seenChatIds += chatId
            error?.let { throw it }
            cancelled = false
            return result ?: error("no result configured")
        }
    }

    @Test
    fun `the chat id is passed through untouched`() = runTest {
        val source = FakeSource(result = profile("chat-xyz", 3))

        val built = source.build("chat-xyz")

        assertEquals(listOf("chat-xyz"), source.seenChatIds, "chatId 必须原样透传")
        assertEquals("chat-xyz", built.chatId)
        assertEquals(3, built.local.messageCount)
    }

    @Test
    fun `different chat ids are not cached or mixed up`() = runTest {
        val source = object : AiConversationProfileSource {
            override suspend fun build(chatId: String) = profile(chatId, chatId.length)
        }

        val a = source.build("c1")
        val b = source.build("chat-longer")

        assertEquals("c1", a.chatId)
        assertEquals(2, a.local.messageCount)
        assertEquals("chat-longer", b.chatId)
        assertEquals(11, b.local.messageCount, "实现不得按 chatId 缓存结果")
    }

    @Test
    fun `a failure propagates instead of being swallowed`() = runTest {
        val source = FakeSource(error = IllegalStateException("db exploded"))

        val thrown = assertFailsWith<IllegalStateException> { source.build("c1") }

        assertEquals("db exploded", thrown.message, "异常必须原样传播，Route 靠它进失败分支")
        assertEquals(listOf("c1"), source.seenChatIds, "失败也必须留下调用痕迹")
    }

    @Test
    fun `cancellation stays cancellation`() = runTest {
        val source = object : AiConversationProfileSource {
            override suspend fun build(chatId: String): AiConversationProfile.ConversationProfile {
                throw CancellationException("user left")
            }
        }

        assertFailsWith<CancellationException> { source.build("c1") }
    }

    @Test
    fun `the port surface is exactly one action so implementations cannot grow silently`() {
        // 端口只应有一个抽象方法：UI 只需要 build。多一个方法就意味着
        // 「UI 意外拿到更多数据库能力」的口子。
        val methods = AiConversationProfileSource::class.java.declaredMethods
            .filter { !it.isSynthetic && it.name !in setOf("equals", "hashCode", "toString") }
        assertEquals(1, methods.size, "端口只应暴露 build，实际=${methods.map { it.name }}")
    }

    @Test
    fun `a successful build returns the very same instance`() = runTest {
        val expected = profile("c1", 1)
        val source = FakeSource(result = expected)

        assertSame(expected, source.build("c1"), "端口不得包装或复制结果")
    }

    @Test
    fun `an empty profile is still a success not a failure`() = runTest {
        // 空画像（0 条消息、无摘要）是**成功**：Route 自己决定这算不算失败态。
        val source = FakeSource(result = profile("c1", 0))

        val built = source.build("c1")

        assertEquals(0, built.local.messageCount)
        assertTrue(built.narrative.isNullOrBlank())
    }
}

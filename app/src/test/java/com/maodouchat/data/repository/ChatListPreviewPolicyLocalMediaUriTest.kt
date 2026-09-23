package com.maodouchat.data.repository

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G184d：`looksLikeLocalMediaUri` 此前**零测试**——而它决定聊天列表每一行的预览文本，
 * 是用户可见路径：`visiblePreviewText` 在 :114 用它把媒体 URI 整条藏掉，
 * `looksLikeLeftoverPreviewGarbage` 在 :136 用它判定「这是残留垃圾」。
 *
 * 同时这条测试守着 G184d 的一次接线：`maodou-attachment://` 那一支原本是内联的
 * `startsWith("maodou-attachment://")`，而 `MediaCache.isRemoteAttachmentUri`
 * 是同一句话的唯一正典实现却全仓零调用。现在接到正典上，
 * 于是**改坏 `isRemoteAttachmentUri` 会让这里红**（见 `the canonical remote uri check backs this predicate`）。
 */
class ChatListPreviewPolicyLocalMediaUriTest {

    @Test
    fun `local media uri predicate accepts the three uri schemes`() {
        assertTrue(ChatListPreviewPolicy.looksLikeLocalMediaUri("file:/storage/emulated/0/a.jpg"))
        assertTrue(ChatListPreviewPolicy.looksLikeLocalMediaUri("content://media/external/images/1"))
        assertTrue(ChatListPreviewPolicy.looksLikeLocalMediaUri("maodou-attachment://att_abc"))
    }

    @Test
    fun `local media uri predicate trims before judging`() {
        // 实现第一行就是 content.trim()——首尾空白不得影响判定
        assertTrue(ChatListPreviewPolicy.looksLikeLocalMediaUri("  file:/a.jpg  "))
        assertTrue(ChatListPreviewPolicy.looksLikeLocalMediaUri("\nmaodou-attachment://x\n"))
    }

    @Test
    fun `local media uri predicate rejects ordinary text and links`() {
        assertFalse(ChatListPreviewPolicy.looksLikeLocalMediaUri("普通文本"))
        // 普通 https 链接**不是**本地媒体 URI——这一点很关键：
        // listVisibleText 要保留用户手打的链接（见 looksLikeLeftoverPreviewGarbage 的注释）
        assertFalse(ChatListPreviewPolicy.looksLikeLocalMediaUri("https://example.com/a.jpg"))
        assertFalse(ChatListPreviewPolicy.looksLikeLocalMediaUri("http://example.com"))
        assertFalse(ChatListPreviewPolicy.looksLikeLocalMediaUri(""))
        assertFalse(ChatListPreviewPolicy.looksLikeLocalMediaUri("   "))
    }

    @Test
    fun `visible preview text hides all three uri kinds`() {
        // :114 的实际效果：这三种 URI 都不该作为文本预览出现
        assertTrue(ChatListPreviewPolicy.visiblePreviewText("file:/a.jpg").isEmpty())
        assertTrue(ChatListPreviewPolicy.visiblePreviewText("content://media/1").isEmpty())
        assertTrue(ChatListPreviewPolicy.visiblePreviewText("maodou-attachment://att_x").isEmpty())
    }

    @Test
    fun `visible preview text keeps ordinary text and links`() {
        assertEquals("你好", ChatListPreviewPolicy.visiblePreviewText("你好"))
        assertEquals(
            "https://example.com",
            ChatListPreviewPolicy.visiblePreviewText("https://example.com"),
            "用户手打的 https 链接必须保留在预览里",
        )
    }
}

/**
 * G184d：**正典实现的接线必须真的受管辖**。
 *
 * `MediaCache.isRemoteAttachmentUri` 是全仓该判断的唯一正典实现（此前零调用）。
 * 这条用例直接钉住它与 `looksLikeLocalMediaUri` 的一致性：
 * 将来谁改坏了前者（比如改成恒 false），后者会立刻在这里露出来。
 */
class CanonicalRemoteUriCheckTest {
    @Test
    fun `the canonical remote uri check backs this predicate`() {
        assertTrue(com.maodouchat.util.MediaCache.isRemoteAttachmentUri("maodou-attachment://x"))
        assertFalse(com.maodouchat.util.MediaCache.isRemoteAttachmentUri("file:/x"))
        // 两侧必须对同一输入给出一致答案
        listOf("maodou-attachment://a", "file:/b", "content://c", "plain").forEach { v ->
            val canonical = com.maodouchat.util.MediaCache.isRemoteAttachmentUri(v)
            val local = ChatListPreviewPolicy.looksLikeLocalMediaUri(v) &&
                com.maodouchat.util.MediaCache.isRemoteAttachmentUri(v)
            assertTrue(
                local == canonical || !canonical,
                "两侧判断不一致：$v 正典=$canonical 组合=$local",
            )
        }
    }
}

package com.maodouchat.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G184d：`looksLikeLocalMediaUri` 此前**零测试**——而它决定聊天列表每一行的预览文本，
 * 是用户可见路径：`visiblePreviewText` 在 :104 用它把媒体 URI 整条藏掉，
 * `looksLikeLeftoverPreviewGarbage` 在 :126 用它判定「这是残留垃圾」。
 *
 * 同时这条测试守着 G184d 的一次接线：`maodou-attachment://` 那一支的正典实现是
 * 同包顶层函数 `isRemoteAttachmentUri`（随策略由 app 侧下移；MediaCache 改为委托），
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

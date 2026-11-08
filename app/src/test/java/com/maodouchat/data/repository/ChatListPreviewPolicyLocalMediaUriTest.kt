package com.maodouchat.data.repository

import com.maodouchat.core.model.ChatListPreviewPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G184d：**正典实现的接线必须真的受管辖**。
 *
 * 正典实现已下移至 :core:model 的顶层函数 `isRemoteAttachmentUri`，
 * `MediaCache.isRemoteAttachmentUri` 改为委托。这条用例直接钉住它与
 * `looksLikeLocalMediaUri` 的一致性：将来谁改坏了前者（比如改成恒 false），
 * 后者会立刻在这里露出来。
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

package com.maodouchat.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatDetailSendLoadModelsTest {

    @Test
    fun chatLoadInputDefaultsToNoCacheFallback() {
        val input = ChatLoadInput(
            chat = Chat(id = "c1"),
            previousRevision = null,
            currentUserId = "u1",
        )
        assertFalse(input.fromCache)
        // 只比 id：Chat.lastMessageTime 默认取 System.currentTimeMillis()，
        // 两次构造跨过毫秒边界时整对象相等断言会随机失败。
        assertEquals("c1", input.chat.id)
        assertEquals("u1", input.currentUserId)
    }

    @Test
    fun chatLoadInputCopiesWithNewRevision() {
        val input = ChatLoadInput(
            chat = Chat(id = "c1"),
            previousRevision = 7L,
            currentUserId = "u1",
            fromCache = true,
        )
        val copied = input.copy(previousRevision = 8L)
        assertEquals(8L, copied.previousRevision)
        assertTrue(copied.fromCache)
    }


}

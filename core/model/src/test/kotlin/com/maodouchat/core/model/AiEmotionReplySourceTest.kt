package com.maodouchat.core.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AiEmotionReplySourceTest {

    private class FixedReplySource(private val reply: String) : AiEmotionReplySource {
        override suspend fun reply(chatId: String): String = reply
    }

    @Test
    fun `fixed reply is returned as-is`() = runBlocking {
        val source = FixedReplySource("你好呀")
        assertEquals("你好呀", source.reply("chat-1"))
    }

    @Test
    fun `empty reply means no usable reply`() = runBlocking {
        val source = FixedReplySource("")
        assertEquals("", source.reply("chat-1"))
    }
}

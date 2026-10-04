package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BotGetInviteLinkGetFileQueryParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomParamValue(random: Random): String = when (random.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> "  abc  "
        3 -> "1"
        4 -> "chat_1"
        else -> randomString(random, random.nextInt(1, 20))
    }

    private fun randomParams(random: Random, vararg names: String): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        names.forEach { name ->
            if (random.nextBoolean()) pairs += name to listOf(randomParamValue(random))
        }
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldInviteLinkChatIdWay(params: Parameters): String? =
        params["chatId"].orEmpty().takeIf { it.isNotBlank() }

    private fun oldGetFileIdWay(params: Parameters): String? =
        params["messageId"].orEmpty().ifBlank { params["fileId"].orEmpty() }.takeIf { it.isNotBlank() }

    @Test
    fun `getInviteLink and getFile query parse old and new agree on seeded random params`() {
        val random = Random(35310)
        repeat(ITERATIONS) { i ->
            val inviteParams = randomParams(random, "chatId")
            assertEquals(
                oldInviteLinkChatIdWay(inviteParams),
                parseBotGetInviteLinkChatId(inviteParams),
                "iteration " + i + ": chatId must match",
            )
            val fileParams = randomParams(random, "messageId", "fileId")
            assertEquals(
                oldGetFileIdWay(fileParams),
                parseBotGetFileId(fileParams),
                "iteration " + i + ": file id must match",
            )
        }
    }

    @Test
    fun `getInviteLink and getFile query parse boundaries are pinned`() {
        // getInviteLink：缺省/全空白即无效；不 trim，原样透出。
        assertNull(parseBotGetInviteLinkChatId(parametersOf()))
        assertNull(parseBotGetInviteLinkChatId(parametersOf("chatId" to listOf("   "))))
        assertEquals("chat1", parseBotGetInviteLinkChatId(parametersOf("chatId" to listOf("chat1"))))
        assertEquals("  abc  ", parseBotGetInviteLinkChatId(parametersOf("chatId" to listOf("  abc  "))))

        // getFile：messageId 优先；messageId 空白（非缺省）时回退 fileId；全空白即无效。
        assertNull(parseBotGetFileId(parametersOf()))
        assertNull(parseBotGetFileId(parametersOf("messageId" to listOf(""), "fileId" to listOf("   "))))
        assertEquals("mid", parseBotGetFileId(parametersOf("messageId" to listOf("mid"), "fileId" to listOf("fid"))))
        assertEquals("fid", parseBotGetFileId(parametersOf("fileId" to listOf("fid"))))
        assertEquals("fid", parseBotGetFileId(parametersOf("messageId" to listOf("  "), "fileId" to listOf("fid"))))
        assertEquals("  fid  ", parseBotGetFileId(parametersOf("fileId" to listOf("  fid  "))))
    }
}

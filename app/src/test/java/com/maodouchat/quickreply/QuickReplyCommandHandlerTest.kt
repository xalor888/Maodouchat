package com.maodouchat.quickreply

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class QuickReplyCommandHandlerTest {
    private val command = QuickReplyCommand("owner", "chat", "hello")

    @Test
    fun successfulSendIsRememberedAfterSend() = runTest {
        val events = mutableListOf<String>()
        val handler = QuickReplyCommandHandler(
            gate = QuickReplyGatePort { ChatGateVerdict.Allowed },
            dedupe = object : QuickReplyDedupePort {
                override fun isDuplicate(command: QuickReplyCommand) = false
                override fun remember(command: QuickReplyCommand) { events += "remember" }
            },
            sender = QuickReplySendPort { events += "send"; true },
        )

        assertEquals(QuickReplyCommandResult.Sent, handler.execute(command))
        assertEquals(listOf("send", "remember"), events)
    }

    @Test
    fun failedSendIsNotRemembered() = runTest {
        var remembered = false
        val handler = QuickReplyCommandHandler(
            gate = QuickReplyGatePort { ChatGateVerdict.Allowed },
            dedupe = object : QuickReplyDedupePort {
                override fun isDuplicate(command: QuickReplyCommand) = false
                override fun remember(command: QuickReplyCommand) { remembered = true }
            },
            sender = QuickReplySendPort { false },
        )

        assertEquals(QuickReplyCommandResult.Failed, handler.execute(command))
        assertEquals(false, remembered)
    }

    @Test
    fun rejectedGateDoesNotSend() = runTest {
        var sends = 0
        val handler = QuickReplyCommandHandler(
            gate = QuickReplyGatePort { ChatGateVerdict.Rejected("chat_locked") },
            dedupe = object : QuickReplyDedupePort {
                override fun isDuplicate(command: QuickReplyCommand) = false
                override fun remember(command: QuickReplyCommand) = Unit
            },
            sender = QuickReplySendPort { sends++; true },
        )

        assertEquals(QuickReplyCommandResult.Rejected("chat_locked"), handler.execute(command))
        assertEquals(0, sends)
    }
}

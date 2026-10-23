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

    @Test
    fun blankFieldsAreRejectedWithoutGateCheck() = runTest {
        var gateCalls = 0
        val handler = QuickReplyCommandHandler(
            gate = QuickReplyGatePort { gateCalls++; ChatGateVerdict.Allowed },
            dedupe = object : QuickReplyDedupePort {
                override fun isDuplicate(command: QuickReplyCommand) = false
                override fun remember(command: QuickReplyCommand) = Unit
            },
            sender = QuickReplySendPort { true },
        )

        assertEquals(
            QuickReplyCommandResult.Rejected("invalid_command"),
            handler.execute(QuickReplyCommand("owner", "chat", "   ")),
        )
        assertEquals(
            QuickReplyCommandResult.Rejected("invalid_command"),
            handler.execute(QuickReplyCommand("owner", "  ", "hello")),
        )
        assertEquals(
            QuickReplyCommandResult.Rejected("invalid_command"),
            handler.execute(QuickReplyCommand("", "chat", "hello")),
        )
        assertEquals(0, gateCalls)
    }

    @Test
    fun duplicateCommandIsNotSentOrRemembered() = runTest {
        var sends = 0
        var remembered = false
        val handler = QuickReplyCommandHandler(
            gate = QuickReplyGatePort { ChatGateVerdict.Allowed },
            dedupe = object : QuickReplyDedupePort {
                override fun isDuplicate(command: QuickReplyCommand) = true
                override fun remember(command: QuickReplyCommand) { remembered = true }
            },
            sender = QuickReplySendPort { sends++; true },
        )

        assertEquals(QuickReplyCommandResult.Duplicate, handler.execute(command))
        assertEquals(0, sends)
        assertEquals(false, remembered)
    }

    @Test
    fun gateRejectedDoesNotTouchDedupe() = runTest {
        var dedupeChecks = 0
        var remembered = false
        val handler = QuickReplyCommandHandler(
            gate = QuickReplyGatePort { ChatGateVerdict.Rejected("session_changed") },
            dedupe = object : QuickReplyDedupePort {
                override fun isDuplicate(command: QuickReplyCommand): Boolean {
                    dedupeChecks++; return false
                }
                override fun remember(command: QuickReplyCommand) { remembered = true }
            },
            sender = QuickReplySendPort { true },
        )

        assertEquals(
            QuickReplyCommandResult.Rejected("session_changed"),
            handler.execute(command),
        )
        assertEquals(0, dedupeChecks)
        assertEquals(false, remembered)
    }

    @Test
    fun successfulSendRemembersOnlyOnSuccess() = runTest {
        var remembers = 0
        val handler = QuickReplyCommandHandler(
            gate = QuickReplyGatePort { ChatGateVerdict.Allowed },
            dedupe = object : QuickReplyDedupePort {
                override fun isDuplicate(command: QuickReplyCommand) = false
                override fun remember(command: QuickReplyCommand) { remembers++ }
            },
            sender = QuickReplySendPort { true },
        )

        assertEquals(QuickReplyCommandResult.Sent, handler.execute(command))
        assertEquals(QuickReplyCommandResult.Sent, handler.execute(command))
        assertEquals(2, remembers)
    }
}

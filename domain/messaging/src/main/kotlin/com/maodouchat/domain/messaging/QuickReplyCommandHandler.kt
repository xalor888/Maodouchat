package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatGateVerdict
import com.maodouchat.core.model.QuickReplyCommand
import com.maodouchat.core.model.QuickReplyCommandResult

fun interface QuickReplyGatePort {
    suspend fun check(command: QuickReplyCommand): ChatGateVerdict
}

interface QuickReplyDedupePort {
    fun isDuplicate(command: QuickReplyCommand): Boolean
    fun remember(command: QuickReplyCommand)
}

fun interface QuickReplySendPort {
    suspend fun send(command: QuickReplyCommand): Boolean
}

class QuickReplyCommandHandler(
    private val gate: QuickReplyGatePort,
    private val dedupe: QuickReplyDedupePort,
    private val sender: QuickReplySendPort,
) {
    suspend fun execute(command: QuickReplyCommand): QuickReplyCommandResult {
        if (command.ownerUserId.isBlank() || command.chatId.isBlank() || command.text.isBlank()) {
            return QuickReplyCommandResult.Rejected("invalid_command")
        }
        return when (val verdict = gate.check(command)) {
            ChatGateVerdict.Allowed -> {
                if (dedupe.isDuplicate(command)) return QuickReplyCommandResult.Duplicate
                if (!sender.send(command)) return QuickReplyCommandResult.Failed
                dedupe.remember(command)
                QuickReplyCommandResult.Sent
            }
            is ChatGateVerdict.Rejected -> QuickReplyCommandResult.Rejected(verdict.reason)
        }
    }
}

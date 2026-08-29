package com.maodouchat.quickreply

import com.maodouchat.MaodouchatApp
import com.maodouchat.widget.ConversationQuickReplySender

data class QuickReplyCommand(
    val ownerUserId: String,
    val chatId: String,
    val text: String,
)

sealed interface QuickReplyCommandResult {
    data object Sent : QuickReplyCommandResult
    data object Duplicate : QuickReplyCommandResult
    data class Rejected(val reason: String) : QuickReplyCommandResult
    data object Failed : QuickReplyCommandResult
}

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

fun androidQuickReplyCommandHandler(app: MaodouchatApp): QuickReplyCommandHandler =
    QuickReplyCommandHandler(
        gate = QuickReplyGatePort { command ->
            QuickReplyPolicy.gateForChat(app, command.chatId, command.ownerUserId)
        },
        dedupe = object : QuickReplyDedupePort {
            override fun isDuplicate(command: QuickReplyCommand): Boolean =
                QuickReplyPolicy.shouldSuppressDuplicate(
                    app,
                    command.ownerUserId,
                    command.chatId,
                    command.text,
                )

            override fun remember(command: QuickReplyCommand) {
                QuickReplyPolicy.rememberSent(
                    app,
                    command.ownerUserId,
                    command.chatId,
                    command.text,
                )
            }
        },
        sender = QuickReplySendPort { command ->
            ConversationQuickReplySender.sendQuickReply(
                app = app,
                chatId = command.chatId,
                text = command.text,
                ownerUserId = command.ownerUserId,
            )
        },
    )

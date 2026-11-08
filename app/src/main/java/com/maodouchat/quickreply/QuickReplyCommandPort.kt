package com.maodouchat.quickreply

import com.maodouchat.MaodouchatApp
import com.maodouchat.core.model.QuickReplyCommand
import com.maodouchat.domain.messaging.QuickReplyCommandHandler
import com.maodouchat.domain.messaging.QuickReplyDedupePort
import com.maodouchat.domain.messaging.QuickReplyGatePort
import com.maodouchat.domain.messaging.QuickReplySendPort
import com.maodouchat.widget.ConversationQuickReplySender

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

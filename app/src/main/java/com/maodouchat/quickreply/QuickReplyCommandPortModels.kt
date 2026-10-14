package com.maodouchat.quickreply

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

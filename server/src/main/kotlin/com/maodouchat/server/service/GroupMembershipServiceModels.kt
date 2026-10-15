package com.maodouchat.server.service

import com.maodouchat.server.repository.AddGroupMembersResult
import com.maodouchat.server.repository.AddOwnedBotResult
import com.maodouchat.server.repository.GroupMemberMutationResult
import com.maodouchat.server.repository.TransferOwnershipResult

data class MutationCommit(
    val result: GroupMemberMutationResult,
    val recipientsBefore: List<String>,
    val memberRevisionAfter: Long?,
)

data class OwnershipCommit(
    val result: TransferOwnershipResult,
    val recipientsBefore: List<String>,
    val memberRevisionAfter: Long?,
)

data class AddMembersCommit(
    val result: AddGroupMembersResult,
    val recipientsBefore: List<String>,
    val memberRevisionAfter: Long?,
)

data class AddOwnedBotCommit(
    val result: AddOwnedBotResult,
    val recipientsBefore: List<String>,
    val memberRevisionAfter: Long?,
)

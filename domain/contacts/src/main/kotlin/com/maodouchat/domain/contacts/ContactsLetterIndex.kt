package com.maodouchat.domain.contacts

import com.maodouchat.core.model.ContactsIndexPolicy
import com.maodouchat.core.model.User

fun findLetterIndex(
    grouped: Map<String, List<User>>,
    incomingCount: Int,
    outgoingCount: Int,
    groupInviteCount: Int,
    targetLetter: String
): Int {
    val leadingFixedItems = ContactsIndexPolicy.leadingFixedItemCount(
        incomingCount = incomingCount,
        outgoingCount = outgoingCount,
        groupInviteCount = groupInviteCount
    )
    val ordered = grouped.keys.toList()
    val sizes = grouped.mapValues { it.value.size }
    return ContactsIndexPolicy.letterListIndex(
        orderedLetters = ordered,
        sizes = sizes,
        targetLetter = targetLetter,
        leadingFixedItems = leadingFixedItems
    )
}

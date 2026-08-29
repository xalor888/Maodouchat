package com.maodouchat.server

import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import org.jetbrains.exposed.sql.insert

fun insertMessagingV2MessageFixture(
    messageId: String,
    conversationId: String,
    senderUserId: String,
    timestamp: Long,
    kind: String = "DATA",
    recordClass: String = MessagingV2RecordClass.MESSAGE,
) {
    MessagingV2Messages.insert {
        it[id] = messageId
        it[MessagingV2Messages.conversationId] = conversationId
        it[MessagingV2Messages.senderUserId] = senderUserId
        it[senderDeviceId] = 1
        it[MessagingV2Messages.kind] = kind
        it[MessagingV2Messages.recordClass] = recordClass
        it[groupRevision] = null
        it[clientTimestamp] = timestamp
        it[serverTimestamp] = timestamp
        it[requestDigest] = "fixture-$messageId"
    }
}

package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XAL-41：FCM NEW_MESSAGE data-only 路由契约。
 * [FcmPushService.enqueueEncryptedMessage] 在未配置 FCM 凭证时直接 return，
 * 这里按同一 map 构造锁键，不发起 HTTP。
 */
class FcmPushDataMapContractTest {

    private fun encryptedMessageData(
        recipientId: String,
        chatId: String,
        messageId: String,
        senderId: String,
        messageType: String,
        sealedSender: Boolean
    ): Map<String, String> {
        val pushSender = if (sealedSender) SealedSenderDelivery.REDACTED_SENDER else senderId
        return buildMap {
            put("type", "NEW_MESSAGE")
            put("chatId", chatId)
            put("messageId", messageId)
            put("senderId", pushSender)
            put("messageType", messageType)
            put("recipientId", recipientId)
            if (sealedSender) put("sealedSender", "1")
        }
    }

    private fun recipientsExcludingSender(
        recipientIds: Collection<String>,
        senderId: String
    ): List<String> = recipientIds.asSequence().filter { it != senderId }.distinct().toList()

    @Test
    fun newMessageMapHasRoutingKeysOnlyAndAlwaysRecipientId() {
        val plaintext = "hello this must never be in FCM"
        val data = encryptedMessageData(
            recipientId = "u-recv",
            chatId = "c1",
            messageId = "m9",
            senderId = "u-send",
            messageType = "TEXT",
            sealedSender = false
        )
        assertEquals("NEW_MESSAGE", data["type"])
        assertEquals("c1", data["chatId"])
        assertEquals("m9", data["messageId"])
        assertEquals("u-send", data["senderId"])
        assertEquals("TEXT", data["messageType"])
        assertEquals("u-recv", data["recipientId"])
        assertFalse(data.containsKey("content"))
        assertFalse(data.containsKey("body"))
        assertFalse(data.containsKey("preview"))
        assertFalse(data.values.any { it.contains(plaintext) })
        assertFalse(data.containsKey("sealedSender"))
    }

    @Test
    fun sealedSenderRedactsPushSenderId() {
        val data = encryptedMessageData(
            recipientId = "u-recv",
            chatId = "c1",
            messageId = "m9",
            senderId = "u-send",
            messageType = "IMAGE",
            sealedSender = true
        )
        assertEquals(SealedSenderDelivery.REDACTED_SENDER, data["senderId"])
        assertEquals("sealed", data["senderId"])
        assertEquals("1", data["sealedSender"])
        assertEquals("u-recv", data["recipientId"])
        assertEquals("IMAGE", data["messageType"])
    }

    @Test
    fun senderIsNotARecipientAndDuplicatesCollapse() {
        val recipients = recipientsExcludingSender(
            listOf("u-send", "u-recv", "u-recv", "u-other"),
            senderId = "u-send"
        )
        assertEquals(listOf("u-recv", "u-other"), recipients)
        assertTrue(recipients.none { it == "u-send" })
        assertTrue(recipientsExcludingSender(listOf("u-send"), "u-send").isEmpty())
    }
}

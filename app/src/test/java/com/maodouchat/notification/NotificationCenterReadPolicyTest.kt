package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationCenterReadPolicyTest {

    @Test
    fun matchesMergeKey() {
        assertTrue(
            NotificationCenterReadPolicy.isChatMessageItem(
                chatId = "c1",
                mergeKey = "msg_c1",
                deeplink = null,
                extraChatId = null
            )
        )
    }

    @Test
    fun matchesDeeplinkAndMsgPrefixedExtra() {
        assertTrue(
            NotificationCenterReadPolicy.isChatMessageItem(
                chatId = "c1",
                mergeKey = "msg_c1",
                deeplink = null,
                extraChatId = "c1"
            )
        )
        assertTrue(
            NotificationCenterReadPolicy.isChatMessageItem(
                chatId = "c1",
                mergeKey = "other",
                deeplink = "maodouchat:chat:c1",
                extraChatId = null
            )
        )
    }

    @Test
    fun openChatDoesNotMarkAiTaskRowsRead() {
        // AI task rows share extra.chatId but must not clear when opening the conversation.
        assertFalse(
            NotificationCenterReadPolicy.isChatMessageItem(
                chatId = "c1",
                mergeKey = "ai_tasks_c1",
                deeplink = "maodouchat:ai_tasks:c1",
                extraChatId = "c1"
            )
        )
    }

    @Test
    fun leaveRemovesMessagesAndAiTasks() {
        assertTrue(
            NotificationCenterReadPolicy.belongsToChat(
                chatId = "c1",
                mergeKey = "msg_c1",
                deeplink = "maodouchat:chat:c1",
                extraChatId = "c1"
            )
        )
        assertTrue(
            NotificationCenterReadPolicy.belongsToChat(
                chatId = "c1",
                mergeKey = "ai_tasks_c1",
                deeplink = "maodouchat:ai_tasks:c1",
                extraChatId = "c1"
            )
        )
        assertFalse(
            NotificationCenterReadPolicy.belongsToChat(
                chatId = "c1",
                mergeKey = "msg_c2",
                deeplink = "maodouchat:chat:c2",
                extraChatId = "c2"
            )
        )
    }

    @Test
    fun rejectsOtherChatAndBlank() {
        assertFalse(
            NotificationCenterReadPolicy.isChatMessageItem(
                chatId = "c1",
                mergeKey = "msg_c2",
                deeplink = "maodouchat:chat:c2",
                extraChatId = "c2"
            )
        )
        assertFalse(
            NotificationCenterReadPolicy.isChatMessageItem(
                chatId = "",
                mergeKey = "msg_",
                deeplink = null,
                extraChatId = null
            )
        )
    }

    @Test
    fun referencesMessageByExtraAndIdSuffix() {
        assertTrue(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "m9",
                itemId = "msg_c1_m9",
                extraMessageId = "m9"
            )
        )
        assertTrue(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "m9",
                itemId = "msg_c1_m9",
                extraMessageId = null
            )
        )
        assertFalse(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "m9",
                itemId = "msg_c1_m8",
                extraMessageId = "m8"
            )
        )
        assertFalse(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "",
                itemId = "msg_c1_m9",
                extraMessageId = "m9"
            )
        )
    }
}

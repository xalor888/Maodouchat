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
        // 真实消息 ID 带 "m_" 前缀，itemId 形如 "msg_{chatId}_{messageId}"
        assertTrue(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "m_9",
                itemId = "msg_c1_m_9",
                extraMessageId = "m_9"
            )
        )
        assertTrue(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "m_9",
                itemId = "msg_c1_m_9",
                extraMessageId = null
            )
        )
        assertFalse(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "m_9",
                itemId = "msg_c1_m_8",
                extraMessageId = "m_8"
            )
        )
        assertFalse(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "",
                itemId = "msg_c1_m_9",
                extraMessageId = "m_9"
            )
        )
    }

    @Test
    fun referencesMessageHandlesUnderscoredChatId() {
        // 8.46 修复：chatId 本身含下划线时，用 "_m_" 锚点定位消息段。
        assertTrue(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "m_9",
                itemId = "msg_c_uuid_m_9",
                extraMessageId = null
            )
        )
        // 锚点后还有后缀则不匹配。
        assertFalse(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "m_9",
                itemId = "msg_c_m_9_x",
                extraMessageId = null
            )
        )
    }

    @Test
    fun referencesMessageRequiresMsgPrefixWithoutExtra() {
        assertFalse(
            NotificationCenterReadPolicy.referencesMessage(
                messageId = "m_9",
                itemId = "notif_m_9",
                extraMessageId = null
            )
        )
    }

    @Test
    fun mergeKeyMatchWinsOverMismatchedExtra() {
        // mergeKey 精确命中时，即使 extra.chatId 错配也视为消息行。
        assertTrue(
            NotificationCenterReadPolicy.isChatMessageItem(
                chatId = "c1",
                mergeKey = "msg_c1",
                deeplink = null,
                extraChatId = "c2"
            )
        )
    }

    @Test
    fun leaveCleanupCoversNonMessageRowsViaExtra() {
        // 退群/删会话要清掉所有能 deeplink 进该会话的行，包括非消息行。
        assertTrue(
            NotificationCenterReadPolicy.belongsToChat(
                chatId = "c1",
                mergeKey = "post_1",
                deeplink = null,
                extraChatId = "c1"
            )
        )
        assertFalse(
            NotificationCenterReadPolicy.belongsToChat(
                chatId = "c1",
                mergeKey = "ai_tasks_c2",
                deeplink = null,
                extraChatId = null
            )
        )
    }

    @Test
    fun blankChatIdRejectsAllChannels() {
        assertFalse(
            NotificationCenterReadPolicy.isChatMessageItem(
                chatId = "",
                mergeKey = "msg_",
                deeplink = null,
                extraChatId = null
            )
        )
        assertFalse(
            NotificationCenterReadPolicy.belongsToChat(
                chatId = "",
                mergeKey = "msg_c1",
                deeplink = "maodouchat:chat:c1",
                extraChatId = "c1"
            )
        )
    }
}

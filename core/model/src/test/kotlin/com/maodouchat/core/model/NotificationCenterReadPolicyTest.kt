package com.maodouchat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCenterReadPolicyTest {

    @Test
    fun `message rows match by merge key deeplink or msg-prefixed extra`() {
        assertTrue(
            NotificationCenterReadPolicy.isChatMessageItem("c1", "msg_c1", null, null)
        )
        assertTrue(
            NotificationCenterReadPolicy.isChatMessageItem(
                "c1", "other", "maodouchat:chat:c1", null
            )
        )
        assertTrue(
            NotificationCenterReadPolicy.isChatMessageItem("c1", "msg_x", null, "c1")
        )
    }

    @Test
    fun `ai task rows are not message rows but belong to the chat`() {
        assertFalse(
            NotificationCenterReadPolicy.isChatMessageItem(
                "c1", "ai_tasks_c1", null, "c1"
            )
        )
        assertTrue(
            NotificationCenterReadPolicy.belongsToChat("c1", "ai_tasks_c1", null, null)
        )
        assertTrue(
            NotificationCenterReadPolicy.belongsToChat(
                "c1", "other", "maodouchat:ai_tasks:c1", null
            )
        )
        assertFalse(NotificationCenterReadPolicy.belongsToChat("c1", "post_1", null, null))
    }

    @Test
    fun `message reference uses m anchor so underscored chat ids still match`() {
        assertTrue(
            NotificationCenterReadPolicy.referencesMessage("m_9", "msg_c_x_m_9", null)
        )
        assertTrue(
            NotificationCenterReadPolicy.referencesMessage("m_9", "msg_c1_m_8", "m_9")
        )
        assertFalse(
            NotificationCenterReadPolicy.referencesMessage("m_9", "msg_c1_m_8", null)
        )
    }

    @Test
    fun `blank ids never match`() {
        assertFalse(
            NotificationCenterReadPolicy.isChatMessageItem("", "msg_", null, null)
        )
        assertFalse(
            NotificationCenterReadPolicy.belongsToChat("", "msg_", null, null)
        )
        assertFalse(
            NotificationCenterReadPolicy.referencesMessage("", "msg_c1_m_9", null)
        )
    }
}

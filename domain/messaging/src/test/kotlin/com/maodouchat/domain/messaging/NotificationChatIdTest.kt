package com.maodouchat.domain.messaging

import com.maodouchat.core.model.NotificationCenterItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationChatIdTest {

    private fun item(
        extra: Map<String, String> = emptyMap(),
        deeplink: String? = null,
        mergeKey: String = "",
    ) = NotificationCenterItem(id = "n1", type = "MESSAGE", mergeKey = mergeKey, title = "t", deeplink = deeplink, extra = extra)

    @Test
    fun `extra chatId wins over deeplink and mergeKey`() {
        assertEquals("c1", resolvedNotificationChatId(item(extra = mapOf("chatId" to "c1"), deeplink = "maodouchat:chat:c2", mergeKey = "msg_c3")))
    }

    @Test
    fun `blank extra chatId falls through to deeplink`() {
        assertEquals("c1", resolvedNotificationChatId(item(extra = mapOf("chatId" to "  "), deeplink = "maodouchat:chat:c1")))
    }

    @Test
    fun `chat deeplink resolves to chatId`() {
        assertEquals("c1", resolvedNotificationChatId(item(deeplink = "maodouchat:chat:c1")))
    }

    @Test
    fun `ai tasks deeplink resolves to chatId`() {
        assertEquals("c9", resolvedNotificationChatId(item(deeplink = "maodouchat:ai_tasks:c9")))
    }

    @Test
    fun `msg mergeKey prefix resolves to chatId`() {
        assertEquals("c7", resolvedNotificationChatId(item(mergeKey = "msg_c7")))
    }

    @Test
    fun `ai tasks mergeKey prefix resolves to chatId`() {
        assertEquals("c8", resolvedNotificationChatId(item(mergeKey = "ai_tasks_c8")))
    }

    @Test
    fun `bare msg prefix and unknown deeplink resolve to null`() {
        assertNull(resolvedNotificationChatId(item(mergeKey = "msg_")))
        assertNull(resolvedNotificationChatId(item(deeplink = "maodouchat:post:p1")))
        assertNull(resolvedNotificationChatId(item()))
    }
}

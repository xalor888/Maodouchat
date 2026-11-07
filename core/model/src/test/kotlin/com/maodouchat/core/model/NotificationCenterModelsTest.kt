package com.maodouchat.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationCenterModelsTest {

    @Test
    fun itemDefaultsToUnreadSingleCount() {
        val item = NotificationCenterItem(
            id = "n1", type = NotificationCenterType.MESSAGE, mergeKey = "k", title = "t",
        )
        assertFalse(item.read)
        assertEquals(1, item.count)
        assertEquals(emptyMap(), item.extra)
        assertTrue(item.createdAt > 0)
        assertTrue(item.updatedAt > 0)
    }

    @Test
    fun adminBroadcastProjectionCarriesItemAndBanner() {
        val item = NotificationCenterItem(
            id = "n1", type = NotificationCenterType.MESSAGE, mergeKey = "k", title = "t",
        )
        val projection = AdminBroadcastProjection(item = item, bannerText = "b")
        assertEquals(item, projection.item)
        assertEquals("b", projection.bannerText)
    }

    @Test
    fun notifFilterAllMatchesEveryItem() {
        val item = NotificationCenterItem(
            id = "n1", type = NotificationCenterType.MESSAGE, mergeKey = "k", title = "t",
        )
        assertTrue(NotifFilter.ALL.matches(item))
    }

    @Test
    fun notifFilterUnreadMatchesOnlyUnread() {
        val unread = NotificationCenterItem(
            id = "n1", type = NotificationCenterType.MESSAGE, mergeKey = "k", title = "t",
        )
        val read = unread.copy(read = true)
        assertTrue(NotifFilter.UNREAD.matches(unread))
        assertFalse(NotifFilter.UNREAD.matches(read))
    }

    @Test
    fun notifFilterTypeMatchesOnlyItsOwnType() {
        val cases = listOf(
            NotifFilter.MESSAGE to NotificationCenterType.MESSAGE,
            NotifFilter.MISSED_CALL to NotificationCenterType.MISSED_CALL,
            NotifFilter.AI_TASK to NotificationCenterType.AI_TASK,
            NotifFilter.POST_INTERACTION to NotificationCenterType.POST_INTERACTION,
            NotifFilter.FRIEND_REQUEST to NotificationCenterType.FRIEND_REQUEST,
        )
        for ((filter, type) in cases) {
            val item = NotificationCenterItem(
                id = "n1", type = type, mergeKey = "k", title = "t",
            )
            assertTrue(filter.matches(item))
            val other = item.copy(type = NotificationCenterType.SECURITY)
            assertFalse(filter.matches(other))
        }
    }
}

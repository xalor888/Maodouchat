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
}

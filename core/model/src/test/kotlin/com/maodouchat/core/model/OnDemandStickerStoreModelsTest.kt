package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class OnDemandStickerStoreModelsTest {

    @Test
    fun downloadResult_holdsFields() {
        val result = StickerPackDownloadResult("pack1", 3, 1, "partial")
        assertEquals("pack1", result.packId)
        assertEquals(3, result.downloaded)
        assertEquals(1, result.failed)
        assertEquals("partial", result.message)
    }

    @Test
    fun remotePack_holdsStickers() {
        val pack = RemotePack("p", listOf(RemoteSticker("a", "https://example.invalid/a", "aa")))
        assertEquals(1, pack.stickers.size)
        assertEquals("a", pack.stickers[0].name)
    }

    @Test
    fun packLock_defaultsToZeroUsersWithFreshMutex() {
        val first = PackLock()
        val second = PackLock()
        assertEquals(0, first.users)
        assertNotSame(first.mutex, second.mutex)
    }
}

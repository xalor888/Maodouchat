package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AttachmentDownloadCoordinatorModelsTest {

    @Test
    fun `lock ref starts unused`() {
        val ref = LockRef()
        assertNotNull(ref.mutex)
        assertEquals(0, ref.users)
    }

    @Test
    fun `lock ref tracks users`() {
        val ref = LockRef(users = 2)
        assertEquals(2, ref.users)
        ref.users++
        assertEquals(3, ref.users)
    }
}

package com.maodouchat.domain.groups

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class GroupAvatarModelsTest {

    @Test
    fun `avatar url defaults to null`() {
        val p = GroupParticipant(name = "Ada")
        assertEquals("Ada", p.name)
        assertNull(p.avatarUrl)
    }

    @Test
    fun `copy keeps the other fields`() {
        val p = GroupParticipant(name = "Bo", avatarUrl = "https://x/a.png")
        val q = p.copy(name = "Cy")
        assertEquals("Cy", q.name)
        assertEquals("https://x/a.png", q.avatarUrl)
    }
}

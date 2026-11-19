package com.maodouchat.domain.groups

import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.Feedback
import com.maodouchat.core.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JoinGroupInviteModelsTest {

    @Test
    fun joinGroupInviteResultVariants() {
        val joined = JoinGroupInviteResult.Joined(ChatDto(id = "c1", groupName = "g"))
        assertEquals("c1", (joined as JoinGroupInviteResult.Joined).chat.id)
        assertTrue(JoinGroupInviteResult.InvalidCode is JoinGroupInviteResult)
        val failed = JoinGroupInviteResult.Failed(Feedback(Kind.NETWORK))
        assertEquals(Kind.NETWORK, (failed as JoinGroupInviteResult.Failed).feedback.kind)
        assertTrue(JoinGroupInviteResult.Aborted is JoinGroupInviteResult)
    }
}

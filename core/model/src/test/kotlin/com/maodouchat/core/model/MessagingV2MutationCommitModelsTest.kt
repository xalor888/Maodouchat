package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagingV2MutationCommitModelsTest {

    @Test
    fun `mutation commit defaults to no projection error`() {
        assertNull(MessagingV2MutationCommit().localProjectionError)
        val error = IllegalStateException("projection failed")
        assertEquals(error, MessagingV2MutationCommit(error).localProjectionError)
    }
}

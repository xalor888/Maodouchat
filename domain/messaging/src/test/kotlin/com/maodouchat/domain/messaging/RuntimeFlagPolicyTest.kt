package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Flag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeFlagPolicyTest {

    @Test
    fun nearbyIsForcedOff() {
        assertTrue(RuntimeFlagPolicy.isForcedOff(Flag("nearby_enabled", false)))
    }

    @Test
    fun chatExportIsForcedOff() {
        assertTrue(RuntimeFlagPolicy.isForcedOff(Flag("chat_export_enabled", false)))
    }

    @Test
    fun otherFlagsAreNotForcedOff() {
        assertFalse(RuntimeFlagPolicy.isForcedOff(Flag("ai_master_enabled", true)))
        assertFalse(RuntimeFlagPolicy.isForcedOff(Flag("chat_folders_enabled", true)))
    }
}

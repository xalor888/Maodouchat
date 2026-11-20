package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupCallCapabilitiesTest {

    @Test
    fun `mesh size and unimplemented features are explicit`() {
        assertTrue(GroupCallCapabilities.canStartMesh(2))
        assertTrue(GroupCallCapabilities.canStartMesh(GroupCallCapabilities.MAX_MESH_MEMBERS))
        assertFalse(GroupCallCapabilities.canStartMesh(1))
        assertFalse(GroupCallCapabilities.canStartMesh(GroupCallCapabilities.MAX_MESH_MEMBERS + 1))
        assertTrue(GroupCallCapabilities.isUnimplemented(Feature.SFU))
        assertTrue(GroupCallCapabilities.isUnimplemented(Feature.SCREEN_SHARE))
        assertTrue(GroupCallCapabilities.isUnimplemented(Feature.CALL_RECORDING))
        assertFalse(GroupCallCapabilities.SFU_SUPPORTED)
        assertFalse(GroupCallCapabilities.SCREEN_SHARE_SUPPORTED)
        assertFalse(GroupCallCapabilities.CALL_RECORDING_SUPPORTED)
    }

}

package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.ChatFeatureGatePolicy.GroupPlayGate
import com.maodouchat.domain.messaging.ChatFeatureGatePolicy.ReactionsGate
import com.maodouchat.domain.messaging.ChatFeatureGatePolicy.VoiceMessagesGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatFeatureGatePolicyTest {

    @Test
    fun `voice gate reports voice disabled first`() {
        assertEquals(VoiceMessagesGate.VoiceDisabled, ChatFeatureGatePolicy.voiceMessagesGate(false, false))
        assertEquals(VoiceMessagesGate.VoiceDisabled, ChatFeatureGatePolicy.voiceMessagesGate(false, true))
    }

    @Test
    fun `voice gate reports media upload disabled`() {
        assertEquals(VoiceMessagesGate.MediaUploadDisabled, ChatFeatureGatePolicy.voiceMessagesGate(true, false))
    }

    @Test
    fun `voice gate allows both enabled`() {
        assertEquals(VoiceMessagesGate.Allowed, ChatFeatureGatePolicy.voiceMessagesGate(true, true))
    }

    @Test
    fun `stickers and silent send follow their flag`() {
        assertTrue(ChatFeatureGatePolicy.stickersAllowed(true))
        assertFalse(ChatFeatureGatePolicy.stickersAllowed(false))
        assertTrue(ChatFeatureGatePolicy.silentSendAllowed(true))
        assertFalse(ChatFeatureGatePolicy.silentSendAllowed(false))
    }

    @Test
    fun `reactions gate reports reactions disabled first`() {
        assertEquals(ReactionsGate.ReactionsDisabled, ChatFeatureGatePolicy.reactionsGate(false, true, true))
    }

    @Test
    fun `reactions gate blocks secret chat when block enabled`() {
        assertEquals(ReactionsGate.SecretReactionBlocked, ChatFeatureGatePolicy.reactionsGate(true, true, true))
    }

    @Test
    fun `reactions gate allows secret chat when block disabled or not secret`() {
        assertEquals(ReactionsGate.Allowed, ChatFeatureGatePolicy.reactionsGate(true, true, false))
        assertEquals(ReactionsGate.Allowed, ChatFeatureGatePolicy.reactionsGate(true, false, true))
        assertEquals(ReactionsGate.Allowed, ChatFeatureGatePolicy.reactionsGate(true, null, true))
    }

    @Test
    fun `group play gate requires group`() {
        assertEquals(GroupPlayGate.NotGroup, ChatFeatureGatePolicy.groupPlayGate(null, true))
        assertEquals(GroupPlayGate.NotGroup, ChatFeatureGatePolicy.groupPlayGate(false, true))
    }

    @Test
    fun `group play gate requires flag and allows group with flag`() {
        assertEquals(GroupPlayGate.GroupPlayDisabled, ChatFeatureGatePolicy.groupPlayGate(true, false))
        assertEquals(GroupPlayGate.Allowed, ChatFeatureGatePolicy.groupPlayGate(true, true))
    }
}

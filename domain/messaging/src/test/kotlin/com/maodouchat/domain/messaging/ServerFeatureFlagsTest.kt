package com.maodouchat.domain.messaging

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// parseServerFeatureFlags：自 app 侧 ChatListServerFlagsTest 随迁。
// 默认值不是统一的——三项默认 false，其余默认 true；绑定表一致性校验仍在 app 侧。
class ServerFeatureFlagsTest {

    @Test
    fun `empty json falls back to defaults with three switches off`() {
        val flags = parseServerFeatureFlags(JSONObject())

        assertEquals(86, flags.size)
        assertFalse(flags.getValue("chatExportEnabled"))
        assertFalse(flags.getValue("nearbyEnabled"))
        assertFalse(flags.getValue("secretExternalLinkBlockEnabled"))
        assertTrue(flags.getValue("appLockEnabled"))
        assertTrue(flags.getValue("callsEnabled"))
        assertTrue(flags.getValue("chatLockEnabled"))
        assertTrue(flags.getValue("markdownEnabled"))
        assertTrue(flags.getValue("secretChatEnabled"))
        assertTrue(flags.getValue("typingIndicatorsEnabled"))
        val falseKeys = flags.filterValues { !it }.keys
        assertEquals(
            setOf("chatExportEnabled", "nearbyEnabled", "secretExternalLinkBlockEnabled"),
            falseKeys
        )
    }

    @Test
    fun `explicit server values win over defaults`() {
        val o = JSONObject()
            .put("appLockEnabled", false)
            .put("chatExportEnabled", true)
            .put("nearbyEnabled", true)
            .put("secretExternalLinkBlockEnabled", true)
            .put("callsEnabled", false)

        val flags = parseServerFeatureFlags(o)

        assertFalse(flags.getValue("appLockEnabled"))
        assertFalse(flags.getValue("callsEnabled"))
        assertTrue(flags.getValue("chatExportEnabled"))
        assertTrue(flags.getValue("nearbyEnabled"))
        assertTrue(flags.getValue("secretExternalLinkBlockEnabled"))
        assertTrue(flags.getValue("chatLockEnabled"))
        assertEquals(86, flags.size)
    }
}

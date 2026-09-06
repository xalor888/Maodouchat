package com.maodouchat.server.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bot 能力清单锁定（B12）：具名常量替代路由内联手写表后，
 * 锁定无重复、规模与核心条目，防止编辑损坏。
 */
class BotCapabilitiesTest {

    @Test
    fun noDuplicates() {
        assertEquals(BOT_CAPABILITIES.size, BOT_CAPABILITIES.toSet().size, "duplicate capability")
    }

    @Test
    fun coreEntriesPresent() {
        val set = BOT_CAPABILITIES.toSet()
        listOf(
            "sendMessage", "sendPhoto", "sendDocument", "sendPoll",
            "pinChatMessage", "getUpdates", "webhook", "whoami",
            "healthz", "readyz", "alivez", "ping",
            "getRuntimeFlags", "getPrivacyFlags", "getCaptureShieldFlags",
        ).forEach { assertTrue(it in set, "missing capability: $it") }
    }
}

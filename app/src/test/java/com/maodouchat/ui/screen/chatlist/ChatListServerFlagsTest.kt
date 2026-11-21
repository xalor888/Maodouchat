package com.maodouchat.ui.screen.chatlist

import com.maodouchat.domain.messaging.serverFeatureFlagBindings
import com.maodouchat.util.RuntimeFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G145：服务端功能开关解析的测试。
 *
 * 保护 G142（`parseServerFeatureFlags` 从 86 行同构声明收敛）、
 * G143（`applyServerFeatureFlags` 从 86 行手写 setEnabled 收敛）、
 * G144（`fetchPublicStatusBanner` 抽出）这三次重构。
 *
 * 表结构测试（86 对唯一、key 集与解析表一致、默认值分布）已随绑定表迁入
 * `:domain:messaging` 的 `ServerFeatureFlagBindingsTest`；此处只留 app 侧才有意义的
 * 一件事：**domain 表的 Flag 字面量与 `RuntimeFlags` 常量按值相等**——改一处不同步即红。
 */
class ChatListServerFlagsTest {

    @Test
    fun `binding table flags exactly match RuntimeFlags constants`() {
        // 未入表的只有这三个：AI_MASTER 走 `aiEnabled` 总闸，AI_IMAGE_OCR 与 FAKE_CHAT
        // 不由服务端开关驱动。
        val excluded = setOf(RuntimeFlags.AI_MASTER, RuntimeFlags.AI_IMAGE_OCR, RuntimeFlags.FAKE_CHAT)
        val expected = RuntimeFlags.allFlags.filterNot { it in excluded }.toSet()
        val actual = serverFeatureFlagBindings.map { it.first }.toSet()

        assertEquals(86, actual.size)
        assertEquals("domain 表与 RuntimeFlags 常量不同步——加开关/改默认值时两边都要改", expected, actual)
    }

    @Test
    fun `binding table keeps the original constant to key pairs`() {
        // G143 的绑定表由原先 86 行手写调用逐条生成；这几对是抽样哨兵。
        val expected = mapOf(
            RuntimeFlags.APP_LOCK to "appLockEnabled",
            RuntimeFlags.CHAT_EXPORT to "chatExportEnabled",
            RuntimeFlags.NEARBY to "nearbyEnabled",
            RuntimeFlags.SECRET_EXTERNAL_LINK_BLOCK to "secretExternalLinkBlockEnabled",
            RuntimeFlags.SECRET_CHAT to "secretChatEnabled",
        )
        // AI_MASTER 走的是 `aiEnabled` 而不是 flags（服务端 AI 总闸），
        // 所以它不在绑定表里——见 G143 的记录与 ChatListServerFlags 的 fetchPublicStatusBanner。
        assertTrue("AI_MASTER 不应在绑定表里", serverFeatureFlagBindings.none { it.first == RuntimeFlags.AI_MASTER })
        val actual = serverFeatureFlagBindings.toMap()
        expected.forEach { (flag, key) ->
            assertEquals("常量 ${flag.key} 的绑定被改坏了", key, actual[flag])
        }
    }
}

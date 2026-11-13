package com.maodouchat.ui.screen.chatlist

import com.maodouchat.domain.messaging.parseServerFeatureFlags
import com.maodouchat.util.RuntimeFlags
import org.json.JSONObject
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
 * 重点盯两件事：
 * 1. **默认值不是统一的**——三项默认 false，其余默认 true。改错一项就是
 *    「服务端没下发时行为反转」；
 * 2. **绑定表与解析表的 key 集合必须一致**——不一致会在运行时 `!!` 抛空指针。
 */
class ChatListServerFlagsTest {

    // ─── FLAG_BINDINGS / applyServerFeatureFlags ───

    @Test
    fun `binding table has no duplicate constants or keys`() {
        val consts = FLAG_BINDINGS.map { it.first }
        val keys = FLAG_BINDINGS.map { it.second }

        assertEquals("绑定表有重复常量", consts.size, consts.toSet().size)
        assertEquals("绑定表有重复 key", keys.size, keys.toSet().size)
        assertEquals(86, consts.size)
        assertEquals(86, keys.size)
    }

    @Test
    fun `binding table keys exactly match the parser output keys`() {
        val parsed = parseServerFeatureFlags(JSONObject()).keys
        val bound = FLAG_BINDINGS.map { it.second }.toSet()

        assertEquals(
            "绑定表与解析表不同步——加开关时两边都要改",
            parsed,
            bound
        )
    }

    @Test
    fun `binding table uses distinct flag instances`() {
        val flags = FLAG_BINDINGS.map { it.first }
        assertEquals("绑定表有重复的 Flag 实例", flags.size, flags.toSet().size)
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
        assertTrue("AI_MASTER 不应在绑定表里", FLAG_BINDINGS.none { it.first == RuntimeFlags.AI_MASTER })
        val actual = FLAG_BINDINGS.toMap()
        expected.forEach { (flag, key) ->
            assertEquals("常量 ${flag.key} 的绑定被改坏了", key, actual[flag])
        }
    }
}

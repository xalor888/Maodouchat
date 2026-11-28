package com.maodouchat.domain.messaging

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// serverFeatureFlagBindings：自 app 侧 ChatListServerFlagsTest 随迁（G143 表结构三例）+
// 本轮新增的默认值分布与微妙默认值差异断言。
class ServerFeatureFlagBindingsTest {

    @Test
    fun `binding table has 86 unique flags and keys`() {
        val flags = serverFeatureFlagBindings.map { it.first }
        val keys = serverFeatureFlagBindings.map { it.second }

        assertEquals(86, flags.size)
        assertEquals(86, keys.size)
        assertEquals("绑定表有重复 Flag", flags.size, flags.toSet().size)
        assertEquals("绑定表有重复 key", keys.size, keys.toSet().size)
    }

    @Test
    fun `binding table keys exactly match the parser output keys`() {
        val parsed = parseServerFeatureFlags(JSONObject()).keys
        val bound = serverFeatureFlagBindings.map { it.second }.toSet()

        assertEquals(
            "绑定表与解析表不同步——加开关时两边都要改",
            parsed,
            bound
        )
    }

    @Test
    fun `only chat export and nearby default to false locally`() {
        // Flag.default 是本地读取默认值：false 的只有这两项（FAKE_CHAT 不在表内）。
        // 服务端缺省默认值 false 的是另一组三项（见 ServerFeatureFlagsTest），
        // 两处默认值是不同的事实，不要互相抄。
        val falseFlags = serverFeatureFlagBindings.map { it.first }
            .filter { !it.default }.map { it.key }.toSet()
        assertEquals(setOf("chat_export_enabled", "nearby_enabled"), falseFlags)
    }

    @Test
    fun `secret external link block keeps its split defaults`() {
        // 唯一一处两边默认值不一致：本地默认 true，服务端缺省 false；既有行为，原样保留。
        val flag = serverFeatureFlagBindings.toMap()
            .entries.single { it.value == "secretExternalLinkBlockEnabled" }.key
        assertEquals("secret_external_link_block_enabled", flag.key)
        assertTrue(flag.default)
        assertFalse(parseServerFeatureFlags(JSONObject()).getValue("secretExternalLinkBlockEnabled"))
    }

    @Test
    fun `ai master key is not in the binding table`() {
        // AI_MASTER 走的是 `aiEnabled` 而不是 flags（服务端 AI 总闸），所以它不在绑定表里。
        assertTrue(serverFeatureFlagBindings.none { it.first.key == "ai_master_enabled" })
    }
}

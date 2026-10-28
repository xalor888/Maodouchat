package com.maodouchat.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G178：`WebRTCAudioPolicy` 的测试（G178 刚从 WebRTCManager 抽出）。
 *
 * 盯的点：三个键名用的是**标准名称**而不是已废弃的 `goog*` 前缀。
 */
class WebRTCAudioPolicyTest {

    @Test
    fun `the three standard audio constraints are all enabled`() {
        val constraints = standardAudioConstraints()
        assertEquals(
            listOf(
                "echoCancellation" to "true",
                "autoGainControl" to "true",
                "noiseSuppression" to "true",
            ),
            constraints,
        )
    }

    @Test
    fun `no deprecated goog prefixed key names`() {
        // 改回 goog* 前缀会被废弃 API 警告；这条把标准名称钉住
        standardAudioConstraints().forEach { (key, _) ->
            assertTrue("用了废弃的 goog* 前缀: $key", !key.startsWith("goog"))
        }
    }

    @Test
    fun `every value is the string true`() {
        standardAudioConstraints().forEach { (_, value) ->
            assertEquals("true", value)
        }
    }

    @Test
    fun `exactly three constraints and no duplicates`() {
        val keys = standardAudioConstraints().map { it.first }
        assertEquals(3, keys.size)
        assertEquals(keys.toSet().size, keys.size)
    }

    @Test
    fun `constraints keep echo gain noise order`() {
        val keys = standardAudioConstraints().map { it.first }
        assertTrue(keys.indexOf("echoCancellation") < keys.indexOf("autoGainControl"))
        assertTrue(keys.indexOf("autoGainControl") < keys.indexOf("noiseSuppression"))
    }

    @Test
    fun `repeated calls return the same list`() {
        assertEquals(standardAudioConstraints(), standardAudioConstraints())
    }

    @Test
    fun `keys contain no separator or deprecated prefix`() {
        standardAudioConstraints().forEach { (key, _) ->
            assertTrue("键名含分隔符: $key", key.none { it == '_' || it == '-' || it == ' ' })
            assertTrue("键名是 goog* 变体: $key", !key.lowercase().startsWith("goog"))
        }
    }

    @Test
    fun `every value parses as boolean true`() {
        standardAudioConstraints().forEach { (_, value) ->
            assertTrue(value.toBooleanStrict())
        }
    }

    @Test
    fun `key set matches the three expected names`() {
        assertEquals(
            setOf("echoCancellation", "autoGainControl", "noiseSuppression"),
            standardAudioConstraints().map { it.first }.toSet(),
        )
    }
}

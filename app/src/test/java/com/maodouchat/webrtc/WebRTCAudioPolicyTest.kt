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
}

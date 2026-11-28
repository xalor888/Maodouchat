package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * CallType.detectFromSdp 的随机 SDP oracle fuzz。
 * oracle 按生产源码逐字转录：仅当某行 trim 后以 m=video 开头（忽略大小写）
 * 且其后端口数字非 0 时判 VIDEO，其余一律 AUDIO。随机输入固定种子，可复现。
 */
class CallTypeSdpFuzzTest {

    private val random = Random(20261122)

    private fun expectedType(sdp: String?): CallType {
        if (sdp.isNullOrBlank()) return CallType.AUDIO
        for (line in sdp.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("m=video", ignoreCase = true)) {
                val port = trimmed.substring(7).trimStart()
                    .takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                if (port != 0) return CallType.VIDEO
            }
        }
        return CallType.AUDIO
    }

    private fun randomSdpLine(): String {
        val port = listOf("0", "1", "9", "80", "5000", "65535", "70000", "9999999999", "2147483648", "", "abc", "09", "00").random(random)
        val media = listOf("m=audio", "m=video", "m=VIDEO", "M=Video", "m=Video", "m=videofoo", "m=video0").random(random)
        val prefix = listOf("", "", "", "  ", "\t").random(random)
        return when (random.nextInt(0, 6)) {
            0 -> "$prefix$media $port UDP/TLS/RTP/SAVPF 96"
            1 -> "$prefix$media $port"
            2 -> "$prefix$media"
            3 -> listOf("v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "c=IN IP4 0.0.0.0", "a=rtpmap:96 H264/90000", "a=video", "xm=video 9 UDP/TLS/RTP/SAVPF 96", "hello", "").random(random)
            4 -> "$prefix$media $port UDP/TLS/RTP/SAVPF 111"
            else -> "$prefix$media\t$port UDP/TLS/RTP/SAVPF 96"
        }
    }

    @Test
    fun detectFromSdpMatchesOracle() {
        repeat(300) {
            val lines = List(random.nextInt(1, 7)) { randomSdpLine() }
            val separator = listOf("\n", "\r\n").random(random)
            val sdp = lines.joinToString(separator)
            assertEquals("sdp=<$sdp>", expectedType(sdp), CallType.detectFromSdp(sdp))
        }
    }

    @Test
    fun detectFromSdpNullBlankAndAudioOnlyPinned() {
        assertEquals(CallType.AUDIO, CallType.detectFromSdp(null))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp(""))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("   "))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("\t\n "))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("v=0\r\na=rtpmap:96 H264/90000\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("a=video"))
        // 不间断空格不是空白：isNullOrBlank 为假，但无 m=video 行仍判 AUDIO。
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("\u00A0"))
    }

    @Test
    fun detectFromSdpPortEdgesPinned() {
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("v=0\r\nm=video 0 UDP/TLS/RTP/SAVPF 96\r\n"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=video 00 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("m=video 09 UDP/TLS/RTP/SAVPF 96"))
        // 超出 Int 范围的端口 toIntOrNull 为 null，按 0 处理。
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=video 9999999999 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=video 2147483648 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("m=video 2147483647 UDP/TLS/RTP/SAVPF 96"))
        // Java Integer.parseInt 走 Character.digit，接受任意 Unicode 十进制数字：
        // 阿拉伯-印度数字 ٣ 与全角 ５ 都能解析成非 0 端口，因此判 VIDEO。
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("m=video \u0663 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("m=video \uFF15 UDP/TLS/RTP/SAVPF 96"))
        // 但 0 仍是 0，阿拉伯-印度数字 ٠ 也按 0 处理。
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=video \u0660 UDP/TLS/RTP/SAVPF 96"))
        // 端口前有符号或缺失数字时数字前缀为空，按 0 处理。
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=video +9 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=video -9 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=video"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=video "))
        // m=video 后紧跟非空白字符时，子串起点的数字前缀仍决定结果。
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=videofoo 9 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=video0 9 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("m=video9 UDP/TLS/RTP/SAVPF 96"))
        // 大小写不敏感，行首空白先 trim。
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("M=VIDEO 9 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("  \tm=Video 9 UDP/TLS/RTP/SAVPF 96"))
        // 任一行命中即 VIDEO，不必是首行。
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\nm=video 9 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("v=0\nm=video 0 UDP/TLS/RTP/SAVPF 96\nm=audio 9 UDP/TLS/RTP/SAVPF 111"))
    }

    @Test
    fun detectFromSdpSeparatorsPinned() {
        // \r 也是行分隔符；混合分隔符同样逐行判断。
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("v=0\rm=video 9 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\rm=video 9 UDP/TLS/RTP/SAVPF 96"))
        assertEquals(CallType.AUDIO, CallType.detectFromSdp("m=video 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111"))
        // 行尾回车不影响：trim 剥掉行内空白。
        assertEquals(CallType.VIDEO, CallType.detectFromSdp("m=video 9 UDP/TLS/RTP/SAVPF 96\r"))
    }
}

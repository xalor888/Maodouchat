package com.maodouchat.server.common

import java.io.ByteArrayInputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G187a：`WebhookSecurityUtils` —— 出站 webhook 的安全闸门。
 *
 * 377 行零散在多处的安全不变量：SSRF 地址过滤、HTTP 响应头解析（含请求 smuggling
 * 防御）、body 读取上限（DoS 防线）。
 *
 * **已覆盖部分（不要重写）**：`MinimalRouteTest.RoutingSecurityHelperTest` 已测
 * `isAllowedWebhookAddress` 8 个地址、`isAllowedWebhookUrl` 14 个 URL、
 * `postPinnedWebhookJson` 端口 0 抛错、`readPinnedWebhookResponse` 两条
 * （100-then-200、冲突 Content-Length 抛错）。
 *
 * 本文件补的是剩下的：约 20 个未测 IP 段、`allowLoopback=true` 的反向语义、
 * 以及 body 读取策略（chunked / Content-Length 截断 / 204-304 / maxBodyBytes=0）
 * 与响应头防御（101、informational 上限、TE+CL ambiguity、各类畸形头）。
 *
 * 响应解析全部用 `ByteArrayInputStream`，不需要网络。
 */
class WebhookSecurityUtilsTest {

    private fun addr(literal: String): InetAddress = InetAddress.getByName(literal)

    // ---- isAllowedWebhookAddress：allowLoopback 的反向语义 ----

    @Test
    fun `loopback is allowed only when the caller opted in`() {
        // allowLoopback=true 时走 isLoopbackAddress 短路——与 false 时完全相反
        assertTrue(addr("127.0.0.1").isAllowedWebhookAddress(allowLoopback = true), "http 方案下 loopback 必须放行")
        assertFalse(addr("8.8.8.8").isAllowedWebhookAddress(allowLoopback = true), "非 loopback 公网地址在 allowLoopback=true 时必须拒")
    }

    // ---- IPv4 私有/保留段（cold ranges）----

    @Test
    fun `ipv4 private and reserved ranges are all rejected`() {
        val blocked = listOf(
            "0.0.0.0",          // 0.x
            "0.1.2.3",
            "10.0.0.1",         // 10/8
            "10.255.255.255",
            "127.0.0.1",        // 127/8
            "127.255.255.254",
            "224.0.0.1",        // >=224 组播
            "255.255.255.255",
            "172.16.0.1",       // 172.16/12
            "172.31.255.255",
            "192.168.1.1",      // 192.168/16
            "198.18.0.1",       // 198.18/15
            "198.19.255.255",
            "169.254.1.1",      // 169.254/16
            "192.0.0.1",        // 192.0.0/24
            "192.0.2.1",
            "192.31.196.1",
            "192.52.193.1",
            "192.175.48.1",
            "198.51.100.1",
            "203.0.113.1",
        )
        blocked.forEach { literal ->
            assertFalse(
                addr(literal).isAllowedWebhookAddress(allowLoopback = false),
                "$literal 必须被拒",
            )
        }
    }

    @Test
    fun `ipv4 boundaries just outside the blocked ranges are allowed`() {
        val allowed = listOf(
            "100.63.255.255",   // 100.64-127 之下
            "100.128.0.1",      // 100.64-127 之上
            "172.15.255.255",   // 172.16/12 之下
            "172.32.0.1",       // 172.16/12 之上
            "198.17.255.255",   // 198.18/15 之下
            "198.20.0.1",       // 198.18/15 之上
            "192.1.0.1",        // 192.0.0/24 之上
            "192.0.3.1",
            "11.0.0.1",         // 普通公网
            "1.1.1.1",
        )
        allowed.forEach { literal ->
            assertTrue(
                addr(literal).isAllowedWebhookAddress(allowLoopback = false),
                "$literal 应放行",
            )
        }
    }

    // ---- IPv6 ----

    @Test
    fun `ipv6 addresses outside global unicast are rejected`() {
        val blocked = listOf(
            "64:ff9b::1",       // octets[0]=0x64 不在 0x20..0x3f（NAT64 前缀本身）
            "fc00::1",          // ULA，octets[0]=0xfc
            "2001:0::1",        // 2001::/23 下界
            "2001:1::1",        // 2001::/23 上界
            "2001:db8::1",      // 2001:db8::/32
            "2002::1",          // 2002::/16 6to4
            "2002:7f00:1::",    // 6to4 里包 IPv4 loopback
            "3fff:0::1",        // 3fff::/20
        )
        blocked.forEach { literal ->
            assertFalse(
                addr(literal).isAllowedWebhookAddress(allowLoopback = false),
                "$literal 必须被拒",
            )
        }
    }

    @Test
    fun `ipv6 boundaries just outside the special purpose blocks are allowed`() {
        // 注意：实现的判定是 octets[2] <= 0x01，而 octets[2] 是「第二组的高字节」。
        // 2001:2::1 的第二组是 0002 → 字节 0x00,0x02 → octets[2]=0x00 <= 0x01 → **仍被拒**。
        // 要落到 else 分支，第二组高位必须 >= 0x02，例如 2001:0200::1。
        val allowed = listOf(
            "2001:200::1",              // 第二组 0x0200 → octets[2]=0x02
            "3fff:1000::1",             // 3fff::/20 之上
            "2606:4700:4700::1111",     // 普通全球单播
            "2400:cb00::1",
        )
        allowed.forEach { literal ->
            assertTrue(
                addr(literal).isAllowedWebhookAddress(allowLoopback = false),
                "$literal 应放行",
            )
        }
    }

    @Test
    fun `the two thousand one block is wider than its comment claims`() {
        // 注释写 2001::/23，但 octets[2] <= 0x01 实际挡住的是「第二组高位 0x00/0x01」，
        // 即 2001:0000-01ff:: 整片。这里把这个更宽的真实范围钉住：
        assertFalse(addr("2001:2::1").isAllowedWebhookAddress(allowLoopback = false), "2001:2::1 的第二组高位是 0x00")
        assertFalse(addr("2001:1ff::1").isAllowedWebhookAddress(allowLoopback = false))
        assertTrue(addr("2001:200::1").isAllowedWebhookAddress(allowLoopback = false))
    }

    // ---- readPinnedWebhookResponse：入参 ----

    @Test
    fun `body byte budget must stay within the supported window`() {
        val ok = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".byteInputStream(Charsets.US_ASCII)
        assertEquals("", readPinnedWebhookResponse(ok, maxBodyBytes = 64 * 1024).body)
        assertFailsWith<IllegalArgumentException> {
            readPinnedWebhookResponse(ok, maxBodyBytes = 64 * 1024 + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            readPinnedWebhookResponse(ok, maxBodyBytes = -1)
        }
    }

    // ---- body 读取策略 ----

    @Test
    fun `a huge content length is truncated to the body budget`() {
        // DoS 防线：对端声称 Content-Length: 100000，但 maxBodyBytes 只有 8
        val payload = "0123456789"
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\n\r\n$payload"
        val response = readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 8)
        assertEquals(200, response.statusCode)
        assertEquals("01234567", response.body, "必须只读 maxBodyBytes 字节，不能按 Content-Length 全读")
    }

    @Test
    fun `a short content length stops before the budget`() {
        val response = readPinnedWebhookResponse(
            "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabcXXXXXXXX"
                .byteInputStream(Charsets.US_ASCII),
            maxBodyBytes = 64
        )
        assertEquals("abc", response.body, "必须只读 Content-Length 指定的字节")
    }

    @Test
    fun `no content length and no transfer encoding reads to end of stream`() {
        val response = readPinnedWebhookResponse(
            "HTTP/1.1 200 OK\r\n\r\nhello".byteInputStream(Charsets.US_ASCII),
            maxBodyBytes = 64
        )
        assertEquals("hello", response.body)
    }

    @Test
    fun `a zero body budget returns an empty body without reading`() {
        val response = readPinnedWebhookResponse(
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".byteInputStream(Charsets.US_ASCII),
            maxBodyBytes = 0
        )
        assertEquals(200, response.statusCode)
        assertEquals("", response.body, "maxBodyBytes=0 必须返回空 body")
    }

    @Test
    fun `no content responses carry no body`() {
        listOf(204, 304).forEach { code ->
            val response = readPinnedWebhookResponse(
                "HTTP/1.1 $code No Content\r\nContent-Length: 5\r\n\r\nhello"
                    .byteInputStream(Charsets.US_ASCII),
                maxBodyBytes = 64
            )
            assertEquals(code, response.statusCode)
            assertEquals("", response.body, "$code 必须是空 body")
        }
    }

    // ---- chunked 解码 ----

    @Test
    fun `a chunked body is decoded across chunks`() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
        val response = readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        assertEquals("hello world", response.body, "多 chunk 必须拼成完整 body")
    }

    @Test
    fun `a chunked body is truncated to the body budget`() {
        // 单个 5 字节 chunk，budget 只有 3 → 必须截断，且不得抛错
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
        val response = readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 3)
        assertEquals("hel", response.body, "chunked body 也必须受 maxBodyBytes 限制")
    }

    @Test
    fun `a chunk extension after the size is tolerated`() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5;name=value\r\nhello\r\n0\r\n\r\n"
        val response = readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        assertEquals("hello", response.body, "chunk-size 后的扩展参数必须被忽略")
    }

    @Test
    fun `a non hexadecimal chunk size is rejected`() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\nhello\r\n0\r\n\r\n"
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        }
    }

    @Test
    fun `a missing chunk terminator is rejected`() {
        // chunk 数据后没有 CRLF
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhelloXX0\r\n\r\n"
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        }
    }

    // ---- 响应头防御 ----

    @Test
    fun `a protocol upgrade response is rejected`() {
        val raw = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n"
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        }
    }

    @Test
    fun `at most five informational responses are tolerated`() {
        val prefix = "HTTP/1.1 100 Continue\r\n\r\n".repeat(5)
        val raw = "${prefix}HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
        val response = readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        assertEquals(200, response.statusCode, "5 个 informational 后跟 200 必须放行")
        assertEquals("ok", response.body)
    }

    @Test
    fun `a sixth informational response is rejected`() {
        val prefix = "HTTP/1.1 100 Continue\r\n\r\n".repeat(6)
        val raw = "${prefix}HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        }
    }

    @Test
    fun `transfer encoding together with content length is rejected`() {
        // HTTP 请求 smuggling 的经典形态
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        }
    }

    @Test
    fun `an unsupported transfer encoding is rejected`() {
        listOf("gzip", "chunked, gzip", "gzip, chunked").forEach { value ->
            val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: $value\r\n\r\nhello"
            assertFailsWith<IllegalStateException> {
                readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
            }
        }
    }

    @Test
    fun `a malformed status line is rejected`() {
        listOf(
            "HTTP/2 200 OK\r\n\r\n",
            "HTTP/1.1 20 OK\r\n\r\n",
            "200 OK\r\n\r\n",
            "HTTP/1.1 600 OK\r\n\r\n",
        ).forEach { raw ->
            assertFailsWith<IllegalStateException> {
                readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
            }
        }
    }

    @Test
    fun `an empty response is rejected`() {
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(ByteArrayInputStream(ByteArray(0)), maxBodyBytes = 64)
        }
    }

    @Test
    fun `an invalid header name is rejected`() {
        listOf("Bad Header: x", ": x", "Bad(Header): x").forEach { line ->
            val raw = "HTTP/1.1 200 OK\r\n$line\r\n\r\n"
            assertFailsWith<IllegalStateException> {
                readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
            }
        }
    }

    @Test
    fun `a header value carrying control characters is rejected`() {
        // \u0001 是控制字符，不属于 tab / 可打印区间
        val raw = "HTTP/1.1 200 OK\r\nX-Bad: a\u0001b\r\n\r\n"
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        }
    }

    @Test
    fun `a header value carrying DEL is rejected`() {
        val raw = "HTTP/1.1 200 OK\r\nX-Bad: a\u007fb\r\n\r\n"
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        }
    }

    @Test
    fun `a tab inside a header value is tolerated`() {
        val raw = "HTTP/1.1 200 OK\r\nX-Ok: a\tb\r\nContent-Length: 0\r\n\r\n"
        val response = readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        assertEquals(200, response.statusCode, "tab 是允许的空白")
    }

    @Test
    fun `headers that overflow the cap are rejected`() {
        // 单行超限会先撞上「line is too large」，这里用多行累积越过 32KB
        val sb = StringBuilder("HTTP/1.1 200 OK\r\n")
        repeat(80) { sb.append("X-Pad-").append(it).append(": ").append("a".repeat(400)).append("\r\n") }
        sb.append("\r\n")
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(sb.toString().byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        }
    }

    @Test
    fun `headers that end without a blank line are rejected`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        }
    }

    @Test
    fun `a status line without a reason phrase is accepted`() {
        val raw = "HTTP/1.1 200\r\nContent-Length: 2\r\n\r\nok"
        val response = readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        assertEquals(200, response.statusCode)
        assertEquals("ok", response.body)
    }

    @Test
    fun `an http one zero status line is accepted`() {
        val raw = "HTTP/1.0 500 Server Error\r\nContent-Length: 2\r\n\r\nno"
        val response = readPinnedWebhookResponse(raw.byteInputStream(Charsets.US_ASCII), maxBodyBytes = 64)
        assertEquals(500, response.statusCode)
        assertEquals("no", response.body)
    }
}

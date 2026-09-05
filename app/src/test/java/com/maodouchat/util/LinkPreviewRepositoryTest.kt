package com.maodouchat.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import android.util.Log

class LinkPreviewRepositoryTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<Throwable>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        LinkPreviewRepository.clear()
    }

    @After
    fun tearDown() {
        LinkPreviewRepository.clientOverride = null
        LinkPreviewRepository.clear()
        unmockkStatic(Log::class)
    }

    @Test
    fun downloadAndParse_blocksRedirectToInternalIp() {
        val requestCount = AtomicInteger(0)
        val testClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                val url = chain.request().url.toString()
                if (url == "https://example.com/redirect-internal") {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "http://127.0.0.1:8080/admin")
                        .body("".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<html></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                }
            }
            .build()

        LinkPreviewRepository.clientOverride = testClient

        val preview = LinkPreviewRepository.downloadAndParse("https://example.com/redirect-internal")

        // 验证：检测到重定向至内网目标（127.0.0.1），应直接阻断，绝不发起第二跳请求
        assertNull(preview)
        assertEquals(1, requestCount.get())
    }

    @Test
    fun downloadAndParse_blocksHttpsToHttpDowngrade() {
        val requestCount = AtomicInteger(0)
        val testClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "http://example.com/insecure")
                    .body("".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        LinkPreviewRepository.clientOverride = testClient

        val preview = LinkPreviewRepository.downloadAndParse("https://example.com/secure")

        // 验证：阻止从 HTTPS 向 HTTP 的协议降级重定向
        assertNull(preview)
        assertEquals(1, requestCount.get())
    }

    @Test
    fun downloadAndParse_followsSafeHttpsRedirect() {
        val requestedUrls = mutableListOf<String>()
        val testClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                requestedUrls.add(url)
                if (url == "https://example.com/start") {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(301)
                        .message("Moved Permanently")
                        .header("Location", "https://example.com/final-page")
                        .body("".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    val html = """
                        <html><head>
                        <meta property="og:title" content="Redirected Title" />
                        <meta property="og:description" content="Redirected Description" />
                        </head><body></body></html>
                    """.trimIndent()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(html.toResponseBody("text/html".toMediaType()))
                        .build()
                }
            }
            .build()

        LinkPreviewRepository.clientOverride = testClient

        val preview = LinkPreviewRepository.downloadAndParse("https://example.com/start")

        assertNotNull(preview)
        assertEquals("Redirected Title", preview?.title)
        assertEquals("Redirected Description", preview?.description)
        assertEquals(listOf("https://example.com/start", "https://example.com/final-page"), requestedUrls)
    }

    @Test
    fun downloadAndParse_blocksCloudMetadataRedirect() {
        val requestCount = AtomicInteger(0)
        val testClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "http://169.254.169.254/latest/meta-data")
                    .body("".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        LinkPreviewRepository.clientOverride = testClient

        val preview = LinkPreviewRepository.downloadAndParse("https://example.com/metadata-probe")

        // 验证：阻止向云元数据 169.254.169.254 重定向
        assertNull(preview)
        assertEquals(1, requestCount.get())
    }
}

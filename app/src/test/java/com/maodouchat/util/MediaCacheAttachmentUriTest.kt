package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 附件 URI 仍在 app 侧 MediaCache；编解码已迁入 :domain:messaging（见 EncryptedAttachmentReferenceCodecTest）。
class MediaCacheAttachmentUriTest {

    @Test
    fun attachmentUriRoundTrips() {
        val id = "att_" + "b".repeat(30)
        val uri = MediaCache.attachmentUri(id)
        assertEquals("maodou-attachment://$id", uri)
        assertTrue(MediaCache.isRemoteAttachmentUri(uri), "自己的 scheme 必须被认成远端附件")
        assertFalse(MediaCache.isRemoteAttachmentUri("file:///tmp/x.jpg"), "file:// 不是远端附件")
        assertFalse(MediaCache.isRemoteAttachmentUri("https://example.com/a"), "https:// 不是远端附件")
        assertFalse(MediaCache.isRemoteAttachmentUri(""), "空串不是远端附件")
    }
}

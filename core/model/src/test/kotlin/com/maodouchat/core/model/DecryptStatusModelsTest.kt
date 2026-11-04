package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DecryptStatusModelsTest {

    @Test
    fun `decrypt texts carry their fields`() {
        val texts = DecryptTexts(
            failed = "f", pending = "p", sessionMissing = "sm",
            identityChanged = "ic", groupFailed = "gf", groupKeyMissing = "gkm",
            groupIdentityChanged = "gic", groupNewer = "gn", imageFailed = "im",
            gifFailed = "gif", stickerFailed = "st", locationFailed = "lo",
            videoFailed = "vi", voiceFailed = "vo", fileFailed = "fi",
        )
        assertEquals("f", texts.failed)
        assertEquals("gkm", texts.groupKeyMissing)
        assertEquals("fi", texts.fileFailed)
    }
}

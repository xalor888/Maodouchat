package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentFileExtensionPolicyTest {

    @Test
    fun pdfKeepsExtension() {
        assertEquals(".pdf", AttachmentFileExtensionPolicy.fileExtension("report.pdf"))
    }

    @Test
    fun uppercaseExtensionIsLowercased() {
        assertEquals(".jpg", AttachmentFileExtensionPolicy.fileExtension("PHOTO.JPG"))
    }

    @Test
    fun nullFileNameFallsBackToBin() {
        assertEquals(".bin", AttachmentFileExtensionPolicy.fileExtension(null))
    }

    @Test
    fun noExtensionFallsBackToBin() {
        assertEquals(".bin", AttachmentFileExtensionPolicy.fileExtension("noext"))
    }

    @Test
    fun multiDotTakesLastSegment() {
        assertEquals(".gz", AttachmentFileExtensionPolicy.fileExtension("archive.tar.gz"))
    }

    @Test
    fun tooLongExtensionFallsBackToBin() {
        assertEquals(".bin", AttachmentFileExtensionPolicy.fileExtension("a.verylongextension"))
    }

    @Test
    fun trailingDotFallsBackToBin() {
        assertEquals(".bin", AttachmentFileExtensionPolicy.fileExtension("file."))
    }

    @Test
    fun invalidCharsFallsBackToBin() {
        assertEquals(".bin", AttachmentFileExtensionPolicy.fileExtension("a.b-c"))
    }
}

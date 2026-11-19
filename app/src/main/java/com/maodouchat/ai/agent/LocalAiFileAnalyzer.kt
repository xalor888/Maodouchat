package com.maodouchat.ai.agent

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.maodouchat.domain.messaging.PreparedDocument
import com.maodouchat.domain.messaging.PreparedDocumentKind
import com.maodouchat.domain.messaging.LocalAiFilePrepPolicy
import com.maodouchat.domain.messaging.LocalAiPdfPagePolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Decode a chat attachment for the user-configured model. Text files become UTF-8;
 * PDFs are rendered locally to JPEG pages. Bytes never go to Maodou /api/ai.
 */
object LocalAiFileAnalyzer {
    const val MAX_TEXT_CHARS = LocalAiFilePrepPolicy.MAX_TEXT_CHARS
    const val MAX_PDF_PAGES = LocalAiPdfPagePolicy.MAX_PDF_PAGES
    const val MAX_PAGE_WIDTH = LocalAiPdfPagePolicy.MAX_PAGE_WIDTH

    fun prepare(fileName: String, mimeType: String, fileBase64: String): PreparedDocument? {
        val bytes = runCatching {
            Base64.getDecoder().decode(LocalAiFilePrepPolicy.normalizeBase64(fileBase64))
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        if (LocalAiFilePrepPolicy.isPdf(mimeType, fileName, bytes)) {
            val pages = renderPdfPages(bytes) ?: return null
            if (pages.isEmpty()) return null
            return PreparedDocument(
                kind = PreparedDocumentKind.PDF_PAGES,
                fileName = LocalAiFilePrepPolicy.defaultPdfFileName(fileName),
                mimeType = "application/pdf",
                pageJpegsBase64 = pages
            )
        }
        if (!LocalAiFilePrepPolicy.isTextMime(mimeType)) return null
        val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        if (!LocalAiFilePrepPolicy.isUsableText(decoded)) return null
        return PreparedDocument(
            kind = PreparedDocumentKind.TEXT,
            fileName = LocalAiFilePrepPolicy.defaultTextFileName(fileName),
            mimeType = LocalAiFilePrepPolicy.defaultTextMime(mimeType),
            text = LocalAiFilePrepPolicy.truncateText(decoded)
        )
    }

    private fun renderPdfPages(bytes: ByteArray): List<String>? {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var temp: File? = null
        return try {
            temp = File.createTempFile("maodou-ai-pdf", ".pdf")
            temp.writeBytes(bytes)
            descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            val pageCount = LocalAiPdfPagePolicy.cappedPageCount(renderer.pageCount)
            if (pageCount <= 0) return null
            buildList {
                for (index in 0 until pageCount) {
                    renderer.openPage(index).use { page ->
                        val (width, height) = LocalAiPdfPagePolicy.renderSize(page.width, page.height)
                        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val jpeg = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpeg)
                        bitmap.recycle()
                        add(Base64.getEncoder().encodeToString(jpeg.toByteArray()))
                    }
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            temp?.delete()
        }
    }
}

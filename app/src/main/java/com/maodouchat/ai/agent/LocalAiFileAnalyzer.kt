package com.maodouchat.ai.agent

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Decode a chat attachment for the user-configured model. Text files become UTF-8;
 * PDFs are rendered locally to JPEG pages. Bytes never go to Maodou /api/ai.
 */
object LocalAiFileAnalyzer {
    const val MAX_TEXT_CHARS = 12_000
    const val MAX_PDF_PAGES = 4
    const val MAX_PAGE_WIDTH = 1024

    data class PreparedDocument(
        val kind: Kind,
        val fileName: String,
        val mimeType: String,
        val text: String = "",
        val pageJpegsBase64: List<String> = emptyList()
    )

    enum class Kind { TEXT, PDF_PAGES }

    fun prepare(fileName: String, mimeType: String, fileBase64: String): PreparedDocument? {
        val bytes = runCatching {
            Base64.getDecoder().decode(fileBase64.replace('-', '+').replace('_', '/'))
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val mime = mimeType.lowercase()
        val looksPdf = mime == "application/pdf" ||
            fileName.lowercase().endsWith(".pdf") ||
            (bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)))
        if (looksPdf) {
            val pages = renderPdfPages(bytes) ?: return null
            if (pages.isEmpty()) return null
            return PreparedDocument(
                kind = Kind.PDF_PAGES,
                fileName = fileName.ifBlank { "document.pdf" },
                mimeType = "application/pdf",
                pageJpegsBase64 = pages
            )
        }
        val textMime = mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "text/markdown" ||
            mime == "text/csv"
        if (!textMime) return null
        val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        if (decoded.isBlank() || '\u0000' in decoded || '\uFFFD' in decoded) return null
        return PreparedDocument(
            kind = Kind.TEXT,
            fileName = fileName.ifBlank { "document.txt" },
            mimeType = mime.ifBlank { "text/plain" },
            text = decoded.take(MAX_TEXT_CHARS)
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
            val pageCount = renderer.pageCount.coerceAtMost(MAX_PDF_PAGES)
            if (pageCount <= 0) return null
            buildList {
                for (index in 0 until pageCount) {
                    renderer.openPage(index).use { page ->
                        val scale = (MAX_PAGE_WIDTH.toFloat() / page.width.coerceAtLeast(1))
                            .coerceAtMost(2f)
                            .coerceAtLeast(0.25f)
                        val width = (page.width * scale).toInt().coerceIn(64, MAX_PAGE_WIDTH)
                        val height = (page.height * scale).toInt().coerceIn(64, 2_048)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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

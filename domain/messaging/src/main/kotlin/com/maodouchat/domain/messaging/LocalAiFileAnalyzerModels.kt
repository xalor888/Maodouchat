package com.maodouchat.domain.messaging

data class PreparedDocument(
    val kind: PreparedDocumentKind,
    val fileName: String,
    val mimeType: String,
    val text: String = "",
    val pageJpegsBase64: List<String> = emptyList()
)

enum class PreparedDocumentKind { TEXT, PDF_PAGES }

package com.maodouchat.ai.agent

data class PreparedDocument(
    val kind: Kind,
    val fileName: String,
    val mimeType: String,
    val text: String = "",
    val pageJpegsBase64: List<String> = emptyList()
)

enum class Kind { TEXT, PDF_PAGES }

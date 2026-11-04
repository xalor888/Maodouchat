package com.maodouchat.core.model

enum class AiSummaryScope { RECENT, TODAY, SEVEN_DAYS, THIRTY_DAYS, SEARCH_RESULTS, UNREAD }

enum class AiImageAnalysisMode(val wireValue: String) {
    DESCRIBE("describe"), OCR("ocr"), SAFETY("safety")
}

enum class AiFileAnalysisMode(val wireValue: String) {
    SUMMARIZE("summarize"), QUESTION("question")
}

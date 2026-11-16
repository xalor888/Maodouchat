package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiAuditLabelsTest {

    @Test
    fun `feature kinds cover all known features`() {
        val known = mapOf(
            "rewrite" to AiFeatureLabelKind.Rewrite,
            "suggest_replies" to AiFeatureLabelKind.SuggestReplies,
            "summarize" to AiFeatureLabelKind.Summarize,
            "transcribe_voice" to AiFeatureLabelKind.TranscribeVoice,
            "translate_message" to AiFeatureLabelKind.TranslateMessage,
            "semantic_search" to AiFeatureLabelKind.SemanticSearch,
            "global_semantic_search" to AiFeatureLabelKind.GlobalSemanticSearch,
            "group_assistant" to AiFeatureLabelKind.GroupAssistant,
            "image_analyze" to AiFeatureLabelKind.ImageAnalyze,
            "file_analyze" to AiFeatureLabelKind.FileAnalyze,
        )
        known.forEach { (feature, expected) ->
            assertEquals(expected, aiFeatureLabelKind(feature), feature)
        }
    }

    @Test
    fun `feature kind is case-insensitive`() {
        assertEquals(AiFeatureLabelKind.Rewrite, aiFeatureLabelKind("REWRITE"))
        assertEquals(AiFeatureLabelKind.Summarize, aiFeatureLabelKind("Summarize"))
    }

    @Test
    fun `blank feature is generic, unknown is raw`() {
        assertEquals(AiFeatureLabelKind.Generic, aiFeatureLabelKind(""))
        assertEquals(AiFeatureLabelKind.Generic, aiFeatureLabelKind("   "))
        assertEquals(AiFeatureLabelKind.Raw, aiFeatureLabelKind("voice_clone"))
    }

    @Test
    fun `status kinds cover all known statuses`() {
        assertEquals(AiStatusLabelKind.Success, aiStatusLabelKind("success"))
        assertEquals(AiStatusLabelKind.Failed, aiStatusLabelKind("failed"))
        assertEquals(AiStatusLabelKind.Failed, aiStatusLabelKind("error"))
        assertEquals(AiStatusLabelKind.Disabled, aiStatusLabelKind("disabled"))
        assertEquals(AiStatusLabelKind.RateLimited, aiStatusLabelKind("rate_limited"))
    }

    @Test
    fun `status kind is case-insensitive`() {
        assertEquals(AiStatusLabelKind.Success, aiStatusLabelKind("SUCCESS"))
        assertEquals(AiStatusLabelKind.Failed, aiStatusLabelKind("Failed"))
    }

    @Test
    fun `blank status is unknown, unknown is raw`() {
        assertEquals(AiStatusLabelKind.Unknown, aiStatusLabelKind(""))
        assertEquals(AiStatusLabelKind.Unknown, aiStatusLabelKind("   "))
        assertEquals(AiStatusLabelKind.Raw, aiStatusLabelKind("queued"))
    }
}

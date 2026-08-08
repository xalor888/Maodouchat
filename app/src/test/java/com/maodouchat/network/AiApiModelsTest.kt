package com.maodouchat.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AiApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun translationRequestKeepsChineseCompatibilityDefault() {
        val request = AiTranslateRequest(text = "hello")
        val encoded = json.encodeToString(request)

        assertEquals("中文", request.targetLanguage)
        assertEquals("中文", json.decodeFromString<AiTranslateRequest>(encoded).targetLanguage)
    }

    @Test
    fun groupAssistantResponseRoundTripsTasks() {
        val response = AiGroupAssistantResponse(
            answer = "已整理",
            mode = "tasks",
            model = "test-model",
            tasks = listOf(AiGroupTask(title = "确认方案", owner = "小猫", dueAt = 42L))
        )

        assertEquals(response, json.decodeFromString<AiGroupAssistantResponse>(json.encodeToString(response)))
    }
}

package com.maodouchat.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AiApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun localContextMessageRoundTrips() {
        val message = AiContextMessage(sender = "小猫", text = "hello")
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }

    @Test
    fun groupTaskRoundTrips() {
        val task = AiGroupTask(title = "确认方案", owner = "小猫", dueAt = 42L)
        assertEquals(task, json.decodeFromString<AiGroupTask>(json.encodeToString(task)))
    }
}

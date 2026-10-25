package com.maodouchat.ai.agent

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionEngineTest {

    private val provider = LocalAiProvider(
        id = "p",
        name = "t",
        baseUrl = "https://example.test/v1",
        apiKey = "k",
        model = "m"
    )

    @Test
    fun `send tool pauses for approval`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                OpenAiCompatClient.Completion.Tools(
                    listOf(AgentToolCall("c1", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")),
                    ""
                )
            },
            executeTool = { _, _ -> error("must not execute send without approval") }
        )
        val events = engine.runTurn(provider, emptyList(), "发一句你好", null).toList()
        assertTrue(events.single() is AgentTurnEvent.NeedsApproval)
        val pending = (events.single() as AgentTurnEvent.NeedsApproval).pending
        assertEquals("send_text_message", pending.call.name)
        assertTrue(pending.preview.contains("chat-1"))
    }

    @Test
    fun `update chat pauses for approval`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                OpenAiCompatClient.Completion.Tools(
                    listOf(AgentToolCall("c1", "update_chat", """{"chatId":"chat-1","muted":true}""")),
                    ""
                )
            },
            executeTool = { _, _ -> error("must not mute without approval") }
        )
        val events = engine.runTurn(provider, emptyList(), "免打扰这个会话", null).toList()
        assertTrue(events.single() is AgentTurnEvent.NeedsApproval)
        assertEquals("update_chat", (events.single() as AgentTurnEvent.NeedsApproval).pending.call.name)
    }

    @Test
    fun `read tool executes then finalizes`() = runBlocking {
        var executed = 0
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "list_chats", "{}")),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Text("你有 1 个会话")
                }
            },
            executeTool = { name, _ ->
                executed++
                assertEquals("list_chats", name)
                "c1\tAlice\thi\t"
            }
        )
        val events = engine.runTurn(provider, emptyList(), "列出会话", null).toList()
        assertEquals(1, executed)
        assertTrue(events.any { it is AgentTurnEvent.ToolFinished && it.name == "list_chats" })
        assertTrue(events.last() is AgentTurnEvent.AssistantFinal)
    }

    @Test
    fun `approved send is executed on resume`() = runBlocking {
        var executed = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ -> OpenAiCompatClient.Completion.Text("已排队") },
            executeTool = { name, args ->
                executed++
                assertEquals("send_text_message", name)
                assertTrue(args.contains("chat-1"))
                "Queued m_1"
            }
        )
        val events = engine.runTurn(
            provider,
            emptyList(),
            "发一句",
            null,
            approvedCall = AgentToolCall("c1", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")
        ).toList()
        assertEquals(1, executed)
        assertTrue(events.any { it is AgentTurnEvent.ToolFinished })
        assertEquals("已排队", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `completion error becomes Failed`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ -> OpenAiCompatClient.Completion.Error("boom") }
        )
        val events = engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals(1, events.size)
        assertTrue(events.single() is AgentTurnEvent.Failed)
        assertEquals("boom", (events.single() as AgentTurnEvent.Failed).message)
    }

    @Test
    fun `unknown tool writes error back and continues`() = runBlocking {
        var executed = 0
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "dump_host_sql", "{}")),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Text("ok")
                }
            },
            executeTool = { _, _ ->
                executed++
                "never"
            }
        )
        val events = engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals(0, executed)
        assertEquals("ok", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `rewrite text goes through model path not host`() = runBlocking {
        var executed = 0
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                when (round) {
                    1 -> OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "rewrite_text", """{"text":"hi","mode":"polish"}""")),
                        ""
                    )
                    2 -> OpenAiCompatClient.Completion.Text(" polished ")
                    else -> OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { _, _ ->
                executed++
                "never"
            }
        )
        val events = engine.runTurn(provider, emptyList(), "润色这句", null).toList()
        assertEquals(0, executed)
        val finished = events.filterIsInstance<AgentTurnEvent.ToolFinished>().single()
        assertEquals("rewrite_text", finished.name)
        assertEquals("polished", finished.result)
        assertEquals("done", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `read tool emits started before finished`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                OpenAiCompatClient.Completion.Tools(
                    listOf(AgentToolCall("c1", "list_chats", "{}")),
                    ""
                )
            },
            executeTool = { _, _ -> "c1\tAlice\thi\t" }
        )
        val events = engine.runTurn(provider, emptyList(), "列出会话", null).take(3).toList()
        assertTrue(events[0] is AgentTurnEvent.ToolStarted)
        assertTrue(events[1] is AgentTurnEvent.ToolFinished)
        assertTrue(events[2] is AgentTurnEvent.ToolStarted)
    }

    @Test
    fun `text completion is trimmed before final`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ -> OpenAiCompatClient.Completion.Text("  你有 1 个会话  ") }
        )
        val events = engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals("你有 1 个会话", (events.single() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `two read calls in one round execute in order`() = runBlocking {
        val order = mutableListOf<String>()
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(
                            AgentToolCall("c1", "list_chats", "{}"),
                            AgentToolCall("c2", "get_chat_history", """{"chatId":"chat-1"}""")
                        ),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { name, _ ->
                order += name
                "result-$name"
            }
        )
        val events = engine.runTurn(provider, emptyList(), "查两个", null).toList()
        assertEquals(listOf("list_chats", "get_chat_history"), order)
        val finished = events.filterIsInstance<AgentTurnEvent.ToolFinished>()
        assertEquals(listOf("list_chats", "get_chat_history"), finished.map { it.name })
        assertEquals("done", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `error after a tool round becomes Failed`() = runBlocking {
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "list_chats", "{}")),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Error("late boom")
                }
            },
            executeTool = { _, _ -> "ok" }
        )
        val events = engine.runTurn(provider, emptyList(), "列出会话", null).toList()
        assertTrue(events.any { it is AgentTurnEvent.ToolFinished })
        assertTrue(events.none { it is AgentTurnEvent.AssistantFinal })
        assertEquals("late boom", (events.last() as AgentTurnEvent.Failed).message)
    }

    @Test
    fun `empty tool calls exhaust rounds without tool events`() = runBlocking {
        var completions = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                completions++
                OpenAiCompatClient.Completion.Tools(emptyList(), "")
            },
            executeTool = { _, _ -> error("no tool call expected") }
        )
        val events = engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals(AgentToolPolicy.MAX_TOOL_ROUNDS, completions)
        assertTrue(events.none { it is AgentTurnEvent.ToolStarted || it is AgentTurnEvent.ToolFinished })
        assertEquals("工具轮次过多，已停止", (events.last() as AgentTurnEvent.Failed).message)
    }

    @Test
    fun `approved call prefill event comes before the model turn`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ -> OpenAiCompatClient.Completion.Text("已排队") },
            executeTool = { _, _ -> "Queued m_1" }
        )
        val events = engine.runTurn(
            provider,
            emptyList(),
            "发一句",
            null,
            approvedCall = AgentToolCall("c1", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")
        ).toList()
        val first = events.first() as AgentTurnEvent.ToolFinished
        assertEquals("send_text_message", first.name)
        assertEquals("Queued m_1", first.result)
        assertEquals("已排队", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `tool rounds exhaust to failed`() = runBlocking {
        var executions = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                OpenAiCompatClient.Completion.Tools(
                    listOf(AgentToolCall("c1", "list_chats", "{}")),
                    ""
                )
            },
            executeTool = { _, _ ->
                executions++
                "ok"
            }
        )
        val events = engine.runTurn(provider, emptyList(), "列出会话", null).toList()
        assertEquals(AgentToolPolicy.MAX_TOOL_ROUNDS, executions)
        assertTrue(events.last() is AgentTurnEvent.Failed)
        assertEquals("工具轮次过多，已停止", (events.last() as AgentTurnEvent.Failed).message)
    }

    @Test
    fun `unknown tool in a round does not block the next read call`() = runBlocking {
        var executed = 0
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(
                            AgentToolCall("c0", "dump_host_sql", "{}"),
                            AgentToolCall("c1", "list_chats", "{}")
                        ),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Text("ok")
                }
            },
            executeTool = { name, _ ->
                executed++
                assertEquals("list_chats", name)
                "ok"
            }
        )
        val events = engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals(1, executed)
        assertEquals(
            listOf("list_chats"),
            events.filterIsInstance<AgentTurnEvent.ToolFinished>().map { it.name }
        )
        assertTrue(events.none { it is AgentTurnEvent.ToolStarted && it.name == "dump_host_sql" })
        assertEquals("ok", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `need-user call after an allowed call stops the round`() = runBlocking {
        var executed = 0
        var completions = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                completions++
                OpenAiCompatClient.Completion.Tools(
                    listOf(
                        AgentToolCall("c1", "list_chats", "{}"),
                        AgentToolCall("c2", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")
                    ),
                    ""
                )
            },
            executeTool = { _, _ ->
                executed++
                "ok"
            }
        )
        val events = engine.runTurn(provider, emptyList(), "发一句", null).toList()
        assertEquals(1, executed)
        assertEquals(1, completions)
        assertEquals(3, events.size)
        assertEquals("list_chats", (events[0] as AgentTurnEvent.ToolStarted).name)
        assertEquals("list_chats", (events[1] as AgentTurnEvent.ToolFinished).name)
        assertEquals("send_text_message", (events[2] as AgentTurnEvent.NeedsApproval).pending.call.name)
        assertTrue(events.none { it is AgentTurnEvent.AssistantFinal || it is AgentTurnEvent.Failed })
    }

    @Test
    fun `blank rewrite text reports error without a model call`() = runBlocking {
        var completions = 0
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                completions++
                round++
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "rewrite_text", """{"text":"   ","mode":"polish"}""")),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { _, _ -> error("rewrite must not reach the host") }
        )
        val events = engine.runTurn(provider, emptyList(), "润色这句", null).toList()
        assertEquals(2, completions)
        val finished = events.filterIsInstance<AgentTurnEvent.ToolFinished>().single()
        assertEquals("rewrite_text", finished.name)
        assertEquals("Error: text required", finished.result)
        assertEquals("done", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `rewrite model error surfaces as the tool result`() = runBlocking {
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                when (round) {
                    1 -> OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "rewrite_text", """{"text":"hi","mode":"polish"}""")),
                        ""
                    )
                    2 -> OpenAiCompatClient.Completion.Error("model down")
                    else -> OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { _, _ -> error("rewrite must not reach the host") }
        )
        val events = engine.runTurn(provider, emptyList(), "润色这句", null).toList()
        assertEquals(3, round)
        val finished = events.filterIsInstance<AgentTurnEvent.ToolFinished>().single()
        assertEquals("rewrite_text", finished.name)
        assertEquals("model down", finished.result)
        assertEquals("done", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `history system messages are filtered and user text is capped`() = runBlocking {
        var captured: List<AgentChatMessage> = emptyList()
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                if (captured.isEmpty()) captured = messages.toList()
                OpenAiCompatClient.Completion.Text("done")
            },
            executeTool = { _, _ -> error("no tool call expected") }
        )
        val history = listOf(
            AgentChatMessage(role = "system", content = "sys-old"),
            AgentChatMessage(role = "user", content = "old")
        )
        val events = engine.runTurn(provider, history, "x".repeat(5000), null).toList()
        assertEquals("done", (events.single() as AgentTurnEvent.AssistantFinal).text)
        assertEquals("system", captured.first().role)
        assertTrue(captured.none { it.content == "sys-old" })
        assertTrue(captured.any { it.role == "user" && it.content == "old" })
        val last = captured.last()
        assertEquals("user", last.role)
        assertEquals(4000, last.content.length)
        assertEquals("x".repeat(4000), last.content)
    }


    @Test
    fun `unknown tool error text is fed back to the next model round`() = runBlocking {
        var round = 0
        var roundTwoMessages: List<AgentChatMessage> = emptyList()
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                round++
                if (round == 2) roundTwoMessages = messages.toList()
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "dump_host_sql", "{}")),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Text("ok")
                }
            },
            executeTool = { _, _ -> error("unknown tool must not execute") }
        )
        val events = engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals("ok", (events.last() as AgentTurnEvent.AssistantFinal).text)
        val toolMessage = roundTwoMessages.single { it.role == "tool" }
        assertEquals("c1", toolMessage.toolCallId)
        assertEquals("dump_host_sql", toolMessage.toolName)
        assertEquals("Error: unknown tool dump_host_sql", toolMessage.content)
    }

    @Test
    fun `rewrite text emits finished without started`() = runBlocking {
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                when (round) {
                    1 -> OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "rewrite_text", """{"text":"hi","mode":"polish"}""")),
                        ""
                    )
                    2 -> OpenAiCompatClient.Completion.Text(" polished ")
                    else -> OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { _, _ -> error("rewrite must not reach the host") }
        )
        val events = engine.runTurn(provider, emptyList(), "润色这句", null).toList()
        assertTrue(events.none { it is AgentTurnEvent.ToolStarted })
        assertEquals("polished", events.filterIsInstance<AgentTurnEvent.ToolFinished>().single().result)
        assertEquals("done", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `tool result is fed back as a tool message to the next round`() = runBlocking {
        var round = 0
        var roundTwoMessages: List<AgentChatMessage> = emptyList()
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                round++
                if (round == 2) roundTwoMessages = messages.toList()
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "list_chats", "{}")),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { _, _ -> "c1\tAlice\thi\t" }
        )
        engine.runTurn(provider, emptyList(), "列出会话", null).toList()
        val toolMessage = roundTwoMessages.single { it.role == "tool" }
        assertEquals("c1", toolMessage.toolCallId)
        assertEquals("list_chats", toolMessage.toolName)
        assertEquals("c1\tAlice\thi\t", toolMessage.content)
        val assistantMessage = roundTwoMessages.single { it.role == "assistant" }
        assertEquals(listOf("c1"), assistantMessage.toolCalls.map { it.id })
    }

    @Test
    fun `approved call prefill appends assistant and tool messages before the model turn`() = runBlocking {
        var captured: List<AgentChatMessage> = emptyList()
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                if (captured.isEmpty()) captured = messages.toList()
                OpenAiCompatClient.Completion.Text("已排队")
            },
            executeTool = { _, _ -> "Queued m_1" }
        )
        engine.runTurn(
            provider,
            emptyList(),
            "发一句",
            null,
            approvedCall = AgentToolCall("c1", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")
        ).toList()
        val assistantMessage = captured.single { it.role == "assistant" }
        assertEquals("c1", assistantMessage.toolCalls.single().id)
        assertEquals("send_text_message", assistantMessage.toolCalls.single().name)
        val toolMessage = captured.single { it.role == "tool" }
        assertEquals("c1", toolMessage.toolCallId)
        assertEquals("Queued m_1", toolMessage.content)
    }

    @Test
    fun `history window keeps only the last twenty four messages`() = runBlocking {
        var captured: List<AgentChatMessage> = emptyList()
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                if (captured.isEmpty()) captured = messages.toList()
                OpenAiCompatClient.Completion.Text("done")
            },
            executeTool = { _, _ -> error("no tool call expected") }
        )
        val history = (0 until 30).map { AgentChatMessage(role = "user", content = "h$it") }
        engine.runTurn(provider, history, "now", null).toList()
        assertEquals(26, captured.size)
        assertEquals("h6", captured[1].content)
        assertEquals("h29", captured[24].content)
        assertEquals("now", captured[25].content)
    }

    @Test
    fun `approved call is followed by a read tool round before final`() = runBlocking {
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c2", "list_chats", "{}")),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { name, args ->
                when (name) {
                    "send_text_message" -> {
                        assertTrue(args.contains("chat-1"))
                        "Queued m_1"
                    }
                    "list_chats" -> "ok"
                    else -> error("unexpected tool $name")
                }
            }
        )
        val events = engine.runTurn(
            provider,
            emptyList(),
            "发一句",
            null,
            approvedCall = AgentToolCall("c1", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")
        ).toList()
        assertEquals("send_text_message", (events[0] as AgentTurnEvent.ToolFinished).name)
        assertEquals("Queued m_1", (events[0] as AgentTurnEvent.ToolFinished).result)
        assertEquals("list_chats", (events[1] as AgentTurnEvent.ToolStarted).name)
        assertEquals("list_chats", (events[2] as AgentTurnEvent.ToolFinished).name)
        assertEquals("done", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `blank user text still reaches the model as an empty user message`() = runBlocking {
        var captured: List<AgentChatMessage> = emptyList()
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                if (captured.isEmpty()) captured = messages.toList()
                OpenAiCompatClient.Completion.Text("done")
            },
            executeTool = { _, _ -> error("no tool call expected") }
        )
        val events = engine.runTurn(provider, emptyList(), "", null).toList()
        assertEquals("done", (events.single() as AgentTurnEvent.AssistantFinal).text)
        val last = captured.last()
        assertEquals("user", last.role)
        assertEquals("", last.content)
    }

    @Test
    fun `rewrite text without mode defaults to polish and finishes`() = runBlocking {
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                when (round) {
                    1 -> OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "rewrite_text", """{"text":"hi"}""")),
                        ""
                    )
                    2 -> OpenAiCompatClient.Completion.Text(" polished ")
                    else -> OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { _, _ -> error("rewrite must not reach the host") }
        )
        val events = engine.runTurn(provider, emptyList(), "润色这句", null).toList()
        val finished = events.filterIsInstance<AgentTurnEvent.ToolFinished>().single()
        assertEquals("rewrite_text", finished.name)
        assertEquals("polished", finished.result)
        assertEquals("done", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `two unknown tools in one round both feed errors back`() = runBlocking {
        var round = 0
        var roundTwoMessages: List<AgentChatMessage> = emptyList()
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                round++
                if (round == 2) roundTwoMessages = messages.toList()
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(
                            AgentToolCall("c0", "dump_host_sql", "{}"),
                            AgentToolCall("c1", "drop_tables", "{}")
                        ),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Text("ok")
                }
            },
            executeTool = { _, _ -> error("unknown tool must not execute") }
        )
        val events = engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals("ok", (events.last() as AgentTurnEvent.AssistantFinal).text)
        assertTrue(events.none { it is AgentTurnEvent.ToolStarted || it is AgentTurnEvent.ToolFinished })
        val toolMessages = roundTwoMessages.filter { it.role == "tool" }
        assertEquals(2, toolMessages.size)
        assertEquals("Error: unknown tool dump_host_sql", toolMessages[0].content)
        assertEquals("Error: unknown tool drop_tables", toolMessages[1].content)
        assertEquals("c0", toolMessages[0].toolCallId)
        assertEquals("c1", toolMessages[1].toolCallId)
    }

    @Test
    fun `send approval pending carries the original call id and arguments`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                OpenAiCompatClient.Completion.Tools(
                    listOf(AgentToolCall("c9", "send_text_message", """{"chatId":"chat-9","text":"hello"}""")),
                    ""
                )
            },
            executeTool = { _, _ -> error("must not execute send without approval") }
        )
        val events = engine.runTurn(provider, emptyList(), "发一句", null).toList()
        assertEquals(1, events.size)
        val pending = (events.single() as AgentTurnEvent.NeedsApproval).pending
        assertEquals("c9", pending.call.id)
        assertEquals("send_text_message", pending.call.name)
        assertTrue(pending.call.argumentsJson.contains("chat-9"))
        assertTrue(pending.call.argumentsJson.contains("hello"))
    }

}

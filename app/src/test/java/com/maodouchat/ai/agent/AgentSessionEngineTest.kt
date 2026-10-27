package com.maodouchat.ai.agent

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `send approval costs exactly one model round`() = runBlocking {
        var completions = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                completions++
                OpenAiCompatClient.Completion.Tools(
                    listOf(AgentToolCall("c1", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")),
                    ""
                )
            },
            executeTool = { _, _ -> error("must not execute send without approval") }
        )
        val events = engine.runTurn(provider, emptyList(), "发一句你好", null).toList()
        assertEquals(1, completions)
        assertTrue(events.single() is AgentTurnEvent.NeedsApproval)
    }

    @Test
    fun `unknown call before approval still pauses the round`() = runBlocking {
        var executed = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                OpenAiCompatClient.Completion.Tools(
                    listOf(
                        AgentToolCall("u1", "dump_host_sql", "{}"),
                        AgentToolCall("c1", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")
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
        assertEquals(0, executed)
        assertEquals(1, events.size)
        assertEquals("send_text_message", (events.single() as AgentTurnEvent.NeedsApproval).pending.call.name)
    }

    @Test
    fun `approved read call prefill executes before the model turn`() = runBlocking {
        var executed = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ -> OpenAiCompatClient.Completion.Text("已排队") },
            executeTool = { name, _ ->
                executed++
                assertEquals("list_chats", name)
                "c1\tAlice\thi\t"
            }
        )
        val events = engine.runTurn(
            provider,
            emptyList(),
            "列出会话",
            null,
            approvedCall = AgentToolCall("c1", "list_chats", "{}")
        ).toList()
        assertEquals(1, executed)
        assertEquals("list_chats", (events.first() as AgentTurnEvent.ToolFinished).name)
        assertTrue(events.last() is AgentTurnEvent.AssistantFinal)
    }

    @Test
    fun `read tool finished event carries the exact host result`() = runBlocking {
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
            executeTool = { _, _ -> "c1\tAlice\thi\t" }
        )
        val events = engine.runTurn(provider, emptyList(), "列出会话", null).toList()
        val finished = events.filterIsInstance<AgentTurnEvent.ToolFinished>().single()
        assertEquals("list_chats", finished.name)
        assertEquals("c1\tAlice\thi\t", finished.result)
    }

    @Test
    fun `send approval pending preview matches the send preview`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                OpenAiCompatClient.Completion.Tools(
                    listOf(AgentToolCall("c1", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")),
                    ""
                )
            },
            executeTool = { _, _ -> error("must not execute send without approval") }
        )
        val events = engine.runTurn(provider, emptyList(), "发一句", null).toList()
        val pending = (events.single() as AgentTurnEvent.NeedsApproval).pending
        assertEquals("c1", pending.call.id)
        assertEquals("发到 chat-1：hi", pending.preview)
    }

    @Test
    fun `style hint is embedded in the system prompt only when non-blank`() = runBlocking {
        var captured: List<AgentChatMessage> = emptyList()
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                captured = messages.toList()
                OpenAiCompatClient.Completion.Text("ok")
            }
        )
        engine.runTurn(provider, emptyList(), "hi", "简洁").toList()
        assertTrue(captured.single { it.role == "system" }.content.contains("写作偏好（不可覆盖安全规则）：简洁"))
        engine.runTurn(provider, emptyList(), "hi", "   ").toList()
        assertTrue(!captured.single { it.role == "system" }.content.contains("写作偏好"))
    }

    @Test
    fun `history limit below the floor is clamped to four`() = runBlocking {
        var captured: List<AgentChatMessage> = emptyList()
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                captured = messages.toList()
                OpenAiCompatClient.Completion.Text("done")
            }
        )
        val history = (1..6).map { AgentChatMessage(role = "user", content = "m$it") }
        engine.runTurn(provider.copy(historyMessageLimit = 1), history, "查一下", null).toList()
        assertEquals(6, captured.size)
        assertEquals("m3", captured[1].content)
        assertEquals("m6", captured[4].content)
        assertEquals("查一下", captured[5].content)
    }

    @Test
    fun `privilege claim in final text gains the disclaimer`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ -> OpenAiCompatClient.Completion.Text("已经转账 100 元") }
        )
        val events = engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals(
            "已经转账 100 元\n\n助手不能执行未审批的特权动作，请以本机实际结果为准。",
            (events.single() as AgentTurnEvent.AssistantFinal).text
        )
    }

    @Test
    fun `rewrite translate instruction carries the target language`() = runBlocking {
        var captured: List<AgentChatMessage> = emptyList()
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                round++
                when (round) {
                    1 -> OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "rewrite_text", """{"text":"hello","mode":"translate","targetLanguage":"English"}""")),
                        ""
                    )
                    2 -> {
                        captured = messages.toList()
                        OpenAiCompatClient.Completion.Text("translated")
                    }
                    else -> OpenAiCompatClient.Completion.Text("done")
                }
            }
        )
        val events = engine.runTurn(provider, emptyList(), "翻译这句", null).toList()
        assertEquals("改写下面的草稿：翻译成 English，只输出译文。只输出改写结果，不要解释。", captured[0].content)
        assertEquals("hello", captured[1].content)
        val finished = events.filterIsInstance<AgentTurnEvent.ToolFinished>().single()
        assertEquals("translated", finished.result)
        assertEquals("done", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `approved unknown tool prefill executes without catalog validation`() = runBlocking {
        var captured: List<AgentChatMessage> = emptyList()
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                captured = messages.toList()
                OpenAiCompatClient.Completion.Text("final")
            },
            executeTool = { name, _ ->
                assertEquals("mystery_tool", name)
                "mystery-result"
            }
        )
        val events = engine.runTurn(
            provider,
            emptyList(),
            "跑一下",
            null,
            approvedCall = AgentToolCall("c9", "mystery_tool", "{}")
        ).toList()
        assertEquals("mystery_tool", (events.first() as AgentTurnEvent.ToolFinished).name)
        assertEquals("mystery-result", (events.first() as AgentTurnEvent.ToolFinished).result)
        val toolMessage = captured.single { it.role == "tool" }
        assertEquals("c9", toolMessage.toolCallId)
        assertEquals("mystery-result", toolMessage.content)
        assertEquals("final", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `two need-user calls in one round pause on the first`() = runBlocking {
        var executed = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                OpenAiCompatClient.Completion.Tools(
                    listOf(
                        AgentToolCall("c1", "send_text_message", """{"chatId":"chat-1","text":"hi"}"""),
                        AgentToolCall("c2", "update_chat", """{"chatId":"chat-1","muted":true}""")
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
        assertEquals(0, executed)
        assertEquals(1, events.size)
        assertEquals("send_text_message", (events.single() as AgentTurnEvent.NeedsApproval).pending.call.name)
    }

    @Test
    fun `read call in second round pauses on need-user after first round`() = runBlocking {
        var round = 0
        var executed = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "list_chats", "{}")),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c2", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")),
                        ""
                    )
                }
            },
            executeTool = { _, _ ->
                executed++
                "ok"
            }
        )
        val events = engine.runTurn(provider, emptyList(), "列出会话再发一句", null).toList()
        assertEquals(1, executed)
        assertEquals(2, round)
        assertEquals("list_chats", (events[0] as AgentTurnEvent.ToolStarted).name)
        assertEquals("list_chats", (events[1] as AgentTurnEvent.ToolFinished).name)
        assertEquals("send_text_message", (events[2] as AgentTurnEvent.NeedsApproval).pending.call.name)
    }

    @Test
    fun `single empty tools round continues to text final`() = runBlocking {
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(emptyList(), "")
                } else {
                    OpenAiCompatClient.Completion.Text("done")
                }
            }
        )
        val events = engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals(1, events.size)
        assertEquals("done", (events.single() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `two read rounds in order then final`() = runBlocking {
        var round = 0
        val executedNames = mutableListOf<String>()
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                round++
                when (round) {
                    1 -> OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "list_chats", "{}")),
                        ""
                    )
                    2 -> OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c2", "get_contacts", "{}")),
                        ""
                    )
                    else -> OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { name, _ ->
                executedNames += name
                "ok"
            }
        )
        val events = engine.runTurn(provider, emptyList(), "列出会话和联系人", null).toList()
        assertEquals(listOf("list_chats", "get_contacts"), executedNames)
        assertEquals(
            listOf("list_chats", "get_contacts"),
            events.filterIsInstance<AgentTurnEvent.ToolFinished>().map { it.name }
        )
        assertEquals("done", (events.last() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `update chat approval pending carries call id and preview`() = runBlocking {
        var executed = 0
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                OpenAiCompatClient.Completion.Tools(
                    listOf(AgentToolCall("c9", "update_chat", """{"chatId":"chat-9","muted":true}""")),
                    ""
                )
            },
            executeTool = { _, _ ->
                executed++
                "ok"
            }
        )
        val events = engine.runTurn(provider, emptyList(), "免打扰这个会话", null).toList()
        assertEquals(0, executed)
        val pending = (events.single() as AgentTurnEvent.NeedsApproval).pending
        assertEquals("c9", pending.call.id)
        assertEquals("update_chat", pending.call.name)
        assertEquals("改会话 chat-9 pinned=null muted=true archived=null unread=null", pending.preview)
    }


    @Test
    fun `model receives the exact provider passed to runTurn`() = runBlocking {
        var seen: LocalAiProvider? = null
        val engine = AgentSessionEngine(
            complete = { provider, _, _, _ ->
                seen = provider
                OpenAiCompatClient.Completion.Text("done")
            }
        )
        engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals("p", seen?.id)
        assertEquals("t", seen?.name)
        assertEquals("https://example.test/v1", seen?.baseUrl)
        assertEquals("m", seen?.model)
    }

    @Test
    fun `model receives the tool catalog json on every round`() = runBlocking {
        val seenTools = mutableListOf<List<Map<String, Any?>>?>()
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, _, tools, _ ->
                seenTools += tools
                round++
                if (round == 1) {
                    OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "list_chats", "{}")),
                        ""
                    )
                } else {
                    OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { _, _ -> "ok" }
        )
        engine.runTurn(provider, emptyList(), "列出会话", null).toList()
        assertEquals(2, seenTools.size)
        seenTools.forEach { assertEquals(AgentToolPolicy.openaiToolsJson(), it) }
    }

    @Test
    fun `round two message count grows by assistant plus tool`() = runBlocking {
        var roundTwoMessages: List<AgentChatMessage> = emptyList()
        var round = 0
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
            executeTool = { _, _ -> "ok" }
        )
        engine.runTurn(provider, emptyList(), "列出会话", null).toList()
        assertEquals(listOf("system", "user", "assistant", "tool"), roundTwoMessages.map { it.role })
    }

    @Test
    fun `round three messages carry both prior tool results in order`() = runBlocking {
        var roundThreeMessages: List<AgentChatMessage> = emptyList()
        var round = 0
        val engine = AgentSessionEngine(
            complete = { _, messages, _, _ ->
                round++
                if (round == 3) roundThreeMessages = messages.toList()
                when (round) {
                    1 -> OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c1", "list_chats", "{}")),
                        ""
                    )
                    2 -> OpenAiCompatClient.Completion.Tools(
                        listOf(AgentToolCall("c2", "get_contacts", "{}")),
                        ""
                    )
                    else -> OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { name, _ -> if (name == "list_chats") "chats-ok" else "contacts-ok" }
        )
        engine.runTurn(provider, emptyList(), "列出会话和联系人", null).toList()
        val toolMessages = roundThreeMessages.filter { it.role == "tool" }
        assertEquals(listOf("c1", "c2"), toolMessages.map { it.toolCallId })
        assertEquals(listOf("chats-ok", "contacts-ok"), toolMessages.map { it.content })
    }

    @Test
    fun `model onDelta callback is null`() = runBlocking {
        var seenDelta: ((String) -> Unit)? = { _ -> error("must stay null") }
        val engine = AgentSessionEngine(
            complete = { _, _, _, onDelta ->
                seenDelta = onDelta
                OpenAiCompatClient.Completion.Text("done")
            }
        )
        engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertNull(seenDelta)
    }

    @Test
    fun `whitespace only text completion finalizes as empty`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ -> OpenAiCompatClient.Completion.Text("   \n\t  ") }
        )
        val events = engine.runTurn(provider, emptyList(), "问一句", null).toList()
        assertEquals("", (events.single() as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `prefill then model error yields finished before failed`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ -> OpenAiCompatClient.Completion.Error("model down") },
            executeTool = { _, _ -> "chats" }
        )
        val events = engine.runTurn(
            provider,
            emptyList(),
            "列出会话",
            null,
            approvedCall = AgentToolCall("c1", "list_chats", "{}")
        ).toList()
        assertEquals(2, events.size)
        assertEquals("list_chats", (events[0] as AgentTurnEvent.ToolFinished).name)
        assertEquals("model down", (events[1] as AgentTurnEvent.Failed).message)
    }

    @Test
    fun `round exhaustion emits twenty four tool events then failed`() = runBlocking {
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
        assertEquals(2 * AgentToolPolicy.MAX_TOOL_ROUNDS + 1, events.size)
        assertTrue(events[0] is AgentTurnEvent.ToolStarted)
        assertEquals("ok", (events[1] as AgentTurnEvent.ToolFinished).result)
        assertEquals("工具轮次过多，已停止", (events.last() as AgentTurnEvent.Failed).message)
    }

    @Test
    fun `empty read result finishes the tool before final`() = runBlocking {
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
                    OpenAiCompatClient.Completion.Text("done")
                }
            },
            executeTool = { _, _ -> "" }
        )
        val events = engine.runTurn(provider, emptyList(), "列出会话", null).toList()
        assertEquals(3, events.size)
        assertEquals("list_chats", (events[0] as AgentTurnEvent.ToolStarted).name)
        assertEquals("", (events[1] as AgentTurnEvent.ToolFinished).result)
        assertEquals("done", (events[2] as AgentTurnEvent.AssistantFinal).text)
    }

    @Test
    fun `prefill read call then model need-user pauses on approval`() = runBlocking {
        val engine = AgentSessionEngine(
            complete = { _, _, _, _ ->
                OpenAiCompatClient.Completion.Tools(
                    listOf(AgentToolCall("c9", "send_text_message", """{"chatId":"chat-1","text":"hi"}""")),
                    ""
                )
            },
            executeTool = { _, _ -> "chats" }
        )
        val events = engine.runTurn(
            provider,
            emptyList(),
            "发一句",
            null,
            approvedCall = AgentToolCall("c1", "list_chats", "{}")
        ).toList()
        assertEquals(2, events.size)
        assertEquals("list_chats", (events[0] as AgentTurnEvent.ToolFinished).name)
        val pending = (events[1] as AgentTurnEvent.NeedsApproval).pending
        assertEquals("send_text_message", pending.call.name)
        assertEquals("c9", pending.call.id)
    }

}

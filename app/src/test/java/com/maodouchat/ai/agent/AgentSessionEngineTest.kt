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
}

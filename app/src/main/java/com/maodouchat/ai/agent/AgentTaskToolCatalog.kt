package com.maodouchat.ai.agent

internal val listDraftsToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_drafts",
    description = "List this account's unsent chat drafts.",
    parameters = emptyMap(),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val getDraftToolSpec = AgentToolPolicy.ToolSpec(
    name = "get_draft",
    description = "Read the unsent draft for one chat.",
    parameters = mapOf("chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Local chat id")),
    required = listOf("chatId"),
    risk = AgentToolPolicy.Risk.READ
)

internal val listLocalTasksToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_local_tasks",
    description = "List on-device AI task reminders.",
    parameters = mapOf("limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val listMissedCallsToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_missed_calls",
    description = "List recent missed calls stored on this device.",
    parameters = mapOf("limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max rows, default 12, max 30")),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val listNotificationsToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_notifications",
    description = "List in-app notification-center items (messages, missed calls, posts, tasks).",
    parameters = mapOf("limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val createLocalTaskToolSpec = AgentToolPolicy.ToolSpec(
    name = "create_local_task",
    description = "Create a local AI task reminder (not a chat message).",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Chat to attach the task to"),
        "title" to AgentToolPolicy.ToolSpec.Parameter("string", "Task title"),
        "dueText" to AgentToolPolicy.ToolSpec.Parameter("string", "Human due date text")
    ),
    required = listOf("chatId", "title"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val completeLocalTaskToolSpec = AgentToolPolicy.ToolSpec(
    name = "complete_local_task",
    description = "Mark a local AI task completed or not. Requires approval.",
    parameters = mapOf(
        "taskId" to AgentToolPolicy.ToolSpec.Parameter("string", "Task id"),
        "completed" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=done")
    ),
    required = listOf("taskId", "completed"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val deleteLocalTaskToolSpec = AgentToolPolicy.ToolSpec(
    name = "delete_local_task",
    description = "Delete a local AI task reminder. Requires approval.",
    parameters = mapOf("taskId" to AgentToolPolicy.ToolSpec.Parameter("string", "Task id")),
    required = listOf("taskId"),
    risk = AgentToolPolicy.Risk.WRITE
)

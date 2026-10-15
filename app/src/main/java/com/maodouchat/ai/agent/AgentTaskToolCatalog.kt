package com.maodouchat.ai.agent

internal val listDraftsToolSpec = ToolSpec(
    name = "list_drafts",
    description = "List this account's unsent chat drafts.",
    parameters = emptyMap(),
    required = emptyList(),
    risk = Risk.READ
)

internal val getDraftToolSpec = ToolSpec(
    name = "get_draft",
    description = "Read the unsent draft for one chat.",
    parameters = mapOf("chatId" to ToolSpec.Parameter("string", "Local chat id")),
    required = listOf("chatId"),
    risk = Risk.READ
)

internal val listLocalTasksToolSpec = ToolSpec(
    name = "list_local_tasks",
    description = "List on-device AI task reminders.",
    parameters = mapOf("limit" to ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
    required = emptyList(),
    risk = Risk.READ
)

internal val listMissedCallsToolSpec = ToolSpec(
    name = "list_missed_calls",
    description = "List recent missed calls stored on this device.",
    parameters = mapOf("limit" to ToolSpec.Parameter("integer", "Max rows, default 12, max 30")),
    required = emptyList(),
    risk = Risk.READ
)

internal val listNotificationsToolSpec = ToolSpec(
    name = "list_notifications",
    description = "List in-app notification-center items (messages, missed calls, posts, tasks).",
    parameters = mapOf("limit" to ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
    required = emptyList(),
    risk = Risk.READ
)

internal val createLocalTaskToolSpec = ToolSpec(
    name = "create_local_task",
    description = "Create a local AI task reminder (not a chat message).",
    parameters = mapOf(
        "chatId" to ToolSpec.Parameter("string", "Chat to attach the task to"),
        "title" to ToolSpec.Parameter("string", "Task title"),
        "dueText" to ToolSpec.Parameter("string", "Human due date text")
    ),
    required = listOf("chatId", "title"),
    risk = Risk.WRITE
)

internal val completeLocalTaskToolSpec = ToolSpec(
    name = "complete_local_task",
    description = "Mark a local AI task completed or not. Requires approval.",
    parameters = mapOf(
        "taskId" to ToolSpec.Parameter("string", "Task id"),
        "completed" to ToolSpec.Parameter("boolean", "true=done")
    ),
    required = listOf("taskId", "completed"),
    risk = Risk.WRITE
)

internal val deleteLocalTaskToolSpec = ToolSpec(
    name = "delete_local_task",
    description = "Delete a local AI task reminder. Requires approval.",
    parameters = mapOf("taskId" to ToolSpec.Parameter("string", "Task id")),
    required = listOf("taskId"),
    risk = Risk.WRITE
)

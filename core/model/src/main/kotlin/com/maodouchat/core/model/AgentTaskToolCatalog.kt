package com.maodouchat.core.model

import com.maodouchat.core.model.Parameter
import com.maodouchat.core.model.Risk
import com.maodouchat.core.model.ToolSpec

val listDraftsToolSpec = ToolSpec(
    name = "list_drafts",
    description = "List this account's unsent chat drafts.",
    parameters = emptyMap(),
    required = emptyList(),
    risk = Risk.READ
)

val getDraftToolSpec = ToolSpec(
    name = "get_draft",
    description = "Read the unsent draft for one chat.",
    parameters = mapOf("chatId" to Parameter("string", "Local chat id")),
    required = listOf("chatId"),
    risk = Risk.READ
)

val listLocalTasksToolSpec = ToolSpec(
    name = "list_local_tasks",
    description = "List on-device AI task reminders.",
    parameters = mapOf("limit" to Parameter("integer", "Max rows, default 20, max 40")),
    required = emptyList(),
    risk = Risk.READ
)

val listMissedCallsToolSpec = ToolSpec(
    name = "list_missed_calls",
    description = "List recent missed calls stored on this device.",
    parameters = mapOf("limit" to Parameter("integer", "Max rows, default 12, max 30")),
    required = emptyList(),
    risk = Risk.READ
)

val listNotificationsToolSpec = ToolSpec(
    name = "list_notifications",
    description = "List in-app notification-center items (messages, missed calls, posts, tasks).",
    parameters = mapOf("limit" to Parameter("integer", "Max rows, default 20, max 40")),
    required = emptyList(),
    risk = Risk.READ
)

val createLocalTaskToolSpec = ToolSpec(
    name = "create_local_task",
    description = "Create a local AI task reminder (not a chat message).",
    parameters = mapOf(
        "chatId" to Parameter("string", "Chat to attach the task to"),
        "title" to Parameter("string", "Task title"),
        "dueText" to Parameter("string", "Human due date text")
    ),
    required = listOf("chatId", "title"),
    risk = Risk.WRITE
)

val completeLocalTaskToolSpec = ToolSpec(
    name = "complete_local_task",
    description = "Mark a local AI task completed or not. Requires approval.",
    parameters = mapOf(
        "taskId" to Parameter("string", "Task id"),
        "completed" to Parameter("boolean", "true=done")
    ),
    required = listOf("taskId", "completed"),
    risk = Risk.WRITE
)

val deleteLocalTaskToolSpec = ToolSpec(
    name = "delete_local_task",
    description = "Delete a local AI task reminder. Requires approval.",
    parameters = mapOf("taskId" to Parameter("string", "Task id")),
    required = listOf("taskId"),
    risk = Risk.WRITE
)

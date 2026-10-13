package com.maodouchat.network

/**
 * Per-chat incremental sync cursor (timestamp + optional message id for same-ms stability).
 * A single global cursor would advance when opening a newer chat and permanently skip
 * older un-synced messages in other chats.
 */
data class SyncCursor(val timestampMs: Long, val messageId: String = "")

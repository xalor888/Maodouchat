package com.maodouchat.explore.repository

import java.util.concurrent.ConcurrentHashMap

interface PostMutationJournal {
    fun record(entry: JournalEntry)
    fun commit(entryId: String)
    fun rollback(entryId: String): MutationSnapshot?
    fun getPendingEntries(): List<JournalEntry>
    fun clear()
}

class InMemoryPostMutationJournal : PostMutationJournal {
    private val entries = ConcurrentHashMap<String, JournalEntry>()

    override fun record(entry: JournalEntry) {
        entries[entry.id] = entry
    }

    override fun commit(entryId: String) {
        entries.remove(entryId)
    }

    override fun rollback(entryId: String): MutationSnapshot? {
        val entry = entries.remove(entryId) ?: return null
        return entry.snapshot
    }

    override fun getPendingEntries(): List<JournalEntry> =
        entries.values.sortedBy { it.timestamp }

    override fun clear() {
        entries.clear()
    }
}

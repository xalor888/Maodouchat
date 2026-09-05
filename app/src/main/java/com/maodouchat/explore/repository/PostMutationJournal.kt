package com.maodouchat.explore.repository

import com.maodouchat.network.PostCommentDto
import com.maodouchat.network.PostDto
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class MutationType {
    LIKE_POST,
    LIKE_COMMENT,
    DELETE_POST,
    DELETE_COMMENT,
    EDIT_POST,
    EDIT_COMMENT
}

sealed class MutationSnapshot {
    data class PostLike(
        val postId: String,
        val wasLiked: Boolean,
        val previousCount: Int
    ) : MutationSnapshot()

    data class CommentLike(
        val commentId: String,
        val postId: String,
        val wasLiked: Boolean,
        val previousCount: Int
    ) : MutationSnapshot()

    data class PostDeletion(
        val post: PostDto,
        val originalIndex: Int
    ) : MutationSnapshot()

    data class CommentDeletion(
        val comment: PostCommentDto,
        val originalIndex: Int
    ) : MutationSnapshot()

    data class PostEdit(
        val postId: String,
        val previousContent: String,
        val previousVisibility: String
    ) : MutationSnapshot()

    data class CommentEdit(
        val commentId: String,
        val previousContent: String
    ) : MutationSnapshot()
}

data class JournalEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: MutationType,
    val targetId: String,
    val snapshot: MutationSnapshot,
    val timestamp: Long = System.currentTimeMillis()
)

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

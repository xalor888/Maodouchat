package com.maodouchat.server

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.CommentLikes
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.PostRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostMetaBlockedVisibilityTest {

    @Test
    fun `blocked user likes and comments do not count toward visible post meta`() {
        val dbUrl =
            "jdbc:h2:mem:post-meta-block-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()

        val now = System.currentTimeMillis()
        transaction {
            listOf("u1", "u2", "u3").forEach { id ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = id
                    it[Users.email] = "$id@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
            BlockedUsers.insert {
                it[BlockedUsers.blockerId] = "u1"
                it[BlockedUsers.blockedId] = "u2"
            }
            Posts.insert {
                it[Posts.id] = "post_1"
                it[Posts.authorId] = "u3"
                it[Posts.content] = "visible"
                it[Posts.imageUrls] = "[]"
                it[Posts.visibility] = "PUBLIC"
                it[Posts.createdAt] = now
            }
            Posts.insert {
                it[Posts.id] = "post_2"
                it[Posts.authorId] = "u2"
                it[Posts.content] = "blocked"
                it[Posts.imageUrls] = "[]"
                it[Posts.visibility] = "PUBLIC"
                it[Posts.createdAt] = now
            }
            PostLikes.insert {
                it[PostLikes.postId] = "post_1"
                it[PostLikes.userId] = "u2"
                it[PostLikes.createdAt] = now
            }
            PostLikes.insert {
                it[PostLikes.postId] = "post_1"
                it[PostLikes.userId] = "u3"
                it[PostLikes.createdAt] = now
            }
            PostComments.insert {
                it[PostComments.id] = "comment_1"
                it[PostComments.postId] = "post_1"
                it[PostComments.authorId] = "u2"
                it[PostComments.content] = "blocked"
                it[PostComments.createdAt] = now
            }
            PostComments.insert {
                it[PostComments.id] = "comment_2"
                it[PostComments.postId] = "post_1"
                it[PostComments.authorId] = "u3"
                it[PostComments.content] = "visible"
                it[PostComments.createdAt] = now
            }
            PostComments.insert {
                it[PostComments.id] = "comment_3"
                it[PostComments.postId] = "post_1"
                it[PostComments.authorId] = "u3"
                it[PostComments.content] = "reply"
                it[PostComments.parentId] = "comment_2"
                it[PostComments.createdAt] = now
            }
            CommentLikes.insert {
                it[CommentLikes.commentId] = "comment_2"
                it[CommentLikes.userId] = "u2"
                it[CommentLikes.createdAt] = now
            }
            CommentLikes.insert {
                it[CommentLikes.commentId] = "comment_2"
                it[CommentLikes.userId] = "u3"
                it[CommentLikes.createdAt] = now
            }
        }

        val post = PostRepository().getPostById("post_1", "u1")!!
        assertEquals(1, post.likeCount)
        assertEquals(2, post.commentCount)

        val feed = PostRepository().getFeed("u1", limit = 50)
        assertEquals(listOf("post_1"), feed.map { it.id })
        assertEquals(1, feed.single().likeCount)
        assertEquals(2, feed.single().commentCount)

        val comments = PostRepository().getComments("post_1", "u1", limit = 50)!!
        assertEquals(setOf("comment_2", "comment_3"), comments.map { it.id }.toSet())
        assertEquals(1, comments.first { it.id == "comment_2" }.likeCount)
        assertEquals(1, PostRepository().getComment("comment_2", "u1")?.likeCount)

        assertTrue(PostRepository().deleteCommentForModeration("comment_2"))
        assertEquals(null, PostRepository().getComment("comment_3", "u1")?.parentId)
    }
}

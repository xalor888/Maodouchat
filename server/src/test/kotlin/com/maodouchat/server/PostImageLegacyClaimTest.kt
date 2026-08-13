package com.maodouchat.server

import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.PostRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PostImageLegacyClaimTest {

    @Test
    fun `legacy post without claim row still blocks image reuse`() {
        val storageDir = Files.createTempDirectory("maodou-legacy-post-claims").toFile()
        System.setProperty("STORAGE_DIR", storageDir.absolutePath)
        val dbUrl =
            "jdbc:h2:mem:legacy-post-claim-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()

        val postsDir = File(storageDir, "posts").apply { mkdirs() }
        val filename = "post_u1_abcd1234.jpg"
        File(postsDir, filename).writeText("stub")
        val now = System.currentTimeMillis()
        val url = "http://localhost:8080/api/files/post-image/$filename"

        transaction {
            Users.insert {
                it[Users.id] = "u1"
                it[Users.name] = "User"
                it[Users.email] = "u1@test.local"
                it[Users.passwordHash] = "x"
            }
            // Simulates a post created before post_image_claims existed: no claim row.
            Posts.insert {
                it[Posts.id] = "p_legacy"
                it[Posts.authorId] = "u1"
                it[Posts.content] = "legacy"
                it[Posts.imageUrls] = """["$url"]"""
                it[Posts.visibility] = "PUBLIC"
                it[Posts.createdAt] = now
            }
        }

        assertFailsWith<IllegalArgumentException> {
            PostRepository().createPost("u1", "duplicate", listOf(url), "PUBLIC")
        }
    }
}

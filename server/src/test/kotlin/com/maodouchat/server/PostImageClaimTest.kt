package com.maodouchat.server

import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.service.FileStorageService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostImageClaimTest {

    @Test
    fun `post image claim survives repository instance restart`() {
        val storageDir = Files.createTempDirectory("maodou-post-claims").toFile()
        System.setProperty("STORAGE_DIR", storageDir.absolutePath)
        val dbUrl =
            "jdbc:h2:mem:post-claim-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()

        val postsDir = File(storageDir, "posts").apply { mkdirs() }
        val filename = "post_u1_abcd1234.jpg"
        File(postsDir, filename).writeText("stub")

        transaction {
            Users.insert {
                it[Users.id] = "u1"
                it[Users.name] = "User"
                it[Users.email] = "u1@test.local"
                it[Users.passwordHash] = "x"
            }
        }

        val url = "http://localhost:8080/api/files/post-image/$filename"
        val firstRepo = PostRepository()
        val first = firstRepo.createPost("u1", "first", listOf(url), "PUBLIC")

        // A fresh repository has an empty in-memory claim cache; the DB must still reject reuse.
        val secondRepo = PostRepository()
        assertEquals(first.id, secondRepo.findPostIdByImageFilename(filename))
        assertFailsWith<IllegalArgumentException> {
            secondRepo.createPost("u1", "second", listOf(url), "PUBLIC")
        }
        assertEquals(true, FileStorageService.resolveFile("posts", filename)?.isFile == true)
    }
}

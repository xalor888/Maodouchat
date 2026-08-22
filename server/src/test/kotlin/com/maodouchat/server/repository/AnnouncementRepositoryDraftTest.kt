package com.maodouchat.server.repository

import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 管理端公告：草稿可删除、立即创建仍 ACTIVE、过期读取侧派生 EXPIRED。
 */
class AnnouncementRepositoryDraftTest {

    private var database: Database? = null

    private fun setupDb(): AnnouncementRepository {
        val dbUrl =
            "jdbc:h2:mem:ann-draft-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            Users.insert {
                it[id] = "u_ann"
                it[Users.name] = "Ann"
                it[Users.email] = "ann@test.local"
                it[Users.passwordHash] = "x"
            }
        }
        return AnnouncementRepository()
    }

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `draft create can be deleted but live create cannot`() {
        val repo = setupDb()
        val now = System.currentTimeMillis()
        val draft = repo.create(
            title = "draft",
            content = "c",
            level = "INFO",
            targetAudience = "ALL",
            targetTagId = null,
            startsAt = now,
            expiresAt = now + 86_400_000L,
            createdBy = "u_ann",
            asDraft = true
        )
        assertEquals("DRAFT", draft.status)
        assertTrue(draft.publishedAt == null)
        assertTrue(repo.delete(draft.id), "草稿必须能硬删除")

        val live = repo.create(
            title = "live",
            content = "c",
            level = "INFO",
            targetAudience = "ALL",
            targetTagId = null,
            startsAt = now,
            expiresAt = now + 86_400_000L,
            createdBy = "u_ann",
            asDraft = false
        )
        assertEquals("ACTIVE", live.status)
        assertFalse(repo.delete(live.id), "已发布公告不得硬删除")
    }

    @Test
    fun `publish promotes draft to ACTIVE`() {
        val repo = setupDb()
        val now = System.currentTimeMillis()
        val draft = repo.create(
            title = "to-publish",
            content = "c",
            level = "INFO",
            targetAudience = "ALL",
            targetTagId = null,
            startsAt = now + 86_400_000L,
            expiresAt = now + 2 * 86_400_000L,
            createdBy = "u_ann",
            asDraft = true
        )
        val published = repo.publish(draft.id, "u_ann")
        assertEquals("ACTIVE", published?.status)
        assertTrue(published?.publishedAt != null)
    }

    @Test
    fun `list derives EXPIRED from ACTIVE past window`() {
        val repo = setupDb()
        val now = System.currentTimeMillis()
        val expired = repo.create(
            title = "old",
            content = "c",
            level = "INFO",
            targetAudience = "ALL",
            targetTagId = null,
            startsAt = now - 2 * 86_400_000L,
            expiresAt = now - 1_000L,
            createdBy = "u_ann"
        )
        // create 返回 get()：库内仍是 ACTIVE，读取侧已派生 EXPIRED
        assertEquals("EXPIRED", expired.status)
        val listed = repo.list(status = "EXPIRED", now = now)
        assertEquals(1, listed.size)
        assertEquals("EXPIRED", listed.single().status)
        assertEquals(0, repo.list(status = "ACTIVE", now = now).size)
    }

    @Test
    fun `updating a draft does not auto-activate`() {
        val repo = setupDb()
        val now = System.currentTimeMillis()
        val draft = repo.create(
            title = "keep-draft",
            content = "c",
            level = "INFO",
            targetAudience = "ALL",
            targetTagId = null,
            startsAt = now,
            expiresAt = now + 86_400_000L,
            createdBy = "u_ann",
            asDraft = true
        )
        val updated = repo.update(draft.id, title = "renamed", content = null, level = null, targetAudience = null, targetTagId = null, startsAt = now, expiresAt = now + 86_400_000L)
        assertEquals("DRAFT", updated?.status)
        assertEquals("renamed", updated?.title)
    }
}

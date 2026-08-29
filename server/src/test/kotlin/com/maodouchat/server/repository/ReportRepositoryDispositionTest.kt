package com.maodouchat.server.repository

import com.maodouchat.server.db.Reports
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportRepositoryDispositionTest {
    private var database: Database? = null

    @AfterEach
    fun closeDatabase() {
        database?.let(TransactionManager::closeAndUnregister)
        database = null
    }

    @Test
    fun `actionTaken is committed only after business action succeeds`() {
        setupDatabase()
        val repository = ReportRepository()

        val failed = repository.executeActionAfterBusinessSuccess(
            reportId = "report-1",
            reviewerId = "reviewer",
            action = "NO_ACTION",
            resolutionNote = "reviewed",
        ) { false }

        assertTrue(failed is ReportRepository.ExecuteActionResult.BusinessActionFailed)
        transaction {
            val report = Reports.selectAll().where { Reports.id eq "report-1" }.single()
            assertEquals("OPEN", report[Reports.status])
            assertNull(report[Reports.actionTaken])
        }

        val completed = repository.executeActionAfterBusinessSuccess(
            reportId = "report-1",
            reviewerId = "reviewer",
            action = "NO_ACTION",
            resolutionNote = "reviewed",
        ) { true }

        assertTrue(completed is ReportRepository.ExecuteActionResult.Completed)
        transaction {
            val report = Reports.selectAll().where { Reports.id eq "report-1" }.single()
            assertEquals("RESOLVED", report[Reports.status])
            assertEquals("NO_ACTION", report[Reports.actionTaken])
        }
    }

    private fun setupDatabase() {
        database = Database.connect(
            "jdbc:h2:mem:report-disposition-${AtomicInteger().incrementAndGet()}-${kotlin.random.Random.nextInt()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        initDatabase()
        transaction {
            listOf("reporter", "reviewer").forEach { userId ->
                Users.insert {
                    it[id] = userId
                    it[name] = userId
                    it[email] = "$userId@test.local"
                    it[passwordHash] = "x"
                }
            }
            Reports.insert {
                it[id] = "report-1"
                it[reporterId] = "reporter"
                it[targetType] = "POST"
                it[targetId] = "post-1"
                it[chatId] = null
                it[messageId] = null
                it[reason] = "spam"
                it[description] = null
                it[status] = "OPEN"
                it[reviewerId] = null
                it[resolutionNote] = null
                it[actionTaken] = null
                it[actionAt] = null
                it[createdAt] = 1L
                it[resolvedAt] = null
            }
        }
    }
}

package com.maodouchat.server.repository

import com.maodouchat.server.db.ModerationRules
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.CreateModerationRuleRequest
import com.maodouchat.server.model.UpdateModerationRuleRequest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModerationRuleRepositoryRegexTest {

    private lateinit var database: Database
    private lateinit var repository: ModerationRuleRepository

    @BeforeEach
    fun setUp() {
        val dbUrl = "jdbc:h2:mem:moderation-regex-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        transaction(database) {
            SchemaUtils.create(Users, ModerationRules, RiskEvents)
            Users.insert {
                it[id] = TEST_USER_ID
                it[name] = "regex-test-user"
                it[email] = "regex-test@example.test"
                it[passwordHash] = "unused"
            }
        }
        repository = ModerationRuleRepository()
    }

    @AfterEach
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
    }

    @Test
    @Timeout(5)
    fun `catastrophic pattern finishes quickly and a later ordinary rule still matches`() {
        repeat(2) { index ->
            repository.createRule(regexRule("catastrophic-$index", "(a+)+$", priority = index))
        }

        val startedAt = System.nanoTime()
        val maliciousEvaluation = repository.evaluate(
            userId = TEST_USER_ID,
            source = "POST",
            content = "a".repeat(11_999) + "!"
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertFalse(maliciousEvaluation.blocked)
        assertTrue(elapsedMs < 1_000, "linear-time moderation evaluation took ${elapsedMs}ms")

        val ordinaryRuleId = repository.createRule(
            regexRule("ordinary", "needle", action = "AUTO_DELETE", priority = 100)
        )
        val ordinaryEvaluation = repository.evaluate(TEST_USER_ID, "POST", "contains a NEEDLE")

        assertTrue(ordinaryEvaluation.blocked)
        assertEquals("AUTO_DELETE", ordinaryEvaluation.action)
        assertTrue(ordinaryEvaluation.matches.any { it.ruleId == ordinaryRuleId })
    }

    @Test
    @Timeout(10)
    fun `concurrent evaluations do not skip regex rules`() {
        repeat(2) { index ->
            repository.createRule(regexRule("concurrent-catastrophic-$index", "(a+)+$", priority = index))
        }
        val ordinaryRuleId = repository.createRule(
            regexRule("concurrent-ordinary", "needle", action = "AUTO_DELETE", priority = 100)
        )
        val requestCount = 24
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(12)

        try {
            val futures = List(requestCount) {
                executor.submit<ModerationRuleRepository.Evaluation> {
                    start.await()
                    repository.evaluate(
                        TEST_USER_ID,
                        "COMMENT",
                        "a".repeat(11_980) + "! needle"
                    )
                }
            }
            start.countDown()
            val evaluations = futures.map { it.get(8, TimeUnit.SECONDS) }

            assertTrue(evaluations.all { it.blocked && it.matches.any { match -> match.ruleId == ordinaryRuleId } })
            val persistedOrdinaryMatches = transaction(database) {
                RiskEvents.selectAll().where { RiskEvents.ruleId eq ordinaryRuleId }.count()
            }
            assertEquals(requestCount.toLong(), persistedOrdinaryMatches)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `create and update reject Java-only regex syntax`() {
        val javaOnlyBackreference = "(a)\\1"

        assertFailsWith<IllegalArgumentException> {
            repository.createRule(regexRule("java-backreference", javaOnlyBackreference))
        }

        val keywordRuleId = repository.createRule(
            CreateModerationRuleRequest(
                name = "literal-backreference",
                matchType = "KEYWORD",
                pattern = javaOnlyBackreference
            )
        )
        assertNull(
            repository.updateRule(
                keywordRuleId,
                UpdateModerationRuleRequest(matchType = "REGEX")
            )
        )
        assertEquals("KEYWORD", repository.getRules().single().matchType)
    }

    @Test
    fun `ruleExists distinguishes missing rules from invalid updates`() {
        assertFalse(repository.ruleExists("rule_missing"))
        val id = repository.createRule(
            CreateModerationRuleRequest(
                name = "exists",
                matchType = "KEYWORD",
                pattern = "spam"
            )
        )
        assertTrue(repository.ruleExists(id))
        assertNull(
            repository.updateRule(id, UpdateModerationRuleRequest(scope = "NOT_A_SCOPE"))
        )
        assertTrue(repository.ruleExists(id), "非法更新不得删规则")
        assertNull(repository.updateRule("rule_missing", UpdateModerationRuleRequest(name = "x")))
    }

    private fun regexRule(
        name: String,
        pattern: String,
        action: String = "WARN_MOD",
        priority: Int = 0
    ) = CreateModerationRuleRequest(
        name = name,
        scope = "ALL",
        matchType = "REGEX",
        pattern = pattern,
        action = action,
        priority = priority
    )

    private companion object {
        const val TEST_USER_ID = "moderation-regex-user"
    }
}

package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupCheckins
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupCheckinRepositoryTest {

    private var database: Database? = null

    private val dbUrl =
        "jdbc:h2:mem:group-checkin-repo-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

    private fun setupDb() {
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        val now = System.currentTimeMillis()
        transaction {
            listOf("u1", "u2").forEach { id ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = id
                    it[Users.email] = "$id@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
            Chats.insert {
                it[Chats.id] = "g1"
                it[Chats.isGroup] = true
                it[Chats.chatType] = "GROUP"
                it[Chats.groupName] = "Group"
                it[Chats.lastMessageType] = "TEXT"
                it[Chats.lastMessageTime] = now
            }
            listOf("u1", "u2").forEach { id ->
                ChatParticipants.insert {
                    it[ChatParticipants.chatId] = "g1"
                    it[ChatParticipants.userId] = id
                    it[ChatParticipants.role] = "MEMBER"
                    it[ChatParticipants.joinedAt] = now
                }
            }
        }
    }

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `myCheckin before checking in reports visible todayCount`() {
        setupDb()
        val now = System.currentTimeMillis()
        transaction {
            GroupCheckins.insert {
                it[GroupCheckins.chatId] = "g1"
                it[GroupCheckins.userId] = "u2"
                it[GroupCheckins.checkinDate] = LocalDate.now().toString()
                it[GroupCheckins.streak] = 1
                it[GroupCheckins.totalCount] = 1
                it[GroupCheckins.checkedAt] = now
            }
        }

        val mine = GroupCheckinRepository.myCheckin("g1", "u1")!!
        assertFalse(mine.alreadyCheckedIn)
        assertEquals(0, mine.todayRank)
        assertEquals(1, mine.todayCount)
        assertEquals(0, mine.streak)
    }

    @Test
    fun `closePk response is inactive with closedAt set`() {
        setupDb()
        val created = GroupCheckinRepository.createPk("g1", "u1", "左", "右")!!
        assertTrue(created.active)
        assertNull(created.closedAt)

        val closed = GroupCheckinRepository.closePk(created.id, "u1")!!
        assertFalse(closed.active)
        assertNotNull(closed.closedAt)

        val fetched = GroupCheckinRepository.getPk(created.id, "u1")!!
        assertFalse(fetched.active)
        assertNotNull(fetched.closedAt)

        assertNull(GroupCheckinRepository.votePk(created.id, "u2", "left"))
    }

    @Test
    fun `votePk can change choice without unique-violation 500`() {
        setupDb()
        val pk = GroupCheckinRepository.createPk("g1", "u1", "左", "右")!!
        val first = GroupCheckinRepository.votePk(pk.id, "u2", "left")!!
        assertEquals("left", first.myChoice)
        assertEquals(1, first.leftCount)
        assertEquals(0, first.rightCount)

        val changed = GroupCheckinRepository.votePk(pk.id, "u2", "right")!!
        assertEquals("right", changed.myChoice)
        assertEquals(0, changed.leftCount)
        assertEquals(1, changed.rightCount)
        assertEquals(1, changed.totalVoters)
    }

    @Test
    fun `joinChain is idempotent for the same member`() {
        setupDb()
        val chain = GroupCheckinRepository.createChain("g1", "u1", "接龙", "主题", 10)!!
        val first = GroupCheckinRepository.joinChain(chain.id, "u2", "第一条")!!
        assertTrue(first.myJoined)
        assertEquals(1, first.entryCount)

        val again = GroupCheckinRepository.joinChain(chain.id, "u2", "第二条应忽略")!!
        assertEquals(1, again.entryCount)
        assertEquals("第一条", again.entries.single().content)
    }
}

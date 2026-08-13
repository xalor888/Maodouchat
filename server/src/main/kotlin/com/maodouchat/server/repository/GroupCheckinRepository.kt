package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import com.maodouchat.server.db.GroupCheckins
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDate
import java.util.UUID

/**
 * 群签到 / 群接龙 / 群 PK 仓库（B3）。
 *
 * 全部为群内公开元数据，明文存储。所有写操作都校验「群聊 + 成员」身份，
 * 与 GroupPlayRepository 的校验口径保持一致。
 */
object GroupCheckinRepository {

    // ── DTO ──────────────────────────────────────────────

    @Serializable
    data class CheckinDto(
        val chatId: String,
        val userId: String,
        val date: String,
        val streak: Int,
        val totalCount: Int,
        val todayRank: Int,
        val todayCount: Int,
        val alreadyCheckedIn: Boolean,
        val checkedAt: Long
    )

    @Serializable
    data class CheckinRankEntry(
        val userId: String,
        val streak: Int,
        val totalCount: Int,
        val lastCheckedAt: Long
    )

    @Serializable
    data class ChainEntryDto(
        val id: String,
        val userId: String,
        val sequence: Int,
        val content: String,
        val createdAt: Long
    )

    @Serializable
    data class ChainDto(
        val id: String,
        val chatId: String,
        val creatorId: String,
        val title: String,
        val topic: String,
        val maxEntries: Int,
        val active: Boolean,
        val createdAt: Long,
        val closedAt: Long? = null,
        val entryCount: Int = 0,
        val myJoined: Boolean = false,
        val entries: List<ChainEntryDto> = emptyList()
    )

    @Serializable
    data class PkDto(
        val id: String,
        val chatId: String,
        val creatorId: String,
        val leftTitle: String,
        val rightTitle: String,
        val active: Boolean,
        val createdAt: Long,
        val closedAt: Long? = null,
        val leftCount: Int = 0,
        val rightCount: Int = 0,
        val totalVoters: Int = 0,
        val myChoice: String? = null
    )

    // ── 群签到 ──────────────────────────────────────────

    fun checkIn(chatId: String, userId: String): CheckinDto? {
        if (!isValidId(chatId) || !isValidId(userId)) return null
        val today = LocalDate.now().toString()
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null

            val now = System.currentTimeMillis()
            val existing = GroupCheckins.selectAll().where {
                (GroupCheckins.chatId eq chatId) and
                    (GroupCheckins.userId eq userId) and
                    (GroupCheckins.checkinDate eq today)
            }.firstOrNull()

            val previous = GroupCheckins.selectAll().where {
                (GroupCheckins.chatId eq chatId) and (GroupCheckins.userId eq userId)
            }
                .orderBy(GroupCheckins.checkinDate to SortOrder.DESC)
                .limit(1)
                .firstOrNull()

            if (existing != null) {
                return@transaction toCheckinDto(chatId, userId, existing, now)
            }

            val yesterday = LocalDate.now().minusDays(1).toString()
            val streak = if (previous != null && previous[GroupCheckins.checkinDate] == yesterday) {
                (previous[GroupCheckins.streak] + 1).coerceAtLeast(1)
            } else 1
            val totalCount = (previous?.get(GroupCheckins.totalCount) ?: 0) + 1

            GroupCheckins.insert {
                it[GroupCheckins.chatId] = chatId
                it[GroupCheckins.userId] = userId
                it[GroupCheckins.checkinDate] = today
                it[GroupCheckins.streak] = streak
                it[GroupCheckins.totalCount] = totalCount
                it[GroupCheckins.checkedAt] = now
            }
            val row = GroupCheckins.selectAll().where {
                (GroupCheckins.chatId eq chatId) and
                    (GroupCheckins.userId eq userId) and
                    (GroupCheckins.checkinDate eq today)
            }.first()
            toCheckinDto(chatId, userId, row, now)
        }
    }

    fun myCheckin(chatId: String, userId: String): CheckinDto? {
        if (!isValidId(chatId) || !isValidId(userId)) return null
        val today = LocalDate.now().toString()
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null
            val row = GroupCheckins.selectAll().where {
                (GroupCheckins.chatId eq chatId) and
                    (GroupCheckins.userId eq userId) and
                    (GroupCheckins.checkinDate eq today)
            }.firstOrNull()
            if (row == null) {
                // 未签到也返回一条友好快照（streak 取自最近一次）
                val previous = GroupCheckins.selectAll().where {
                    (GroupCheckins.chatId eq chatId) and (GroupCheckins.userId eq userId)
                }
                    .orderBy(GroupCheckins.checkinDate to SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
                return@transaction CheckinDto(
                    chatId = chatId,
                    userId = userId,
                    date = today,
                    streak = 0,
                    totalCount = previous?.get(GroupCheckins.totalCount) ?: 0,
                    todayRank = 0,
                    todayCount = 0,
                    alreadyCheckedIn = false,
                    checkedAt = 0L
                )
            }
            toCheckinDto(chatId, userId, row, System.currentTimeMillis())
        }
    }

    fun checkinRanking(chatId: String, limit: Int = 20, viewerId: String? = null): List<CheckinRankEntry> {
        if (chatId.isBlank()) return emptyList()
        return transaction {
            val blocked = blockedUserIdsInTx(viewerId)
            // 8.46 修复：原先把全群签到历史载入内存（500 人×365 天≈18 万行）+ groupBy；
            // 改为一条 ROW_NUMBER 窗口函数 SQL 只取每个用户「最新一行」的 streak/totalCount/checkedAt。
            val safeLimit = limit.coerceIn(1, 100)
            val blockedFilter = if (blocked.isEmpty()) "" else {
                " AND user_id NOT IN (" + List(blocked.size) { "?" }.joinToString(",") + ")"
            }
            val sql = """
                SELECT user_id, streak, total_count, checked_at FROM (
                    SELECT user_id, streak, total_count, checked_at,
                           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY checkin_date DESC) AS rn
                    FROM group_checkins
                    WHERE chat_id = ?
                ) t WHERE rn = 1
                $blockedFilter
                ORDER BY total_count DESC, streak DESC, checked_at DESC
                LIMIT ?
            """.trimIndent()
            val params = mutableListOf<Pair<org.jetbrains.exposed.sql.IColumnType, Any>>(
                org.jetbrains.exposed.sql.VarCharColumnType() to chatId
            )
            blocked.forEach { id ->
                params += org.jetbrains.exposed.sql.VarCharColumnType() to id
            }
            params += org.jetbrains.exposed.sql.IntegerColumnType() to safeLimit
            org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
                sql,
                params
            ) { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            CheckinRankEntry(
                                userId = rs.getString("user_id"),
                                streak = rs.getInt("streak"),
                                totalCount = rs.getInt("total_count"),
                                lastCheckedAt = rs.getLong("checked_at")
                            )
                        )
                    }
                }
            } ?: emptyList()
        }
    }

    private fun toCheckinDto(chatId: String, userId: String, row: ResultRow, now: Long): CheckinDto {
        val date = row[GroupCheckins.checkinDate]
        val blocked = blockedUserIdsInTx(userId)
        // 8.48 修复 M14：rank/count 用 COUNT 聚合——此前全量载入当日签到行（活跃大群上万行）
        val myCheckedAt = row[GroupCheckins.checkedAt]
        val visibleBase = if (blocked.isEmpty()) {
            (GroupCheckins.chatId eq chatId) and (GroupCheckins.checkinDate eq date)
        } else {
            (GroupCheckins.chatId eq chatId) and
                (GroupCheckins.checkinDate eq date) and
                (GroupCheckins.userId notInList blocked.toList())
        }
        val todayCount = GroupCheckins.selectAll()
            .where { visibleBase }
            .count().toInt()
        // rank = 在我之前签到的行数 + 1（同 checkedAt 用 userId 稳定排序）
        val earlier = GroupCheckins.selectAll()
            .where {
                visibleBase and
                    ((GroupCheckins.checkedAt less myCheckedAt) or
                        ((GroupCheckins.checkedAt eq myCheckedAt) and (GroupCheckins.userId less userId)))
            }
            .count().toInt()
        return CheckinDto(
            chatId = chatId,
            userId = userId,
            date = date,
            streak = row[GroupCheckins.streak],
            totalCount = row[GroupCheckins.totalCount],
            todayRank = earlier + 1,
            todayCount = todayCount,
            alreadyCheckedIn = true,
            checkedAt = myCheckedAt
        )
    }

    // ── 群接龙 ──────────────────────────────────────────

    fun createChain(chatId: String, creatorId: String, title: String, topic: String, maxEntries: Int): ChainDto? {
        if (!isValidId(chatId) || !isValidId(creatorId)) return null
        val t = title.trim()
        val tp = topic.trim()
        if (t.isBlank() || t.length > MAX_CHAIN_TITLE_LENGTH) return null
        if (tp.length > MAX_CHAIN_TOPIC_LENGTH) return null
        val cap = maxEntries.coerceIn(MIN_CHAIN_ENTRIES, MAX_CHAIN_ENTRIES)
        val id = "chain_" + UUID.randomUUID().toString().replace("-", "").take(16)
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, creatorId)) return@transaction null
            GroupChains.insert {
                it[GroupChains.id] = id
                it[GroupChains.chatId] = chatId
                it[GroupChains.creatorId] = creatorId
                it[GroupChains.title] = t
                it[GroupChains.topic] = tp
                it[GroupChains.maxEntries] = cap
                it[GroupChains.active] = true
                it[GroupChains.createdAt] = System.currentTimeMillis()
            }
            getChainInTransaction(id, creatorId)
        }
    }

    fun listChains(chatId: String, viewerId: String, limit: Int = 30): List<ChainDto> {
        if (!isValidId(chatId) || !isValidId(viewerId)) return emptyList()
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                ?: return@transaction emptyList()
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, viewerId)) return@transaction emptyList()
            val blocked = blockedUserIdsInTx(viewerId)
            val chainBase = GroupChains.selectAll().where { GroupChains.chatId eq chatId }
            val chainQuery = if (blocked.isEmpty()) chainBase
            else chainBase.andWhere { GroupChains.creatorId notInList blocked.toList() }
            val chains = chainQuery
                .orderBy(GroupChains.createdAt to SortOrder.DESC, GroupChains.id to SortOrder.DESC)
                .limit(limit.coerceIn(1, 100))
                .toList()
            // 8.48 修复 H2：批量取全部条目（此前 toChainDto 逐条查 → N+1）
            val chainIds = chains.map { it[GroupChains.id] }
            val entriesByChain = if (chainIds.isEmpty()) emptyMap() else
                GroupChainEntries.selectAll()
                    .where { GroupChainEntries.chainId inList chainIds }
                    .orderBy(GroupChainEntries.sequence to SortOrder.ASC)
                    .toList()
                    .groupBy { it[GroupChainEntries.chainId] }
            chains.mapNotNull { toChainDto(it, viewerId, entriesByChain[it[GroupChains.id]].orEmpty()) }
        }
    }

    fun getChain(chainId: String, viewerId: String): ChainDto? {
        if (!isValidId(chainId) || !isValidId(viewerId)) return null
        return transaction {
            val chain = GroupChains.selectAll().where { GroupChains.id eq chainId }.firstOrNull()
                ?: return@transaction null
            if (!isMemberInTransaction(chain[GroupChains.chatId], viewerId)) return@transaction null
            if (chain[GroupChains.creatorId] in blockedUserIdsInTx(viewerId)) return@transaction null
            toChainDto(chain, viewerId)
        }
    }

    fun joinChain(chainId: String, userId: String, content: String): ChainDto? {
        if (!isValidId(chainId) || !isValidId(userId)) return null
        val c = content.trim()
        if (c.isBlank() || c.length > MAX_CHAIN_CONTENT_LENGTH) return null
        return transaction {
            // 8.50 修复 H1：先锁 chat 再锁 chain——原「先锁 chain 再锁 chat」与 leaveChat/
            // deleteChatRows 的「chat → chain」构成 AB-BA 死锁环（PG deadlock / SQLite locked）
            val chatId = GroupChains.select(GroupChains.chatId)
                .where { GroupChains.id eq chainId }
                .firstOrNull()?.get(GroupChains.chatId) ?: return@transaction null
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            val chain = GroupChains.selectAll().where { GroupChains.id eq chainId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null
            val closed = !chain[GroupChains.active] || (chain[GroupChains.closedAt] != null)
            if (closed) return@transaction null
            val already = GroupChainEntries.selectAll().where {
                (GroupChainEntries.chainId eq chainId) and (GroupChainEntries.userId eq userId)
            }.count() > 0
            if (already) return@transaction toChainDto(chain, userId)
            val currentCount = GroupChainEntries.selectAll().where { GroupChainEntries.chainId eq chainId }.count()
            // 满员不是「成功但未加入」：返回 null，路由按「接龙已结束或人数已满」回 400，
            // 否则客户端会把失败响应当成功刷新成未加入状态。
            if (currentCount >= chain[GroupChains.maxEntries]) return@transaction null

            val id = "ce_" + UUID.randomUUID().toString().replace("-", "").take(16)
            GroupChainEntries.insert {
                it[GroupChainEntries.id] = id
                it[GroupChainEntries.chainId] = chainId
                it[GroupChainEntries.userId] = userId
                it[GroupChainEntries.sequence] = (currentCount + 1).toInt()
                it[GroupChainEntries.content] = c
                it[GroupChainEntries.createdAt] = System.currentTimeMillis()
            }
            toChainDto(chain, userId)
        }
    }

    private fun getChainInTransaction(chainId: String, viewerId: String): ChainDto? {
        val chain = GroupChains.selectAll().where { GroupChains.id eq chainId }.firstOrNull()
            ?: return null
        return toChainDto(chain, viewerId)
    }

    private fun toChainDto(chain: ResultRow, viewerId: String, preloadedEntries: List<ResultRow> = emptyList()): ChainDto? {
        val chainId = chain[GroupChains.id]
        val blocked = blockedUserIdsInTx(viewerId)
        // 8.48：列表路径由调用方批量预取；单条路径（空）此处回查
        val entryRows = if (preloadedEntries.isNotEmpty()) preloadedEntries else
            GroupChainEntries.selectAll().where { GroupChainEntries.chainId eq chainId }
                .orderBy(GroupChainEntries.sequence to SortOrder.ASC)
                .toList()
        val entries = entryRows.filter { it[GroupChainEntries.userId] !in blocked }.map {
            ChainEntryDto(
                id = it[GroupChainEntries.id],
                userId = it[GroupChainEntries.userId],
                sequence = it[GroupChainEntries.sequence],
                content = it[GroupChainEntries.content],
                createdAt = it[GroupChainEntries.createdAt]
            )
        }
        return ChainDto(
            id = chainId,
            chatId = chain[GroupChains.chatId],
            creatorId = chain[GroupChains.creatorId],
            title = chain[GroupChains.title],
            topic = chain[GroupChains.topic],
            maxEntries = chain[GroupChains.maxEntries],
            active = chain[GroupChains.active] && chain[GroupChains.closedAt] == null,
            createdAt = chain[GroupChains.createdAt],
            closedAt = chain[GroupChains.closedAt],
            entryCount = entries.size,
            myJoined = entries.any { it.userId == viewerId },
            entries = entries
        )
    }

    // ── 群 PK ──────────────────────────────────────────

    fun createPk(chatId: String, creatorId: String, leftTitle: String, rightTitle: String): PkDto? {
        if (!isValidId(chatId) || !isValidId(creatorId)) return null
        val lt = leftTitle.trim()
        val rt = rightTitle.trim()
        if (lt.isBlank() || rt.isBlank() || lt.length > MAX_PK_TITLE_LENGTH || rt.length > MAX_PK_TITLE_LENGTH) return null
        val id = "pk_" + UUID.randomUUID().toString().replace("-", "").take(16)
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, creatorId)) return@transaction null
            GroupPkRounds.insert {
                it[GroupPkRounds.id] = id
                it[GroupPkRounds.chatId] = chatId
                it[GroupPkRounds.creatorId] = creatorId
                it[GroupPkRounds.leftTitle] = lt
                it[GroupPkRounds.rightTitle] = rt
                it[GroupPkRounds.active] = true
                it[GroupPkRounds.createdAt] = System.currentTimeMillis()
            }
            getPkInTransaction(id, creatorId)
        }
    }

    fun listChatPks(chatId: String, viewerId: String, limit: Int = 30): List<PkDto> {
        if (!isValidId(chatId) || !isValidId(viewerId)) return emptyList()
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                ?: return@transaction emptyList()
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, viewerId)) return@transaction emptyList()
            val blocked = blockedUserIdsInTx(viewerId)
            val pkBase = GroupPkRounds.selectAll().where { GroupPkRounds.chatId eq chatId }
            val pkQuery = if (blocked.isEmpty()) pkBase
            else pkBase.andWhere { GroupPkRounds.creatorId notInList blocked.toList() }
            val pks = pkQuery
                .orderBy(GroupPkRounds.createdAt to SortOrder.DESC, GroupPkRounds.id to SortOrder.DESC)
                .limit(limit.coerceIn(1, 100))
                .toList()
            // 8.48 修复 H3：批量取投票（此前 toPkDto 逐个 PK 全量载入 → N+1）
            val pkIds = pks.map { it[GroupPkRounds.id] }
            val votesByPk = if (pkIds.isEmpty()) emptyMap() else
                GroupPkVotes.selectAll()
                    .where { GroupPkVotes.pkId inList pkIds }
                    .toList()
                    .groupBy { it[GroupPkVotes.pkId] }
            pks.mapNotNull { toPkDto(it, viewerId, votesByPk[it[GroupPkRounds.id]].orEmpty()) }
        }
    }

    fun getPk(pkId: String, viewerId: String): PkDto? {
        if (!isValidId(pkId) || !isValidId(viewerId)) return null
        return transaction {
            val pk = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.firstOrNull()
                ?: return@transaction null
            if (!isMemberInTransaction(pk[GroupPkRounds.chatId], viewerId)) return@transaction null
            if (pk[GroupPkRounds.creatorId] in blockedUserIdsInTx(viewerId)) return@transaction null
            toPkDto(pk, viewerId)
        }
    }

    fun votePk(pkId: String, userId: String, choice: String): PkDto? {
        if (!isValidId(pkId) || !isValidId(userId)) return null
        val c = choice.trim().lowercase()
        if (c != "left" && c != "right") return null
        return transaction {
            // 8.50 修复 H1：先锁 chat 再锁 pk（原「先 pk 后 chat」与删除路径构成死锁环）
            val chatId = GroupPkRounds.select(GroupPkRounds.chatId)
                .where { GroupPkRounds.id eq pkId }
                .firstOrNull()?.get(GroupPkRounds.chatId) ?: return@transaction null
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            val pk = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null
            if (!pk[GroupPkRounds.active] || pk[GroupPkRounds.closedAt] != null) return@transaction null
            val now = System.currentTimeMillis()
            GroupPkVotes.deleteWhere { (GroupPkVotes.pkId eq pkId) and (GroupPkVotes.userId eq userId) }
            GroupPkVotes.insert {
                it[GroupPkVotes.pkId] = pkId
                it[GroupPkVotes.userId] = userId
                it[GroupPkVotes.choice] = c
                it[GroupPkVotes.votedAt] = now
            }
            toPkDto(pk, userId)
        }
    }

    fun closePk(pkId: String, userId: String): PkDto? {
        if (!isValidId(pkId) || !isValidId(userId)) return null
        return transaction {
            val pk = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (pk[GroupPkRounds.creatorId] != userId) return@transaction null
            GroupPkRounds.update({ GroupPkRounds.id eq pkId }) {
                it[active] = false
                it[closedAt] = System.currentTimeMillis()
            }
            toPkDto(pk, userId)
        }
    }

    private fun getPkInTransaction(pkId: String, viewerId: String): PkDto? {
        val pk = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.firstOrNull()
            ?: return null
        return toPkDto(pk, viewerId)
    }

    private fun toPkDto(pk: ResultRow, viewerId: String, preloadedVotes: List<ResultRow> = emptyList()): PkDto? {
        val pkId = pk[GroupPkRounds.id]
        val blocked = blockedUserIdsInTx(viewerId)
        // 8.48：列表路径由调用方批量预取；单条路径（空）此处回查
        val votes = (if (preloadedVotes.isNotEmpty()) preloadedVotes else
            GroupPkVotes.selectAll().where { GroupPkVotes.pkId eq pkId }.toList()
            ).filter { it[GroupPkVotes.userId] !in blocked }
        var left = 0
        var right = 0
        var myChoice: String? = null
        for (v in votes) {
            if (v[GroupPkVotes.choice] == "left") left++ else right++
            if (v[GroupPkVotes.userId] == viewerId) myChoice = v[GroupPkVotes.choice]
        }
        return PkDto(
            id = pkId,
            chatId = pk[GroupPkRounds.chatId],
            creatorId = pk[GroupPkRounds.creatorId],
            leftTitle = pk[GroupPkRounds.leftTitle],
            rightTitle = pk[GroupPkRounds.rightTitle],
            active = pk[GroupPkRounds.active] && pk[GroupPkRounds.closedAt] == null,
            createdAt = pk[GroupPkRounds.createdAt],
            closedAt = pk[GroupPkRounds.closedAt],
            leftCount = left,
            rightCount = right,
            totalVoters = votes.size,
            myChoice = myChoice
        )
    }

    // ── 通用 ──────────────────────────────────────────

    /**
     * 清理超过保留期的群玩法数据（默认 365 天），防止 B3 五张表无限增长：
     * - GroupCheckins 签到：按 checkedAt
     * - GroupChains 接龙 + GroupChainEntries 明细：按 createdAt
     * - GroupPkRounds PK + GroupPkVotes 投票：按 createdAt / votedAt
     * 由 Routing.kt 的周期清理循环调用；返回 (表名 -> 删除行数)。
     */
    fun purgeOldData(retentionDays: Int = 365): Map<String, Int> {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            val staleChainIds = GroupChains.select(GroupChains.id)
                .where { GroupChains.createdAt less cutoff }
                .map { it[GroupChains.id] }
            val entryDeletedByChain = if (staleChainIds.isNotEmpty()) {
                GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList staleChainIds }
            } else 0
            val stalePkIds = GroupPkRounds.select(GroupPkRounds.id)
                .where { GroupPkRounds.createdAt less cutoff }
                .map { it[GroupPkRounds.id] }
            val voteDeletedByPk = if (stalePkIds.isNotEmpty()) {
                GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList stalePkIds }
            } else 0
            mapOf(
                "checkins" to GroupCheckins.deleteWhere { GroupCheckins.checkedAt less cutoff },
                "chains" to GroupChains.deleteWhere { GroupChains.createdAt less cutoff },
                "chainEntries" to (entryDeletedByChain + GroupChainEntries.deleteWhere { GroupChainEntries.createdAt less cutoff }),
                "pkRounds" to GroupPkRounds.deleteWhere { GroupPkRounds.createdAt less cutoff },
                "pkVotes" to (voteDeletedByPk + GroupPkVotes.deleteWhere { GroupPkVotes.votedAt less cutoff })
            )
        }
    }

    private fun blockedUserIdsInTx(viewerId: String?): Set<String> {
        if (viewerId.isNullOrBlank()) return emptySet()
        return BlockedUsers.selectAll()
            .where {
                (BlockedUsers.blockerId eq viewerId) or (BlockedUsers.blockedId eq viewerId)
            }
            .map { row ->
                if (row[BlockedUsers.blockerId] == viewerId) row[BlockedUsers.blockedId]
                else row[BlockedUsers.blockerId]
            }
            .toSet()
    }

    private fun isMemberInTransaction(chatId: String, userId: String): Boolean =
        ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
        }.count() > 0

    private fun isValidId(value: String): Boolean =
        value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private const val MIN_CHAIN_ENTRIES = 2
    private const val MAX_CHAIN_ENTRIES = 1_000
    private const val MAX_CHAIN_TITLE_LENGTH = 200
    private const val MAX_CHAIN_TOPIC_LENGTH = 500
    private const val MAX_CHAIN_CONTENT_LENGTH = 500
    private const val MAX_PK_TITLE_LENGTH = 120
}

package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.User

/**
 * 群聊 @ 提及：检测输入、候选过滤、插入 token、从正文提取 mentions。
 * 展示 token 使用 `@displayName`（可含空格则用 displayName 原文）；
 * meta.mentions 存 userId 列表（E2EE 解密后客户端可读）。
 *
 * 与 @AI 助手命令共存：以 `@AI` 开头且后续为助手查询时不算成员提及。
 */
object MentionPolicy {
    const val EVERYONE_ID: String = "__everyone__"
    private const val AI_PREFIX = "@AI"

    data class ActiveQuery(
        /** `@` 在全文中的起始下标 */
        val atIndex: Int,
        /** `@` 之后、光标之前的过滤串（不含 @） */
        val filter: String,
    )

    data class Candidate(
        val userId: String,
        val displayName: String,
        val isEveryone: Boolean = false,
    )

    data class InsertResult(
        val text: String,
        /** 建议光标位置（插入段末尾） */
        val cursor: Int,
    )

    /**
     * 在 [cursor] 处是否处于「正在输入 @xxx」状态。
     * 规则：从 cursor 向前找最近未转义的 `@`，且中间无空白换行；`@` 前为文首或空白。
     */
    fun activeQuery(text: String, cursor: Int = text.length): ActiveQuery? {
        if (text.isEmpty()) return null
        val c = cursor.coerceIn(0, text.length)
        if (c == 0) return null
        // 光标落在空白上则不算进行中 query
        if (c < text.length && text[c].isWhitespace()) return null
        var i = c - 1
        while (i >= 0) {
            val ch = text[i]
            when {
                ch == '@' -> {
                    val beforeOk = i == 0 || text[i - 1].isWhitespace()
                    if (!beforeOk) return null
                    val filter = text.substring(i + 1, c)
                    // 已是完整 @AI 助手前缀且后面还有空格分隔的查询时，不弹成员列表
                    if (filter.equals("AI", ignoreCase = true)) return null
                    if (filter.startsWith("AI ", ignoreCase = true)) return null
                    return ActiveQuery(atIndex = i, filter = filter)
                }
                ch.isWhitespace() -> return null
                else -> i--
            }
        }
        return null
    }

    fun shouldShowPicker(text: String, isGroupChat: Boolean, cursor: Int = text.length): Boolean {
        if (!isGroupChat) return false
        // 整段以 @AI 助手命令开头时不抢入口
        val trimmed = text.trimStart()
        if (trimmed.startsWith(AI_PREFIX, ignoreCase = true) &&
            (trimmed.length == 3 || trimmed.getOrNull(3)?.isWhitespace() == true)
        ) {
            // 若当前 active query 不是从这段 @AI 起，仍可在后文 @ 人
            val q = activeQuery(text, cursor) ?: return false
            val token = text.substring(q.atIndex).take(3)
            if (token.equals(AI_PREFIX, ignoreCase = true)) return false
        }
        return activeQuery(text, cursor) != null
    }

    fun filterCandidates(
        participants: List<User>,
        currentUserId: String,
        filter: String,
        includeEveryone: Boolean = true,
        limit: Int = 80,
    ): List<Candidate> {
        val q = filter.trim()
        val people = participants
            .asSequence()
            .filter { it.id.isNotBlank() && it.id != currentUserId && it.id != EVERYONE_ID }
            .map { Candidate(userId = it.id, displayName = it.displayName.ifBlank { it.id }) }
            .filter { c ->
                if (q.isEmpty()) true
                else c.displayName.contains(q, ignoreCase = true) ||
                    c.userId.contains(q, ignoreCase = true)
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()

        val out = ArrayList<Candidate>(limit)
        if (includeEveryone) {
            val everyoneLabel = "everyone"
            val everyoneMatch = q.isEmpty() ||
                everyoneLabel.startsWith(q, ignoreCase = true) ||
                "所有人".contains(q) ||
                q.equals("all", ignoreCase = true)
            if (everyoneMatch) {
                out.add(Candidate(userId = EVERYONE_ID, displayName = everyoneLabel, isEveryone = true))
            }
        }
        for (p in people) {
            if (out.size >= limit) break
            out.add(p)
        }
        return out
    }

    /**
     * 用 `@displayName ` 替换 active query 区间。displayName 内空白保留。
     */
    fun insertMention(
        text: String,
        cursor: Int,
        displayName: String,
        query: ActiveQuery? = null,
    ): InsertResult {
        val active = query ?: activeQuery(text, cursor) ?: return InsertResult(text, cursor)
        val label = displayName.trim().ifBlank { return InsertResult(text, cursor) }
        val insertion = "@$label "
        val before = text.substring(0, active.atIndex)
        val after = text.substring(cursor.coerceIn(0, text.length))
        val newText = before + insertion + after
        return InsertResult(text = newText, cursor = before.length + insertion.length)
    }

    /**
     * 从发送正文提取被 @ 的 userId。
     * 匹配 `@displayName`（按名称最长优先）与遗留 `@userId`。
     * `@everyone` / `@所有人` → [EVERYONE_ID]。
     */
    fun extractMentionIds(
        text: String,
        participants: List<User>,
        currentUserId: String,
    ): List<String> {
        if (text.isBlank()) return emptyList()
        // 整段仅为 @AI 助手命令（只有一个 @）时不当成员提及
        val trimmed = text.trimStart()
        if (trimmed.startsWith(AI_PREFIX, ignoreCase = true) &&
            (trimmed.length == 3 || trimmed.getOrNull(3)?.isWhitespace() == true) &&
            text.count { it == '@' } == 1
        ) {
            return emptyList()
        }

        val ids = linkedSetOf<String>()
        val nameToId = LinkedHashMap<String, String>()
        for (u in participants) {
            if (u.id.isBlank() || u.id == currentUserId) continue
            val name = u.displayName.trim()
            if (name.isNotEmpty()) nameToId.putIfAbsent(name, u.id)
            nameToId.putIfAbsent(u.id, u.id)
        }
        // 最长名优先，避免短名误伤
        val names = nameToId.keys.sortedByDescending { it.length }
        var i = 0
        while (i < text.length) {
            if (text[i] != '@') {
                i++
                continue
            }
            val beforeOk = i == 0 || text[i - 1].isWhitespace()
            if (!beforeOk) {
                i++
                continue
            }
            val rest = text.substring(i + 1)
            // everyone
            when {
                rest.startsWith("everyone", ignoreCase = true) && boundaryAfter(rest, 8) -> {
                    ids.add(EVERYONE_ID)
                    i += 1 + 8
                    continue
                }
                rest.startsWith("所有人") && boundaryAfter(rest, 3) -> {
                    ids.add(EVERYONE_ID)
                    i += 1 + 3
                    continue
                }
                rest.startsWith("all", ignoreCase = true) && boundaryAfter(rest, 3) -> {
                    ids.add(EVERYONE_ID)
                    i += 1 + 3
                    continue
                }
            }
            var matched: String? = null
            for (name in names) {
                if (rest.startsWith(name) && boundaryAfter(rest, name.length)) {
                    matched = name
                    break
                }
            }
            if (matched != null) {
                nameToId[matched]?.let { ids.add(it) }
                i += 1 + matched.length
            } else {
                i++
            }
        }
        return ids.toList()
    }

    /** 是否应在本地通知强调「有人 @ 了你」（解密后客户端判断）。 */
    fun shouldHighlightMention(
        mentionIds: List<String>,
        currentUserId: String,
        notificationsMuted: Boolean,
    ): Boolean {
        if (notificationsMuted || currentUserId.isBlank()) return false
        if (mentionIds.contains(EVERYONE_ID)) return true
        return mentionIds.contains(currentUserId)
    }

    fun displayTokenForUser(user: User): String = "@${user.displayName.ifBlank { user.id }}"

    private fun boundaryAfter(rest: String, len: Int): Boolean {
        if (rest.length == len) return true
        if (rest.length < len) return false
        val next = rest[len]
        return next.isWhitespace() || next == ',' || next == '.' || next == '!' ||
            next == '?' || next == ':' || next == ';' || next == '，' || next == '。' ||
            next == '！' || next == '？'
    }
}

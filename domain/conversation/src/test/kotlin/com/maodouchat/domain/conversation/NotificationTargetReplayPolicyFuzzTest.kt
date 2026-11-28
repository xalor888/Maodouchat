package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// bypassesOwnerCheck reimplemented by hand: only the two public
// entry kinds skip the owner check.
internal fun bypassesOwnerCheckOracle167(target: NotificationTarget): Boolean =
    target is NotificationTarget.PublicProfile || target is NotificationTarget.GroupInvite

// evaluate reimplemented from the production branches: a generation
// mismatch drops first; nav-ready needs login plus a current route
// that is not the login route; while waiting, only a non-bypass
// target with a non-blank live user id can be dropped on owner
// mismatch; once nav-ready, any non-bypass target whose owner
// differs from the live user id (null included) drops, otherwise
// it navigates.
internal fun evaluateOracle167(
    target: NotificationTarget,
    isLoggedIn: Boolean,
    currentRoute: String?,
    loginRoute: String,
    liveSessionGeneration: Long,
    liveUserId: String?
): NotificationTargetReplayDecision {
    if (target.sessionGeneration != liveSessionGeneration) {
        return NotificationTargetReplayDecision.Drop
    }
    val navReady = isLoggedIn && currentRoute != null && currentRoute != loginRoute
    if (!navReady) {
        if (!bypassesOwnerCheckOracle167(target)) {
            val uid = liveUserId?.takeIf(String::isNotBlank)
            if (uid != null && target.ownerUserId != uid) {
                return NotificationTargetReplayDecision.Drop
            }
        }
        return NotificationTargetReplayDecision.ContinueWaiting
    }
    if (!bypassesOwnerCheckOracle167(target) && target.ownerUserId != liveUserId) {
        return NotificationTargetReplayDecision.Drop
    }
    return NotificationTargetReplayDecision.Navigate
}

// ---------- generators ----------

internal fun replayTarget167(
    kind: Int,
    ref: String,
    generation: Long,
    owner: String,
    messageId: String? = null
): NotificationTarget = when (kind) {
    0 -> NotificationTarget.Chat(ref, generation, owner, messageId)
    1 -> NotificationTarget.AiTasks(ref, generation, owner)
    2 -> NotificationTarget.Post(ref, generation, owner)
    3 -> NotificationTarget.PublicProfile(ref, generation, owner)
    else -> NotificationTarget.GroupInvite(ref, generation, owner)
}

internal data class ReplayCase167(
    val target: NotificationTarget,
    val isLoggedIn: Boolean,
    val currentRoute: String?,
    val loginRoute: String,
    val liveSessionGeneration: Long,
    val liveUserId: String?
)

internal val REPLAY_REF_POOL167: List<String> = listOf(
    "", "c1", "u1", "u2", "x", " ", "\u00A0", "a b", "c-1", "\uD83D\uDE00",
)

internal val REPLAY_OWNER_POOL167: List<String> = listOf(
    "", "u1", "u2", " ", "\u00A0", "u1 ", " u1", "x",
)

internal val REPLAY_ROUTE_POOL167: List<String?> = listOf(
    null, "login", "home", "", "chat", "login ", "Login", "home/chat",
)

internal val REPLAY_GEN_POOL167: List<Long> = listOf(
    0L, 1L, 2L, 7L, -1L, Long.MAX_VALUE, Long.MIN_VALUE,
)

internal val REPLAY_USER_POOL167: List<String?> = listOf(
    null, "", " ", "\u00A0", "u1", "u2", "u1 ", " u1", "x",
)

internal fun randomReplayCase167(rnd: Random): ReplayCase167 {
    val kind = rnd.nextInt(5)
    val gen = REPLAY_GEN_POOL167[rnd.nextInt(REPLAY_GEN_POOL167.size)]
    val liveGen = if (rnd.nextInt(10) < 7) gen
    else REPLAY_GEN_POOL167[rnd.nextInt(REPLAY_GEN_POOL167.size)]
    val owner = REPLAY_OWNER_POOL167[rnd.nextInt(REPLAY_OWNER_POOL167.size)]
    val target = replayTarget167(
        kind,
        REPLAY_REF_POOL167[rnd.nextInt(REPLAY_REF_POOL167.size)],
        gen,
        owner,
        if (kind == 0 && rnd.nextInt(10) < 3) "m1" else null
    )
    val loginRoute = if (rnd.nextInt(10) < 8) "login" else ""
    return ReplayCase167(
        target,
        rnd.nextInt(10) != 0,
        REPLAY_ROUTE_POOL167[rnd.nextInt(REPLAY_ROUTE_POOL167.size)],
        loginRoute,
        liveGen,
        REPLAY_USER_POOL167[rnd.nextInt(REPLAY_USER_POOL167.size)]
    )
}

internal fun replayWireJson167(c: ReplayCase167): String {
    fun esc(s: String): String {
        val sb = StringBuilder()
        sb.append("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        val v = ch.code
                        sb.append("0123456789abcdef"[v shr 12])
                        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                        sb.append("0123456789abcdef"[v and 0xF])
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }
    fun optStr(s: String?): String = if (s == null) "null" else esc(s)
    val kind = when (c.target) {
        is NotificationTarget.Chat -> 0
        is NotificationTarget.AiTasks -> 1
        is NotificationTarget.Post -> 2
        is NotificationTarget.PublicProfile -> 3
        is NotificationTarget.GroupInvite -> 4
    }
    val ref = when (val t = c.target) {
        is NotificationTarget.Chat -> t.id
        is NotificationTarget.AiTasks -> t.chatId
        is NotificationTarget.Post -> t.id
        is NotificationTarget.PublicProfile -> t.username
        is NotificationTarget.GroupInvite -> t.code
    }
    val msgId = (c.target as? NotificationTarget.Chat)?.messageId
    val sb = StringBuilder()
    sb.append("{\"kind\":$kind,\"ref\":${esc(ref)},\"msgId\":${optStr(msgId)},")
    sb.append("\"gen\":${c.target.sessionGeneration},\"owner\":${esc(c.target.ownerUserId)},")
    sb.append("\"loggedIn\":${c.isLoggedIn},\"route\":${optStr(c.currentRoute)},")
    sb.append("\"loginRoute\":${esc(c.loginRoute)},\"liveGen\":${c.liveSessionGeneration},")
    sb.append("\"liveUser\":${optStr(c.liveUserId)}}")
    return sb.toString()
}

class NotificationTargetReplayPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val login = "login"
        fun ev(
            target: NotificationTarget,
            loggedIn: Boolean,
            route: String?,
            liveGen: Long,
            liveUser: String?,
            loginRoute: String = login
        ) = NotificationTargetReplayPolicy.evaluate(
            target, loggedIn, route, loginRoute, liveGen, liveUser
        )
        fun evOracle(
            target: NotificationTarget,
            loggedIn: Boolean,
            route: String?,
            liveGen: Long,
            liveUser: String?,
            loginRoute: String = login
        ) = evaluateOracle167(target, loggedIn, route, loginRoute, liveGen, liveUser)
        data class PinnedCase(
            val target: NotificationTarget,
            val loggedIn: Boolean,
            val route: String?,
            val liveGen: Long,
            val liveUser: String?,
            val expected: NotificationTargetReplayDecision
        )
        val cases = listOf(
            // nav-ready + owner match -> Navigate (all non-bypass kinds).
            PinnedCase(NotificationTarget.Chat("c1", 7L, "u1", "m1"), true, "home", 7L, "u1", NotificationTargetReplayDecision.Navigate),
            PinnedCase(NotificationTarget.AiTasks("c1", 7L, "u1"), true, "home", 7L, "u1", NotificationTargetReplayDecision.Navigate),
            PinnedCase(NotificationTarget.Post("p1", 7L, "u1"), true, "home", 7L, "u1", NotificationTargetReplayDecision.Navigate),
            PinnedCase(NotificationTarget.PublicProfile("bob", 7L, "u9"), true, "home", 7L, null, NotificationTargetReplayDecision.Navigate),
            PinnedCase(NotificationTarget.GroupInvite("code", 7L, "u9"), true, "home", 7L, null, NotificationTargetReplayDecision.Navigate),
            // nav-ready + owner mismatch -> Drop (bypass kinds exempt).
            PinnedCase(NotificationTarget.Chat("c1", 7L, "u2"), true, "home", 7L, "u1", NotificationTargetReplayDecision.Drop),
            PinnedCase(NotificationTarget.Post("p1", 7L, "u2"), true, "home", 7L, "u1", NotificationTargetReplayDecision.Drop),
            PinnedCase(NotificationTarget.PublicProfile("bob", 7L, "u2"), true, "home", 7L, "u1", NotificationTargetReplayDecision.Navigate),
            PinnedCase(NotificationTarget.GroupInvite("code", 7L, "u2"), true, "home", 7L, "u1", NotificationTargetReplayDecision.Navigate),
            // nav-ready + live user null: even an empty owner differs from null.
            PinnedCase(NotificationTarget.Chat("c1", 7L, ""), true, "home", 7L, null, NotificationTargetReplayDecision.Drop),
            PinnedCase(NotificationTarget.Chat("c1", 7L, "u1"), true, "home", 7L, null, NotificationTargetReplayDecision.Drop),
            // generation mismatch drops before anything else.
            PinnedCase(NotificationTarget.GroupInvite("code", 8L, "u9"), true, "home", 7L, null, NotificationTargetReplayDecision.Drop),
            PinnedCase(NotificationTarget.Chat("c1", Long.MIN_VALUE, "u1"), true, "home", Long.MAX_VALUE, "u1", NotificationTargetReplayDecision.Drop),
        )
        for (c in cases) {
            assertEquals(
                c.expected, ev(c.target, c.loggedIn, c.route, c.liveGen, c.liveUser),
                "evaluate(kind=${c.target::class.simpleName},gen=${c.target.sessionGeneration}," +
                    "owner=${c.target.ownerUserId},liveUser=${c.liveUser})"
            )
            assertEquals(
                c.expected, evOracle(c.target, c.loggedIn, c.route, c.liveGen, c.liveUser),
                "oracle(kind=${c.target::class.simpleName},gen=${c.target.sessionGeneration}," +
                    "owner=${c.target.ownerUserId},liveUser=${c.liveUser})"
            )
        }
        // Waiting branch: not logged in / no route / login route.
        val waiting = NotificationTarget.Chat("c1", 7L, "u1")
        for ((loggedIn, route) in listOf(false to "home", true to null, true to "login")) {
            assertEquals(
                NotificationTargetReplayDecision.ContinueWaiting,
                ev(waiting, loggedIn, route, 7L, "u1"),
                "waiting(loggedIn=$loggedIn,route=$route)"
            )
            assertEquals(
                NotificationTargetReplayDecision.ContinueWaiting,
                evOracle(waiting, loggedIn, route, 7L, "u1"),
                "oracle waiting(loggedIn=$loggedIn,route=$route)"
            )
        }
        // Waiting branch + owner mismatch: drops only with a non-blank live id.
        val stranger = NotificationTarget.Chat("c1", 7L, "u2")
        for (liveUser in listOf("u1", "u2")) {
            val expected = if (liveUser == "u1") {
                NotificationTargetReplayDecision.Drop
            } else {
                NotificationTargetReplayDecision.ContinueWaiting
            }
            assertEquals(
                expected, ev(stranger, false, "home", 7L, liveUser),
                "waiting-mismatch(liveUser=$liveUser)"
            )
            assertEquals(
                expected, evOracle(stranger, false, "home", 7L, liveUser),
                "oracle waiting-mismatch(liveUser=$liveUser)"
            )
        }
        for (liveUser in listOf(null, "", " ", "\u00A0")) {
            assertEquals(
                NotificationTargetReplayDecision.ContinueWaiting,
                ev(stranger, false, "home", 7L, liveUser),
                "waiting-blank-live(liveUser=$liveUser)"
            )
        }
        // Waiting branch + bypass kind never drops on owner mismatch.
        val bypassWaiting = NotificationTarget.PublicProfile("bob", 7L, "u2")
        assertEquals(
            NotificationTargetReplayDecision.ContinueWaiting,
            ev(bypassWaiting, false, "home", 7L, "u1"),
            "waiting-bypass"
        )
        // Empty login route: empty current route equals it, so not nav-ready.
        assertEquals(
            NotificationTargetReplayDecision.ContinueWaiting,
            ev(waiting, true, "", 7L, "u1", loginRoute = ""),
            "empty-login-route"
        )
        assertEquals(
            NotificationTargetReplayDecision.Navigate,
            ev(waiting, true, "home", 7L, "u1", loginRoute = ""),
            "empty-login-route-nav"
        )
        // Blank owner vs blank live user while nav-ready: equal strings navigate.
        assertEquals(
            NotificationTargetReplayDecision.Navigate,
            ev(NotificationTarget.Post("p1", 7L, " "), true, "home", 7L, " "),
            "blank-owner-nav"
        )
        // bypassesOwnerCheck pinned per kind.
        for (kind in 0..4) {
            val t = replayTarget167(kind, "r", 7L, "u1")
            val expected = kind == 3 || kind == 4
            assertEquals(expected, NotificationTargetReplayPolicy.bypassesOwnerCheck(t), "bypass(kind=$kind)")
            assertEquals(expected, bypassesOwnerCheckOracle167(t), "oracle bypass(kind=$kind)")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(167001)
        repeat(4000) {
            val c = randomReplayCase167(rnd)
            assertEquals(
                evaluateOracle167(
                    c.target, c.isLoggedIn, c.currentRoute, c.loginRoute,
                    c.liveSessionGeneration, c.liveUserId
                ),
                NotificationTargetReplayPolicy.evaluate(
                    c.target, c.isLoggedIn, c.currentRoute, c.loginRoute,
                    c.liveSessionGeneration, c.liveUserId
                ),
                "evaluate(random)"
            )
            assertEquals(
                bypassesOwnerCheckOracle167(c.target),
                NotificationTargetReplayPolicy.bypassesOwnerCheck(c.target),
                "bypassesOwnerCheck(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every kind x generation-match x login x route-kind x live-user
        // x owner: the whole decision table, no sampling.
        val routes: List<String?> = listOf(null, "login", "home")
        val liveUsers: List<String?> = listOf(null, "", " ", "u1", "u2")
        val owners = listOf("u1", "u2", "")
        for (kind in 0..4) {
            for (genMatch in listOf(true, false)) {
                for (loggedIn in listOf(false, true)) {
                    for (route in routes) {
                        for (liveUser in liveUsers) {
                            for (owner in owners) {
                                val target = replayTarget167(
                                    kind, "r", if (genMatch) 7L else 8L, owner
                                )
                                assertEquals(
                                    evaluateOracle167(target, loggedIn, route, "login", 7L, liveUser),
                                    NotificationTargetReplayPolicy.evaluate(
                                        target, loggedIn, route, "login", 7L, liveUser
                                    ),
                                    "sweep(kind=$kind,genMatch=$genMatch,loggedIn=$loggedIn," +
                                        "route=$route,liveUser=$liveUser,owner=$owner)"
                                )
                            }
                        }
                    }
                }
            }
        }
        // Generation extremes: MIN/MAX on both sides.
        for (gen in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
            for (liveGen in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
                val target = replayTarget167(0, "r", gen, "u1")
                assertEquals(
                    evaluateOracle167(target, true, "home", "login", liveGen, "u1"),
                    NotificationTargetReplayPolicy.evaluate(
                        target, true, "home", "login", liveGen, "u1"
                    ),
                    "sweep-gen(gen=$gen,liveGen=$liveGen)"
                )
            }
        }
    }
}

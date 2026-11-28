package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr211a(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit211a(b: Boolean): String = if (b) "1" else "0"

private fun opt211a(s: String?): String = s?.let { encStr211a(it) } ?: "~"

private fun tri211a(v: Boolean?): String = when (v) {
    null -> "~"
    true -> "1"
    else -> "0"
}

private fun triFrom211a(s: String): Boolean? = when (s) {
    "~" -> null
    "1" -> true
    else -> false
}

// 只含本簇触及的 ChatDetailUiState 字段。
private data class SrchState211a(
    val isSecretChat: Boolean? = null,
    val aiEnabled: Boolean = false,
    val semanticSearchError: String? = null,
    val semanticSearchResultIds: List<String> = emptyList(),
    val semanticSearchQuery: String = "",
    val isSemanticSearching: Boolean = false,
    val showAiConsentDialog: Boolean = false,
    val navigationTargetMessageId: String? = null,
)

private fun SrchState211a.toUiState(): ChatDetailUiState = ChatDetailUiState(
    isSecretChat = isSecretChat,
    aiEnabled = aiEnabled,
    semanticSearchError = semanticSearchError,
    semanticSearchResultIds = semanticSearchResultIds,
    semanticSearchQuery = semanticSearchQuery,
    isSemanticSearching = isSemanticSearching,
    showAiConsentDialog = showAiConsentDialog,
    navigationTargetMessageId = navigationTargetMessageId,
)

private fun srchStateFromUi211a(s: ChatDetailUiState): SrchState211a = SrchState211a(
    isSecretChat = s.isSecretChat,
    aiEnabled = s.aiEnabled,
    semanticSearchError = s.semanticSearchError,
    semanticSearchResultIds = s.semanticSearchResultIds,
    semanticSearchQuery = s.semanticSearchQuery,
    isSemanticSearching = s.isSemanticSearching,
    showAiConsentDialog = s.showAiConsentDialog,
    navigationTargetMessageId = s.navigationTargetMessageId,
)

private fun srchStateEnc211a(s: SrchState211a): String = listOf(
    tri211a(s.isSecretChat),
    bit211a(s.aiEnabled),
    opt211a(s.semanticSearchError),
    s.semanticSearchResultIds.joinToString(";") { encStr211a(it) },
    encStr211a(s.semanticSearchQuery),
    bit211a(s.isSemanticSearching),
    bit211a(s.showAiConsentDialog),
    opt211a(s.navigationTargetMessageId),
).joinToString(":")

// op kind：0 admitSemanticSearch，1 clearSemanticSearch，
// 2 navigateTo，3 consumeNavigation。
private data class SrchOp211a(
    val kind: Int,
    val query: String = "",
    val candidateIds: List<String> = emptyList(),
    val errSecret: String = "E:SECRET",
    val errAi: String = "E:AI",
    val errBlank: String = "E:BLANK",
    val errNoCtx: String = "E:NOCTX",
    val showConsent: Boolean = false,
    val messageId: String = "",
)

private data class SrchCase211a(
    val seed: SrchState211a = SrchState211a(),
    val ops: List<SrchOp211a> = emptyList(),
)

private fun srchOracle211a(c: SrchCase211a): String {
    val effects = mutableListOf<String>()
    var state = c.seed
    fun snap(prefix: String) {
        effects += prefix + ":" + srchStateEnc211a(state)
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                if (state.isSecretChat == true) {
                    state = state.copy(semanticSearchError = op.errSecret)
                    effects += "ADMIT:R"
                } else if (!state.aiEnabled) {
                    state = state.copy(semanticSearchError = op.errAi)
                    effects += "ADMIT:R"
                } else {
                    val normalized = op.query.trim().take(300)
                    if (normalized.isBlank()) {
                        state = state.copy(semanticSearchError = op.errBlank)
                        effects += "ADMIT:R"
                    } else {
                        val safeIds = op.candidateIds.filter(String::isNotBlank).distinct().take(100)
                        if (safeIds.isEmpty()) {
                            state = state.copy(semanticSearchError = op.errNoCtx)
                            effects += "ADMIT:R"
                        } else {
                            effects += "ADMIT:A:" + encStr211a(normalized) + ":" +
                                safeIds.joinToString(";") { encStr211a(it) }
                        }
                    }
                }
            }
            1 -> state = state.copy(
                semanticSearchResultIds = emptyList(),
                semanticSearchQuery = "",
                isSemanticSearching = false,
                semanticSearchError = null,
                showAiConsentDialog = op.showConsent,
            )
            2 -> if (!op.messageId.isBlank()) {
                state = state.copy(navigationTargetMessageId = op.messageId)
            }
            else -> state = state.copy(navigationTargetMessageId = null)
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun srchProd211a(c: SrchCase211a): String {
    val effects = mutableListOf<String>()
    var state = c.seed.toUiState()
    fun snap(prefix: String) {
        effects += prefix + ":" + srchStateEnc211a(srchStateFromUi211a(state))
    }
    val controller = ChatSearchSelectionStateController()
    for (op in c.ops) {
        when (op.kind) {
            0 -> when (val r = controller.admitSemanticSearch(
                state, op.query, op.candidateIds,
                op.errSecret, op.errAi, op.errBlank, op.errNoCtx,
            )) {
                is SemanticSearchAdmission.Accepted -> effects += "ADMIT:A:" +
                    encStr211a(r.query) + ":" +
                    r.candidateIds.joinToString(";") { encStr211a(it) }
                is SemanticSearchAdmission.Rejected -> {
                    state = r.state
                    effects += "ADMIT:R"
                }
            }
            1 -> state = controller.clearSemanticSearch(state, op.showConsent)
            2 -> state = controller.navigateTo(state, op.messageId)
            else -> state = controller.consumeNavigation(state)
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val SRCH_QUERIES211a = listOf("", " ", "  hi  ", "q", "a".repeat(350), "  " + "b".repeat(400))
private val SRCH_IDS211a = listOf("m1", "m2", "m3", "", "  ")

private fun randomSrchState211a(r: Random): SrchState211a = SrchState211a(
    isSecretChat = listOf<Boolean?>(null, true, false)[r.nextInt(3)],
    aiEnabled = r.nextInt(2) == 0,
    semanticSearchError = listOf<String?>(null, "E0", "")[r.nextInt(3)],
    semanticSearchResultIds = List(r.nextInt(0, 3)) { SRCH_IDS211a[r.nextInt(SRCH_IDS211a.size)] },
    semanticSearchQuery = listOf("", "old")[r.nextInt(2)],
    isSemanticSearching = r.nextInt(2) == 0,
    showAiConsentDialog = r.nextInt(2) == 0,
    navigationTargetMessageId = listOf<String?>(null, "m1", "")[r.nextInt(3)],
)

private fun randomSrchOp211a(r: Random): SrchOp211a {
    val kind = r.nextInt(4)
    return SrchOp211a(
        kind = kind,
        query = SRCH_QUERIES211a[r.nextInt(SRCH_QUERIES211a.size)],
        candidateIds = List(r.nextInt(0, 5)) { SRCH_IDS211a[r.nextInt(SRCH_IDS211a.size)] },
        errSecret = listOf("E:SECRET", "")[r.nextInt(2)],
        errAi = listOf("E:AI", "")[r.nextInt(2)],
        errBlank = listOf("E:BLANK", "")[r.nextInt(2)],
        errNoCtx = listOf("E:NOCTX", "")[r.nextInt(2)],
        showConsent = r.nextInt(2) == 0,
        messageId = listOf("", "m9", "  ")[r.nextInt(3)],
    )
}

private fun randomSrchCase211a(r: Random): SrchCase211a = SrchCase211a(
    seed = randomSrchState211a(r),
    ops = List(r.nextInt(0, 9)) { randomSrchOp211a(r) },
)

private fun boundarySrchCase211a(r: Random): SrchCase211a {
    val base = SrchCase211a(seed = SrchState211a(aiEnabled = true))
    val longIds = (1..105).map { "m" + (it % 3) }
    return when (r.nextInt(24)) {
        0 -> base.copy(seed = SrchState211a(isSecretChat = true, aiEnabled = true), ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1"))))
        1 -> base.copy(seed = SrchState211a(aiEnabled = false), ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1"))))
        2 -> base.copy(ops = listOf(SrchOp211a(0, query = "", candidateIds = listOf("m1"))))
        3 -> base.copy(ops = listOf(SrchOp211a(0, query = "   ", candidateIds = listOf("m1"))))
        4 -> base.copy(ops = listOf(SrchOp211a(0, query = "q", candidateIds = emptyList())))
        5 -> base.copy(ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("", "  "))))
        6 -> base.copy(ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1", "m1", "", "m2", "m1"))))
        7 -> base.copy(ops = listOf(SrchOp211a(0, query = "q", candidateIds = longIds)))
        8 -> base.copy(ops = listOf(SrchOp211a(0, query = "  " + "x".repeat(400), candidateIds = listOf("m1"))))
        9 -> base.copy(ops = listOf(SrchOp211a(0, query = "a".repeat(300), candidateIds = listOf("m1"))))
        10 -> base.copy(ops = listOf(SrchOp211a(0, query = "a".repeat(301), candidateIds = listOf("m1"))))
        11 -> base.copy(seed = SrchState211a(aiEnabled = true, semanticSearchResultIds = listOf("m1", "m2"), semanticSearchQuery = "old", isSemanticSearching = true, semanticSearchError = "E", showAiConsentDialog = false), ops = listOf(SrchOp211a(1, showConsent = true)))
        12 -> base.copy(ops = listOf(SrchOp211a(1, showConsent = false)))
        13 -> base.copy(ops = listOf(SrchOp211a(2, messageId = "")))
        14 -> base.copy(ops = listOf(SrchOp211a(2, messageId = "  ")))
        15 -> base.copy(ops = listOf(SrchOp211a(2, messageId = "m9")))
        16 -> base.copy(seed = SrchState211a(navigationTargetMessageId = "m1"), ops = listOf(SrchOp211a(3)))
        17 -> base.copy(ops = listOf(SrchOp211a(3)))
        18 -> base.copy(ops = listOf(SrchOp211a(2, messageId = "m9"), SrchOp211a(3), SrchOp211a(2, messageId = "m10")))
        19 -> base.copy(seed = SrchState211a(isSecretChat = null, aiEnabled = true), ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1"))))
        20 -> base.copy(seed = SrchState211a(isSecretChat = false, aiEnabled = true), ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1"))))
        21 -> base.copy(ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1", "m2"), errSecret = "", errAi = "", errBlank = "", errNoCtx = "")))
        22 -> base.copy(seed = SrchState211a(isSecretChat = true), ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1"))))
        else -> base.copy(ops = listOf(SrchOp211a(0, query = " q ", candidateIds = listOf(" m1 ", "m1"))))
    }
}

// ---------- tests ----------

class ChatSearchSelectionStateControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(211101)
        repeat(300) {
            val c = randomSrchCase211a(r)
            assertEquals(srchOracle211a(c), srchProd211a(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(211102)
        repeat(200) {
            val c = boundarySrchCase211a(r)
            assertEquals(srchOracle211a(c), srchProd211a(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            SrchCase211a(seed = SrchState211a(aiEnabled = true), ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1")))),
            SrchCase211a(seed = SrchState211a(isSecretChat = true, aiEnabled = true), ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1")))),
            SrchCase211a(seed = SrchState211a(aiEnabled = false), ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1")))),
            SrchCase211a(seed = SrchState211a(aiEnabled = true), ops = listOf(SrchOp211a(0, query = "", candidateIds = listOf("m1")))),
            SrchCase211a(seed = SrchState211a(aiEnabled = true), ops = listOf(SrchOp211a(0, query = "q", candidateIds = emptyList()))),
            SrchCase211a(seed = SrchState211a(aiEnabled = true), ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1", "m1", "", "m2")))),
            SrchCase211a(seed = SrchState211a(aiEnabled = true), ops = listOf(SrchOp211a(0, query = "a".repeat(350), candidateIds = listOf("m1")))),
            SrchCase211a(seed = SrchState211a(semanticSearchResultIds = listOf("m1"), semanticSearchQuery = "old", isSemanticSearching = true, semanticSearchError = "E"), ops = listOf(SrchOp211a(1, showConsent = true))),
            SrchCase211a(ops = listOf(SrchOp211a(2, messageId = "m9"))),
            SrchCase211a(seed = SrchState211a(navigationTargetMessageId = "m1"), ops = listOf(SrchOp211a(3))),
            SrchCase211a(seed = SrchState211a(aiEnabled = true), ops = listOf(SrchOp211a(0, query = "q", candidateIds = listOf("m1")), SrchOp211a(2, messageId = "m1"), SrchOp211a(3))),
            SrchCase211a(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(srchOracle211a(c), srchProd211a(c))
        }
    }
}

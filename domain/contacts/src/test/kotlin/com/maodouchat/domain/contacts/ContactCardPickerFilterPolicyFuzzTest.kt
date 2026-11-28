// Fuzz / random-oracle tests for ContactCardPickerFilterPolicy
// (checklist line 931, 158th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: a contact
// matches when the display name (nickname if non-blank, else name if
// non-blank, else id) or the name contains the query ignoring case;
// filter trims the query and returns everything when the trimmed query
// is empty (note: isEmpty, not isBlank -- a query of only NEL is not
// empty after trim and filters to nothing). Case-insensitive
// containment is the JDK regionMatches rule; the oracle calls the same
// stdlib contains the production does, so the two cannot drift on
// case mapping.
package com.maodouchat.domain.contacts

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun cardDisplayNameOracle158(nickname: String?, name: String, id: String): String =
    nickname?.takeIf(String::isNotBlank) ?: name.takeIf(String::isNotBlank) ?: id

internal fun cardMatchesOracle158(
    nickname: String?,
    name: String,
    id: String,
    query: String,
): Boolean {
    val display = cardDisplayNameOracle158(nickname, name, id)
    return display.contains(query, ignoreCase = true) ||
        name.contains(query, ignoreCase = true)
}

internal fun cardFilterOracle158(contacts: List<Pair<Chat, User>>, query: String): List<Pair<Chat, User>> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return contacts
    return contacts.filter { (_, user) ->
        cardMatchesOracle158(user.nickname, user.name, user.id, trimmed)
    }
}

// ---------- generators ----------

internal val CARD_WORD_POOL158 = listOf(
    "hello", "HELLO", "HeLLo", "world", "a", "A", "", " ", "q1", "q2",
    "Σ", "σ", "ς", "ΟΣ", "ος", "οσ", "İ", "ı", "I", "i", "ß", "ẞ",
    "K", "K", "k", "ſ", "s", "S", "µ", "μ", "Μ", "Ǆ", "ǅ", "ǆ",
    "Ÿ", "ÿ", "é", "É", "ﬀ", "张三", "群名", "\u00a0", "\u0085",
    "Alice", "ALICE", "alice", "Bob", "bob", "Al", "ali",
)

internal val CARD_ALPHABET158 = "abcXYZ019 \t\u00a0\u00e9\u4e2d\u0085\u2003ΣσςİıIißẞK\u212a kſsSµμΜǄǅǆŸÿéÉ\uFB00"

internal fun randomCardChar158(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> CARD_ALPHABET158[rnd.nextInt(CARD_ALPHABET158.length)].toString()
}

internal fun randomCardString158(rnd: Random): String {
    val lengthUnits = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomCardChar158(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal fun randomCardUser158(rnd: Random): User = User(
    id = if (rnd.nextInt(10) < 6) CARD_WORD_POOL158[rnd.nextInt(CARD_WORD_POOL158.size)] else randomCardString158(rnd),
    name = if (rnd.nextInt(10) < 6) CARD_WORD_POOL158[rnd.nextInt(CARD_WORD_POOL158.size)] else randomCardString158(rnd),
    nickname = if (rnd.nextInt(10) < 3) null
        else if (rnd.nextInt(10) < 6) CARD_WORD_POOL158[rnd.nextInt(CARD_WORD_POOL158.size)]
        else randomCardString158(rnd),
)

internal fun randomCardContact158(rnd: Random, index: Int): Pair<Chat, User> =
    Pair(Chat(id = "c$index"), randomCardUser158(rnd))

internal data class CardCase158(
    val contacts: List<Pair<Chat, User>>,
    val query: String,
)

internal fun randomCardCase158(rnd: Random): CardCase158 {
    val count = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(7, 11)
        else -> rnd.nextInt(0, 6)
    }
    val contacts = List(count) { i -> randomCardContact158(rnd, i) }
    val query = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> if (contacts.isNotEmpty()) {
            val base = contacts[rnd.nextInt(contacts.size)].second
            val field = when (rnd.nextInt(3)) {
                0 -> cardDisplayNameOracle158(base.nickname, base.name, base.id)
                1 -> base.name
                else -> base.id
            }
            when (rnd.nextInt(3)) {
                0 -> field
                1 -> field.uppercase()
                else -> field.lowercase()
            }
        } else {
            randomCardString158(rnd)
        }
        else -> randomCardString158(rnd)
    }
    return CardCase158(contacts, query)
}

internal fun cardJsonEscape158(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun cardUserJson158(user: User): String {
    val sb = StringBuilder()
    val nickname = user.nickname
    sb.append("{\"id\":\"").append(cardJsonEscape158(user.id)).append("\"")
    sb.append(",\"name\":\"").append(cardJsonEscape158(user.name)).append("\"")
    if (nickname != null) {
        sb.append(",\"nick\":\"").append(cardJsonEscape158(nickname)).append("\"")
    }
    sb.append("}")
    return sb.toString()
}

internal fun cardContactJson158(contact: Pair<Chat, User>): String =
    "{\"chat\":\"" + cardJsonEscape158(contact.first.id) + "\",\"user\":" +
        cardUserJson158(contact.second) + "}"

internal fun cardMatchesWireJson158(contact: Pair<Chat, User>, query: String): String =
    cardContactJson158(contact).dropLast(1) + ",\"q\":\"" + cardJsonEscape158(query) + "\"}"

internal fun cardFilterWireJson158(case: CardCase158): String {
    val sb = StringBuilder()
    sb.append("{\"contacts\":[")
    case.contacts.forEachIndexed { i, c ->
        if (i > 0) sb.append(",")
        sb.append(cardContactJson158(c))
    }
    sb.append("],\"q\":\"").append(cardJsonEscape158(case.query)).append("\"}")
    return sb.toString()
}

class ContactCardPickerFilterPolicyFuzzTest {

    private fun check(contacts: List<Pair<Chat, User>>, query: String) {
        for ((_, user) in contacts) {
            assertEquals(
                cardMatchesOracle158(user.nickname, user.name, user.id, query),
                ContactCardPickerFilterPolicy.matches(user, query),
            )
        }
        assertEquals(
            cardFilterOracle158(contacts, query),
            ContactCardPickerFilterPolicy.filter(contacts, query),
        )
    }

    @Test
    fun pinnedBoundaries() {
        val alice = Pair(Chat("c1"), User(id = "u1", name = "Alice", nickname = "Al"))
        val bob = Pair(Chat("c2"), User(id = "u2", name = "Bob", nickname = "   "))
        val blank = Pair(Chat("c3"), User(id = "u3", name = "", nickname = null))
        // Display name prefers a non-blank nickname, then name, then id.
        assertEquals("Al", cardDisplayNameOracle158("Al", "Alice", "u1"))
        assertEquals("Bob", cardDisplayNameOracle158("   ", "Bob", "u2"))
        assertEquals("u3", cardDisplayNameOracle158(null, "", "u3"))
        assertEquals("u3", cardDisplayNameOracle158(null, "   ", "u3"))
        // (contact, query, expected matches)
        val matchCases = listOf(
            Triple(alice, "al", true),
            Triple(alice, "AL", true),
            Triple(alice, "ALICE", true),
            Triple(alice, "alice", true),
            // The id is NOT a matched field here (only display name and
            // name are): "u1" hits nothing even though it is the id.
            Triple(alice, "u1", false),
            Triple(alice, "zzz", false),
            Triple(alice, "", true),
            Triple(alice, " ", false),
            Triple(bob, "bob", true),
            Triple(bob, " ", false),
            Triple(bob, "u2", false),
            Triple(blank, "u3", true),
            Triple(blank, "", true),
            Triple(blank, "x", false),
            // Case-fold pairs (regionMatches): sigma, İ/ı, ß/ẞ, K/Kelvin.
            Triple(Pair(Chat("s1"), User("s1", "ΟΣ")), "ος", true),
            Triple(Pair(Chat("s1"), User("s1", "ΟΣ")), "οσ", true),
            Triple(Pair(Chat("s1"), User("s1", "İ")), "ı", true),
            Triple(Pair(Chat("s1"), User("s1", "ß")), "ẞ", true),
            Triple(Pair(Chat("s1"), User("s1", "K")), "K", true),
            Triple(Pair(Chat("s1"), User("s1", "ﬀ")), "FF", false),
        )
        for ((contact, query, expected) in matchCases) {
            val user = contact.second
            assertEquals(
                expected,
                cardMatchesOracle158(user.nickname, user.name, user.id, query),
            )
            assertEquals(expected, ContactCardPickerFilterPolicy.matches(user, query))
        }
        // (contacts, query, expected chat ids in order)
        val filterCases = listOf(
            Triple(listOf(alice, bob), "ali", listOf("c1")),
            Triple(listOf(alice, bob), "ALI", listOf("c1")),
            Triple(listOf(alice, bob), "  ", listOf("c1", "c2")),
            Triple(listOf(alice, bob), "", listOf("c1", "c2")),
            Triple(listOf(alice, bob), "  bob  ", listOf("c2")),
            Triple(listOf(alice, bob), "zzz", listOf()),
            // "u" hits no display name or name (the id is not matched).
            Triple(listOf(bob, alice), "u", listOf()),
            Triple(listOf(), "ali", listOf()),
        )
        for ((contacts, query, expectedIds) in filterCases) {
            assertEquals(expectedIds, cardFilterOracle158(contacts, query).map { it.first.id })
            assertEquals(expectedIds, ContactCardPickerFilterPolicy.filter(contacts, query).map { it.first.id })
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(158001)
        repeat(2000) {
            val case = randomCardCase158(rnd)
            check(case.contacts, case.query)
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every word as a name/id field against every word query.
        for (field in CARD_WORD_POOL158) {
            val contact = Pair(Chat("w"), User("w", field))
            for (query in CARD_WORD_POOL158) {
                assertEquals(
                    cardMatchesOracle158(null, field, "w", query),
                    ContactCardPickerFilterPolicy.matches(contact.second, query),
                )
            }
        }
        // Fixed list against every word query (filter path).
        val list = listOf(
            Pair(Chat("a"), User("a", "Alice", nickname = "Al")),
            Pair(Chat("b"), User("b", "Bob")),
            Pair(Chat("c"), User("c", "ΟΣ")),
        )
        for (query in CARD_WORD_POOL158) {
            assertEquals(
                cardFilterOracle158(list, query),
                ContactCardPickerFilterPolicy.filter(list, query),
            )
        }
        // Blank-ish queries return the list untouched (NBSP is trimmed;
        // NEL is not -- it filters to nothing instead).
        for (query in listOf("", " ", "  \t ", "\u00a0")) {
            assertEquals(list, cardFilterOracle158(list, query))
            assertEquals(list, ContactCardPickerFilterPolicy.filter(list, query))
        }
        assertEquals(emptyList(), cardFilterOracle158(list, "\u0085"))
        assertEquals(emptyList(), ContactCardPickerFilterPolicy.filter(list, "\u0085"))
    }
}

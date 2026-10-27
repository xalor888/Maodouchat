package com.maodouchat.ai.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAiGatewayRankTest {

    @Test
    fun `rankSemantic scores local tokens without a network call`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "meeting notes",
            listOf(
                "m1" to "random photo",
                "m2" to "the meeting notes are here",
                "m3" to "meeting later"
            )
        )
        assertEquals(listOf("m2", "m3"), ranked)
    }

    @Test
    fun `blank query returns empty list`() = runBlocking {
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("   ", listOf("m1" to "meeting notes")))
    }

    @Test
    fun `candidates without any token hit are filtered out`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "quarterly report",
            listOf("m1" to "random photo", "m2" to "the quarterly report draft")
        )
        assertEquals(listOf("m2"), ranked)
    }

    @Test
    fun `more token hits rank before fewer hits`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "alpha beta",
            listOf("c1" to "alpha only here", "c2" to "alpha and beta both here")
        )
        assertEquals(listOf("c2", "c1"), ranked)
    }

    @Test
    fun `matching ignores case`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "Meeting",
            listOf("m1" to "MEETING notes are here")
        )
        assertEquals(listOf("m1"), ranked)
    }

    @Test
    fun `scored ranking returns descending scores`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red blue",
            listOf("c1" to "red car", "c2" to "red and blue bike")
        )
        assertEquals(listOf("c2" to 2.0, "c1" to 1.0), scored)
    }

    @Test
    fun `query tokens beyond twelve are ignored`() = runBlocking {
        val query = "apple banana cherry dragon eagle falcon gorilla hotel india juliet kilo lima mike"
        val ranked = LocalAiGateway.rankSemantic(
            query,
            listOf(
                "c1" to "apple banana cherry dragon eagle falcon gorilla hotel india juliet kilo lima",
                "c2" to "mike only"
            )
        )
        assertEquals(listOf("c1"), ranked)
        val scored = LocalAiGateway.rankSemanticScored(query, listOf("c1" to "apple banana cherry dragon eagle falcon gorilla hotel india juliet kilo lima"))
        assertEquals(listOf("c1" to 12.0), scored)
    }

    @Test
    fun `repeated query tokens count once per occurrence`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red red blue",
            listOf("c1" to "red car", "c2" to "blue bike")
        )
        assertEquals(listOf("c1" to 2.0, "c2" to 1.0), scored)
    }

    @Test
    fun `equal scores keep candidate order`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "red",
            listOf("c1" to "red one", "c2" to "red two")
        )
        assertEquals(listOf("c1", "c2"), ranked)
        val scored = LocalAiGateway.rankSemanticScored(
            "red",
            listOf("c1" to "red one", "c2" to "red two")
        )
        assertEquals(listOf("c1" to 1.0, "c2" to 1.0), scored)
    }

    @Test
    fun `substring token matches count without word boundaries`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "meet",
            listOf("m1" to "random photo", "m2" to "meeting notes")
        )
        assertEquals(listOf("m2"), ranked)
    }

    @Test
    fun `tabs and newlines split query tokens like spaces`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "meeting\tnotes",
            listOf("m1" to "random photo", "m2" to "the meeting notes are here")
        )
        assertEquals(listOf("m2"), ranked)
    }

    @Test
    fun `empty candidates rank to empty`() = runBlocking {
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("red", emptyList()))
        assertEquals(emptyList<Pair<String, Double>>(), LocalAiGateway.rankSemanticScored("red", emptyList()))
    }

    @Test
    fun `empty string query ranks to empty`() = runBlocking {
        val candidates = listOf("m1" to "red car")
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("", candidates))
        assertEquals(emptyList<Pair<String, Double>>(), LocalAiGateway.rankSemanticScored("", candidates))
    }

    @Test
    fun `zero score candidates are filtered between equal hits`() = runBlocking {
        val candidates = listOf("c1" to "red one", "c2" to "nothing here", "c3" to "red two")
        assertEquals(listOf("c1", "c3"), LocalAiGateway.rankSemantic("red", candidates))
        assertEquals(
            listOf("c1" to 1.0, "c3" to 1.0),
            LocalAiGateway.rankSemanticScored("red", candidates)
        )
    }

    @Test
    fun `twelfth token counts and earlier tokens score`() = runBlocking {
        // 子串匹配无词边界：用互不为子串的词，避免 w1 命中 w12 这类前缀干扰。
        val query = "apple banana cherry dragon eagle falcon gorilla hotel india juliet kilo lima"
        val scored = LocalAiGateway.rankSemanticScored(
            query,
            listOf("c1" to "lima only", "c2" to "apple banana cherry")
        )
        assertEquals(listOf("c2" to 3.0, "c1" to 1.0), scored)
    }

    @Test
    fun `duplicate candidate ids are both kept`() = runBlocking {
        val candidates = listOf("c1" to "red one", "c1" to "red two")
        assertEquals(listOf("c1", "c1"), LocalAiGateway.rankSemantic("red", candidates))
        assertEquals(
            listOf("c1" to 1.0, "c1" to 1.0),
            LocalAiGateway.rankSemanticScored("red", candidates)
        )
    }

    @Test
    fun `surrounding whitespace in query does not change ranking`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "  alpha beta  ",
            listOf("c1" to "alpha only here", "c2" to "alpha and beta both here")
        )
        assertEquals(listOf("c2", "c1"), ranked)
    }

    @Test
    fun `token repeated in candidate text counts once`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red",
            listOf("c1" to "red red red car")
        )
        assertEquals(listOf("c1" to 1.0), scored)
    }

    @Test
    fun `uppercase query and candidate text score the same`() = runBlocking {
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored("BLUE", listOf("c1" to "BLUE SKY"))
        )
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored("blue", listOf("c1" to "blue sky"))
        )
    }

    @Test
    fun `scored ranking preserves input order across three equal hits`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red",
            listOf("c1" to "red one", "c2" to "red two", "c3" to "red three")
        )
        assertEquals(listOf("c1" to 1.0, "c2" to 1.0, "c3" to 1.0), scored)
    }

    @Test
    fun `missing token contributes zero without penalty`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red blue green",
            listOf("c1" to "red blue")
        )
        assertEquals(listOf("c1" to 2.0), scored)
    }

    @Test
    fun `query token matching a longer candidate word scores per token`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red reddish",
            listOf("c1" to "reddish car")
        )
        assertEquals(listOf("c1" to 2.0), scored)
    }

    @Test
    fun `punctuation attached to a query token breaks the match`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "red,",
            listOf("m1" to "red car")
        )
        assertEquals(emptyList<String>(), ranked)
    }

    @Test
    fun `empty candidate text never scores`() = runBlocking {
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("red", listOf("c1" to "")))
        assertEquals(
            emptyList<Pair<String, Double>>(),
            LocalAiGateway.rankSemanticScored("red", listOf("c1" to ""))
        )
    }

    @Test
    fun `thirteenth duplicate of an earlier token is ignored`() = runBlocking {
        val query = "apple banana cherry dragon eagle falcon gorilla hotel india juliet kilo lima apple"
        val scored = LocalAiGateway.rankSemanticScored(query, listOf("c1" to "apple only"))
        assertEquals(listOf("c1" to 1.0), scored)
        assertEquals(listOf("c1"), LocalAiGateway.rankSemantic(query, listOf("c1" to "apple only")))
    }

    @Test
    fun `token order in query does not affect score`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "beta alpha",
            listOf("c1" to "alpha and beta both here")
        )
        assertEquals(listOf("c1" to 2.0), scored)
    }

    @Test
    fun `thirteenth distinct token is ignored even when it matches`() = runBlocking {
        // 只取前 12 个分词：仅含第 13 个词的候选得 0 分被滤掉，含首词的候选只按首词计分。
        val query = "apple banana cherry dragon eagle falcon gorilla hotel india juliet kilo lima mango"
        assertEquals(
            emptyList<String>(),
            LocalAiGateway.rankSemantic(query, listOf("c1" to "mango only"))
        )
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored(query, listOf("c1" to "apple mango"))
        )
    }

    @Test
    fun `whitespace-only candidate text is filtered`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "red",
            listOf("c1" to "red one", "c2" to "   ", "c3" to "red two")
        )
        assertEquals(listOf("c1", "c3"), ranked)
    }

    @Test
    fun `duplicate text under different ids keeps both in order`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red",
            listOf("c1" to "red one", "c2" to "red one")
        )
        assertEquals(listOf("c1" to 1.0, "c2" to 1.0), scored)
        assertEquals(
            listOf("c1", "c2"),
            LocalAiGateway.rankSemantic("red", listOf("c1" to "red one", "c2" to "red one"))
        )
    }

    @Test
    fun `mixed case multi-token query scores all tokens`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "Red BLUE green",
            listOf("c1" to "red blue green sky")
        )
        assertEquals(listOf("c1" to 3.0), scored)
    }

    @Test
    fun `uppercase substring token matches case-insensitively`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "MEET",
            listOf("m1" to "random photo", "m2" to "MEETING notes")
        )
        assertEquals(listOf("m2"), ranked)
    }

    @Test
    fun `query token repeated counts per query occurrence`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red red",
            listOf("c1" to "red red red car")
        )
        assertEquals(listOf("c1" to 2.0), scored)
        assertEquals(
            listOf("c1"),
            LocalAiGateway.rankSemantic("red red", listOf("c1" to "red red red car"))
        )
    }

    @Test
    fun `descending sort orders mixed scores across candidates`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red blue green",
            listOf("c1" to "red", "c2" to "red blue green", "c3" to "red blue")
        )
        assertEquals(listOf("c2" to 3.0, "c3" to 2.0, "c1" to 1.0), scored)
    }

    @Test
    fun `multiple internal spaces collapse like single spaces`() = runBlocking {
        assertEquals(
            listOf("c1" to 2.0),
            LocalAiGateway.rankSemanticScored("red   blue", listOf("c1" to "red blue"))
        )
    }

    @Test
    fun `mixed case substring token matches`() = runBlocking {
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored("ReD", listOf("c1" to "REDDISH car"))
        )
    }

    @Test
    fun `hyphenated query token stays one token`() = runBlocking {
        val candidates = listOf("c1" to "red-blue sky", "c2" to "red blue sky")
        assertEquals(listOf("c1" to 1.0), LocalAiGateway.rankSemanticScored("red-blue", candidates))
        assertEquals(listOf("c1"), LocalAiGateway.rankSemantic("red-blue", candidates))
    }

    @Test
    fun `non-breaking space does not split query tokens`() = runBlocking {
        // 分词正则 \s+ 只认 ASCII 空白，不间断空格不断词，整串当一个分词匹配不到。
        val candidates = listOf("c1" to "red blue")
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("red blue", candidates))
        assertEquals(
            emptyList<Pair<String, Double>>(),
            LocalAiGateway.rankSemanticScored("red blue", candidates)
        )
    }

    @Test
    fun `single character tokens match across words`() = runBlocking {
        assertEquals(
            listOf("c1" to 2.0),
            LocalAiGateway.rankSemanticScored("d b", listOf("c1" to "red blue"))
        )
    }

    @Test
    fun `regex metacharacters in query token match literally`() = runBlocking {
        // 计分用 contains 子串匹配，分词里的 . 不当正则。
        val candidates = listOf("c1" to "red car", "c2" to "r.d car")
        assertEquals(listOf("c2"), LocalAiGateway.rankSemantic("r.d", candidates))
    }

    @Test
    fun `tie order stays stable after a higher score`() = runBlocking {
        val candidates = listOf("c1" to "red", "c2" to "red blue", "c3" to "blue")
        assertEquals(
            listOf("c2" to 2.0, "c1" to 1.0, "c3" to 1.0),
            LocalAiGateway.rankSemanticScored("red blue", candidates)
        )
    }

    @Test
    fun `carriage return splits query tokens`() = runBlocking {
        assertEquals(
            listOf("c1" to 2.0),
            LocalAiGateway.rankSemanticScored("red\rblue", listOf("c1" to "red blue"))
        )
    }

    @Test
    fun `newline splits query tokens`() = runBlocking {
        assertEquals(
            listOf("c1" to 2.0),
            LocalAiGateway.rankSemanticScored("red\nblue", listOf("c1" to "red blue"))
        )
    }

    @Test
    fun `tab padded query matches like spaces`() = runBlocking {
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored("\tred\t", listOf("c1" to "red car"))
        )
        assertEquals(
            listOf("c1"),
            LocalAiGateway.rankSemantic("\tred\t", listOf("c1" to "red car"))
        )
    }

    @Test
    fun `repeated token across both positions scores per occurrence`() = runBlocking {
        assertEquals(
            listOf("c1" to 3.0),
            LocalAiGateway.rankSemanticScored("red blue red", listOf("c1" to "red blue"))
        )
    }

    @Test
    fun `compound candidate word matches both substring tokens`() = runBlocking {
        assertEquals(
            listOf("c1" to 2.0),
            LocalAiGateway.rankSemanticScored("red blue", listOf("c1" to "redblue"))
        )
        assertEquals(
            listOf("c1"),
            LocalAiGateway.rankSemantic("red blue", listOf("c1" to "redblue"))
        )
    }

    @Test
    fun `candidate text newline does not break substring matching`() = runBlocking {
        assertEquals(
            listOf("c1" to 2.0),
            LocalAiGateway.rankSemanticScored("red blue", listOf("c1" to "red\nblue sky"))
        )
    }

    @Test
    fun `empty candidate text never matches`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "meeting",
            listOf("e1" to "", "m1" to "meeting notes")
        )
        assertEquals(listOf("m1"), ranked)
    }

    @Test
    fun `vertical tab splits query tokens`() = runBlocking {
        assertEquals(
            listOf("c1" to 2.0),
            LocalAiGateway.rankSemanticScored("alpha\u000Bbeta", listOf("c1" to "alpha beta"))
        )
    }

    @Test
    fun `form feed splits query tokens`() = runBlocking {
        assertEquals(
            listOf("c1" to 2.0),
            LocalAiGateway.rankSemanticScored("alpha\u000Cbeta", listOf("c1" to "alpha beta"))
        )
    }

    @Test
    fun `query token order does not change ranking`() = runBlocking {
        val candidates = listOf("c1" to "alpha only", "c2" to "alpha beta")
        assertEquals(listOf("c2", "c1"), LocalAiGateway.rankSemantic("beta alpha", candidates))
        assertEquals(listOf("c2", "c1"), LocalAiGateway.rankSemantic("alpha beta", candidates))
    }

    @Test
    fun `scored ranking for blank query returns empty`() = runBlocking {
        assertEquals(
            emptyList<Pair<String, Double>>(),
            LocalAiGateway.rankSemanticScored("   ", listOf("m1" to "meeting notes"))
        )
    }


    @Test
    fun `empty candidate id is kept when its text matches`() = runBlocking {
        val candidates = listOf("" to "red apple")
        assertEquals(listOf(""), LocalAiGateway.rankSemantic("red", candidates))
        assertEquals(listOf("" to 1.0), LocalAiGateway.rankSemanticScored("red", candidates))
    }

    @Test
    fun `candidate ids are returned verbatim without trim or case folding`() = runBlocking {
        val candidates = listOf("  ID  " to "red one", "MiXeD" to "red two")
        assertEquals(listOf("  ID  ", "MiXeD"), LocalAiGateway.rankSemantic("red", candidates))
        assertEquals(
            listOf("  ID  " to 1.0, "MiXeD" to 1.0),
            LocalAiGateway.rankSemanticScored("red", candidates)
        )
    }

    @Test
    fun `duplicate ids reorder by score`() = runBlocking {
        val candidates = listOf("c1" to "red one", "c1" to "red blue two")
        assertEquals(listOf("c1", "c1"), LocalAiGateway.rankSemantic("red blue", candidates))
        assertEquals(
            listOf("c1" to 2.0, "c1" to 1.0),
            LocalAiGateway.rankSemanticScored("red blue", candidates)
        )
    }

    @Test
    fun `query token longer than candidate text scores zero`() = runBlocking {
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("redbluegreen", listOf("c1" to "red")))
        assertEquals(
            emptyList<Pair<String, Double>>(),
            LocalAiGateway.rankSemanticScored("redbluegreen", listOf("c1" to "red"))
        )
    }

    @Test
    fun `exact duplicate candidates are both kept`() = runBlocking {
        val candidates = listOf("c1" to "red one", "c1" to "red one")
        assertEquals(listOf("c1", "c1"), LocalAiGateway.rankSemantic("red", candidates))
        assertEquals(
            listOf("c1" to 1.0, "c1" to 1.0),
            LocalAiGateway.rankSemanticScored("red", candidates)
        )
    }

    @Test
    fun `accented token matches case-insensitively`() = runBlocking {
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored("CAFÉ", listOf("c1" to "café au lait"))
        )
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored("café", listOf("c1" to "CAFÉ BAR"))
        )
    }

    @Test
    fun `em space does not split query tokens`() = runBlocking {
        val candidates = listOf("e1" to "red\u2003blue sky", "e2" to "red blue sky")
        assertEquals(listOf("e1"), LocalAiGateway.rankSemantic("red\u2003blue", candidates))
        assertEquals(
            listOf("e1" to 1.0),
            LocalAiGateway.rankSemanticScored("red\u2003blue", candidates)
        )
    }

    @Test
    fun `candidate id text is not searched`() = runBlocking {
        val candidates = listOf("red" to "blue sky", "c1" to "red apple")
        assertEquals(listOf("c1"), LocalAiGateway.rankSemantic("red", candidates))
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored("red", candidates)
        )
    }

    @Test
    fun `empty candidate id without a hit is filtered out`() = runBlocking {
        val candidates = listOf("" to "blue", "c1" to "red")
        assertEquals(listOf("c1"), LocalAiGateway.rankSemantic("red", candidates))
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored("red", candidates)
        )
    }

    @Test
    fun `prefix-overlapping tokens both count on the same span`() = runBlocking {
        assertEquals(
            listOf("c1" to 2.0),
            LocalAiGateway.rankSemanticScored("red redd", listOf("c1" to "reddish"))
        )
        assertEquals(
            listOf("c1"),
            LocalAiGateway.rankSemantic("red redd", listOf("c1" to "reddish"))
        )
    }

    @Test
    fun `comma-joined query token matches only as a whole`() = runBlocking {
        val candidates = listOf("c1" to "red,blue sky", "c2" to "red blue sky")
        assertEquals(listOf("c1" to 1.0), LocalAiGateway.rankSemanticScored("red,blue", candidates))
        assertEquals(listOf("c1"), LocalAiGateway.rankSemantic("red,blue", candidates))
    }

    @Test
    fun `trailing period token does not match bare word`() = runBlocking {
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("red.", listOf("m1" to "red car")))
        assertEquals(
            emptyList<Pair<String, Double>>(),
            LocalAiGateway.rankSemanticScored("red.", listOf("m1" to "red car"))
        )
    }

    @Test
    fun `underscore token matches literally`() = runBlocking {
        val candidates = listOf("c1" to "red_blue sky", "c2" to "red blue sky")
        assertEquals(listOf("c1" to 1.0), LocalAiGateway.rankSemanticScored("red_blue", candidates))
        assertEquals(listOf("c1"), LocalAiGateway.rankSemantic("red_blue", candidates))
    }

    @Test
    fun `three identical query tokens triple the score`() = runBlocking {
        assertEquals(
            listOf("c1" to 3.0),
            LocalAiGateway.rankSemanticScored("red red red", listOf("c1" to "red car"))
        )
    }

    @Test
    fun `single-token candidate ranks below both-token candidate`() = runBlocking {
        val candidates = listOf("c1" to "beta only", "c2" to "alpha beta")
        assertEquals(listOf("c2", "c1"), LocalAiGateway.rankSemantic("alpha beta", candidates))
        assertEquals(
            listOf("c2" to 2.0, "c1" to 1.0),
            LocalAiGateway.rankSemanticScored("alpha beta", candidates)
        )
    }

    @Test
    fun `chinese query tokens score per token`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "会议 记录",
            listOf("c1" to "会议记录在这里", "c2" to "会议通知")
        )
        assertEquals(listOf("c1" to 2.0, "c2" to 1.0), scored)
    }

    @Test
    fun `digit tokens score like word tokens`() = runBlocking {
        assertEquals(
            listOf("c1" to 2.0),
            LocalAiGateway.rankSemanticScored("2024 report", listOf("c1" to "2024 annual report"))
        )
    }

    @Test
    fun `plus token matches literally`() = runBlocking {
        val candidates = listOf("c1" to "c++ guide", "c2" to "c guide")
        assertEquals(listOf("c1" to 1.0), LocalAiGateway.rankSemanticScored("c++", candidates))
        assertEquals(listOf("c1"), LocalAiGateway.rankSemantic("c++", candidates))
    }

    @Test
    fun `whitespace-only tab newline query ranks to empty`() = runBlocking {
        val candidates = listOf("c1" to "red car")
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("\t \n", candidates))
        assertEquals(
            emptyList<Pair<String, Double>>(),
            LocalAiGateway.rankSemanticScored("\t \n", candidates)
        )
    }

    @Test
    fun `alphanumeric token scores as one token`() = runBlocking {
        val candidates = listOf("c1" to "abc123 notes", "c2" to "abc notes")
        assertEquals(listOf("c1" to 1.0), LocalAiGateway.rankSemanticScored("abc123", candidates))
        assertEquals(listOf("c1"), LocalAiGateway.rankSemantic("abc123", candidates))
    }

}

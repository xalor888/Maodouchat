package com.maodouchat.domain.messaging

import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// authHeaders restated by hand: Anthropic gets x-api-key plus the
// version header (blank version falls back to the fixed default),
// and a Bearer header only when the key is non-blank; the OpenAI
// protocols get just the Bearer header plus the organization header
// when set; the provider's extra JSON headers are appended last.
internal fun authHeadersOracle172(provider: LocalAiProvider): List<Pair<String, String>> =
    buildList {
        when (provider.protocol) {
            LocalAiProtocol.ANTHROPIC_MESSAGES -> {
                add("x-api-key" to provider.apiKey)
                add(
                    "anthropic-version" to
                        provider.anthropicVersion.ifBlank { "2023-06-01" }
                )
                if (provider.apiKey.isNotBlank()) {
                    add("Authorization" to "Bearer ${provider.apiKey}")
                }
            }
            else -> {
                add("Authorization" to "Bearer ${provider.apiKey}")
                if (provider.organization.isNotBlank()) {
                    add("OpenAI-Organization" to provider.organization)
                }
            }
        }
        extraHeadersOracle172(provider).forEach { (k, v) -> add(k to v) }
    }

// extraHeaders restated by hand: blank JSON means no headers, broken
// JSON means no headers, and only non-blank keys with non-blank
// trimmed values survive. A duplicate key makes org.json throw
// while parsing, so the whole JSON yields no headers at all.
internal fun extraHeadersOracle172(provider: LocalAiProvider): Map<String, String> {
    val raw = provider.extraHeadersJson.ifBlank { "{}" }
    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
    return buildMap {
        obj.keys().forEach { key ->
            val value = obj.optString(key).trim()
            if (key.isNotBlank() && value.isNotBlank()) put(key, value)
        }
    }
}

// Production authHeaders returns the model-endpoint auth headers.
internal fun authHeadersProduction172(provider: LocalAiProvider): List<Pair<String, String>> =
    AgentHttpAuthPolicy.authHeaders(provider)

// ---------- generators ----------

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonAuth172(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
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

internal fun provider172(
    protocol: LocalAiProtocol,
    apiKey: String,
    anthropicVersion: String,
    organization: String,
    extraHeadersJson: String
): LocalAiProvider = LocalAiProvider(
    id = "p1",
    name = "n",
    baseUrl = "https://example.test",
    apiKey = apiKey,
    model = "m",
    protocol = protocol,
    anthropicVersion = anthropicVersion,
    organization = organization,
    extraHeadersJson = extraHeadersJson
)

internal data class AuthCase172(
    val protocol: LocalAiProtocol,
    val apiKey: String,
    val anthropicVersion: String,
    val organization: String,
    val extraHeadersJson: String
)

internal val AUTH_PROTOCOL_POOL172: List<LocalAiProtocol> = listOf(
    LocalAiProtocol.OPENAI_CHAT_COMPLETIONS,
    LocalAiProtocol.OPENAI_RESPONSES,
    LocalAiProtocol.ANTHROPIC_MESSAGES
)

internal val AUTH_API_KEY_POOL172: List<String> = listOf(
    "", "k", "sk-abc", "  ", "key with spaces", "x".repeat(40)
)

internal val AUTH_VERSION_POOL172: List<String> = listOf(
    "", "2023-06-01", "2024-01-01", "  ", "v1"
)

internal val AUTH_ORG_POOL172: List<String> = listOf(
    "", "org-1", "  ", "acme"
)

internal val AUTH_EXTRA_KEY_POOL172: List<String> = listOf(
    "X-Custom", "X-B", "Authorization", "x-api-key", ""
)

internal val AUTH_EXTRA_VALUE_POOL172: List<String> = listOf(
    "v", "  ", "", "1", "a b"
)

// At most one extra header per generated case: org.json keeps its
// keys in hash order, so multi-key JSON would make the Python model
// guess the order. Multi-key headers are covered by the pinned
// set-equality case below instead.
internal val AUTH_EXTRA_JSON_POOL172: List<String> = listOf(
    "", "  ", "{}", "{\"X-Custom\": \"1\"}", "{\"a\": \"b\"}",
    "{invalid", "{\"n\": 5}", "{\"b\": true}", "{\"z\": null}",
    "{\"k\":\"a\",\"k\":\"b\"}", "{\"\": \"v\"}", "{\"k\": \"  \"}",
    "{\"k\": \" v \"}", "[1, 2]", "not json"
)

internal fun randomAuthCase172(rnd: Random): AuthCase172 {
    val extra = if (rnd.nextInt(3) == 0) {
        AUTH_EXTRA_JSON_POOL172[rnd.nextInt(AUTH_EXTRA_JSON_POOL172.size)]
    } else {
        val k = AUTH_EXTRA_KEY_POOL172[rnd.nextInt(AUTH_EXTRA_KEY_POOL172.size)]
        val v = AUTH_EXTRA_VALUE_POOL172[rnd.nextInt(AUTH_EXTRA_VALUE_POOL172.size)]
        "{${escJsonAuth172(k)}: ${escJsonAuth172(v)}}"
    }
    return AuthCase172(
        protocol = AUTH_PROTOCOL_POOL172[rnd.nextInt(AUTH_PROTOCOL_POOL172.size)],
        apiKey = AUTH_API_KEY_POOL172[rnd.nextInt(AUTH_API_KEY_POOL172.size)],
        anthropicVersion = AUTH_VERSION_POOL172[rnd.nextInt(AUTH_VERSION_POOL172.size)],
        organization = AUTH_ORG_POOL172[rnd.nextInt(AUTH_ORG_POOL172.size)],
        extraHeadersJson = extra
    )
}

internal fun authWireJson172(c: AuthCase172): String =
    "{\"protocol\":${escJsonAuth172(c.protocol.name)}," +
        "\"apiKey\":${escJsonAuth172(c.apiKey)}," +
        "\"anthropicVersion\":${escJsonAuth172(c.anthropicVersion)}," +
        "\"organization\":${escJsonAuth172(c.organization)}," +
        "\"extraHeadersJson\":${escJsonAuth172(c.extraHeadersJson)}}"

class AgentHttpAuthPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "", "") to listOf(
                "Authorization" to "Bearer k"
            ),
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "", "", "", "") to listOf(
                "Authorization" to "Bearer "
            ),
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "org-1", "") to listOf(
                "Authorization" to "Bearer k",
                "OpenAI-Organization" to "org-1"
            ),
            // Blank organization adds no header; the version field
            // is ignored outside Anthropic.
            AuthCase172(LocalAiProtocol.OPENAI_RESPONSES, "k", "v9", "  ", "") to listOf(
                "Authorization" to "Bearer k"
            ),
            AuthCase172(LocalAiProtocol.ANTHROPIC_MESSAGES, "k", "", "", "") to listOf(
                "x-api-key" to "k",
                "anthropic-version" to "2023-06-01",
                "Authorization" to "Bearer k"
            ),
            AuthCase172(LocalAiProtocol.ANTHROPIC_MESSAGES, "", "", "", "") to listOf(
                "x-api-key" to "",
                "anthropic-version" to "2023-06-01"
            ),
            AuthCase172(LocalAiProtocol.ANTHROPIC_MESSAGES, "k", "2024-01-01", "org-1", "") to listOf(
                "x-api-key" to "k",
                "anthropic-version" to "2024-01-01",
                "Authorization" to "Bearer k"
            ),
            // Blank version falls back to the default, blank key
            // still drops the Bearer header on Anthropic.
            AuthCase172(LocalAiProtocol.ANTHROPIC_MESSAGES, "  ", "  ", "", "") to listOf(
                "x-api-key" to "  ",
                "anthropic-version" to "2023-06-01"
            ),
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "", "{\"X-Custom\": \"1\"}") to listOf(
                "Authorization" to "Bearer k",
                "X-Custom" to "1"
            ),
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "", "{invalid") to listOf(
                "Authorization" to "Bearer k"
            ),
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "", "{\"n\": 5}") to listOf(
                "Authorization" to "Bearer k",
                "n" to "5"
            ),
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "", "{\"b\": true}") to listOf(
                "Authorization" to "Bearer k",
                "b" to "true"
            ),
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "", "{\"z\": null}") to listOf(
                "Authorization" to "Bearer k"
            ),
            // A duplicate key makes org.json throw while parsing,
            // so the whole JSON yields no extra headers at all.
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "", "{\"k\":\"a\",\"k\":\"b\"}") to listOf(
                "Authorization" to "Bearer k"
            ),
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "", "{\"\": \"v\"}") to listOf(
                "Authorization" to "Bearer k"
            ),
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "", "{\"k\": \"  \"}") to listOf(
                "Authorization" to "Bearer k"
            ),
            AuthCase172(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "", "{\"k\": \" v \"}") to listOf(
                "Authorization" to "Bearer k",
                "k" to "v"
            )
        )
        for ((c, expected) in cases) {
            val provider = provider172(c.protocol, c.apiKey, c.anthropicVersion, c.organization, c.extraHeadersJson)
            assertEquals(expected, authHeadersProduction172(provider), "headers($c)")
            assertEquals(expected, authHeadersOracle172(provider), "oracle($c)")
        }
        // Multi-key extra headers: org.json iterates keys in hash
        // order, so only the set is pinned here, not the order.
        val multi = provider172(
            LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, "k", "", "",
            "{\"A\": \"1\", \"B\": \"2\"}"
        )
        val want = setOf("Authorization" to "Bearer k", "A" to "1", "B" to "2")
        assertEquals(want, authHeadersProduction172(multi).toSet(), "headers(multi)")
        assertEquals(want, authHeadersOracle172(multi).toSet(), "oracle(multi)")
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(172006)
        repeat(4000) {
            val c = randomAuthCase172(rnd)
            val provider = provider172(c.protocol, c.apiKey, c.anthropicVersion, c.organization, c.extraHeadersJson)
            assertEquals(
                authHeadersOracle172(provider),
                authHeadersProduction172(provider),
                "headers(random $c)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every protocol against every key / version / org, plus
        // every extra-JSON shape on every protocol.
        for (protocol in AUTH_PROTOCOL_POOL172) {
            for (apiKey in AUTH_API_KEY_POOL172) {
                for (version in AUTH_VERSION_POOL172) {
                    for (org in AUTH_ORG_POOL172) {
                        val c = AuthCase172(protocol, apiKey, version, org, "")
                        val provider = provider172(c.protocol, c.apiKey, c.anthropicVersion, c.organization, c.extraHeadersJson)
                        assertEquals(
                            authHeadersOracle172(provider),
                            authHeadersProduction172(provider),
                            "sweep($c)"
                        )
                    }
                }
            }
            for (extra in AUTH_EXTRA_JSON_POOL172) {
                val c = AuthCase172(protocol, "k", "", "", extra)
                val provider = provider172(c.protocol, c.apiKey, c.anthropicVersion, c.organization, c.extraHeadersJson)
                assertEquals(
                    authHeadersOracle172(provider),
                    authHeadersProduction172(provider),
                    "sweep-extra($c)"
                )
            }
        }
    }
}

package com.maodouchat.ai.privacy

/**
 * AI Data Egress Manifest and Pre-flight Verification Gate.
 *
 * Governs all outbound data transmission to external or local LLMs.
 *
 * Egress Policy:
 * 1. ALLOWED TO TRANSMIT (with user initiation & consent):
 *    - Explicit user queries entered in the AI assistant interface.
 *    - Non-secret, unlocked chat snippets requested by the user or tools.
 *    - Sanitized and bounded system context (temporal anchor, tool schemas).
 *    - Public channel or post contents.
 *
 * 2. STRICTLY FORBIDDEN TO TRANSMIT (Hard-blocked by gate interceptors):
 *    - Secret Chat (端到端双向棘轮密聊) messages, drafts, metadata, or IDs.
 *    - PIN-locked chat contents when the session is locked.
 *    - Local cryptographic keys (Ed25519, X25519 private keys, MasterKey, AES secrets).
 *    - Cleartext account passwords, refresh tokens, session bearer tokens.
 *    - Unmasked personal phone numbers or credentials.
 */
object AiDataEgressManifest {

    enum class EgressDecision {
        ALLOWED,
        DENIED_SECRET_CHAT,
        DENIED_LOCKED_CHAT,
        DENIED_CREDENTIAL_LEAK,
        DENIED_EMPTY_OR_CORRUPT
    }

    private val SENSITIVE_PATTERN = Regex(
        "(?i)(password|passwd|bearer\\s+[a-z0-9_\\-\\.~]{20,}|private_key|begin[ a-z0-9_-]*private key)"
    )

    /**
     * Pre-flight egress gate check before sending messages or prompts to an LLM provider.
     */
    fun evaluateEgress(
        content: String,
        isSecretChat: Boolean = false,
        isLockedChat: Boolean = false,
        isChatUnlocked: Boolean = false
    ): EgressDecision {
        if (content.isBlank()) return EgressDecision.DENIED_EMPTY_OR_CORRUPT
        if (isSecretChat) return EgressDecision.DENIED_SECRET_CHAT
        if (isLockedChat && !isChatUnlocked) return EgressDecision.DENIED_LOCKED_CHAT
        if (SENSITIVE_PATTERN.containsMatchIn(content)) return EgressDecision.DENIED_CREDENTIAL_LEAK

        return EgressDecision.ALLOWED
    }

    /**
     * Enforces pre-flight gate. Throws SecurityException if prohibited.
     */
    @Throws(SecurityException::class)
    fun enforceEgressGate(
        content: String,
        isSecretChat: Boolean = false,
        isLockedChat: Boolean = false,
        isChatUnlocked: Boolean = false
    ) {
        val decision = evaluateEgress(content, isSecretChat, isLockedChat, isChatUnlocked)
        when (decision) {
            EgressDecision.ALLOWED -> Unit
            EgressDecision.DENIED_SECRET_CHAT ->
                throw SecurityException("AI Egress Violation: Secret chats are strictly forbidden from AI transmission.")
            EgressDecision.DENIED_LOCKED_CHAT ->
                throw SecurityException("AI Egress Violation: Locked chats cannot be transmitted without PIN unlock.")
            EgressDecision.DENIED_CREDENTIAL_LEAK ->
                throw SecurityException("AI Egress Violation: Cleartext credentials or cryptographic keys detected in payload.")
            EgressDecision.DENIED_EMPTY_OR_CORRUPT ->
                throw SecurityException("AI Egress Violation: Outbound payload is empty or corrupted.")
        }
    }

    fun isSafeFromCredentialLeak(text: String): Boolean {
        return !SENSITIVE_PATTERN.containsMatchIn(text)
    }
}

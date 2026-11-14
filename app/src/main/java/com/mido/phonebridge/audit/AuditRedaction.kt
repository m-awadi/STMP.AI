package com.mido.phonebridge.audit

import java.util.Locale

/**
 * What of a request's headers and query may be written to the append-only audit log.
 *
 * A credential must never be stored, because the log cannot be edited afterwards. So every
 * header and every query parameter whose name looks like a credential keeps its NAME (the
 * record still shows that the caller sent one) and loses its VALUE: it is stored as exactly
 * [REDACTED], never as a length, a prefix or a hash.
 *
 * The rule is strict on purpose. The key is lower-cased and `-` and `_` are removed; if the
 * result contains any of [SENSITIVE_SUBSTRINGS] the value is redacted. Innocent names that
 * contain a substring by accident (`monkey`, `keyboard`, `author`) are redacted too: losing a
 * harmless value costs a little detail, leaking a credential cannot be undone.
 *
 * Only true hop-by-hop headers ([DROPPED_HEADERS]) are dropped, and only from headers; query
 * keys are never dropped. Shared with iOS through `contract/audit-redaction.json`.
 */
object AuditRedaction {

    const val REDACTED = "[redacted]"

    /** Headers that say nothing about the caller and everything about TCP. */
    val DROPPED_HEADERS: Set<String> = setOf("host", "content-length", "connection", "accept-encoding")

    /** Matched against the normalised key (lower case, no `-` or `_`). */
    val SENSITIVE_SUBSTRINGS: List<String> = listOf(
        "token", "key", "secret", "auth", "session", "signature", "cookie", "password",
        "authorization", "proxyauthorization", "apikey", "accesstoken", "sig", "jwt",
        "bearer", "credential", "passwd", "pwd",
    )

    fun isSensitive(key: String): Boolean {
        val normalised = key.lowercase(Locale.ROOT).replace("-", "").replace("_", "")
        return SENSITIVE_SUBSTRINGS.any { normalised.contains(it) }
    }

    /** Request headers as the log may keep them: hop-by-hop dropped, credentials redacted. */
    fun headers(raw: Map<String, String>): Map<String, String> =
        raw.filterKeys { it.lowercase(Locale.ROOT) !in DROPPED_HEADERS }.let(::redactValues)

    /** Query parameters as the log may keep them: credentials redacted, nothing dropped. */
    fun query(raw: Map<String, String>): Map<String, String> = redactValues(raw)

    private fun redactValues(raw: Map<String, String>): Map<String, String> =
        raw.mapValues { (key, value) -> if (isSensitive(key)) REDACTED else value }
}

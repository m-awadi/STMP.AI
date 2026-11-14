package com.mido.phonebridge.gate

import com.mido.phonebridge.ApprovalGate.ApproveGesture
import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every user-visible string of the approval gate (WP03a section 7), in one place. */
object GateCopy {

    // --- caller ----------------------------------------------------------------------------------

    /** Who is asking, as far as the caller registry knows. */
    enum class CallerKind { VERIFIED, THIS_PHONE, UNVERIFIED, BLOCKED }

    fun callerState(kind: CallerKind): String = when (kind) {
        CallerKind.VERIFIED -> "From · verified caller"
        CallerKind.THIS_PHONE -> "From · this phone"
        CallerKind.UNVERIFIED -> "From · unverified caller"
        CallerKind.BLOCKED -> "From · blocked caller"
    }

    fun isLoopback(remote: String): Boolean = remote == "127.0.0.1" || remote == "::1" || remote == "localhost"

    /** `<remote>` + ` · as <account>` when there is an account worth naming. */
    fun addressLine(remote: String, account: String): String =
        if (account.isNotBlank() && account != "—") "$remote · as $account" else remote

    fun windowTitle(requester: String): String = "Approve request from $requester"

    /** Title when the gate has no resolved view to name the requester. */
    const val FALLBACK_TITLE = "Request details unavailable"

    // --- readout ---------------------------------------------------------------------------------

    const val READOUT_REQUEST = "Request"
    fun readoutLabel(rowLabel: String): String = "$rowLabel · verbatim"

    // --- AI --------------------------------------------------------------------------------------

    const val AI_LABEL = "AI summary · may be wrong"
    const val AI_UNAVAILABLE = "AI summary · unavailable"
    /** Static twin of the animated "thinking…" words, so the two states read the same. */
    const val AI_LOADING_REDUCED = "thinking…"

    /** Kept for callers that want one line. The gate itself shows the risk on its own line. */
    fun aiReadyLabel(risk: Risk): String = "$AI_LABEL · ${risk.name}"

    /** The AI's risk call, named as what it is so "HIGH" cannot be read as confidence. */
    fun aiRiskLine(risk: Risk): String = "${risk.name} risk"
    fun aiFailed(detail: String): String = "Could not summarise — $detail. You can still decide."
    /** Spoken text: the model's summary is untrusted, so hidden characters are read as their tokens. */
    fun aiAnnounceReady(description: String): String =
        "AI summary: ${com.mido.phonebridge.ui.UntrustedText.escape(description)}"
    const val AI_ANNOUNCE_FAILED = "AI summary unavailable"

    /**
     * Words cycled while the model works, so the wait reads as thinking rather than hanging
     * (Mido, 2026-10-19). Honest about being indeterminate: there is no token stream to measure.
     */
    val LOADING_WORDS = listOf("thinking", "wondering", "considering", "reading", "summarising", "weighing it up")
    const val DOTS_MAX = 3
    fun loading(wordIndex: Int, dots: Int): String =
        LOADING_WORDS[wordIndex % LOADING_WORDS.size] + ".".repeat(dots.coerceIn(0, DOTS_MAX))

    // --- arming and timer ------------------------------------------------------------------------

    const val ARM_SUMMARY_PENDING = "waiting for the AI summary"
    fun armGuard(seconds: Long): String = "ready in ${seconds.coerceAtLeast(1)} s"
    fun armArmed(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "auto-deny in %d:%02d".format(Locale.US, s / 60, s % 60)
    }
    fun trailing(requestId: Long): String = "REQ $requestId"
    fun queue(behind: Int): String = "$behind waiting behind this one"

    // --- rows ------------------------------------------------------------------------------------

    const val ROWS_TITLE = "Request details"
    const val NOTIFICATION_TITLE = "On this notification"
    const val FILTER = "FILTER"
    const val REPLY = "REPLY"
    const val OTHER_ACTIONS = "OTHER ACTIONS"
    const val MESSAGES = "MESSAGES"
    fun messages(n: Int): String = "$n in this conversation"
    const val FALLBACK_SOURCE = "SOURCE"
    const val FALLBACK_ENDPOINT = "ENDPOINT"
    const val FALLBACK_SUMMARY = "SUMMARY"

    // --- exception -------------------------------------------------------------------------------

    /**
     * The exception key says what it does (D64). Where a tap approves (TAP, TAP_THEN_BIOMETRIC) it is
     * "approve + add an exception". On a HOLD gate a tap only arms the exception and the hold
     * approves (D58), so the key says "also" and shows whether it is armed.
     */
    fun exceptionKey(gesture: ApproveGesture, on: Boolean): String = when {
        gesture != ApproveGesture.HOLD -> EXCEPTION_APPROVE_KEY
        on -> "$EXCEPTION_ALSO_KEY · ON"
        else -> EXCEPTION_ALSO_KEY
    }

    const val EXCEPTION_APPROVE_KEY = "APPROVE + ADD EXCEPTION"
    const val EXCEPTION_ALSO_KEY = "ALSO ADD EXCEPTION"

    /** D21: a plugin is named by its display name, never its id. */
    fun pluginName(id: String): String = PluginRegistry.all().firstOrNull { it.id == id }?.displayName ?: id

    fun modeName(mode: Mode): String = when (mode) {
        Mode.DONT_DISTURB -> "Don't disturb"
        Mode.PARANOID -> "Paranoid"
    }

    /** D64: user-facing copy says "exception", never "rule". */
    fun exceptionCaption(gesture: ApproveGesture, on: Boolean, rule: Rule): String {
        val plugin = pluginName(rule.plugin)
        val verb = rule.verb?.name?.lowercase() ?: "any verb"
        val mode = modeName(rule.mode)
        return when {
            gesture != ApproveGesture.HOLD -> "Approves this request and sets $plugin · $verb to $mode from now on."
            on -> "The exception is saved when you hold the key: $plugin · $verb → $mode."
            else -> "Tap to also save this exception, then hold the key: $plugin · $verb → $mode."
        }
    }

    // --- password --------------------------------------------------------------------------------

    const val PASSWORD_LABEL = "Sudo password"
    const val PASSWORD_HINT = "sudo password"
    const val PASSWORD_CAPTION = "Runs as root. The password goes to sudo's stdin on this phone, never over the network."

    // --- biometric -------------------------------------------------------------------------------

    const val BIO_UNAVAILABLE_TITLE = "Approve is unavailable"
    const val BIO_NOT_ENROLLED = "No fingerprint is enrolled. Add one in Android Settings, then come back — Deny still works."
    const val BIO_PIN_TITLE = "Fingerprint unavailable"
    const val BIO_USE_PIN = "Fingerprint is unavailable right now. Use your phone PIN to approve."
    const val BIO_LOCKED_TITLE = "Fingerprint unavailable"
    const val BIO_LOCKED = "Fingerprint is unavailable right now, and this phone has no PIN, pattern or password to use instead. " +
        "Set a screen lock in Android Settings, or Deny. This screen checks again on its own."
    /** The receipt's "by" for an approval that the phone PIN (or a fingerprint in the PIN prompt) gave. */
    const val BY_DEVICE_CREDENTIAL = "device credential"
    const val BIO_VAULT_BROKEN = "The vault key is invalid or was reset (a new fingerprint can do this). " +
        "Reset the vault in Settings → Secret vault and add the secrets again. Deny still works."
    const val BIO_SUBTITLE_FALLBACK = "STMP"

    /**
     * The biometric prompt's subtitle: the caller-supplied "$method $path", made safe for a system
     * dialog (one line, no spans, little room): hidden characters, newline and tab written out as
     * `\u{XXXX}` text, and capped with a visible "(+N chars)" ending. See
     * [com.mido.phonebridge.ui.UntrustedText.escapeForSystemSurface].
     */
    fun bioSubtitle(endpoint: String?): String =
        endpoint?.let { com.mido.phonebridge.ui.UntrustedText.escapeForSystemSurface(it) } ?: BIO_SUBTITLE_FALLBACK

    const val APPROVE_WITH_FINGERPRINT = "Approve, then confirm with your fingerprint"

    // --- keys and expander -----------------------------------------------------------------------

    const val DENY = "DENY"
    const val APPROVE = "APPROVE"
    const val HOLD_CAPTION = "TO APPROVE"
    const val RAW_REQUEST = "Raw request"
    const val SHOW = "SHOW"
    const val HIDE = "HIDE"
    fun raw(endpoint: String, detail: String?): String = "endpoint: $endpoint\n\n${detail ?: "(no detail)"}"

    // --- punch -----------------------------------------------------------------------------------

    fun punchMeta(requestId: Long, decidedAtMs: Long): String =
        "№ $requestId · ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(decidedAtMs))}"

    // --- contacts permission ---------------------------------------------------------------------

    const val CONTACTS_TITLE = "Contacts permission needed"
    const val CONTACTS_CAN_ASK = "Without it this request cannot run: the contacts search would tell your agent " +
        "that contacts are unavailable.\n\nTap Approve to be asked again."
    const val CONTACTS_CANNOT_ASK = "Android will not show that prompt again.\n\nTurn on Contacts in Android " +
        "Settings → Apps → STMP → Permissions, then approve this request again."
    const val CONTACTS_OK = "OK"
    const val CONTACTS_OPEN_SETTINGS = "Open Settings"
    const val CONTACTS_CANCEL = "Cancel"
}

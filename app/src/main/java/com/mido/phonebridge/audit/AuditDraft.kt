package com.mido.phonebridge.audit

import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.core.labelOf
import com.mido.phonebridge.core.originOf
import java.util.Locale

/**
 * One request's audit record while it is still being assembled.
 *
 * A draft exists because the record's fields arrive at different times and some of
 * them can only be known at the end: the route after resolution, the outcome after
 * the gate, the body before parsing, the duration at the close. Writing a record at
 * each stage would either duplicate lines or lose the earlier facts.
 *
 * **A draft with no outcome refuses to freeze.** [toRecord] throws rather than
 * defaulting, because a record with a guessed terminal state is worse than no
 * record: it would claim the request ended a particular way when nothing decided
 * that. The dispatcher's job is to set exactly one outcome on every path, and this
 * is the check that makes that a requirement rather than a convention.
 *
 * The setters are named like the fields they set (`summary`, `outcome`, `response`)
 * rather than `withX`, so a call site reads as a sentence: `draft.outcome(...)`.
 */
class AuditDraft(
    private val startedAtMs: Long,
    private val remote: String,
    /**
     * What to CALL the caller in the log: a human-given name, `this device`, or an explicit
     * `unverified caller <ip>`.
     *
     * Required, with no default. It used to be derived here from [remote] by matching one
     * hardcoded IP and otherwise echoing the address — a fallback dressed as a label, which made
     * "named `192.168.1.5`" indistinguishable from "never named at all". Only the caller registry
     * knows the difference, so only the dispatcher can supply this.
     */
    private var requesterLabelValue: String,
    private val method: String,
    private val path: String,
    private val query: Map<String, String>,
    private val requestHeaders: Map<String, String>,
    private val appVersionName: String,
    private val appVersionCode: Int,
) {
    var plugin: String = ""
        private set

    var verb: Verb? = null
        private set

    /**
     * Replace the caller label mid-request.
     *
     * Exists for exactly one case: an unverified caller that a human NAMES while this very
     * request waits. The label captured at construction would say "unverified"; by the time the
     * record is written the name is known, and the log should say what actually happened.
     */
    fun requesterLabel(label: String) {
        requesterLabelValue = label
    }

    /** `METHOD /route/{template}` — the template, never the concrete path. */
    var route: String = ""
        private set

    var summary: String = ""
        private set

    var requestBody: String = ""
        private set

    var outcome: AuditOutcome? = null
        private set

    var responseStatus: Int = 0
        private set

    var responseBody: String = ""
        private set

    var error: String? = null
        private set

    /** True once a route matched, which is what gives the record a plugin and a verb. */
    var routed: Boolean = false
        private set

    private var decision: Decision? = null

    @Volatile
    var written: Boolean = false
        private set

    private var requestBodySkipped = false
    private var responseBodySkipped = false

    /** Whether a human (or a rule) was consulted. Distinguishes "no gate" from "gate said yes". */
    fun gated(): Boolean = decision != null

    /**
     * Record which route answered, BEFORE anything can fail.
     *
     * Called immediately after resolution so a request that is rejected a moment
     * later still carries its plugin and verb. Without that the most interesting
     * failures — the ones that died during validation — would be unattributable.
     */
    fun bindRoute(plugin: String, verb: Verb, routeTemplate: String) {
        this.plugin = plugin
        this.verb = verb
        this.route = "${method.uppercase(Locale.ROOT)} $routeTemplate"
        this.routed = true
    }

    fun summary(s: String) {
        this.summary = s
    }

    /**
     * The request body EXACTLY as sent, called before parsing.
     *
     * The order is the point: a body that fails to parse must still leave evidence.
     * Parsing first and storing second leaves the log saying `invalid JSON body` while
     * keeping not one byte of what actually arrived — a dead end that cost a debugging
     * session to find.
     */
    fun requestBody(raw: String) {
        this.requestBody = raw
    }

    /** This route carries bytes the log must not keep (a file upload). */
    fun requestBodyNotStored() {
        this.requestBody = ""
        this.requestBodySkipped = true
    }

    fun decision(d: Decision) {
        this.decision = d
    }

    fun outcome(o: AuditOutcome) {
        this.outcome = o
    }

    fun response(status: Int, body: String?) {
        this.responseStatus = status
        this.responseBody = body ?: ""
    }

    /** This route's reply is the log itself, or otherwise must not be kept. */
    fun responseNotStored(status: Int) {
        this.responseStatus = status
        this.responseBody = ""
        this.responseBodySkipped = true
    }

    fun error(e: String?) {
        this.error = e
    }

    /** Whether a terminal outcome has been set. Exactly one per request. */
    fun isTerminal(): Boolean = outcome != null

    fun markWritten() {
        written = true
    }

    /**
     * Freeze into the record that will be written.
     *
     * [finishedAtMs] is passed in rather than read here so the duration is measured
     * from a clock reading taken at the close of the request, not at some arbitrary
     * point inside record construction.
     */
    fun toRecord(finishedAtMs: Long): AuditRecord {
        val terminal = outcome
            ?: throw IllegalStateException("audit draft for $method $path has no outcome")

        val boundVerb = verb
        val verbName = boundVerb?.name ?: ""
        val label = if (plugin.isNotBlank() && boundVerb != null) labelOf(plugin, boundVerb) else ""

        val bodyCap = AuditBlobs.cap(requestBody)
        val respCap = AuditBlobs.cap(responseBody)

        return AuditRecord(
            id = AuditId.next(finishedAtMs),
            ts = finishedAtMs,
            outcome = terminal,
            label = label,
            plugin = plugin,
            verb = verbName,
            origin = originOf(remote).name,
            remote = remote,
            requester = requesterLabelValue,
            route = route,
            method = method.uppercase(Locale.ROOT),
            path = path,
            query = AuditRedaction.query(query),
            summary = summary,
            requestHeaders = AuditRedaction.headers(requestHeaders),
            requestBody = bodyCap.text,
            responseStatus = responseStatus,
            responseBody = respCap.text,
            error = error,
            // A request that never reached a gate still needs a decision block, and
            // SECURE/DEFAULT is the honest reading of "the gate would have run".
            decision = decision
                ?: Decision("SECURE", DecisionSource.DEFAULT, null, null, null, null, null, 0),
            durationMs = finishedAtMs - startedAtMs,
            truncated = Truncation(
                requestBody = bodyCap.originalBytes,
                responseBody = respCap.originalBytes,
                responseBodyAbsent = responseBodySkipped,
                requestBodyAbsent = requestBodySkipped,
            ),
            noise = AuditNoise.isNoise(terminal, path),
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
        )
    }
}

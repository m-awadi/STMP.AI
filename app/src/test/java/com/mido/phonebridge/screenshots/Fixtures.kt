package com.mido.phonebridge.screenshots

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.BridgeService
import com.mido.phonebridge.NotifListenerService
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginPermission
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.UpdatePrefs
import com.mido.phonebridge.core.Updater
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.PolicyStore
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.TimeZone

/**
 * State setters for screenshot tests (TESTING 5). They change only in-memory or sandbox state and
 * every one has a matching [reset], called from `@After`.
 */
object Fixtures {

    /** A fixed epoch for fixtures that print an absolute time: 2026-11-11 09:40:00 UTC. */
    const val NOW_MS = 1_794_390_000_000L

    private const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private val SINGLETON_HOLDERS = listOf(
        "com.mido.phonebridge.policy.PolicyStore",
        "com.mido.phonebridge.audit.AuditLog",
        "com.mido.phonebridge.core.PluginSettingsStore",
        "com.mido.phonebridge.callers.CallerStore",
    )

    private var previousZone: TimeZone? = null

    val app: Application get() = ApplicationProvider.getApplicationContext()

    // --- clock ---------------------------------------------------------------------------------

    fun freezeClock() {
        if (previousZone == null) previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    // --- process-wide singletons (each test gets a fresh filesDir, the singletons keep the old one)

    fun dropSingletons() {
        for (name in SINGLETON_HOLDERS) {
            val cls = Class.forName(name)
            val field = cls.declaredFields.firstOrNull { it.name == "singleton" } ?: continue
            field.isAccessible = true
            field.set(null, null)
        }
    }

    // --- Home: server status ---------------------------------------------------------------------

    enum class Server { STOPPED, RUNNING_HEALTHY, RUNNING_ATTENTION, UNRESPONSIVE, FAILED }

    fun server(state: Server) {
        BridgeService.serverUp = state != Server.STOPPED && state != Server.FAILED
        BridgeService.lastError = if (state == Server.FAILED) "Could not bind port 8787: Address already in use" else ""
        BridgeService.bindAddress = if (BridgeService.serverUp) "192.168.1.100" else ""
        // Fresh probe for HEALTHY/ATTENTION, a stale one (older than 3 watchdog intervals) for UNRESPONSIVE.
        BridgeService.lastProbeAtMs = when (state) {
            Server.UNRESPONSIVE -> 1L
            else -> System.currentTimeMillis()
        }
        BridgeService.lastProbeOk = state != Server.UNRESPONSIVE
        val notifListener = state == Server.RUNNING_HEALTHY || state == Server.UNRESPONSIVE
        BridgeService.listenerRef =
            if (notifListener) Robolectric.setupService(NotifListenerService::class.java) else null
        val shadow = shadowOf(app)
        if (state == Server.RUNNING_ATTENTION) shadow.denyPermissions(POST_NOTIFICATIONS)
        else shadow.grantPermissions(POST_NOTIFICATIONS)
    }

    fun resetServer() {
        BridgeService.serverUp = false
        BridgeService.lastError = ""
        BridgeService.bindAddress = ""
        BridgeService.lastProbeAtMs = 0L
        BridgeService.lastProbeOk = false
        BridgeService.listenerRef = null
    }

    // --- gate mode -----------------------------------------------------------------------------

    fun gate(mode: GlobalMode) {
        val store = PolicyStore.from(app)
        store.set(store.get().withGlobal(mode))
    }

    /** The policy file holds bytes no parser accepts. */
    fun gateUnreadable() {
        val dir = File(app.filesDir, "policy").apply { mkdirs() }
        File(dir, "policy.json").writeText("not json {{{")
        dropSingletons()
    }

    // --- audit health --------------------------------------------------------------------------

    fun auditDegraded() = AuditHealth.markDegraded("fixture: the request log could not be written")
    fun resetAudit() = AuditHealth.clear()

    // --- updates card --------------------------------------------------------------------------

    // `System.currentTimeMillis()` is the real clock under Robolectric, and the card prints "N h ago"
    // from it, so the fixture is relative to the real now: "4 h ago" holds for any run that renders
    // within an hour of building the fixture.
    fun updatesUpToDate() {
        val at = System.currentTimeMillis() - FOUR_HOURS_MS
        UpdatePrefs.setLastCheckAt(app, at)
        Updater.lastOutcome = Updater.Outcome.UpToDate(at)
    }

    fun updatesAvailable() {
        UpdatePrefs.setLastCheckAt(app, System.currentTimeMillis() - FOUR_HOURS_MS)
        Updater.lastOutcome = Updater.Outcome.Available(
            version = "99.0.0",
            versionCode = 9900,
            notes = "## 99.0.0 (build 9900) \u2014 fixture\n\n- Dark mode follows the system.\n- Text is set in `Geist`.",
            apkUrl = "https://example.invalid/stmp-v99.0.0.apk",
            sha256 = null,
            sizeBytes = 71_303_168L,
            isBeta = false,
        )
    }

    fun resetUpdates() {
        Updater.lastOutcome = null
        Updater.checking = false
    }

    private const val FOUR_HOURS_MS = 4 * 60 * 60 * 1000L

    // --- permissions ---------------------------------------------------------------------------

    fun contactsPermission(granted: Boolean) {
        val shadow = shadowOf(app)
        if (granted) shadow.grantPermissions(READ_CONTACTS) else shadow.denyPermissions(READ_CONTACTS)
    }

    // --- Settings ------------------------------------------------------------------------------

    fun aiBackend(backend: com.mido.phonebridge.policy.AiBackend, url: String = "") {
        val store = PolicyStore.from(app)
        store.set(store.get().withAiBackend(backend).withAiExternalUrl(url))
    }

    fun callers(vararg entries: Pair<String, com.mido.phonebridge.callers.CallerState>) {
        val store = com.mido.phonebridge.callers.CallerStore.from(app)
        entries.forEach { (ip, state) -> store.set(ip, state, NOW_MS) }
    }

    /**
     * Replaces the on-screen debug log with fixed lines. The ring is process-wide and stamps real
     * wall-clock times, so it is filled through its field instead of `Debug.log`.
     */
    fun debugLog(lines: List<String>) {
        val field = com.mido.phonebridge.Debug::class.java.getDeclaredField("lines").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ring = field.get(null) as ArrayDeque<String>
        ring.clear()
        ring.addAll(lines)
        com.mido.phonebridge.DebugLogOpen.open = true
    }

    fun resetDebugLog() {
        val field = com.mido.phonebridge.Debug::class.java.getDeclaredField("lines").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as ArrayDeque<String>).clear()
        com.mido.phonebridge.DebugLogOpen.open = false
    }

    // --- plugins -------------------------------------------------------------------------------

    /**
     * Registers the production plugin set the way `BridgeService.registerPlugins` does, on a fresh
     * registry: the registry is process-wide and its plugins hold the first test's Context.
     */
    fun registerPlugins() {
        clearPlugins()
        val service = Robolectric.buildService(BridgeService::class.java).get()
        BridgeService::class.java.getDeclaredMethod("registerPlugins")
            .apply { isAccessible = true }
            .invoke(service)
    }

    fun clearPlugins() {
        val cls = com.mido.phonebridge.core.PluginRegistry::class.java
        for (name in listOf("plugins", "routes")) {
            val f = cls.getDeclaredField(name).apply { isAccessible = true }
            when (val v = f.get(com.mido.phonebridge.core.PluginRegistry)) {
                is MutableMap<*, *> -> v.clear()
                is MutableList<*> -> v.clear()
            }
        }
        com.mido.phonebridge.core.PluginRegistry.enabledLookup = null
    }

    // --- request log -----------------------------------------------------------------------------

    /** `count` records, newest last, one minute apart from [NOW_MS]; ids are fixed. */
    fun auditRecords(count: Int): List<String> {
        val store = com.mido.phonebridge.audit.AuditLog.from(app)
        val outcomes = listOf(
            com.mido.phonebridge.audit.AuditOutcome.APPROVED,
            com.mido.phonebridge.audit.AuditOutcome.DENIED,
            com.mido.phonebridge.audit.AuditOutcome.NOTIFY_APPROVED,
            com.mido.phonebridge.audit.AuditOutcome.TIMEOUT,
        )
        return (0 until count).map { i ->
            val id = "2026-11-11T09-40-%02d-fixture%02d".format(i, i)
            store.append(auditRecord(id, NOW_MS + i * 60_000L, outcomes[i % outcomes.size], i))
            id
        }
    }

    internal fun auditRecord(id: String, ts: Long, outcome: com.mido.phonebridge.audit.AuditOutcome, i: Int) =
        com.mido.phonebridge.audit.AuditRecord(
            id = id, ts = ts, outcome = outcome,
            label = "contacts.read", plugin = "contacts", verb = "READ",
            origin = "TAILNET", remote = "100.64.0.7", requester = "Work laptop",
            route = "GET /contacts/search", method = "GET", path = "/contacts/search",
            query = mapOf("q" to "sara"), summary = "Search contacts for sara ($i)",
            requestHeaders = mapOf("Host" to "192.168.1.100:8787"), requestBody = "",
            responseStatus = if (outcome == com.mido.phonebridge.audit.AuditOutcome.DENIED) 403 else 200,
            responseBody = "{\"results\":[]}", error = null,
            decision = com.mido.phonebridge.audit.Decision(
                mode = "SECURE", source = com.mido.phonebridge.audit.DecisionSource.GLOBAL, globalMode = "PARANOID",
                enqueuedAt = ts, shownAt = ts + 100, decidedAt = ts + 2_500,
            ),
            durationMs = 2_600L,
            truncated = com.mido.phonebridge.audit.Truncation(0, 0),
            noise = false, appVersionName = "0.48.19", appVersionCode = 295,
        )

    // --- exceptions ----------------------------------------------------------------------------

    fun exceptions(vararg rules: com.mido.phonebridge.policy.Rule) {
        val store = PolicyStore.from(app)
        store.set(rules.fold(store.get()) { p, r -> p.withRule(r) })
    }

    // --- WP07: Settings -----------------------------------------------------------------------

    private const val LISTENER = "com.mido.phonebridge/com.mido.phonebridge.NotifListenerService"

    /** A sparse file of [length] bytes where the on-device model lives, or none when null. */
    fun modelFile(length: Long?) {
        val f = com.mido.phonebridge.ai.ModelRegistry.modelFile(app)
        f.parentFile?.mkdirs()
        f.delete()
        // Seek and write the last byte: the file stays sparse. `setLength` can allocate the whole
        // length on some JDKs, which fills a nearly full disk with fixture bytes.
        if (length != null && length > 0) {
            java.io.RandomAccessFile(f, "rw").use { it.seek(length - 1); it.write(0) }
        }
    }

    fun provisioner(running: Boolean, progress: com.mido.phonebridge.ai.ModelProvisioner.Progress?, lastError: String) =
        com.mido.phonebridge.ai.ModelProvisioner.setStateForTest(running, progress, lastError)

    /** Notification access: [granted] in Android settings, [connected] when the listener service is bound. */
    fun notificationListener(granted: Boolean, connected: Boolean) {
        android.provider.Settings.Secure.putString(
            app.contentResolver, "enabled_notification_listeners", if (granted) LISTENER else "",
        )
        BridgeService.listenerRef =
            if (connected) Robolectric.setupService(NotifListenerService::class.java) else null
    }

    fun systemAccess(overlay: Boolean, batteryExempt: Boolean, postNotifications: Boolean, phoneDnd: Boolean) {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(overlay)
        val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, batteryExempt)
        val shadow = shadowOf(app)
        if (postNotifications) shadow.grantPermissions(POST_NOTIFICATIONS) else shadow.denyPermissions(POST_NOTIFICATIONS)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        nm.setInterruptionFilter(
            if (phoneDnd) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
        )
    }

    fun autoStartAndSound(autoStart: Boolean, sound: Boolean) {
        val store = PolicyStore.from(app)
        store.set(store.get().withAutoStartOnBoot(autoStart).withSoundEffects(sound))
    }

    fun externalModel(url: String, model: String, dialect: com.mido.phonebridge.policy.ExternalDialect?) {
        val store = PolicyStore.from(app)
        var p = store.get().withAiBackend(com.mido.phonebridge.policy.AiBackend.EXTERNAL).withAiExternalUrl(url)
        if (dialect != null) p = p.withAiExternalModel(model, dialect)
        store.set(p)
    }

    /** Bytes no JSON parser accepts where the caller registry lives. */
    fun callersUnreadable() {
        val dir = File(app.filesDir, "callers").apply { mkdirs() }
        File(dir, "callers.json").writeText("not json {{{")
        dropSingletons()
    }

    fun resetSettings() {
        runCatching { com.mido.phonebridge.ai.ModelRegistry.modelFile(app).delete() }
        com.mido.phonebridge.ai.ModelProvisioner.setStateForTest(false, null, "")
        android.provider.Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
    }

    // --- WP04: Home ------------------------------------------------------------------------------

    /** A running bridge with exactly one of the two attention reasons (or both when both are false). */
    fun serverAttention(listenerOk: Boolean, notifOk: Boolean) {
        server(Server.RUNNING_HEALTHY)
        BridgeService.listenerRef =
            if (listenerOk) Robolectric.setupService(NotifListenerService::class.java) else null
        val shadow = shadowOf(app)
        if (notifOk) shadow.grantPermissions(POST_NOTIFICATIONS) else shadow.denyPermissions(POST_NOTIFICATIONS)
    }

    /** Fingerprint hardware missing, so the Home rocker is disabled and says why. */
    fun fingerprintUnusable() {
        val manager = app.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBiometricManager>(manager)
            .setCanAuthenticate(false)
    }

    /** The sensor is locked out (a temporary fault): the switch stays as stored and says to use the PIN. */
    fun fingerprintTemporarilyUnavailable() {
        com.mido.phonebridge.Fingerprint.canAuthenticateForTest = com.mido.phonebridge.Fingerprint.BIOMETRIC_ERROR_LOCKOUT
        com.mido.phonebridge.Fingerprint.deviceSecureForTest = true
    }

    fun resetFingerprint() {
        com.mido.phonebridge.Fingerprint.canAuthenticateForTest = null
        com.mido.phonebridge.Fingerprint.deviceSecureForTest = null
    }

    /**
     * Three visible requests (READ, EXECUTE, DELETE) plus one noise row that must not show,
     * one minute apart from [NOW_MS].
     */
    fun auditTape() {
        val store = com.mido.phonebridge.audit.AuditLog.from(app)
        val verbs = listOf("READ", "EXECUTE", "DELETE")
        verbs.forEachIndexed { i, verb ->
            val id = "2026-11-11T09-4$i-00-tape0$i"
            store.append(
                auditRecord(id, NOW_MS + i * 60_000L, com.mido.phonebridge.audit.AuditOutcome.APPROVED, i)
                    .copy(verb = verb, summary = "Request number $i ($verb)"),
            )
        }
        store.append(
            auditRecord("2026-11-11T09-59-00-noise00", NOW_MS + 10 * 60_000L, com.mido.phonebridge.audit.AuditOutcome.APPROVED, 9)
                .copy(noise = true, summary = "Background poll"),
        )
    }

    // --- R3 ---

    /** A request, a fingerprint-only settings change and a no-op settings save, newest last. */
    fun auditTapeGateChanges() {
        val store = com.mido.phonebridge.audit.AuditLog.from(app)
        val approved = com.mido.phonebridge.audit.AuditOutcome.APPROVED
        store.append(
            auditRecord("2026-11-11T09-40-00-r3a", NOW_MS, approved, 0)
                .copy(verb = "READ", summary = "Read the plugin help"),
        )
        val fp = com.mido.phonebridge.audit.PolicyAudit.recordFingerprint(true, GlobalMode.WHITELIST, "settings")
        store.append(fp.copy(id = "2026-11-11T09-41-00-r3b", ts = NOW_MS + 60_000L))
        val noOp = com.mido.phonebridge.audit.PolicyAudit.record(GlobalMode.WHITELIST, GlobalMode.WHITELIST, "settings")
        store.append(noOp.copy(id = "2026-11-11T09-42-00-r3c", ts = NOW_MS + 120_000L))
    }

    fun updatesChecking() { Updater.lastOutcome = null; Updater.checking = true }

    fun updatesInstalling() {
        updatesAvailable()
        Updater.installingVersion = "0.49.0"
        Updater.installing = true
    }

    fun updatesAvailableBeta() {
        updatesAvailable()
        UpdatePrefs.setChannel(app, UpdatePrefs.Channel.BETA)
        Updater.lastOutcome = (Updater.lastOutcome as Updater.Outcome.Available).copy(isBeta = true)
    }

    fun updatesUnavailable() {
        UpdatePrefs.setLastCheckAt(app, System.currentTimeMillis() - FOUR_HOURS_MS)
        Updater.lastOutcome = Updater.Outcome.Unavailable("GitHub did not answer")
    }

    fun resetHome() {
        deleteSelfReports()
        Updater.installing = false
        Updater.installingVersion = null
        Updater.installCancelled = false
    }

    private const val CRASH_FILE = "last-crash.txt"
    private const val STALL_FILE = "last-stall.txt"

    private fun deleteSelfReports() {
        listOf(CRASH_FILE, STALL_FILE).forEach { java.io.File(app.filesDir, it).delete() }
    }

    /** Home crash panel: a recorded crash and/or freeze report (R5). */
    fun selfReports(crash: Boolean, stall: Boolean) {
        deleteSelfReports()
        val header = "at=1\nversion=0.52.12 (327)\n"
        if (crash) java.io.File(app.filesDir, CRASH_FILE).writeText(
            header + "thread=main\njava.lang.IllegalStateException: Vault is locked while a request was in flight\n" +
                "    at com.mido.phonebridge.SecretVault.read(SecretVault.kt:212)\n" +
                "    at com.mido.phonebridge.BridgeService.handle(BridgeService.kt:480)\n",
        )
        if (stall) java.io.File(app.filesDir, STALL_FILE).writeText(
            header + "thread=stall-watch\nfrozen thread=main\n" +
                "    at com.mido.phonebridge.HomeFragment.render(HomeFragment.kt:377)\n" +
                "    at android.os.Handler.handleCallback(Handler.java:942)\n",
        )
    }

    // --- WP08: request log, request detail, Exceptions -------------------------------------------

    /** First record of the log fixtures: 2026-11-10 21:41:22 UTC. */
    const val LOG_BASE_MS = 1_794_346_882_505L

    private var previousZoneProperty: String? = null
    private var zonePinned = false

    /** Pins the phone's zone to UTC through the property `TimeZone.setDefault(null)` re-reads, and the log's "now". */
    fun pinLogClock() {
        if (!zonePinned) {
            previousZoneProperty = System.getProperty("user.timezone")
            zonePinned = true
        }
        System.setProperty("user.timezone", "UTC")
        TimeZone.setDefault(null)
        com.mido.phonebridge.LogActivity.nowMs = { NOW_MS }
    }

    private fun unpinLogClock() {
        com.mido.phonebridge.LogActivity.nowMs = { System.currentTimeMillis() }
        if (!zonePinned) return
        val prev = previousZoneProperty
        if (prev == null) System.clearProperty("user.timezone") else System.setProperty("user.timezone", prev)
        zonePinned = false
    }

    private fun auditDir() = File(app.filesDir, "audit")

    /** A fresh request log over this test's sandbox, with no age limit so the fixed 2026 dates stay. */
    fun auditStore(): com.mido.phonebridge.audit.AuditStore {
        val store = com.mido.phonebridge.audit.AuditLog(
            auditDir(), com.mido.phonebridge.audit.AuditRetention(maxAgeDays = 36_500), "0.49.8", 304,
        )
        val field = com.mido.phonebridge.audit.AuditLog::class.java.getDeclaredField("singleton")
        field.isAccessible = true
        field.set(null, store)
        return store
    }

    @Suppress("LongParameterList")
    fun logRecord(
        id: String,
        ts: Long,
        outcome: com.mido.phonebridge.audit.AuditOutcome,
        plugin: String,
        verb: String,
        summary: String,
        durationMs: Long = 2_811L,
        error: String? = null,
        noise: Boolean = false,
        responseStatus: Int = 200,
        requestBody: String = "",
        responseBody: String = "",
        decision: com.mido.phonebridge.audit.Decision = com.mido.phonebridge.audit.Decision(
            mode = "PARANOID", source = com.mido.phonebridge.audit.DecisionSource.GLOBAL, globalMode = "PARANOID",
            enqueuedAt = ts, shownAt = ts + 100, decidedAt = ts + 2_500,
        ),
        truncated: com.mido.phonebridge.audit.Truncation = com.mido.phonebridge.audit.Truncation(0, 0),
    ) = com.mido.phonebridge.audit.AuditRecord(
        id = id, ts = ts, outcome = outcome,
        label = if (plugin.isEmpty()) "" else "$plugin.${verb.lowercase()}", plugin = plugin, verb = verb,
        origin = "TAILNET", remote = "100.64.0.7", requester = "Work laptop",
        route = if (plugin.isEmpty()) "" else "GET /$plugin/items", method = "GET",
        path = "/$plugin/items".takeIf { plugin.isNotEmpty() } ?: "/no-such-path",
        query = if (plugin.isEmpty()) emptyMap() else mapOf("q" to "sara"), summary = summary,
        requestHeaders = mapOf("Host" to "192.168.1.100:8787", "User-Agent" to "curl/8.5.0"),
        requestBody = requestBody, responseStatus = responseStatus, responseBody = responseBody,
        error = error, decision = decision, durationMs = durationMs, truncated = truncated, noise = noise,
        appVersionName = "0.49.8", appVersionCode = 304,
    )

    private fun policyRecord(id: String, ts: Long) = com.mido.phonebridge.audit.PolicyAudit
        .record(GlobalMode.WHITELIST, GlobalMode.PARANOID, "settings")
        .let { it.copy(id = id, ts = ts, decision = it.decision.copy(decidedAt = ts)) }

    /** Eight records over two local days (4 yesterday, 4 today); [withFailure] false drops HANDLER_FAILED. */
    fun logList(withFailure: Boolean = true) {
        pinLogClock()
        val store = auditStore()
        val o = com.mido.phonebridge.audit.AuditOutcome.values().associateBy { it.name }
        fun at(i: Int) = if (i < 4) LOG_BASE_MS + i * 60_000L else NOW_MS - 3_600_000L + (i - 4) * 60_000L
        val rows = listOf(
            logRecord("wp08-0", at(0), o.getValue("APPROVED"), "probe", "READ", "Probe the bridge"),
            logRecord("wp08-1", at(1), o.getValue("NOTIFY_APPROVED"), "help", "READ", "Read the plugin help", 340L),
            logRecord("wp08-2", at(2), o.getValue("DENIED"), "gmail", "EDIT", "Archive 3 messages from Alex", 4_120L, responseStatus = 403),
            logRecord("wp08-3", at(3), o.getValue("TIMEOUT"), "calendar", "DELETE", "Delete event: Dentist", 30_000L, responseStatus = 408),
            logRecord("wp08-4", at(4), o.getValue("DENIED_UNATTENDED"), "contacts", "READ", "Search contacts for sara", 90L, responseStatus = 403),
            policyRecord("wp08-5", at(5)),
            logRecord("wp08-6", at(6), o.getValue("HANDLER_FAILED"), "drive", "WRITE", "Upload report.pdf to Drive", 1_900L, error = "upstream_timeout", responseStatus = 502),
            logRecord("wp08-7", at(7), o.getValue("APPROVED"), "ssh", "EXECUTE", "Run uptime on kvm-box", 61_000L),
        )
        rows.filterIndexed { i, _ -> withFailure || i != 6 }.forEach { store.append(it) }
    }

    /** UNROUTED and REJECTED_INVALID records, hidden by default as noise. */
    fun logNoise() {
        pinLogClock()
        val store = com.mido.phonebridge.audit.AuditLog.from(app)
        val o = com.mido.phonebridge.audit.AuditOutcome.values().associateBy { it.name }
        store.append(logRecord("wp08-n1", NOW_MS - 1_800_000L, o.getValue("UNROUTED"), "", "", "", 5L, noise = true, responseStatus = 404))
        store.append(logRecord("wp08-n2", NOW_MS - 1_700_000L, o.getValue("REJECTED_INVALID"), "", "", "Bad JSON body", 4L, noise = true, responseStatus = 400))
    }

    /** [count] lines in today's day file that no parser accepts, then the log is loaded again. */
    fun logCorruptLines(count: Int) {
        pinLogClock()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(NOW_MS))
        val file = File(auditDir().apply { mkdirs() }, "$day.jsonl")
        repeat(count) { file.appendText("this line is not json $it\n") }
        auditStore()
    }

    /** One stored line that is not JSON but carries an id, so the detail screen can find it. */
    fun logUnreadableLine(id: String) {
        pinLogClock()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(NOW_MS))
        File(auditDir().apply { mkdirs() }, "$day.jsonl").appendText("garbage {\"id\":\"$id\" without a closing brace\n")
        auditStore()
    }

    /** The WP08 detail records: one id per state. */
    fun detailRecord(kind: String): String {
        pinLogClock()
        val store = auditStore()
        val o = com.mido.phonebridge.audit.AuditOutcome.values().associateBy { it.name }
        val ts = LOG_BASE_MS
        val id = "wp08-d-$kind"
        val record = when (kind) {
            "approved" -> logRecord(
                id, ts, o.getValue("APPROVED"), "gmail", "EDIT", "Archive 3 messages from Alex", 2_811L,
                requestBody = "{\"ids\":[\"a1\",\"b2\",\"c3\"],\"action\":\"archive\"}",
                responseBody = "{\"modified\":3,\"ok\":true}",
                decision = com.mido.phonebridge.audit.Decision(
                    mode = "DONT_DISTURB", source = com.mido.phonebridge.audit.DecisionSource.RULE,
                    globalMode = "WHITELIST", rule = "gmail/EDIT", enqueuedAt = ts, shownAt = ts + 120,
                    decidedAt = ts + 2_700, queueDepth = 1,
                ),
            )
            "policy_changed" -> policyRecord(id, ts)
            "unrouted" -> logRecord(id, ts, o.getValue("UNROUTED"), "", "", "", 5L, noise = true, responseStatus = 404)
            "failed_with_error" -> logRecord(
                id, ts, o.getValue("HANDLER_FAILED"), "drive", "WRITE", "Upload report.pdf to Drive", 1_900L,
                error = "upstream_timeout", responseStatus = 502, responseBody = "{\"error\":\"upstream_timeout\"}",
            )
            "truncated" -> logRecord(
                id, ts, o.getValue("APPROVED"), "drive", "WRITE", "Upload report.pdf to Drive", 1_900L,
                requestBody = "{\"name\":\"report.pdf\",\"data\":\"AAAA\"}", responseBody = "{\"ok\":true}",
                truncated = com.mido.phonebridge.audit.Truncation(40_112, 51_200),
            )
            "bodies_absent" -> logRecord(
                id, ts, o.getValue("APPROVED"), "debug", "READ", "Read the debug report", 120L,
                truncated = com.mido.phonebridge.audit.Truncation(0, 0, responseBodyAbsent = true, requestBodyAbsent = true),
            )
            "unattended" -> logRecord(
                id, ts, o.getValue("DENIED_UNATTENDED"), "contacts", "READ", "Search contacts for sara", 90L, responseStatus = 403,
                decision = com.mido.phonebridge.audit.Decision(
                    mode = "PARANOID", source = com.mido.phonebridge.audit.DecisionSource.GLOBAL,
                    globalMode = "PARANOID", enqueuedAt = ts, shownAt = null, decidedAt = ts + 60,
                ),
            )
            else -> error("unknown detail fixture $kind")
        }
        store.append(record)
        return id
    }

    /** Exactly these exceptions: the default policy ships a Help exception, which is removed first. */
    fun exceptionsOnly(vararg rules: com.mido.phonebridge.policy.Rule) {
        val store = PolicyStore.from(app)
        val bare = store.get().rules.fold(store.get()) { p, r -> p.withoutRule(r.plugin, r.verb) }
        store.set(rules.fold(bare) { p, r -> p.withRule(r) })
    }

    /** Registers fake plugins with fixed display names, for the dialogs and rows that print them. */
    fun registerNamedPlugins() {
        clearPlugins()
        listOf("gmail" to "Gmail", "calendar" to "Calendar", "contacts" to "Contacts", "tts" to "Speak", "ssh" to "SSH")
            .forEach { (id, name) -> com.mido.phonebridge.core.PluginRegistry.register(com.mido.phonebridge.WpFakePlugin(id, name)) }
    }

    // --- WP05: Plugins ---------------------------------------------------------------------------

    /** Sets the Google OAuth client id the rows read (blank in a build without sign-in). */
    fun googleClient(id: String) { com.mido.phonebridge.core.AuthManager.clientId = id }

    /** Stored switches off for the plugins with these ids. */
    fun pluginsSwitchedOff(vararg ids: String) {
        val store = com.mido.phonebridge.core.PluginSettingsStore.from(app)
        for (id in ids) com.mido.phonebridge.core.PluginRegistry.get(id)?.let { store.setEnabled(it, false) }
    }

    /** Names in the plaintext vault index (works while the vault is locked). */
    fun secretNames(vararg names: String) {
        val o = org.json.JSONObject()
        for (n in names) o.put(n, org.json.JSONObject().put("docs", "").put("key", ""))
        app.getSharedPreferences("phonebridge_vault", Context.MODE_PRIVATE).edit().putString("vault_meta", o.toString()).apply()
    }

    fun developerOpen(open: Boolean) {
        app.getSharedPreferences("plugins_ui", Context.MODE_PRIVATE).edit().putBoolean("developer_open", open).apply()
    }

    fun resetPlugins() {
        com.mido.phonebridge.core.AuthManager.clientId = ""
        app.getSharedPreferences("phonebridge_vault", Context.MODE_PRIVATE).edit().remove("vault_meta").apply()
        app.getSharedPreferences("plugins_ui", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // --- WP09: the secret vault, SSH connections and the new-device screen -----------------------

    private val todoistMeta = com.mido.phonebridge.core.SecretVault.SecretMeta(
        docs = "https://developer.todoist.com/rest/v2",
        key = "https://app.todoist.com/app/settings/integrations/developer",
        hosts = listOf("api.todoist.com", "*.todoist.com"),
    )

    /** Seeds the vault with [entries] and the given condition. [values] are the open-session secrets. */
    fun vault(
        state: com.mido.phonebridge.core.VaultState.State,
        unlocked: Boolean,
        entries: Map<String, com.mido.phonebridge.core.SecretVault.SecretMeta>,
        values: Map<String, String> = emptyMap(),
    ) {
        com.mido.phonebridge.core.SecretVault.stateForTest = state
        com.mido.phonebridge.core.SecretVault.seedForTest(app, entries, unlocked, values)
    }

    /** `todoist` (two links, two hosts) and `notion` (bare). */
    fun secretEntries() = mapOf(
        "notion" to com.mido.phonebridge.core.SecretVault.SecretMeta(),
        "todoist" to todoistMeta,
    )

    fun secretValues() = mapOf("todoist" to "tdst-DEMO-0000", "notion" to "ntn-DEMO-0000")

    /** The plaintext index holds bytes no parser accepts. */
    fun vaultCorruptIndex() {
        app.getSharedPreferences("phonebridge_vault", Context.MODE_PRIVATE).edit().putString("vault_meta", "not json {{{").commit()
        com.mido.phonebridge.core.SecretVault.stateForTest = com.mido.phonebridge.core.VaultState.State.UNLOCKABLE
    }

    /**
     * A throwaway ed25519 key made for these fixtures only, fixed so the captures are identical on
     * every run. The armour lines are joined at run time so no scanner mistakes this file for a leak.
     */
    private val sshKey: String = listOf(
        "-----BEGIN " + "OPENSSH PRIVATE KEY" + "-----",
        "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW",
        "QyNTUxOQAAACAIskb1dFFLEKdXxIZ3PIX5Wam+Ou2BrF2YYSuCm4/08wAAAJDA1QKBwNUC",
        "gQAAAAtzc2gtZWQyNTUxOQAAACAIskb1dFFLEKdXxIZ3PIX5Wam+Ou2BrF2YYSuCm4/08w",
        "AAAED9m+7u/KK6tPJRjDQjkpbSqXLCCDt2gCcLH272Oqn7RAiyRvV0UUsQp1fEhnc8hflZ",
        "qb467YGsXZhhK4Kbj/TzAAAACm5vIGNvbW1lbnQBAgM=",
        "-----END " + "OPENSSH PRIVATE KEY" + "-----",
    ).joinToString("\n", postfix = "\n")

    private fun sshEntry(host: String, port: Int, user: String, pin: String?) =
        com.mido.phonebridge.plugins.ssh.SshConnection(host, port, user, sshKey, pin).toJson().toString()

    /** `ssh-prod` (pinned) and `ssh-kvm` (not pinned). With [unlocked] false only the names exist. */
    fun sshVault(unlocked: Boolean, brokenEntry: Boolean = false) {
        val ssh = com.mido.phonebridge.core.SecretVault.SecretMeta(kind = com.mido.phonebridge.core.SecretVault.SecretMeta.Kind.SSH)
        val values = mapOf(
            "ssh-prod" to sshEntry("box.example.com", 22, "deploy", "SHA256:Zl4qE3rX0m1wP8uK7yH2cB5nV9dT6sJ0aQxLgWfEoRM"),
            "ssh-kvm" to if (brokenEntry) "not json" else sshEntry("kvm.tail1234.ts.net", 2222, "mido", null),
        )
        vault(
            com.mido.phonebridge.core.VaultState.State.UNLOCKABLE, unlocked,
            mapOf("ssh-kvm" to ssh, "ssh-prod" to ssh), values,
        )
    }

    fun resetVault() = com.mido.phonebridge.core.SecretVault.resetForTest()

    // --- everything ----------------------------------------------------------------------------

    /**
     * R3: the enabled-listeners setting and the system-access shadows outlive a test, so a fixture
     * that never set them inherited the last test's Notification access row (NEEDED in one render,
     * NOT CONNECTED in the next). Every test ends with them back at "nothing granted".
     */
    fun resetSystemState() {
        android.provider.Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        BridgeService.listenerRef = null
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, false)
    }

    fun reset() {
        resetSystemState()
        unpinLogClock()
        resetServer()
        resetAudit()
        resetUpdates()
        resetHome()
        resetDebugLog()
        resetSettings()
        resetPlugins()
        clearPlugins()
        resetVault()
        previousZone?.let { TimeZone.setDefault(it) }
        previousZone = null
    }

    @Suppress("unused")
    private fun unusedContext(): Context = app
}

// --- WP05: Plugins fixtures (appended) ---
/** A plugin with a scripted status, for the Plugins tab screenshots (WP05). */
class FixturePlugin(
    override val id: String,
    override val displayName: String,
    var state: PluginStatus.State = PluginStatus.State.OK,
    var detail: String = "",
    private val throwing: Boolean = false,
    private val perms: List<PluginPermission> = emptyList(),
) : Plugin {
    override fun routes() = emptyList<Route>()
    override fun permissions() = perms
    override fun status(): PluginStatus {
        if (throwing) throw IllegalStateException("fixture status failure")
        return PluginStatus(displayName, state, detail)
    }
}

object PluginFixtures {
    private val OK = PluginStatus.State.OK

    /** Every production plugin id, healthy, with the route-style text the real plugins print. */
    fun healthy(): List<FixturePlugin> = listOf(
        FixturePlugin("google", "Google account", OK, "a@example.com"),
        FixturePlugin("gmail", "Gmail", OK, "ready · token 42m"),
        FixturePlugin("calendar", "Calendar", OK, "ready"),
        FixturePlugin("drive", "Google Drive", OK, "ready"),
        FixturePlugin("notifications", "Notifications", OK, "listener connected"),
        FixturePlugin(
            "contacts", "Contacts", OK, "GET /contacts?query= — names, numbers and emails, up to 20 people",
            perms = listOf(PluginPermission("android.permission.READ_CONTACTS", "Contacts", "It searches your contacts.", false)),
        ),
        FixturePlugin("audio", "Audio", OK, "POST /audio/{cue} — chime, alarm"),
        FixturePlugin("tts", "Speak", OK, "POST /tts — cap 500 chars, line 3, rate 1.0, pitch 1.0"),
        FixturePlugin("secrets", "Secrets (metadata)", OK, "2 secret(s) · GET /secrets"),
        FixturePlugin("ssh", "SSH", OK, "kvm-box, nas"),
        FixturePlugin("help", "Help", OK, "GET /help — 14 plugin(s), contract v4"),
        FixturePlugin("debug", "Debug", OK, "GET /debug"),
        FixturePlugin("logs", "Logs", OK, "GET /logs?lines=100"),
        FixturePlugin("probe", "Probe", OK, "POST /probe"),
    )

    /** Registers [healthy] with [change] applied to the plugin of the matching id, on a fresh registry. */
    fun register(change: (FixturePlugin) -> FixturePlugin = { it }) {
        Fixtures.clearPlugins()
        for (p in healthy()) PluginRegistry.register(change(p))
    }

    /** Replaces the status of the plugins named in [overrides] (id to state and detail). */
    fun registerWith(vararg overrides: Triple<String, PluginStatus.State, String>, throwing: Set<String> = emptySet()) {
        val byId = overrides.associateBy { it.first }
        register { p ->
            val o = byId[p.id]
            when {
                p.id in throwing -> FixturePlugin(p.id, p.displayName, throwing = true)
                o != null -> FixturePlugin(p.id, p.displayName, o.second, o.third, perms = p.permissions())
                else -> p
            }
        }
    }
}

// --- WP10: Onboarding fixtures (appended) ---
/** The three system accesses the introduction's step 3 reads, and the address its step 5 prints. */
object OnboardingFixtures {
    /** Sets `BridgeService.bindAddress`; `Fixtures.reset()` restores it. */
    fun bridgeAddress(address: String) {
        BridgeService.bindAddress = address
    }

    /** Notification access (the listener), draw-over-apps, and the battery exemption. */
    fun permissions(listener: Boolean, overlay: Boolean, battery: Boolean) {
        BridgeService.listenerRef =
            if (listener) Robolectric.setupService(NotifListenerService::class.java) else null
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(overlay)
        val pm = Fixtures.app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(Fixtures.app.packageName, battery)
    }
}

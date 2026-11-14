package com.mido.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.mido.phonebridge.audit.AuditClassify
import com.mido.phonebridge.core.StmpNotifications
import com.mido.phonebridge.audit.AuditDraft
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.AuditStore
import com.mido.phonebridge.audit.Decision
import com.mido.phonebridge.audit.DecisionSource
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.audit.recordResponse
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.RespPayload
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.BridgeStatusNotice
import com.mido.phonebridge.core.StartupRetry
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.core.labelOf
import com.mido.phonebridge.notify.NotifyGate
import com.mido.phonebridge.plugins.calendar.CalendarPlugin
import com.mido.phonebridge.plugins.gmail.GmailPlugin
import com.mido.phonebridge.plugins.notifications.NotificationsPlugin
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.ModeResolution
import com.mido.phonebridge.policy.ModeSource
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.policy.Rule
import com.mido.phonebridge.policy.isAttended
import com.mido.phonebridge.policy.resolve
import com.mido.phonebridge.policy.unattendedAllowed
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Foreground service that owns the HTTP server. Every request is gated through
 * ApprovalGate before any plugin handler runs — a plugin cannot opt out.
 */
class BridgeService : Service() {

    companion object {
        const val PORT = 8787

        @Volatile private var lastForegroundKey = ""

        /**
         * True from `onCreate` to `onDestroy`. [refreshForegroundNotice] posts nothing outside that
         * window, so a late probe or an app-side call can never leave a notice with no service behind it.
         */
        @Volatile var serviceAlive = false

        /**
         * D51: rebuild and re-post the running notification. Called by the service on bind, rebind and the
         * 60 s watchdog tick, and by the app right after it changes the gate mode (HomeFragment.applyGate).
         * It is a no-op while the service is not running (any caller), and from the app also while the
         * bridge is not up, so it never posts a notification for a service that is not there.
         */
        fun refreshForegroundNotice(ctx: Context, forceStarting: Boolean = false, fromApp: Boolean = false) {
            if (!serviceAlive) return
            if (fromApp && !serverUp) return
            val app = ctx.applicationContext
            val address = if (forceStarting || !serverUp) null else bindAddress.takeIf { it.isNotBlank() }
            val mode = try { PolicyStore.from(app).get().global } catch (e: Exception) { null }
            val key = "$address|$mode"
            if (key == lastForegroundKey) return
            lastForegroundKey = key
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try { nm.notify(NOTIF_ID, buildRunningNotification(app, address, mode)) }
            catch (e: Exception) { Debug.log("foreground notice refresh failed: ${e.javaClass.simpleName}: ${e.message}") }
        }

        private fun buildRunningNotification(ctx: Context, address: String?, mode: GlobalMode?): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val stop = PendingIntent.getService(
                ctx, 3, Intent(ctx, BridgeService::class.java).setAction(StmpNotifications.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return StmpNotifications.running(ctx, address, PORT, mode, open, stop)
        }

        /**
         * The one wording for "there is nothing to bind to".
         *
         * Tailscale is a RECOMMENDATION, not a precondition (see `BindAddress`): a phone on plain
         * Wi-Fi has a perfectly good address. The old message named Tailscale as the cause, which
         * sent anyone on LAN, WireGuard or ZeroTier to look at an app they had not installed.
         * Stated once as a constant because it is shown in two places and they must not drift.
         */
        const val NO_ADDRESS_MESSAGE =
            "No reachable network address. Connect to Wi-Fi or start Tailscale." 
        const val CHANNEL_ID = "bridge_service"
        const val NOTIF_ID = 4242

        /**
         * A SECOND channel, and a second id, for "the bridge is not serving".
         *
         * The running notification above is `IMPORTANCE_LOW` on purpose — it is a permanent
         * "I am here" dot, not something that should make noise. A reminder that the bridge is
         * DOWN is the opposite: it exists to be noticed, so it needs its own channel at
         * `IMPORTANCE_DEFAULT`. Sharing the low channel would make the reminder silent, which
         * is the same as not having one.
         *
         * Distinct ids so the two never replace each other: 4242 is the foreground-service
         * notification and MUST stay stable, while 4243 is transient.
         */
        const val STATUS_CHANNEL_ID = "bridge_status"
        const val NOTIF_ID_STATUS = 4243

        /**
         * OAuth credentials, supplied at BUILD time from `local.properties`.
         *
         * **They are deliberately not in this repository.** Every developer registers their own
         * Google Cloud project, because:
         *  - the Android client authenticates by package name + signing SHA-1, so a stranger's
         *    build can never match this app's registration — they need their own regardless;
         *  - Google's docs treat a Web client's secret as confidential;
         *  - sharing one consent screen means other people's users see your app name and spend
         *    your quota.
         *
         * Empty when unset, and empty is handled rather than assumed away: the app builds and
         * runs, and login reports "build not configured" instead of silently authenticating
         * against a project the developer does not control.
         *
         * See README → "Google sign-in" for the exact keys.
         */
        val GOOGLE_CLIENT_ID: String = BuildConfig.GOOGLE_CLIENT_ID
        val GOOGLE_SERVER_CLIENT_ID: String = BuildConfig.GOOGLE_SERVER_CLIENT_ID
        val GOOGLE_CLIENT_SECRET: String = BuildConfig.GOOGLE_CLIENT_SECRET

        @Volatile
        var listenerRef: NotifListenerService? = null

        @Volatile
        var serverUp = false

        @Volatile
        var lastError = ""

        @Volatile
        var bindAddress: String = ""

        /**
         * The last LIVENESS result, as opposed to [serverUp] which is an assertion.
         *
         * `serverUp` records that `start()` once succeeded; this records that a socket
         * actually connected, and how long ago. The UI reads BOTH, so "green" can mean
         * "verified seconds ago" instead of "we said so at boot" — which is the difference
         * between the 2026-10-25 green label and the truth.
         */
        @Volatile
        var lastProbeOk = false

        @Volatile
        var lastProbeAtMs = 0L

        /** How long a probe connect may take before it counts as dead. Local socket, so short. */
        const val PROBE_TIMEOUT_MS = 1_500

        /**
         * How often the watchdog re-proves the server answers.
         *
         * 60s is chosen against the cost, not the symptom: a probe is one local TCP connect
         * (microseconds) every minute, which is free, while a longer gap leaves a dead bridge
         * undiscovered for that much longer. It is deliberately NOT tied to any request — the
         * watchdog must work when nothing is calling in, which is exactly the outage case.
         */
        const val WATCHDOG_INTERVAL_MS = 60_000L

        /**
         * How often to re-check for a tailnet address while waiting for one.
         *
         * The `NetworkCallback` is the fast path and covers the real case (a VPN connecting
         * changes the default network). This is the backstop for an interface appearing without
         * a default-network change, which is possible but rare. One interface enumeration per
         * tick, no network I/O, so 5s costs nothing measurable while keeping recovery quick.
         */
        const val WAIT_POLL_MS = 5_000L

        fun markRunning(v: Boolean) { serverUp = v }

        /**
         * A verified liveness answer, for the UI and `/debug`.
         *
         * **Deliberately derived from the cached probe, not from a fresh connect.** A fresh
         * connect on every `render()` would put blocking network I/O on the main thread, and
         * `render()` runs on every `onResume`. The watchdog already probes every minute and
         * records the result here; this just reads it.
         *
         * Returns `true` only when a probe has SUCCEEDED and is recent enough to still mean
         * something. A stale success is treated as unknown-but-not-failed, because the
         * alternative — painting red for a probe that simply has not run yet — would make a
         * healthy bridge look broken right after a start.
         */
        fun isVerifiedAlive(): Boolean {
            if (!serverUp) return false
            // No probe yet (just bound): trust the bind, since the watchdog has not had a
            // chance to run. Anything older than the probe window is not evidence either way.
            if (lastProbeAtMs == 0L) return true
            val age = System.currentTimeMillis() - lastProbeAtMs
            // Stale beyond a few intervals means the watchdog itself stopped — do not claim
            // health on evidence that old.
            if (age > WATCHDOG_INTERVAL_MS * 3) return false
            return lastProbeOk
        }

        /**
         * Register one secret plugin at runtime, so a secret added in the CRUD screen
         * becomes routable without restarting the service. Idempotent.
         *
         * Static and deliberately narrow: the CRUD screen owns the secret VALUE, but the
         * routing table is the service's, so this is the one seam between them.
         */
        fun registerSecretPlugin(ctx: Context, name: String) {
            if (PluginRegistry.get(name) == null) {
                PluginRegistry.register(
                    com.mido.phonebridge.plugins.secrets.SecretPlugin(ctx.applicationContext, name)
                )
                Debug.log("secret plugin registered at runtime: '$name'")
            }
        }
    }

    private var server: HttpServer? = null
    private var wakeLock: WifiManager.WifiLock? = null

    /** Drives the start-retry ladder. Main looper so a post from any thread lands in order. */
    private val retryHandler = Handler(Looper.getMainLooper())

    /** Drives the liveness watchdog. Separate from [retryHandler] so cancelling one is not
     *  accidentally cancelling the other — they mean different things and have different
     *  lifetimes (retries end at bind, the watchdog runs for as long as the service does). */
    private val watchdogHandler = Handler(Looper.getMainLooper())

    /** Set once the server is bound, so a stale retry cannot fire after a successful start. */
    private var retryAttempt = 0

    /**
     * Watches for the tailnet appearing while the bridge is waiting for it.
     *
     * **Why a callback AND a poll.** `registerDefaultNetworkCallback` fires when the default
     * network changes, which is what happens when Tailscale connects (it is a VPN, so it becomes
     * the default network). That makes the common case instant. But a callback is not a promise
     * that every interface change produces a `Network` object, and `chooseBindAddress()` reads
     * interfaces directly — so a slow poll backs it up. The poll is one interface enumeration
     * (no network I/O), so it costs essentially nothing.
     */
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** True while the service is deliberately staying alive for a tailnet that does not exist yet. */
    private var waitingForAddress = false

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        serviceAlive = true
        createChannel()
        Debug.log("service onCreate")
        // Decode the stamp sample NOW, so the first decision after a cold start already has it.
        // Without this the very first stamp would fail with SoundUnavailable — which is the honest
        // outcome, but a pointless one when the decode could have happened at startup.
        com.mido.phonebridge.alert.SoundAlerts.preload(this)
        // The model warm-up is NOT started here. It used to be, and that was a real cost:
        // `onCreate` runs before `onStartCommand`, so a start that immediately refuses to bind
        // (no tailnet address, unreadable policy, bad port) still paid for loading the model
        // AND running a throwaway GPU inference — then called `stopSelf()`. Every retry paid it
        // again. Mido tapped START repeatedly while the bind was failing, so each tap bought a
        // full engine load for a service that never served a request.
        //
        // The warm now happens in `onStartCommand` after `markRunning(true)` — see
        // `warmModel()`. Readiness still decides whether the bridge may serve; this only
        // decides when the loading cost is paid, and it is paid only by a server that exists.
    }

    /**
     * Give the warm model back when the OS is short on memory.
     *
     * The release valve for a ~700 MiB resident engine (measured 2026-10-19) on a 24/7
     * service. Surrendering it voluntarily is strictly better than being killed: a killed
     * foreground service denies every request, while a released engine merely reloads on
     * the next one. The model FILE is never touched — only the loaded engine.
     *
     * **Why there is no level comparison.** The fine-grained levels
     * (`TRIM_MEMORY_RUNNING_LOW` and friends) are DEPRECATED as of API 34/35 precisely
     * because apps are no longer notified of them, so testing `level >= RUNNING_LOW`
     * would be dead logic behind a build warning. Any trim callback is the OS saying
     * memory is tight, and a luxury is the first thing to surrender — so this releases
     * unconditionally. That is also free in this app: a request arriving with the screen
     * off is DENIED as unattended (v0.12.0), so the engine only ever matters while the
     * screen is on, and the reload cost is bounded by the 0.4 s load measured on the host.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.mido.phonebridge.ai.AiGateway.release()
        // Stop speaking and release the engine. A waiting caller is FAILED rather than left to
        // time out: the service is going away, and a request pinning a thread for a deadline that
        // can never be interesting is worse than a named refusal.
        com.mido.phonebridge.plugins.tts.TtsSpeaker.shutdown()
        Debug.log("onTrimMemory($level): model engine released")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Debug.log("service onStartCommand startId=$startId")
        if (StmpNotifications.isStop(intent)) {
            Debug.log("stop requested from the notification")
            cancelStatusNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat()
            Debug.log("startForeground() ok")
        } catch (e: Exception) {
            lastError = "startForeground: ${e.javaClass.simpleName}: ${e.message}"
            Debug.log("FOREGROUND FAILED: $lastError")
            stopSelf()
            return START_NOT_STICKY
        }

        com.mido.phonebridge.core.AuthManager.init(
            applicationContext, GOOGLE_CLIENT_ID, GOOGLE_SERVER_CLIENT_ID, GOOGLE_CLIENT_SECRET
        )
        // Offline access: ask Google for a one-time server auth code against the
        // Web client, then exchange it for a refresh token. The Web client's
        // SECRET is never embedded.
        PlayAuth.configure(serverClientId = GOOGLE_SERVER_CLIENT_ID, offline = true)
        registerPlugins()

        // POLICY, before any listener exists. An unreadable policy means the server
        // will not come up at all — no default, no fallback to SECURE. A gate running
        // on a switch nobody could read is exactly the failure R1's fail-hard rule
        // exists to prevent, and "assume secure" is still an assumption.
        try {
            val p = PolicyStore.from(applicationContext).get()
            Debug.log("policy loaded: ${p.global}")
        } catch (e: Exception) {
            lastError = "The approval-gate policy file is unreadable (${e.message}). " +
                "The server will not start on an assumption — this needs fixing on the phone."
            Debug.log("REFUSING TO START: $lastError")
            stopSelf()
            return START_NOT_STICKY
        }

        // Wire the one alarm that must never be silent: an error code nobody classified.
        // It answers 500 (the safe direction) but says so in the ring, so "we crashed"
        // and "nobody taught me this code" stay distinguishable.
        com.mido.phonebridge.core.ErrorCodes.unclassified = { code ->
            Debug.log("UNCLASSIFIED error code '$code' -> 500 (defect)")
        }

        if (server == null) {
            val bind = chooseBindAddress()
            if (bind == null) return noAddress()
            bindAddress = bind
            Debug.log("binding to $bind:$PORT")
            val s = HttpServer(this, bind, PORT)
            try {
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                lastError = ""
                Debug.log("HTTP server bound on $bind:$PORT")
            } catch (e: Exception) {
                lastError = "Could not bind port $PORT: ${e.message ?: e.javaClass.simpleName}".trim()
                Debug.log("BIND FAILED: $lastError")
                // A port conflict is not a race: retrying it would only hide the reason. The
                // retry budget exists for the tailnet address appearing, and nothing else.
                postStatusNotification(BridgeStatusNotice.Kind.STOPPED, attempt = 0)
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            Debug.log("server already running")
        }
        // Bound. Clear the ladder so a retry scheduled before this start cannot fire later and
        // start a second server over this one.
        retryAttempt = 0
        retryHandler.removeCallbacksAndMessages(null)
        // And stop watching for a tailnet that is now present, or the callback would keep firing
        // on every network change for the life of the service.
        stopWaiting()
        cancelStatusNotification()
        markRunning(true)
        refreshForegroundNotice(this)
        lastProbeOk = true
        lastProbeAtMs = System.currentTimeMillis()
        startWatchdog()
        warmModel()
        return START_STICKY
    }

    /**
     * No tailnet address: retry, or give up loudly.
     *
     * **This used to `stopSelf()` immediately, and that was the outage.** A start triggered by
     * `BOOT_COMPLETED` happens seconds after the phone comes up, when the Tailscale app has
     * almost certainly not established its `100.x` address yet. Dying there turned "booted a
     * little early" into "the bridge never runs at all" — with no notification and no evidence,
     * which is why the 2026-10-25 outage had to be diagnosed from tailscaled logs.
     *
     * A manual START still behaves correctly under this: the human gets the same error text, the
     * same retries, and a notification if it never succeeds. Retrying costs nothing when the
     * address is there, because the first attempt binds immediately.
     */
    private fun noAddress(): Int {
        // S8: never silently bind all interfaces.
        // The wording is user-facing: it lands verbatim in the hero card when a start
        // fails, so it names the CAUSE and the FIX rather than the bind decision. A
        // user reading "refusing to bind 0.0.0.0" learns nothing they can act on.
        // The CAUSE is "no reachable address", not "Tailscale is off" (Mido's call, 2026-11-03:
        // Tailscale is a recommendation, not a precondition — see `BindAddress`). The old wording
        // sent anyone on plain Wi-Fi, WireGuard or ZeroTier to look at an app they never installed.
        lastError = NO_ADDRESS_MESSAGE
        val delay = StartupRetry.delayFor(retryAttempt)
        if (delay == null) {
            // NOT stopSelf(). The service has to outlive the ladder to notice Tailscale coming
            // back — see startWaitingForAddress.
            startWaitingForAddress()
            return START_STICKY
        }
        retryAttempt += 1
        Debug.log(
            "no tailnet address; retry $retryAttempt/${StartupRetry.maxAttempts()} in ${delay}ms"
        )
        postStatusNotification(BridgeStatusNotice.Kind.RETRYING, attempt = retryAttempt)
        retryHandler.postDelayed({ startSelf() }, delay)
        return START_STICKY
    }

    /**
     * The ladder is spent: stay alive and watch for the tailnet instead of stopping.
     *
     * **This replaced a `stopSelf()`, and the stop was the bug.** Once the service died there was
     * nothing left to notice Tailscale coming back, so reconnecting the VPN left the bridge down
     * until someone opened the app — every single time. The cost of waiting is a foreground
     * service that is already running and a low-importance notification; the cost of stopping is
     * a bridge that needs a human to poke it.
     *
     * The notification changes KIND here, not just text: [BridgeStatusNotice.Kind.WAITING_FOR_ADDRESS]
     * tells the human that doing nothing is correct, which is the opposite of what the old
     * "not running" copy implied.
     */
    private fun startWaitingForAddress() {
        if (waitingForAddress) return
        waitingForAddress = true
        Debug.log("REFUSING TO START: $lastError (waiting for a network address instead of stopping)")
        postStatusNotification(
            BridgeStatusNotice.Kind.WAITING_FOR_ADDRESS,
            attempt = StartupRetry.maxAttempts(),
        )

        // Instant path: the default network changing is what a VPN connecting looks like.
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = checkAddressNow("network available")
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: android.net.NetworkCapabilities,
                ) = checkAddressNow("capabilities changed")
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
            Debug.log("watching for the tailnet: default-network callback registered")
        } catch (e: Exception) {
            // Not fatal: the poll below still finds it. Record it so a slow recovery is explainable.
            Debug.log("network callback registration failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Backup path, and the reason this is "cheap" rather than fragile.
        retryHandler.postDelayed(waitPoll, WAIT_POLL_MS)
    }

    /**
     * If a tailnet address has appeared, stop waiting and start serving.
     *
     * Called from a callback (a binder thread) and from the poll (main looper), so the actual
     * start is posted to the main looper — `onStartCommand` runs there and two threads racing it
     * would be a double-bind.
     */
    private fun checkAddressNow(why: String) {
        if (!waitingForAddress) return
        if (chooseBindAddress() == null) return
        Debug.log("tailnet address appeared ($why) — starting")
        retryHandler.post { startSelf() }
    }

    private val waitPoll: Runnable = object : Runnable {
        override fun run() {
            if (!waitingForAddress) return
            if (chooseBindAddress() != null) {
                Debug.log("tailnet address appeared (poll) — starting")
                startSelf()
                return
            }
            retryHandler.postDelayed(this, WAIT_POLL_MS)
        }
    }

    /** Stop waiting: unregister the callback and drop the poll. Idempotent. */
    private fun stopWaiting() {
        if (!waitingForAddress && netCallback == null) return
        waitingForAddress = false
        retryHandler.removeCallbacks(waitPoll)
        netCallback?.let { cb ->
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // Already gone, or never registered. Nothing to repair.
                Debug.log("network callback unregister: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        netCallback = null
    }

    /** Ask the system for another start, which re-runs the address check. */
    private fun startSelf() {
        try {
            startForegroundService(Intent(this, BridgeService::class.java))
        } catch (e: Exception) {
            Debug.log("retry start failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * The reminder that the bridge is not serving.
     *
     * On its own channel at `IMPORTANCE_DEFAULT` because the running notification is
     * `IMPORTANCE_LOW` — reusing that one would make the reminder silent, which is the same as
     * not having one. `final` distinguishes "still retrying" from "gave up", because those ask
     * the human for different things: wait, versus open Tailscale.
     */
    /**
     * Post (or update) the status notification.
     *
     * Copy comes from [BridgeStatusNotice], which is pure and tested, so the three states cannot
     * drift from what the service is actually doing. The ongoing/autoCancel pair is derived from
     * the same enum for the same reason — a notification that is dismissible while the service is
     * still working would let the human swipe away the only sign that it is waiting.
     */
    private fun postStatusNotification(kind: BridgeStatusNotice.Kind, attempt: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            STATUS_CHANNEL_ID, "STMP problems", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Tells you when STMP could not start."
        }
        nm.createNotificationChannel(ch)

        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = StmpNotifications.status(this, kind, attempt, StartupRetry.maxAttempts(), pi)
        try {
            nm.notify(NOTIF_ID_STATUS, notif)
        } catch (e: Exception) {
            // API 33+ needs POST_NOTIFICATIONS. If it is denied the reminder cannot appear —
            // record that rather than pretending it was shown.
            Debug.log("status notification failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun cancelStatusNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID_STATUS)
        } catch (e: Exception) {
            Debug.log("status notification cancel failed: ${e.message}")
        }
    }

    /**
     * Prove the server is actually serving, instead of trusting the flag that says it should be.
     *
     * **`serverUp` is an assertion, not a check.** It is set `true` once after `start()` and
     * `false` only in `onDestroy`, so nothing re-verifies it. That is how the app painted a
     * green "Running" while every caller got `Connection refused` (2026-10-25): NanoHTTPD had
     * bound an address that had since stopped routing, `start()` had succeeded, and no code ever
     * looked again.
     *
     * `isAlive()` alone is not enough — it only proves the socket object and accept thread
     * exist. A real connect is what answers "would a caller get through".
     *
     * Blocking network I/O, so it MUST run off the main thread.
     */
    private fun probe(): Boolean {
        val s = server ?: return false
        if (!s.isAlive) return false
        val addr = bindAddress
        if (addr.isBlank()) return false
        return try {
            java.net.Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(addr, PORT), PROBE_TIMEOUT_MS)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Every [WATCHDOG_INTERVAL_MS], prove the server still answers; rebind if it does not.
     *
     * **Rebinding is the point.** A watchdog that only complains would leave the bridge down
     * until a human noticed, which is the failure this whole release exists to end. The one case
     * it cannot fix is "there is no tailnet address", and that is exactly what the notification
     * reports.
     */
    private fun startWatchdog() {
        watchdogHandler.removeCallbacksAndMessages(null)
        watchdogHandler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
    }

    // The explicit `: Runnable` is REQUIRED, not style: the object reschedules ITSELF, and
    // without a declared type the compiler has to infer the type of a value that refers to
    // itself and gives up with "recursive problem".
    private val watchdogTick: Runnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            Thread({ finishWatchdogProbe(probe()) }, "bridge-probe").apply { isDaemon = true }.start()
        }
    }

    @Volatile private var destroyed = false

    /**
     * What the probe thread does with its result. The probe can outlive `onDestroy` by up to
     * PROBE_TIMEOUT_MS, so a torn-down service returns without touching the notice or the schedule.
     */
    internal fun finishWatchdogProbe(ok: Boolean) {
        if (destroyed) return
        lastProbeOk = ok
        lastProbeAtMs = System.currentTimeMillis()
        if (!ok && server != null) {
            Debug.log("watchdog: server not answering on $bindAddress:$PORT — rebinding")
            watchdogHandler.post { rebind() }
        }
        refreshForegroundNotice(this)
        if (destroyed) return
        watchdogHandler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
    }

    /**
     * Tear the listener down and build it again on whatever address is current.
     *
     * A stale tailnet address is the case this exists for: the socket is fine, it is simply
     * bound to an IP the tailnet no longer routes. Re-reading `chooseBindAddress()` is what picks
     * up the new one.
     */
    private fun rebind() {
        server?.stop()
        server = null
        markRunning(false)
        val bind = chooseBindAddress()
        if (bind == null) {
            lastError = NO_ADDRESS_MESSAGE
            Debug.log("watchdog: cannot rebind — no reachable address")
            // The watchdog cannot fix this one, so hand over to the same wait path the start
            // ladder uses rather than posting a dead-end notice: Tailscale coming back is the
            // only thing that resolves it, and that is exactly what the wait watches for.
            startWaitingForAddress()
            return
        }
        bindAddress = bind
        val s = HttpServer(this, bind, PORT)
        try {
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            lastError = ""
            lastProbeOk = true
            lastProbeAtMs = System.currentTimeMillis()
            cancelStatusNotification()
            markRunning(true)
            refreshForegroundNotice(this)
            Debug.log("watchdog: rebound on $bind:$PORT")
        } catch (e: Exception) {
            lastError = "Could not bind port $PORT: ${e.message ?: e.javaClass.simpleName}".trim()
            Debug.log("watchdog: REBIND FAILED: $lastError")
            postStatusNotification(BridgeStatusNotice.Kind.STOPPED, attempt = 0)
        }
    }

    /**
     * Load the AI model now that there is a bound server to serve.
     *
     * Called from [onStartCommand] only after [markRunning] — never from `onCreate`. A start
     * that refuses and stops itself must not leave a loaded engine behind, and it must not pay
     * the load on the way out; see the note in `onCreate` for what that cost.
     *
     * Off the main thread, and idempotent: `warm()` returns immediately when the model file is
     * absent, and `LiteRtLm.warm` is synchronized, so a second call finds the engine already
     * built rather than building another one.
     */
    private fun warmModel() {
        Thread({
            try {
                com.mido.phonebridge.ai.AiGateway.warm(applicationContext)
                Debug.log("ai: ${com.mido.phonebridge.ai.AiGateway.status(applicationContext)}")
            } catch (e: Exception) {
                Debug.log("model warm-up failed: ${e.message}")
            }
        }, "model-warm").apply { isDaemon = true }.start()
    }

    /**
     * Android 15+ only. Called when the system has decided this foreground service must
     * stop (e.g. a time-limited type hit its cap). The service is no longer foreground at
     * this point and has a few seconds to call [stopSelf]; if it does not, the system
     * throws ForegroundServiceDidNotStopInTimeException and kills the process — which is
     * exactly the crash this override exists to prevent. We stop cleanly and record why.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Debug.log("onTimeout: system requires stop (startId=$startId fgsType=$fgsType) — stopping")
        stopSelf()
    }

    override fun onDestroy() {
        Debug.log("service onDestroy")
        // First, so a probe thread that finishes from here on posts and schedules nothing.
        destroyed = true
        serviceAlive = false
        // A pending retry must not outlive the service: it would start a new instance after a
        // deliberate STOP, which is the same bug `maybeAutoStartServer` guards against on the
        // app side. The watchdog likewise has nothing left to watch.
        retryHandler.removeCallbacksAndMessages(null)
        watchdogHandler.removeCallbacksAndMessages(null)
        // A waiting service that is destroyed must release the network callback, or the platform
        // holds a registration for a dead service.
        stopWaiting()
        retryAttempt = 0
        // Give the engine back. This was the second half of the memory story: `onTrimMemory`
        // released it, but that is a HINT the OS may never send, so a stopped service could sit
        // on ~700 MiB indefinitely. With START_STICKY the process is also one the OS restarts,
        // so holding an engine through a stop is doubly pointless. The model FILE is untouched —
        // the next start reloads it.
        com.mido.phonebridge.ai.AiGateway.release()
        // Kill any overlay still on screen. These are windows owned by the process:
        // if the service dies without removing them they are exactly the kind of
        // orphaned view that survives a stop and looks like a ghost prompt nobody can
        // dismiss. Their requests resolve through the gate's own shutdown path, so
        // clearing the views here costs no audit records.
        com.mido.phonebridge.notify.NotifyOverlay.dismissAll()
        server?.stop()
        server = null
        markRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerPlugins() {
        // Teach the registry how to answer "is this plugin on". Installed BEFORE any plugin is
        // registered so a request arriving during startup cannot see an empty lookup and treat
        // every plugin as defaulted. The store read is cheap (one SharedPreferences get) and the
        // lookup runs once per request, not per route match.
        if (PluginRegistry.enabledLookup == null) {
            val store = com.mido.phonebridge.core.PluginSettingsStore.from(applicationContext)
            PluginRegistry.enabledLookup = { plugin -> store.isEnabled(plugin) }
        }
        if (PluginRegistry.get("notifications") == null) {
            PluginRegistry.register(NotificationsPlugin(applicationContext))
        }
        // The account is a plugin because it is a precondition the Google plugins share, and
        // because "signed in as X" belongs next to the thing that changes it.
        if (PluginRegistry.get("google") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.google.GooglePlugin(applicationContext))
        }
        if (PluginRegistry.get("gmail") == null) {
            PluginRegistry.register(GmailPlugin(applicationContext))
        }
        if (PluginRegistry.get("calendar") == null) {
            PluginRegistry.register(CalendarPlugin(applicationContext))
        }
        if (PluginRegistry.get("ssh") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.ssh.SshPlugin(applicationContext))
        }
        if (PluginRegistry.get("drive") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.drive.DrivePlugin(applicationContext))
        }
        if (PluginRegistry.get("debug") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.debug.DebugPlugin(applicationContext))
        }
        if (PluginRegistry.get("logs") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.logs.LogcatPlugin(applicationContext))
        }
        if (PluginRegistry.get("audio") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.audio.AudioPlugin(applicationContext))
        }
        // The agent's entry point. Registered beside the rest, gated like the rest — its
        // default rule (help/ANY -> NOTIFY) is seeded into the policy file, not baked in here,
        // so the human can see and change it on the exceptions screen.
        // Contacts: search-only by construction. Registered unconditionally — the route must
        // exist so a caller gets `contacts_unavailable` (a fact about a missing tap) instead of a
        // 404 (which would say the feature does not exist).
        if (PluginRegistry.get("contacts") == null) {
            PluginRegistry.register(
                com.mido.phonebridge.plugins.contacts.ContactsPlugin(applicationContext)
            )
        }
        // Speak: exempt from the gate because the caller check already bounds it to named
        // addresses, and a prompt-first requirement would make it usable only when unnecessary.
        if (PluginRegistry.get("tts") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.tts.TtsPlugin(applicationContext))
        }
        if (PluginRegistry.get("help") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.help.HelpPlugin(applicationContext))
        }
        // The human liveness check: a real gated request with no dependencies, so a fresh
        // install can prove the gate reaches the phone before any Google login or vault exists.
        // `/status` stays the machine one — ungated and safe to poll.
        if (PluginRegistry.get("probe") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.probe.ProbePlugin(applicationContext))
        }
        // The metadata half of the secrets feature: a route that answers "what is this
        // secret, where are its docs, what may it reach" WITHOUT the value. Registered
        // once (not per secret) because it serves the whole index.
        if (PluginRegistry.get("secrets") == null) {
            PluginRegistry.register(
                com.mido.phonebridge.plugins.secrets.SecretIndexPlugin(applicationContext)
            )
        }
        registerSecretPlugins()
    }

    /**
     * Register one plugin per stored secret (plan D7/D8).
     *
     * Dynamic, because the secret list is data, not code: adding a service is adding a
     * secret in the CRUD screen, not a release. The vault's name index is plaintext
     * precisely so this works while the vault is locked — the rules screen must be able
     * to show `todoist × EXECUTE` without a fingerprint.
     *
     * Idempotent (PluginRegistry.register ignores a duplicate id), so a service restart
     * re-registering is a no-op.
     */
    private fun registerSecretPlugins() {
        val names = try {
            com.mido.phonebridge.core.SecretVault.names(applicationContext)
        } catch (e: Exception) {
            // A corrupt index must not silently hide secrets: refuse to start, same as
            // an unreadable policy. Better a bridge that will not serve than one that
            // quietly forgets a credential exists.
            lastError = "The secret index is unreadable (${e.message}), so the stored " +
                "credentials cannot be routed. Refusing to start rather than hide them."
            Debug.log("REFUSING TO START: $lastError")
            stopSelf()
            return
        }
        var broker = 0
        for (name in names) {
            // **AN SSH KEY IS NOT A BROKER SECRET.** One vault holds both, so iterating the name
            // index used to register `POST /secrets/<ssh-name>/call` for an SSH private key — a
            // route that could never succeed: it has no allowed hosts, so `host_not_allowed`
            // fires pre-gate forever, and /help then advertises a capability that does not exist.
            // It also told a caller "there is an HTTP API called evox2", which is simply false.
            //
            // So the kind decides. An unreadable entry is skipped rather than guessed at: a
            // metadata read that throws must not silently produce a broker route either.
            val kind = try {
                com.mido.phonebridge.core.SecretVault.meta(applicationContext, name).kind
            } catch (e: Exception) {
                Debug.log("secret '$name': metadata unreadable (${e.message}); not registering")
                continue
            }
            if (kind != com.mido.phonebridge.core.SecretVault.SecretMeta.Kind.SECRET) {
                Debug.log("secret '$name': kind=$kind — not a broker secret, no route")
                continue
            }
            if (PluginRegistry.get(name) == null) {
                PluginRegistry.register(
                    com.mido.phonebridge.plugins.secrets.SecretPlugin(applicationContext, name)
                )
                broker++
            }
        }
        if (broker > 0) Debug.log("registered $broker broker secret plugin(s)")
    }

    /**
     * The local IPv4 to bind, or null when the phone has no reachable address.
     *
     * **The rule moved into [BindAddress] so it can be tested without a device.** This function
     * is only the Android half: enumerate interfaces, hand the candidates over, take the answer.
     *
     * It used to accept ONLY an address in Tailscale's `100.64.0.0/10` range, so a phone on
     * ordinary Wi-Fi returned null and the service refused to start — making Tailscale a hard
     * requirement hidden inside a helper. It is now a preference, not a precondition.
     */
    private fun chooseBindAddress(): String? {
        val candidates = mutableListOf<com.mido.phonebridge.core.NicAddress>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (addr in nif.inetAddresses) {
                    val ip = addr.hostAddress ?: continue
                    candidates.add(com.mido.phonebridge.core.NicAddress(nif.name, ip))
                }
            }
        } catch (e: Exception) {
            Debug.log("interface enumeration failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        return com.mido.phonebridge.core.BindAddress.choose(candidates)
    }

    private fun startForegroundCompat() {
        // The first post happens before the server is bound, so it reads "starting".
        val notif = buildRunningNotification(this, null, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse, not dataSync: dataSync is capped at 6h/24h on Android 15+ and the
            // system kills the process when the cap is hit.
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // Below 34 specialUse does not exist, and naming dataSync instead fails the subset
            // check Android 12+ runs against the manifest's declared type ("foregroundServiceType
            // 0x1 is not a subset of 0x40000000"), which throws out of startForeground and kills
            // the service before the HTTP server is bound. Untyped is legal below 34, carries no
            // dataSync cap, and is the one request the manifest can always satisfy.
            startForeground(NOTIF_ID, notif)
        }
        Debug.log("foreground notification posted")
        refreshForegroundNotice(this, forceStarting = true)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID, "STMP running", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows that STMP is open for requests." }
        nm.createNotificationChannel(ch)
    }
}

/**
 * NanoHTTPD server. Routes come from PluginRegistry; gating is central.
 *
 * Every resolved request passes through ApprovalGate.await() before its handler
 * runs, so no plugin can execute anything without a human approval.
 */
class HttpServer(
    private val ctx: Context,
    bindHost: String,
    port: Int
) : NanoHTTPD(bindHost, port) {

    private val auditStore: AuditStore get() = AuditLog.from(ctx)

    /**
     * The switch, read once per request.
     *
     * Deliberately NOT cached in a field here: `PolicyStore` caches the parsed file,
     * so this is a map lookup, and going through the store every request means a
     * change made on the home screen takes effect on the very next request with no
     * cache-invalidation path to get wrong.
     */
    private val policy: Policy get() = PolicyStore.from(ctx).get()

    override fun serve(session: IHTTPSession): Response {
        val startedAt = System.currentTimeMillis()
        val rawUri = session.uri
        val path = rawUri.substringBefore('?').trimEnd('/')
        val method = session.method.name
        val remote = session.remoteIpAddress ?: "unknown"
        val query = session.parameters.mapValues { it.value.firstOrNull() ?: "" }

        // The label comes from the REGISTRY, not from the address. `CallerStore.labelFor`
        // answers a human-given name, "this device" for loopback, or an explicit
        // "unverified caller <ip>" — never a bare IP dressed up as a name.
        val callerLabel = callerLabelFor(remote)

        val draft = AuditDraft(
            startedAtMs = startedAt,
            remote = remote,
            requesterLabelValue = callerLabel,
            method = method,
            path = path,
            query = query,
            requestHeaders = session.headers,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE
        )

        // The whole request runs here and NEVER throws: every failure path sets a
        // terminal outcome on the draft, so the epilogue below always has one fact
        // to record and there is no path into the handler without a decision.
        val result = dispatch(session, draft, method, path, remote, query)

        // Audit epilogue, inside the request: the client gets its answer only after
        // the record is on disk. A failed append is a request failure (locked Q1) —
        // the side effect may already have happened, so say exactly that rather than
        // reporting a success we cannot prove.
        return try {
            if (draft.written) throw IllegalStateException("audit record already written for this request")
            draft.markWritten()
            auditStore.append(draft.toRecord(System.currentTimeMillis()))
            // RECORD FIRST, THEN STREAM. For JSON this was already true; for a stream it is the
            // only possible order — once the headers are on the wire a failure can no longer be
            // reported as an error status. The outcome (approved, 200) is known before a byte
            // moves, so the record is complete either way. A mid-transfer socket failure is
            // inherently unreportable; that is a property of streaming, not of this code.
            writeResponse(result)
        } catch (e: Exception) {
            Log.e(TAG, "audit append failed", e)
            json(
                Response.Status.INTERNAL_ERROR,
                err("audit_unavailable", "action may have executed, no record: ${e.message}")
            )
        }
    }

    /**
     * One HTTP response plus the status the audit should record for it.
     *
     * [body] stays a `JSONObject` so the ~13 existing construction sites are untouched; [payload]
     * is the streaming escape hatch. Exactly one of them is meaningful: a route that returns
     * [RespPayload.Stream] sets `payload` and leaves `body` empty, and [writeResponse] checks
     * `payload` FIRST. Keeping `body` non-null avoids a sealed type here that every existing call
     * site would have to unwrap for no benefit.
     */
    private class Dispatched(
        val status: Response.Status,
        val body: JSONObject,
        val payload: RespPayload? = null,
    )

    /**
     * Resolve, describe, gate, execute — setting exactly one terminal [AuditOutcome]
     * on [draft] no matter how it ends.
     */
    private fun dispatch(
        session: IHTTPSession,
        draft: AuditDraft,
        method: String,
        path: String,
        remote: String,
        query: Map<String, String>
    ): Dispatched {
        return try {
            // CALLER CHECK — FIRST, before routing, before policy, before the gate.
            //
            // Deliberately ahead of `resolve`: an unverified address must not learn which routes
            // exist. Answering 404 for an unknown path and 403 for an unknown caller would let a
            // stranger map the surface by watching which refusal they get.
            //
            // Loopback is exempt inside the registry (it is the device itself, unreachable from
            // anywhere else), so the app's own probes never depend on a human naming them.
            val callerState = try {
                com.mido.phonebridge.callers.CallerStore.from(ctx).stateOf(remote)
            } catch (e: Exception) {
                // An unreadable registry is NOT "everyone unverified" and NOT "everyone fine":
                // both would be a decision made by a corrupt file. Refuse, and name the cause.
                draft.outcome(AuditOutcome.HANDLER_FAILED)
                draft.error("caller_registry_unavailable")
                val obj = err(
                    "caller_registry_unavailable",
                    "the caller registry cannot be read (${e.message}); refusing rather than " +
                        "guessing who may call"
                )
                draft.response(500, obj.toString())
                return Dispatched(Response.Status.INTERNAL_ERROR, obj)
            }
            when (callerState) {
                is com.mido.phonebridge.callers.CallerState.Verified -> Unit
                com.mido.phonebridge.callers.CallerState.Denied -> {
                    draft.outcome(AuditOutcome.REJECTED_INVALID)
                    draft.error("caller_denied")
                    val obj = err(
                        "caller_denied",
                        "this address was blocked on the phone. No route will answer it until " +
                            "that block is removed in Options."
                    )
                    draft.response(403, obj.toString())
                    return Dispatched(Response.Status.FORBIDDEN, obj)
                }
                null -> {
                    // Unverified: ask the human, once, with a full-screen prompt triggered by
                    // THIS request. The caller's own first call is the onboarding trigger — a
                    // stranger cannot make the phone light up repeatedly, because the answer is
                    // recorded (verified or denied) rather than forgotten.
                    val outcome = com.mido.phonebridge.callers.CallerPrompt.ask(ctx, remote)
                    when (outcome) {
                        is com.mido.phonebridge.callers.CallerPrompt.Result.Named -> Unit
                        com.mido.phonebridge.callers.CallerPrompt.Result.Blocked -> {
                            draft.outcome(AuditOutcome.REJECTED_INVALID)
                            draft.error("caller_denied")
                            val obj = err(
                                "caller_denied",
                                "this address was blocked on the phone."
                            )
                            draft.response(403, obj.toString())
                            return Dispatched(Response.Status.FORBIDDEN, obj)
                        }
                        com.mido.phonebridge.callers.CallerPrompt.Result.Unavailable -> {
                            draft.outcome(AuditOutcome.REJECTED_INVALID)
                            draft.error("caller_unverified")
                            val obj = err(
                                "caller_unverified",
                                "this address has never been named and the prompt could not be " +
                                    "shown (screen off or locked). Unlock the phone and retry."
                            )
                            draft.response(403, obj.toString())
                            return Dispatched(Response.Status.FORBIDDEN, obj)
                        }
                        com.mido.phonebridge.callers.CallerPrompt.Result.Timeout -> {
                            draft.outcome(AuditOutcome.REJECTED_INVALID)
                            draft.error("caller_unverified")
                            val obj = err(
                                "caller_unverified",
                                "the naming prompt expired with no answer. Retry to be asked again."
                            )
                            draft.response(403, obj.toString())
                            return Dispatched(Response.Status.FORBIDDEN, obj)
                        }
                    }
                }
            }

            val resolved = PluginRegistry.resolve(method, path)
            if (resolved == null) {
                draft.outcome(AuditOutcome.UNROUTED)
                return Dispatched(
                    Response.Status.NOT_FOUND,
                    err("not_found", "unknown route $method $path")
                )
            }
            // Fill the label BEFORE anything can fail: a rejected request still has
            // a plugin and a verb, and the log is far more useful with them.
            draft.bindRoute(resolved.plugin.id, resolved.route.verb, resolved.route.path)

            // A DISABLED PLUGIN STILL ANSWERS, AND IT ANSWERS `plugin_disabled`.
            //
            // The route exists and the caller spelled it correctly, so `not_found` would be a
            // lie that sends them hunting for an unrelated typo. This is checked BEFORE the
            // model gate and before validation: a capability the human turned off has no
            // business waiting on a model download, and telling a caller their input is bad
            // when the feature is simply switched off is the wrong correction to make.
            //
            // 409 rather than 403: it is a conflict between the request and the app's current
            // configuration state, and it is fixable by a human. Recorded as REJECTED_INVALID
            // (a 4xx, so noise) — a disabled plugin is configuration, not a fault.
            if (!PluginRegistry.isEnabled(resolved.plugin.id)) {
                draft.outcome(AuditOutcome.REJECTED_INVALID)
                draft.error("plugin_disabled")
                val obj = err(
                    "plugin_disabled",
                    "the '${resolved.plugin.displayName}' plugin is switched off. " +
                        "Enable it in the app's Plugins screen to use this route."
                )
                draft.response(409, obj.toString())
                return Dispatched(Response.Status.CONFLICT, obj)
            }

            // MODEL READINESS. The AI is core functionality (Mido, 2026-10-19), so the
            // bridge does not serve without it: a missing model is a NOT-READY state,
            // not a degraded one. Every route answers 503 until the model is
            // provisioned, which is what turns "the app downloads its own model" into
            // part of setup rather than a convenience. Checked here, before the body
            // is read and before validation, so the caller learns the bridge is not up
            // without first being asked to fix their input.
            //
            // `/debug` and `/logs` declare `aiSummary = false`, which is what exempts them:
            // a route that needs no AI needs no model. They are how the not-ready state is
            // DIAGNOSED from outside, and blocking them would make a not-ready bridge
            // undiagnosable — the dead end `/debug` exists to prevent. Read-only, and
            // neither opens a decision.
            //
            // Now routed through [AiGateway], which also answers for the global OFF switch: with
            // the AI off there is no model to be missing, so this condition is always false and
            // `503 model_not_ready` can never be the reason a caller is refused. Putting that in
            // the gateway rather than here is the point — one seam, not a check every call site
            // has to remember.
            if (!com.mido.phonebridge.ai.AiGateway.serves(ctx, resolved.route.aiSummary)) {
                // HANDLER_FAILED, not REJECTED_INVALID: a 503 is OUR side, and the audit
                // rule is that 5xx is never noise. Recording it as the caller's mistake
                // would hide a not-ready bridge among typos — the exact misclassification
                // `AuditClassify` exists to prevent.
                draft.outcome(AuditOutcome.HANDLER_FAILED)
                draft.error("model_not_ready")
                // **NOT `ModelRegistry.readiness().detail`.** That always describes the LOCAL
                // model file, so under EXTERNAL it reported "ready (328 MB)" while the bridge was
                // refusing every worded route — a detail that contradicts its own status code and
                // sends a human to look for a missing model that is not missing. `notReadyDetail`
                // asks the configured BACKEND why it cannot answer.
                val obj = err(
                    "model_not_ready",
                    com.mido.phonebridge.ai.AiGateway.notReadyDetail(ctx)
                )
                draft.response(503, obj.toString())
                return Dispatched(Response.Status.SERVICE_UNAVAILABLE, obj)
            }

            // TWO INDEPENDENT DECISIONS, both from the route, both taken HERE — before a byte is
            // read — because each one has to be answered differently on the way in:
            //
            //   streamsRequest  -> do NOT buffer. `readRawBody` builds the whole body into a
            //                      String, which is fine for JSON and fatal for a 200 MB file.
            //   auditRequestBody-> do NOT record. The audit stores bodies verbatim (capped at
            //                      32 KiB), so a streamed upload would write the first 32 KB of
            //                      the user's document permanently into an append-only log.
            //
            // They are separate flags on purpose: an upload needs both for unrelated reasons, and
            // a route could want either alone.
            val streamsRequest = resolved.route.streamsRequest && method == "POST"
            val auditRequest = resolved.route.auditRequestBody

            val rawBody = if (method == "POST" && !streamsRequest) readRawBody(session) else ""
            if (auditRequest) {
                // Verbatim, and BEFORE the parse that can throw. A malformed body still
                // has to leave a record: without this the log says "invalid JSON" and
                // keeps not one byte of what was actually sent.
                draft.requestBody(rawBody)
            } else {
                // Marked as deliberately-not-stored rather than left empty, so "no body was
                // recorded on purpose" stays distinguishable from "the body was empty".
                draft.requestBodyNotStored()
            }
            val body = if (method == "POST" && !streamsRequest) parseBody(rawBody) else JSONObject()

            val req = Req(
                method = method,
                path = path,
                query = query,
                pathParams = resolved.pathParams,
                body = body,
                remote = remote,
                // The handler reads this itself; nothing parses it on its behalf, which is the
                // point — the caller's bytes ARE the payload.
                bodyStream = if (streamsRequest) session.inputStream else null,
                bodyLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
            )

            // THE VERB, resolved ONCE for this request.
            //
            // A route may derive its verb from the payload (the secrets broker maps the
            // OUTBOUND method, so a GET is READ and a DELETE is DELETE). Everything that
            // needs a verb reads this local: policy, the audit label, the exception offer,
            // the NOTIFY card and the AI facts. Six call sites reading one value is the
            // point — they cannot disagree about what the request was, which is exactly how
            // `todoist/EXECUTE` once made "reads only" inexpressible (Mido, 2026-10-19).
            //
            // Computed after the body is parsed (a resolver needs it) but BEFORE validation
            // and the gate, so a bad request is still labelled with what it attempted.
            val verb = resolved.route.effectiveVerb(req)
            // Re-bind: the first bind ran before the body existed, so it could only record
            // the static fallback verb. bindRoute is a plain field-set, so the later call
            // simply wins — no duplicate-label risk.
            draft.bindRoute(resolved.plugin.id, verb, resolved.route.path)

            // PRE-GATE. Input that can never succeed must not cost a tap: the modal
            // is a decision, and there is no decision to make about `count=-1`.
            // Runs before the description, so a bad request never even builds a
            // modal. A throw here is REJECTED_INVALID (4xx, noise) by construction —
            // see the catch below, where `gated()` is still false.
            resolved.route.validate?.invoke(req)

            val summary = describe(resolved.plugin.id, resolved.route.path, body)
            draft.summary(summary)

            val am = com.mido.phonebridge.core.AuthManager
            val account = am.accountEmail
                ?: if (am.isLoggedIn(ctx)) "(signed in)" else "—"

            // POLICY. Resolved after validation (bad input still costs nothing) and
            // before the gate. Guarded rather than assumed: startup already refuses on
            // an unreadable policy, so this is the belt to that braces, and a request
            // must never run on a mode nobody could read.
            val resolvedPolicy = try {
                val p = policy
                resolve(p.global, p.rules, resolved.plugin.id, verb)
            } catch (e: Exception) {
                draft.outcome(AuditOutcome.HANDLER_FAILED)
                draft.error("policy_unavailable")
                val obj = err("policy_unavailable", e.message ?: "policy unreadable")
                draft.response(503, obj.toString())
                return Dispatched(Response.Status.SERVICE_UNAVAILABLE, obj)
            }
            val resolvedPolicyMode = resolvedPolicy.mode

            // THE FLOOR. A request that needs a password the human types at the modal can
            // only be gated by SECURE.
            //
            // NOTIFY approves on five seconds of silence from an overlay card that has no
            // Activity and no biometric — there is nowhere to type a password, and a
            // silent timeout cannot type one either. Letting a NOTIFY rule reach such a
            // request would mean approving it with no password and then running sudo
            // without one, which fails closed only by accident (sudo refuses without a
            // terminal). So the mode is raised HERE, where the route is known — `resolve()`
            // sees only plugin + verb and cannot express this.
            //
            // Computed before the attendance check, because attendance is judged against
            // the mode that will actually gate the request, not the one policy suggested.
            val needsPassword = resolved.route.needsPassword?.invoke(req) == true
            val mode = if (needsPassword) Mode.PARANOID else resolvedPolicyMode
            val globalMode = policy.global

            // **THE VAULT, BEFORE THE ROWS.**
            //
            // A route that signs or spends a stored credential cannot even build its approval card
            // while the vault is shut: `describeRows` reads the key to name the target, and that
            // runs pre-gate. So a locked vault used to kill the request in milliseconds with
            // `secret_locked` and NO prompt — the caller saw a refusal it could not act on and the
            // human was never asked (2026-11-11: four attempts to run `gh` from the bridge, all
            // refused, which read as inattention rather than a locked vault).
            //
            // Asking here fixes that at the only point where it CAN be fixed: before the rows are
            // built. And it is still only a key release — the request then proceeds to its normal
            // gate, where the human reads the real host and taps APPROVE. A fingerprint must never
            // be readable as consent.
            if (resolved.route.needsVault) {
                val vaultState = SecretVault.state(ctx)
                val need = VaultNeed.decide(
                    needsVault = true,
                    unlocked = SecretVault.isUnlocked(),
                    state = vaultState,
                    mode = mode,
                    canPresent = PromptSurface.of(
                        fullScreenIntent = ApprovalGate.canUseFullScreenIntent(ctx),
                        overlayGranted = com.mido.phonebridge.ui.SystemAccess.overlayGranted(ctx),
                    ) != null,
                )
                if (need == VaultNeed.Action.UNLOCK) {
                    Debug.log("${resolved.plugin.id}: vault locked, asking for a fingerprint")
                    val opened = VaultUnlockGate.awaitUnlock(
                        ctx = ctx,
                        endpoint = "$method $path",
                        remote = remote,
                    )
                    if (!opened) {
                        draft.outcome(AuditOutcome.REJECTED_INVALID)
                        draft.error("secret_locked")
                        val obj = err("secret_locked", VaultNeed.detail(VaultNeed.Action.UNLOCK, vaultState))
                        draft.response(403, obj.toString())
                        return Dispatched(Response.Status.FORBIDDEN, obj)
                    }
                } else if (need != VaultNeed.Action.PROCEED) {
                    // A dead end, or a gate mode with nowhere to put a fingerprint. Both refuse
                    // with the thing that would actually change it — never a prompt that cannot
                    // succeed, and never a silent wait.
                    draft.outcome(AuditOutcome.REJECTED_INVALID)
                    draft.error("secret_locked")
                    val obj = err("secret_locked", VaultNeed.detail(need, vaultState))
                    draft.response(403, obj.toString())
                    return Dispatched(Response.Status.FORBIDDEN, obj)
                }
            }

            // ATTENDANCE. A request that cannot be shown to an attending human is DENIED —
            // with one exception, and it is a deliberate reversal of the original rule.
            //
            // Mido, 2026-10-25: *"for notification level calls it shouldn't matter if the screen
            // was off or the phone locked or not, as long as the app was unlocked once for
            // storage unlock"*. So a NOTIFY request may proceed with nobody at the screen while
            // the vault session is open. The rule lives in `unattendedAllowed`, pure and tested,
            // because it decides whether a request runs with no human looking.
            //
            // The consequence is stated where the rule is defined, not hidden here: NOTIFY
            // approves on silence, a dark screen guarantees silence, so this is unattended
            // approval — "unlocked once, trusted after", not "someone saw this".
            val attended = isAttended(ctx)
            if (!attended && !unattendedAllowed(mode, SecretVault.isUnlocked())) {
                draft.decision(
                    Decision(
                        mode.name, decisionSource(resolvedPolicy.source),
                        globalMode.name, resolvedPolicy.ruleKey,
                        null, null, null, 0
                    )
                )
                draft.outcome(AuditOutcome.DENIED_UNATTENDED)
                draft.error("denied")
                val obj = err("denied", "gate unavailable: screen off or device locked")
                draft.response(403, obj.toString())
                return Dispatched(Response.Status.FORBIDDEN, obj)
            }
            // Unattended but permitted: the human is not there, so a card cannot be read and
            // must not be drawn. Recorded as its own fact — an approval nobody could see is
            // not the same evidence as one that was on screen.
            val unattendedProceed = !attended

            // GATE EXEMPTION. A route may skip the gate entirely — today only the audio cue
            // route does, because a cue whose job is to make someone look at the phone cannot
            // require them to look first. See Route.gateExempt: the exemption is only defensible
            // for a route that can do nothing but make a short noise.
            //
            // It still records a normal APPROVED outcome, because Mido's rule is that it "still
            // logs as other requests" — one log, one vocabulary, no private outcome value that a
            // reader has to learn.
            if (resolved.route.gateExempt) {
                draft.outcome(AuditOutcome.APPROVED)
            } else {
                when (mode) {
                    Mode.DONT_DISTURB -> {
                        if (unattendedProceed) {
                            // Nobody is at the screen, so a card cannot be read and must not be
                            // drawn. Drawing one would be the dishonest version: it would report
                            // "shown" for something that was never legible.
                            //
                            // This is the unattended approval the rule permits. It is recorded with
                            // its own detail so the log never reads like an observed decision.
                            draft.decision(
                                Decision(
                                    mode.name, decisionSource(resolvedPolicy.source),
                                    globalMode.name, resolvedPolicy.ruleKey,
                                    null, null, System.currentTimeMillis(), 0
                                )
                            )
                            draft.outcome(AuditOutcome.NOTIFY_APPROVED)
                            draft.error("unattended")
                        } else {
                            // A small overlay card, not a notification: the draw permission
                            // this app already holds makes the prompt ours, so it appears
                            // above whatever the human is looking at instead of fighting the
                            // shade. The card fetches the AI summary FIRST, then starts its 5s
                            // clock; silence approves, a tap approves-and-skips, the ✕ rejects.
                            val decision = NotifyGate.await(
                                ctx,
                                summary,
                                labelOf(resolved.plugin.id, verb),
                                verb,
                                // Null facts for a route that declares `aiSummary = false`: the card
                                // then shows its deterministic content and its clock starts at once.
                                // Never built for an opted-out route, so the rows are not fetched
                                // either — an opted-out route pays nothing for the AI.
                                if (com.mido.phonebridge.ai.AiGateway.requiresAi(ctx, resolved.route.aiSummary))
                                    aiFactsFor(
                                        resolved.route, verb, summary, path, body, query, remote,
                                        // The body EXACTLY as received — `rawBody` is captured before
                                        // parsing, so this is byte-for-byte what the caller sent.
                                        rawBody = rawBody,
                                        url = body.optString("url", "")
                                    )
                                else null,
                                needsAi = com.mido.phonebridge.ai.AiGateway.requiresAi(ctx, resolved.route.aiSummary),
                                // Who is asking, by the registry name (falls back to the address),
                                // so the human who stays silent still saw who asked.
                                requester = callerLabelFor(remote),
                            )
                            when (decision) {
                                is NotifyGate.Result.Approved -> {
                                    draft.decision(
                                        Decision(
                                            mode.name, decisionSource(resolvedPolicy.source),
                                            globalMode.name, resolvedPolicy.ruleKey,
                                            null, decision.shownAt, decision.decidedAt, 0
                                        )
                                    )
                                    draft.outcome(AuditOutcome.NOTIFY_APPROVED)
                                }
                                is NotifyGate.Result.Tapped -> {
                                    // A deliberate tap-approve: recorded as NOTIFY_APPROVED
                                    // (the outcome set is closed) but the decision block's
                                    // decidedAt is the tap instant, and the detail carries the
                                    // distinction so the log can tell a tap from silence.
                                    draft.decision(
                                        Decision(
                                            mode.name, decisionSource(resolvedPolicy.source),
                                            globalMode.name, resolvedPolicy.ruleKey,
                                            null, decision.shownAt, decision.decidedAt, 0
                                        )
                                    )
                                    draft.outcome(AuditOutcome.NOTIFY_APPROVED)
                                    draft.error("tapped")
                                }
                                is NotifyGate.Result.Rejected -> {
                                    draft.decision(
                                        Decision(
                                            mode.name, decisionSource(resolvedPolicy.source),
                                            globalMode.name, resolvedPolicy.ruleKey,
                                            null, decision.shownAt, decision.decidedAt, 0
                                        )
                                    )
                                    draft.outcome(AuditOutcome.NOTIFY_REJECTED)
                                    draft.error("denied")
                                    draft.response(403, null)
                                    return Dispatched(
                                        Response.Status.FORBIDDEN,
                                        err("denied", "request denied by user")
                                    )
                                }
                                is NotifyGate.Result.AiFailed -> {
                                    // The card could not be described, so it was withdrawn without a
                                    // decision. The AI is core (Mido, 2026-10-19): this is a fault on
                                    // our side, answered as a coded error — NEVER as an approval, and
                                    // never as a deny either, because no human said no.
                                    draft.outcome(AuditOutcome.HANDLER_FAILED)
                                    draft.error("ai_unavailable")
                                    val obj = err("ai_unavailable", decision.detail)
                                    draft.response(500, obj.toString())
                                    return Dispatched(Response.Status.INTERNAL_ERROR, obj)
                                }
                                is NotifyGate.Result.Unavailable -> {
                                    // Could not be shown: no permission, evicted, or the screen
                                    // went away mid-window. Deny, and say which. Approving
                                    // because we failed to draw something would be the one
                                    // failure mode this whole mode must never have.
                                    draft.decision(
                                        Decision(
                                            mode.name, decisionSource(resolvedPolicy.source),
                                            globalMode.name, resolvedPolicy.ruleKey,
                                            null, decision.shownAt, decision.decidedAt, 0
                                        )
                                    )
                                    draft.outcome(AuditOutcome.DENIED_UNATTENDED)
                                    draft.error("denied")
                                    val obj = err("denied", "gate unavailable: ${decision.detail}")
                                    draft.response(403, obj.toString())
                                    return Dispatched(Response.Status.FORBIDDEN, obj)
                                }
                            }
                        }
                    }
                    Mode.PARANOID -> {
                        // Central gate: describe the concrete call, then wait for the human.
                        //
                        // The AI summary is NO LONGER computed here (Mido, 2026-10-19). It used
                        // to block the modal for up to 10s and then leave the buttons dead for
                        // another second — two static waits stacked. The modal now appears
                        // immediately and the gate fetches in the background: the buttons enable
                        // when the summary lands, and the 60s clock starts from THAT instant, so
                        // the model's latency is never charged to the human's decision time.
                        //
                        // Rows are built ONCE, before the view, because a bad target must still
                        // throw pre-gate (coded 4xx, no modal) and the AI needs the same rows.
                        val rows = pluginRows(resolved.route, path, body, query, remote)
                        val gate = ApprovalGate.await(
                            ctx, remote, "$method $path", summary,
                            detail = detailFor(resolved.route.path, path, body),
                            account = account,
                            view = approvalView(
                                remote, account, resolved.route, path, body, query,
                                summary = summary,
                                // A `SECURE` modal asks for a fingerprint only when the option says
                                // so. The VAULT is untouched by this: its key is auth-per-use in the
                                // Keystore and `lockOnLoad` still unlocks it at app start, so a locked
                                // vault answers `secret_locked` exactly as it already does under
                                // NOTIFY, which never touched biometrics either.
                                requiresBiometric = mode == Mode.PARANOID && policy.fingerprintForModals,
                                rows = rows,
                                exceptionOffer = ApprovalGate.exceptionOfferFor(
                                    globalMode, resolved.plugin.id, verb, mode, needsPassword
                                ),
                                // The gate needs this to render the field and to refuse a
                                // resolve that arrives without one.
                                needsPassword = needsPassword,
                                effectiveVerb = verb,
                            ),
                            // **THE GATE'S OWN COPY, not just the view's.** The view above only
                            // decides what the modal DRAWS; `await` decides whether
                            // `resolveWithPassword` will accept the typed password. Omitting this
                            // left `Pending.needsPassword` at its default `false`, so every
                            // password was rejected with "does not need a password, or expired"
                            // even though the field had rendered and the human had typed into it
                            // (Mido, 2026-10-27: *"I entered the password and accepted"* → the
                            // call still timed out).
                            needsPassword = needsPassword,
                            // Null for a route that declares `aiSummary = false`: there is nothing
                            // to wait for, so its buttons go live at once.
                            aiFacts = if (com.mido.phonebridge.ai.AiGateway
                                    .requiresAi(ctx, resolved.route.aiSummary))
                                factsFor(
                                    resolved.route, verb, summary, rows,
                                    rawBody = rawBody,
                                    url = body.optString("url", "")
                                ) else null
                        )
                        draft.decision(gateDecision(gate, globalMode, resolvedPolicy))

                        when (gate) {
                            is ApprovalGate.GateResult.Denied -> {
                                draft.outcome(AuditOutcome.DENIED)
                                draft.error("denied")
                                draft.response(403, null)
                                return Dispatched(
                                    Response.Status.FORBIDDEN,
                                    err("denied", "request denied by user")
                                )
                            }
                            is ApprovalGate.GateResult.TimedOut -> {
                                draft.outcome(AuditOutcome.TIMEOUT)
                                draft.error("approval_timeout")
                                draft.response(408, null)
                                return Dispatched(
                                    Response.Status.REQUEST_TIMEOUT,
                                    err("approval_timeout", "approval timed out")
                                )
                            }
                            is ApprovalGate.GateResult.ApprovedWithException -> {
                                // The human approved AND asked for this pair to behave
                                // differently next time. Both halves are real, so both are
                                // recorded: the approval here, the policy move in the same
                                // commit below. A write that fails does NOT deny the request —
                                // the human did approve — but it is surfaced, never swallowed.
                                draft.outcome(AuditOutcome.APPROVED)
                                writeException(gate.rule)
                            }
                            is ApprovalGate.GateResult.ApprovedWithPassword -> {
                                // The human approved AND typed the password sudo needs. The
                                // secret is attached to the request here — the ONE place that
                                // knows both the approval and the Req — and wiped immediately
                                // after the handler returns, in the finally below.
                                //
                                // The audit records THAT a password was supplied, never the
                                // value: the summary gains one word, so no schema change and
                                // nothing to redact.
                                draft.outcome(AuditOutcome.APPROVED)
                                draft.summary("$summary (sudo password supplied)")
                                req.secretInput = gate.password
                            }
                            is ApprovalGate.GateResult.Approved ->
                                draft.outcome(AuditOutcome.APPROVED)
                        }
                    }
                }
            }

            // ONE handler call and ONE response-recording site, for every mode. They used
            // to be two — an ungated branch (the `OFF` switch, removed in 0.12.0) returned
            // EARLY, so `Route.auditResponseBody` was honoured on one path and ignored on
            // the other, and /debug stored its entire body whenever the gate was off
            // (measured on device: ~13.7 KB per record, against ~1 KB under SECURE).
            // The handler call cannot be duplicated again without deleting this comment.
            //
            // The password — when the human typed one — is wiped in a `finally` so it
            // cannot survive a handler that throws. A secret with a cleanup path only on
            // the happy path is a secret that leaks on every failure.
            val resp = try {
                runBlocking { resolved.route.handler(req) }
            } finally {
                req.secretInput?.fill('\u0000')
                req.secretInput = null
            }
            // The route may hand the log a REDACTED copy while the caller keeps the real one
            // (contacts: real numbers out, half-masked numbers retained). Applied here, in the
            // one dispatch site, so the transform cannot be honoured on one path and ignored on
            // another — the exact way `auditResponseBody` itself drifted before `recordResponse`.
            recordResponse(
                draft,
                resolved.route.auditResponseBody,
                resp.status,
                resolved.route.auditResponseTransform?.invoke(resp.body) ?: resp.body,
            )
            Dispatched(statusOf(resp.status), resp.body, resp.payload)
        } catch (e: PluginError) {
            // Two very different failures share this type. BEFORE the gate it is the
            // caller's fault (bad input, unknown id) → 4xx, noise. AFTER the gate the
            // human already approved and the server still failed → a real fault.
            val st = httpStatusFor(e.code)
            draft.outcome(AuditClassify.forError(st.requestStatus, draft.gated()))
            draft.error(e.code)
            val bodyObj = err(e.code, e.message ?: e.code)
            draft.response(st.requestStatus, bodyObj.toString())
            Dispatched(st, bodyObj)
        } catch (e: Exception) {
            Log.e(TAG, "unhandled", e)
            draft.outcome(AuditClassify.forCrash())
            draft.error("internal_error")
            val bodyObj = err("internal_error", e.message ?: "error")
            draft.response(500, bodyObj.toString())
            Dispatched(Response.Status.INTERNAL_ERROR, bodyObj)
        }
    }

    /** Map a gate outcome onto the audit's decision block. */
    private fun gateDecision(
        g: ApprovalGate.GateResult,
        globalMode: GlobalMode,
        resolved: ModeResolution
    ): Decision {
        val mode = Mode.PARANOID.name
        val source = decisionSource(resolved.source)
        val ruleKey = resolved.ruleKey
        return when (g) {
            is ApprovalGate.GateResult.Approved -> Decision(
                mode, source, globalMode.name, ruleKey,
                g.enqueuedAt, g.shownAt, g.decidedAt, g.queueDepth
            )
            // A password approval is an approval: the human decided, and the extra fact
            // (that a secret was supplied) is stated in the SUMMARY, never as the value.
            is ApprovalGate.GateResult.ApprovedWithPassword -> Decision(
                mode, source, globalMode.name, ruleKey,
                g.enqueuedAt, g.shownAt, g.decidedAt, g.queueDepth
            )
            // The exception tap is an approval first: the decision block reads like an
            // approval (a human did approve this request), and the policy move it also
            // caused is its own POLICY_CHANGED record. Two facts, two lines.
            is ApprovalGate.GateResult.ApprovedWithException -> Decision(
                mode, source, globalMode.name, ruleKey,
                g.enqueuedAt, g.shownAt, g.decidedAt, g.queueDepth
            )
            is ApprovalGate.GateResult.Denied -> Decision(
                mode, source, globalMode.name, ruleKey,
                g.enqueuedAt, g.shownAt, g.decidedAt, g.queueDepth
            )
            is ApprovalGate.GateResult.TimedOut -> Decision(
                mode, source, globalMode.name, ruleKey,
                g.enqueuedAt, g.shownAt, g.expiredAt, g.queueDepth
            )
        }
    }

    /**
     * Persist a rule the human authored from the modal, and record it.
     *
     * Read-modify-write, never a fresh [Policy]: constructing a new one would reset every
     * sibling rule, which is a bug this project already paid for once in `applyGate`.
     * Recorded with `source = "modal"` so the log says WHERE a policy decision came from,
     * not merely that one happened.
     *
     * A failure here does not fail the request — the human already approved — but it is
     * surfaced through the audit health banner rather than swallowed, because a rule that
     * took effect unrecorded is the same class of failure as an unrecorded switch flip.
     */
    private fun writeException(rule: Rule) {
        try {
            val store = PolicyStore.from(ctx)
            val current = store.get()
            val before = current.rules.firstOrNull { it.key == rule.key }
            store.set(current.withRule(rule))
            auditStore.append(PolicyAudit.recordRule(before, rule, current.global, "modal"))
        } catch (e: Exception) {
            AuditHealth.markDegraded("modal exception not recorded: ${e.message}")
            Log.w("BridgeService", "exception write failed: ${e.message}")
        }
    }

    /**
     * Map the policy's own source enum onto the audit's. Two enums rather than one
     * because `PolicyAudit` (in the audit package) already imports `GlobalMode` from
     * the policy package, so sharing an enum would make the dependency circular.
     * This function is the single seam, so the mapping exists once.
     */
    private fun decisionSource(source: ModeSource): DecisionSource = when (source) {
        ModeSource.GLOBAL -> DecisionSource.GLOBAL
        ModeSource.RULE -> DecisionSource.RULE
        ModeSource.DEFAULT -> DecisionSource.DEFAULT
    }

    private companion object {
        const val TAG = "HttpServer"
    }

    /**
     * Build the structured WHO / WHAT / ON view for the modal.
     *
     * For notification actions the WHAT is the RESOLVED action label (looked up
     * on the live notification) and the ON block is the real notification — so
     * the modal can never understate what will happen. For every other route we
     * fall back to the route-level description.
     */
    private fun approvalView(
        remote: String,
        account: String,
        route: Route,
        actualPath: String,
        body: JSONObject,
        query: Map<String, String>,
        summary: String,
        requiresBiometric: Boolean,
        /**
         * Target rows, built ONCE by the caller and shared with the AI facts.
         *
         * Passed in rather than gathered here because the summary now needs the SAME rows
         * (see [factsFor]) and building them twice is a second live Google round-trip per
         * approval on a route that pre-fetches its target — gmail trash, calendar delete.
         */
        rows: List<ApprovalRow>,
        /** The exception the modal may offer, or null. See [ApprovalGate.exceptionOfferFor]. */
        exceptionOffer: ApprovalGate.ExceptionOffer? = null,
        /**
         * True when the modal must collect a sudo password.
         *
         * Passed in rather than read from the route here, because the decision is made
         * from the REQUEST (`SudoCommand.invokes(command)`) and this function only sees
         * the body as a JSONObject it should not be re-deriving policy from.
         */
        needsPassword: Boolean = false,
        /** Route.effectiveVerb(req): picks the approve gesture (D12). */
        effectiveVerb: Verb
    ): ApprovalGate.ApprovalView {
        val routeKey = "${route.method} ${route.path}"
        val routePath = route.path

        // /notifications/{id}/action -> resolve the action against live data.
        if (routePath == "/notifications/{id}/action") {
            val pid = actualPath.trim('/').split('/').getOrNull(1) ?: ""
            val sbn = NotifStore.findByPublicId(pid)
            val actionRef: Any = when {
                body.has("action") && body.get("action") is Number -> body.getInt("action")
                body.has("action") -> body.getString("action")
                else -> throw PluginError("bad_request", "missing 'action'")
            }
            // Resolve FIRST: unknown action => coded error, no modal, no 500.
            val action = try {
                ActionExecutor.resolve(ctx, sbn?.key ?: "", actionRef)
            } catch (e: BridgeError) {
                throw PluginError(e.code, e.message ?: e.code)
            }
            val extras = sbn?.notification?.extras
            val appLabel = try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(sbn!!.packageName, 0)).toString()
            } catch (e: Exception) {
                sbn?.packageName ?: "?"
            }
            val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val sender = (sbn?.notification?.extras
                ?.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)?.toString())
                ?.takeIf { it.isNotBlank() } ?: ""
            // Other buttons the same notification offers, excluding the one being
            // fired. Options are the menu; the resolved action is the pick.
            val fired = action.title?.toString() ?: ""
            val otherActions = (sbn?.notification?.actions ?: emptyArray<android.app.Notification.Action>())
                .mapNotNull { it.title?.toString()?.takeIf { t -> t.isNotBlank() && t != fired } }
            // Target rows come from the plugin (NotificationsPlugin.describeRows),
            // same as every other plugin — one source of truth, not a second copy here.
            return ApprovalGate.ApprovalView(
                requester = callerLabelFor(remote),
                account = account,
                actionLabel = action.title?.toString() ?: "(unnamed action)",
                appLabel = appLabel,
                notifTitle = title,
                notifSender = sender,
                notifText = text,
                messageCount = sbn?.notification?.number ?: 0,
                replyText = if (body.has("replyText")) body.getString("replyText") else null,
                options = otherActions,
                destructive = rows.toGateRows(),
                requiresBiometric = requiresBiometric,
                exceptionOffer = exceptionOffer,
                needsPassword = needsPassword,
                verb = effectiveVerb,
                routeKey = routeKey,
                holdExempt = route.holdExempt,
            )
        }

        // /notifications/{id}/dismiss -> resolve the notification ONCE, so the modal
        // can show what is being dismissed exactly like an action does. Same rows,
        // same guarantee: the human sees the real title/body, not a bare verb.
        if (routePath == "/notifications/{id}/dismiss") {
            val pid = actualPath.trim('/').split('/').getOrNull(1) ?: ""
            val sbn = NotifStore.findByPublicId(pid)
                ?: throw PluginError("notification_gone", "no live notification for id '$pid'")
            val extras = sbn.notification.extras
            val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            return ApprovalGate.ApprovalView(
                requester = callerLabelFor(remote),
                account = account,
                actionLabel = "Dismiss a notification",
                appLabel = "",
                notifTitle = title,
                notifSender = "",
                notifText = text,
                messageCount = sbn.notification.number,
                replyText = null,
                destructive = rows.toGateRows(),
                requiresBiometric = requiresBiometric,
                exceptionOffer = exceptionOffer,
                needsPassword = needsPassword,
                verb = effectiveVerb,
                routeKey = routeKey,
                holdExempt = route.holdExempt,
            )
        }

        // Every other route is describable by construction: the label lives on the
        // Route itself (see `Route.describe`), so a route the gate cannot describe
        // cannot be registered. No parallel table to drift out of sync — that is
        // exactly how /calendar/events shipped and 500'd with `undescribed_route`.
        val actionLabel = route.describe
        return ApprovalGate.ApprovalView(
            requester = callerLabelFor(remote),
            account = account,
            actionLabel = actionLabel,
            appLabel = "",
            notifTitle = "",
            notifSender = "",
            notifText = "",
            messageCount = 0,
            replyText = null,
            // State the scope being approved, never a bare verb. A filtered read
            // must show its filters: "count=3, package=com.whatsapp".
            filters = query.filterKeys { it == "count" || it == "package" }
                .filterValues { it.isNotBlank() },
            // Target rows come from the PLUGIN, never a table here: only the plugin
            // knows its request shapes and can fetch live data to name the target.
            // Rows are keyed on method+path inside the plugin, so two routes sharing
            // a path template (GET vs POST, POST vs DELETE) can never be confused.
            destructive = rows.toGateRows(),
            // Biometric is a SECURE-only rule, computed by the policy and passed in.
            // The gate states it; the Activity renders it and never recomputes it.
            // In R3 this always evaluates true, because a view only exists on the
            // SECURE path — it is correctness plumbing so a future mode reusing the
            // modal cannot silently inherit a fingerprint.
            requiresBiometric = requiresBiometric,
            // The exception offer is built ONCE by the caller and passed in, like the rows.
            // Omitting it here was the bug this line exists to prevent: every branch of
            // this function returns an ApprovalView, and a branch that forgets the offer
            // silently renders a modal with no third button — no error, no log, just a
            // feature that is absent on exactly the routes that use this path (calendar,
            // gmail). Every `return ApprovalView(...)` in this function must pass it.
            exceptionOffer = exceptionOffer,
            needsPassword = needsPassword,
            verb = effectiveVerb,
            routeKey = routeKey,
            holdExempt = route.holdExempt,
        )
    }

    /**
     * Translate plugin rows into the gate's row type, **keeping the style**.
     *
     * Two near-identical row types exist because `core` must not depend on the gate: the
     * plugin contract (`ApprovalRow`) and what the modal renders (`ApprovalGate.Row`). The
     * translation is explicit so a new style cannot be silently dropped — the exact bug this
     * fixes: `pluginRows` used to flatten to `label to value`, so every modal row rendered
     * WARN and a plugin could never ask for a different presentation.
     */
    private fun List<ApprovalRow>.toGateRows(): List<ApprovalGate.Row> = map { r ->
        ApprovalGate.Row(
            r.label,
            r.value,
            when (r.style) {
                ApprovalRow.Style.NORMAL -> ApprovalGate.Row.Style.NORMAL
                ApprovalRow.Style.MONO -> ApprovalGate.Row.Style.MONO
                ApprovalRow.Style.LONG -> ApprovalGate.Row.Style.LONG
                ApprovalRow.Style.WARN -> ApprovalGate.Row.Style.WARN
                ApprovalRow.Style.CODE -> ApprovalGate.Row.Style.CODE
                ApprovalRow.Style.JSON -> ApprovalGate.Row.Style.JSON
            }
        )
    }

    /**
     * Build the facts a summariser sees, from rows the caller ALREADY built.
     *
     * Facts are the deterministic projection the modal renders: the route's own label, the
     * audit summary, and the labelled rows. Raw JSON is deliberately NOT handed over — it is
     * unbounded and caller-authored, and the rows are the human-readable truth of it. A
     * summariser that saw different facts than the human could describe something other than
     * what is being approved.
     *
     * **This no longer RUNS the model.** It used to be `aiSummary`, which called the model
     * inline and threw `ai_unavailable` on failure. That moved into [ApprovalGate], because
     * the modal now appears BEFORE the summary and only the gate knows when the request
     * actually becomes the one on screen (Mido, 2026-10-19). Keeping the call here would
     * have meant fetching for a queued request nobody can see yet. This is now a pure
     * projection — cheap, no I/O, no latency.
     *
     * Rows are passed in rather than gathered here so they are built ONCE: the modal needs
     * them and so does the AI, and a second gather is a second live Google round-trip per
     * approval on a route that pre-fetches its target (gmail trash, calendar delete).
     */
    private fun factsFor(
        route: Route,
        verb: Verb,
        summary: String,
        rows: List<ApprovalRow>,
        rawBody: String = "",
        url: String = ""
    ): com.mido.phonebridge.ai.RequestFacts =
        com.mido.phonebridge.ai.RequestFacts(
            plugin = PluginRegistry.resolve(route.method, route.path)?.plugin?.id ?: "?",
            verb = verb.name,
            routeLabel = route.describe,
            summary = summary,
            // The summariser reads FACTS, it does not render them — pairs are its shape. The
            // render hint stops here deliberately: a model that could see "this row is CODE"
            // would be handed a presentation detail it has no use for.
            rows = rows.map { it.label to it.value }.filter { it.second.isNotBlank() },
            rawBody = rawBody,
            url = url
        )

    /**
     * The facts a summariser sees, built for any mode. Row gathering is GUARDED: a
     * route whose rows cannot be built (a pre-fetch that fails) still gets a summary
     * from its label and summary rather than turning a display concern into a request
     * failure. That guard matters most on the NOTIFY path, where the rows were never
     * previously built at all.
     */
    private fun aiFactsFor(
        route: Route,
        verb: Verb,
        summary: String,
        actualPath: String,
        body: JSONObject,
        query: Map<String, String>,
        remote: String,
        rawBody: String = "",
        url: String = ""
    ): com.mido.phonebridge.ai.RequestFacts {
        val rows = try {
            pluginRows(route, actualPath, body, query, remote)
        } catch (e: Exception) {
            emptyList()
        }
        return com.mido.phonebridge.ai.RequestFacts(
            plugin = PluginRegistry.resolve(route.method, route.path)?.plugin?.id ?: "?",
            verb = verb.name,
            routeLabel = route.describe,
            summary = summary,
            // Pairs are the summariser's shape: it reads facts, it does not render them.
            rows = rows.map { it.label to it.value }.filter { it.second.isNotBlank() },
            rawBody = rawBody,
            url = url
        )
    }

    /**
     * Ask the resolved plugin for its modal target rows. Wraps the call so a plugin
     * failing to describe (bad required input, missing id, unreachable live data)
     * surfaces as a coded [PluginError] → status BEFORE any modal, never a partial
     * or placeholder view.
     */
    private fun pluginRows(
        route: Route,
        actualPath: String,
        body: JSONObject,
        query: Map<String, String>,
        remote: String
    ): List<ApprovalRow> {
        val plugin = PluginRegistry.resolve(route.method, route.path)?.plugin
            ?: throw PluginError("internal_error", "no plugin for ${route.method} ${route.path}")
        val pathParams = pathParamsFor(route, actualPath)
        // `path` is the ROUTE TEMPLATE (`/calendar/events/{id}`), because plugins
        // key their `when (method to path)` on the template — that is the route's
        // identity. The concrete path is only needed to extract path params, which
        // is done above. Passing the concrete path here made every plugin's `when`
        // miss and fall through to `emptyList()`, so mutating modals rendered with
        // no target rows at all ("no details").
        val req = Req(
            method = route.method,
            path = route.path,
            query = query,
            pathParams = pathParams,
            body = body,
            remote = remote
        )
        // **The style survives.** This used to be `.map { it.label to it.value }`, which threw
        // away the render hint — so every row in every modal became WARN (mono, amber) and the
        // SSH command could never be CODE. The plugin is the only place that knows its row is
        // a command; discarding the hint here made that knowledge unreachable.
        return plugin.describeRows(req)
    }

    /**
     * Extract the {x} segments a concrete path filled, using the route template as
     * the key. Same matching rule as the registry router: equal segment counts,
     * `{name}` captures, everything else must be equal.
     */
    private fun pathParamsFor(route: Route, actualPath: String): Map<String, String> {
        val tplSegs = route.path.trim('/').split('/')
        val actSegs = actualPath.trim('/').split('/')
        if (tplSegs.size != actSegs.size) {
            throw PluginError("internal_error", "path '${route.path}' does not match '${actualPath}'")
        }
        val out = HashMap<String, String>()
        for (i in tplSegs.indices) {
            val t = tplSegs[i]
            if (t.startsWith("{") && t.endsWith("}")) {
                out[t.substring(1, t.length - 1)] = actSegs[i]
            }
        }
        return out
    }

    /**
     * The label for a caller address, from the REGISTRY.
     *
     * Three possible answers and no fourth: a human-given name, `this device` for loopback, or an
     * explicit `unverified caller <ip>`. There is deliberately no "just use the address" fallback
     * — that was the old behaviour and it made a named host indistinguishable from an unnamed one.
     *
     * An unreadable registry answers `unknown caller <ip>`, which states exactly what is true
     * rather than inventing a name. Dispatch refuses such a request on the same condition, so
     * this label only ever describes a record that is about to say "refused".
     */
    private fun callerLabelFor(remote: String): String = try {
        com.mido.phonebridge.callers.CallerStore.from(ctx).labelFor(remote)
    } catch (e: Exception) {
        "unknown caller $remote"
    }

    // The `requesterLabel` helper that used to live here was a DUPLICATE of the one in
    // core/Labels.kt, with the build box's tailnet IP inlined a second time. Both are gone:
    // the label now comes from CallerStore, which knows a caller's NAME rather than guessing
    // one from its address.

    // Target-row building lives in each Plugin (see Plugin.describeRows): only the
    // plugin knows its request shapes and can fetch live data to name the target.
    // The former hard-coded table here keyed on path (then method+path) and was the
    // exact place a GET/DELETE got validated as a POST. It is gone by design.

    /** Human-readable one-liner for the approval modal. */
    private fun describe(pluginId: String, routePath: String, body: JSONObject): String = when {
        // S4: destructive verbs get verbatim target fields, never reformatted.
        routePath == "/gmail/messages/send" -> {
            val to = jsonArrayToStrings(body.optJSONArray("to")).joinToString(", ").ifBlank { "?" }
            val subj = body.optString("subject").ifBlank { "(no subject)" }
            val bodyPreview = body.optString("body").take(280)
            "SEND EMAIL  to=[$to]  subject=[$subj]  body=[$bodyPreview]"
        }
        routePath == "/gmail/messages/{id}/trash" ->
            "TRASH EMAIL  id=[${body.optString("_target").ifBlank { "?" }}]"
        routePath == "/gmail/messages/{id}/modify" ->
            "MODIFY EMAIL  add=[${jsonArrayToStrings(body.optJSONArray("addLabels"))}] remove=[${jsonArrayToStrings(body.optJSONArray("removeLabels"))}]"
        pluginId == "gmail" -> "Gmail: ${routePath.removePrefix("/gmail")}"
        pluginId == "notifications" ->
            "Notifications: ${routePath.removePrefix("/notifications").ifBlank { "list" }}"
        // A brokered third-party call. The default branch would produce
        // `todoist: /secrets/todoist/call` — a path, which tells the summariser
        // NOTHING about what is about to happen. That was a real complaint (Mido,
        // 2026-10-19: *"I expected the todolist adding one to say Adding 8 todos to
        // PhoneBridge"*): the payload is the whole reason the AI exists, so the
        // deterministic summary has to describe the payload, not the route.
        routePath.startsWith("/secrets/") -> describeSecretCall(pluginId, body)
        else -> if (pluginId.isBlank()) routePath else "$pluginId: $routePath"
    }

    /**
     * A brokered call, described by what it does rather than by its URL.
     *
     * `POST https://api.todoist.com/api/v1/sync` with a body is, to a reader, "some sync call".
     * This turns it into `todoist · POST /api/v1/sync`, and the payload's SHAPE is contributed
     * separately as its own fact row by the secrets plugin — deliberately ADDITIVE (Mido,
     * 2026-10-19: *"the itemadd x 8 thing should be an addition to the context not a
     * replacement"*). Squeezing it into this string instead displaced what the summary was for.
     *
     * Deterministic, so it is true even when the AI is unavailable and the audit record is
     * readable on its own.
     */
    private fun describeSecretCall(pluginId: String, body: JSONObject): String {
        val method = body.optString("method", "GET").uppercase()
        val url = body.optString("url", "")
        val path = runCatching { java.net.URL(url).path }.getOrNull().orEmpty().ifBlank { url.ifBlank { "?" } }

        // Name the RESOURCE, not the path. `GET /api/v1/tasks` gives a summariser nothing to
        // say, so it restates the URL — reported on device as *"says read todoist api both
        // times, absolutely useless"*. The last meaningful path segment is the object being
        // acted on, which is what a human would call it: "tasks", "events", "messages".
        //
        // Deliberately derived from the path rather than a per-API dictionary: this route is
        // generic (any secret, any host), so a table would be a second thing to maintain and
        // would silently miss every API nobody added. The segment is usually right, and when
        // it is not it is still more informative than the raw URL.
        val resource = path.trim('/').split('/')
            .lastOrNull { it.isNotBlank() && !it.all { c -> c.isDigit() } && it.length <= 24 }
            ?.replace('-', ' ')
            ?.replace('_', ' ')

        val query = body.optJSONObject("query")
        val filters = if (query == null) "" else buildList {
            for (k in query.keys()) {
                val v = query.optString(k, "")
                if (v.isNotBlank()) add("$k=$v")
            }
        }.joinToString(", ")

        val head = if (resource.isNullOrBlank()) "$pluginId · $method $path"
        else "$pluginId · $method $resource"
        return if (filters.isBlank()) head else "$head · query: $filters"
    }

    private fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    private fun detailFor(routePath: String, actualPath: String, body: JSONObject): JSONObject =
        JSONObject()
            .put("plugin_route", routePath)
            .put("actual_path", actualPath)
            .put("body", body)

    /**
     * The body as the caller sent it. Never throws on bad content — only on a socket
     * failure, which the caller records as a transport fault rather than a client one.
     *
     * **Forces UTF-8 before NanoHTTPD decodes, and that is a correctness fix, not a
     * preference.** `HTTPSession.parseBody` builds the string with
     * `new String(bytes, contentType.getEncoding())`, and `ContentType.getEncoding()`
     * returns the literal **`"US-ASCII"`** when the header carries no `charset=` (verified
     * in the 2.3.1 bytecode: `getEncoding` does `if (encoding == null) return "US-ASCII"`).
     * US-ASCII cannot represent any byte >= 0x80, so every such byte becomes U+FFFD — and
     * one UTF-8 character is up to 4 bytes, which is why an em-dash (3 bytes) arrived as
     * exactly THREE replacement characters. Any caller sending accented text, Arabic,
     * emoji or a curly quote got silent corruption, and `application/json` has no charset
     * parameter precisely because JSON is defined as UTF-8.
     *
     * `getHeaders()` hands back the session's OWN map (bytecode: `getfield headers;
     * areturn`), so writing here changes what `parseBody` will read. That is the only
     * seam NanoHTTPD offers short of forking the library.
     */
    private fun readRawBody(session: IHTTPSession): String {
        forceUtf8(session)
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    /**
     * Give the request an explicit `charset=UTF-8` when it has none.
     *
     * Deliberately does NOT override a charset the caller actually stated: if someone sends
     * `charset=ISO-8859-1` on purpose, honouring it is correct, and silently rewriting it
     * would be the same class of lie as the US-ASCII default.
     */
    private fun forceUtf8(session: IHTTPSession) {
        val headers = session.headers ?: return
        val current = headers["content-type"] ?: return
        if (current.contains("charset=", ignoreCase = true)) return
        headers["content-type"] = "$current; charset=utf-8"
    }

    /** Parse, failing hard and coded. The raw text is already on the draft by now. */
    private fun parseBody(raw: String): JSONObject = try {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (e: Exception) {
        throw PluginError("bad_request", "invalid JSON body: ${e.message}")
    }

    /**
     * The one place an integer status becomes NanoHTTPD's enum.
     *
     * The previous form was `values().firstOrNull { ... } ?: Response.Status.OK` — an
     * unmapped status silently became **200 OK**. That is the worst possible direction
     * for this fallback: a failure reported as success. Unknown now answers 500, loudly,
     * because "we could not represent this status" must never read as "it worked".
     */
    private fun statusOf(code: Int): Response.Status =
        Response.Status.values().firstOrNull { it.requestStatus == code } ?: run {
            Log.e(TAG, "no NanoHTTPD status for $code -> 500 (defect)")
            Response.Status.INTERNAL_ERROR
        }

    private fun err(code: String, msg: String): JSONObject =
        JSONObject().put("ok", false).put("error", code).put("detail", msg)

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", obj.toString())

    /**
     * Write whatever the handler produced: JSON, or raw bytes.
     *
     * The stream branch uses the file's OWN content type and, when the length is known, a
     * fixed-length response so the caller gets `Content-Length` and can detect a truncated
     * transfer. Unknown length falls back to chunked — never to a fixed length of 0, which would
     * silently present an empty file as a complete one.
     */
    private fun writeResponse(d: Dispatched): Response {
        val payload = d.payload
        if (payload is RespPayload.Stream) {
            return if (payload.lengthBytes >= 0L) {
                newFixedLengthResponse(d.status, payload.contentType, payload.input, payload.lengthBytes)
            } else {
                newChunkedResponse(d.status, payload.contentType, payload.input)
            }
        }
        return json(d.status, d.body)
    }

    private fun httpStatusFor(code: String): Response.Status =
        statusOf(com.mido.phonebridge.core.ErrorCodes.statusFor(code))
}

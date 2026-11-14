package com.mido.phonebridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.audit.AuditHeader
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.core.ApkInstaller
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.core.UpdatePrefs
import com.mido.phonebridge.core.Updater
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.policy.blurb
import com.mido.phonebridge.policy.title
import com.mido.phonebridge.ui.HomeCopy
import com.mido.phonebridge.ui.ServerStatus
import com.mido.phonebridge.ui.FingerprintSetting
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.AuditCopy
import com.mido.phonebridge.ui.kit.ErrorBlock
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.dp
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Wordmark
import com.mido.phonebridge.ui.kit.KnobSelector
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.RockerSwitch
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.kit.StatusRow
import com.mido.phonebridge.ui.kit.StmpDialog
import com.mido.phonebridge.ui.kit.Tape
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Palette
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * Home: the Bridge module, the gate knob, the recent-requests tape and the Updates module.
 *
 * Everything is built once in [buildContent]; [render] updates in place, so the 5 s tick never
 * destroys a control under a finger. The three hosts that are refilled ([bridgeDetailHost],
 * [gateDetailHost]/[gateExtrasHost], [degradedHost], [tapeHost]) refill only when their own key
 * changed, for the same reason.
 */
class HomeFragment : BridgeFragment(), BridgeControl {

    companion object {
        const val REQ_POST_NOTIF = 1001

        /**
         * How often the screen re-renders while resumed. It reads a CACHED probe result, so the
         * cost is a field read; the real probe runs in the service on its own schedule.
         */
        const val UI_TICK_MS = 5_000L

        /** How long the rocker shows a pending look after a tap. */
        const val PENDING_WINDOW_MS = 3_000L

        /** Extra delay so the repaint that ends the pending look lands after the window closed. */
        const val PENDING_CLOSE_MARGIN_MS = 100L

        /**
         * True while a start/stop tap is still settling. Ends when the window closes, when the bridge
         * reached the asked state, or when a start failed (startBridge clears lastError, so one set
         * afterwards is new).
         */
        internal fun isBridgePending(want: Boolean?, up: Boolean, since: Long, now: Long, lastError: String?): Boolean {
            if (want == null || since <= 0L || up == want) return false
            if (now - since >= PENDING_WINDOW_MS) return false
            return !(want && !lastError.isNullOrBlank())
        }

        /** Rows on the Recent requests tape. */
        const val TAPE_ROWS = 3

        /** The knob's order is explicit, never `GlobalMode.values()`. */
        val GATE_ORDER = listOf(GlobalMode.WHITELIST, GlobalMode.PARANOID, GlobalMode.DONT_DISTURB)

        private const val PERMISSION_ASKS = "permission_asks"
        private const val RELEASES_URL = "https://github.com/%s/%s/releases"
    }

    /** The bridge switch. Its position is the stored state, never the tap. */
    private lateinit var serverRocker: RockerSwitch
    private lateinit var bridgeLed: Led
    private lateinit var bridgeTitle: TextView

    /** The bridge headline as painted; for tests. */
    internal val bridgeTitleText: String get() = bridgeTitle.text.toString()
    private lateinit var bridgeDetailHost: LinearLayout

    /** When the last start/stop tap was made, so the rocker can show a pending look while binding. */
    @androidx.annotation.VisibleForTesting
    internal var pendingSince: Long = 0L

    /** The direction the user asked for at tap time (true = start); null when nothing is pending. */
    @androidx.annotation.VisibleForTesting
    internal var pendingWant: Boolean? = null

    private lateinit var degradedHost: LinearLayout
    private lateinit var crashHost: LinearLayout
    private lateinit var gateKnob: KnobSelector
    private lateinit var gateDetailHost: LinearLayout
    private lateinit var gateExtrasHost: LinearLayout
    private lateinit var fingerprintRule: View
    private lateinit var fingerprintRow: LinearLayout
    private lateinit var fingerprintRocker: RockerSwitch
    private lateinit var fingerprintWhy: TextView
    private lateinit var tapeHost: LinearLayout

    /**
     * The update section's container. It is rebuilt ONLY by [refreshUpdateSection], posted, so the
     * control that was tapped is not destroyed while its own click is still being dispatched.
     */
    private lateinit var updatesHost: LinearLayout

    /** Work to run when this screen comes back to the front (the install-permission round trip). */
    private var pendingAfterResume: (() -> Unit)? = null

    private val uiTickHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * What the screen looked like at the last repaint. The tick repaints ONLY when this changed:
     * an unconditional rebuild every 5 s destroyed the View under any press that outlasted the
     * interval and cancelled the click (reported 2026-11-02).
     */
    private var lastRenderSignature: String? = null

    private var lastDegraded: String? = null
    private var lastBridgeKey: String? = null
    private var lastGateKey: String? = null
    private var lastTapeKey: String? = null

    override val tab = Theme.Tab.HOME

    override fun buildContent(container: LinearLayout) {
        val p = Theme.palette(ctx)
        container.addView(buildHeader(p))

        degradedHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(degradedHost, matchWrapParams())

        crashHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(crashHost, matchWrapParams())

        container.addView(buildBridgePanel(p))

        // Every module opens with its own in-panel header (COMPONENTS 12); a secondary key is a
        // small PANEL key, start-aligned, whichever card it sits in.
        val startPanel = Panel.create(ctx)
        startPanel.addView(SectionHeader.create(ctx, "Get started", inPanel = true))
        startPanel.addView(
            KeyButton.create(ctx, "What is STMP?", KeyButton.Kind.PANEL, KeyButton.Size.SMALL) {
                go(Intent(ctx, OnboardingActivity::class.java))
            },
            wrapParams(),
        )
        container.addView(startPanel)

        container.addView(buildGatePanel(p))

        tapeHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(tapeHost, matchWrapParams())

        updatesHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(updatesHost, matchWrapParams())
        // Filled at build time: a host that only `refreshUpdateSection` fills shows a bare header
        // until something is tapped (reported 2026-11-04).
        updatesHost.addView(updateBlock())
    }

    // --- build helpers -------------------------------------------------------------------------

    private fun matchWrapParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun wrapParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun weightParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    /** Header row: the "STMP" wordmark and the version. A single wordmark child, so WP11 swaps one view. */
    private fun buildHeader(p: Palette): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = Wordmark.create(ctx, iconDp = 20, withText = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }
        row.addView(mark, weightParams())
        row.addView(Etched.label(ctx, "v" + BuildConfig.VERSION_NAME).apply { isAllCaps = false }, wrapParams())
        row.layoutParams = matchWrapParams().apply { bottomMargin = ctx.dp(Dimens.SPACE_M) }
        return row
    }

    private fun buildBridgePanel(p: Palette): LinearLayout {
        val panel = Panel.create(ctx)
        val rowA = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        bridgeLed = Led(ctx)
        // Top-aligned so the LED and the rocker track the first line when the title wraps (05-large-text L-01).
        rowA.addView(bridgeLed, LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply {
            marginEnd = ctx.dp(10)
            topMargin = ctx.dp(24)
        })
        val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        text.addView(Etched.label(ctx, "Bridge"))
        bridgeTitle = Type.text(ctx, "", TypeRole.TITLE, p.ink)
        text.addView(bridgeTitle, matchWrapParams())
        rowA.addView(text, weightParams())

        serverRocker = Theme.rocker(
            ctx,
            on = BridgeService.serverUp,
            label = "Bridge",
            pending = pendingSince > 0L,
        ) { want ->
            // Record the tap and POST the rest: this handler sits inside the panel the render
            // repaints. The rocker is NOT flipped here; `render()` moves it once
            // `BridgeService.serverUp` actually changes, so a failed stop cannot show a lie.
            pendingSince = android.os.SystemClock.elapsedRealtime()
            pendingWant = want
            serverRocker.pending = true
            uiTickHandler.post {
                if (want) startBridge() else stopBridge()
                render()
            }
            uiTickHandler.postDelayed({ render() }, 600)
            // The tick only repaints on a changed signature, so close the pending look explicitly.
            uiTickHandler.postDelayed({ render() }, PENDING_WINDOW_MS + PENDING_CLOSE_MARGIN_MS)
        }
        rowA.addView(serverRocker, wrapParams().apply { marginStart = ctx.dp(Dimens.SPACE_S) })
        panel.addView(rowA, matchWrapParams())

        bridgeDetailHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(bridgeDetailHost, matchWrapParams().apply { topMargin = ctx.dp(10) })
        return panel
    }

    private fun buildGatePanel(p: Palette): LinearLayout {
        val panel = Panel.create(ctx)
        panel.addView(Etched.label(ctx, "Gate mode"))
        gateKnob = KnobSelector(ctx, groupLabel = "Gate mode", labels = GATE_ORDER.map { it.title }).apply {
            onSelect = { i -> requestGate(GATE_ORDER[i]) }
        }
        panel.addView(gateKnob, matchWrapParams().apply { topMargin = ctx.dp(Dimens.SPACE_S) })
        gateDetailHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(gateDetailHost, matchWrapParams().apply { topMargin = ctx.dp(Dimens.SPACE_S) })
        gateExtrasHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(gateExtrasHost, matchWrapParams())

        fingerprintRule = hairline(p)
        panel.addView(fingerprintRule, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1),
        ).apply { topMargin = ctx.dp(Dimens.SPACE_M) })
        fingerprintRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(56)
            setPadding(0, ctx.dp(Dimens.SPACE_M), 0, 0)
        }
        fingerprintRow.addView(
            Type.text(ctx, "Fingerprint for approvals", TypeRole.ROW_LABEL, p.ink), weightParams(),
        )
        fingerprintRocker = Theme.rocker(
            ctx,
            on = try { PolicyStore.from(requireContext()).get().fingerprintForModals } catch (e: Exception) { false },
            label = "Fingerprint for approvals",
        ) { want ->
            // A fingerprint is required in BOTH directions (turning it off is the more dangerous one).
            requestFingerprintChange(want)
        }
        fingerprintRow.addView(fingerprintRocker, wrapParams().apply { marginStart = ctx.dp(Dimens.SPACE_S) })
        panel.addView(fingerprintRow, matchWrapParams())
        fingerprintWhy = Type.text(ctx, "", TypeRole.DETAIL, p.cautionText).apply { visibility = View.GONE }
        panel.addView(fingerprintWhy, matchWrapParams().apply { topMargin = ctx.dp(Dimens.SPACE_XS) })
        return panel
    }

    private fun hairline(p: Palette): View = View(ctx).apply { setBackgroundColor(p.hairline) }

    // --- lifecycle -----------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        render()
        // The update section is not part of `render()` (rebuilding it there could tear a control
        // down under a press), so coming back asks for it explicitly.
        if (::updatesHost.isInitialized) refreshUpdateSection()

        // The install-permission dialog promises the install resumes on return. Keep it, posted
        // so the pending action sees a settled frame.
        pendingAfterResume?.let { pending ->
            pendingAfterResume = null
            uiTickHandler.post(pending)
        }
        // Re-render while visible, so a dead server cannot stay green under the human's eyes.
        uiTickHandler.removeCallbacksAndMessages(null)
        uiTickHandler.postDelayed(uiTick, UI_TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        uiTickHandler.removeCallbacksAndMessages(null)
    }

    /** A cheap fingerprint of everything the screen renders: primitives and short strings only. */
    private fun renderSignature(): String = buildString(128) {
        append(BridgeService.serverUp).append('|')
        append(BridgeService.isVerifiedAlive()).append('|')
        append(BridgeService.lastError).append('|')
        append(BridgeService.bindAddress).append('|')
        append(BridgeService.listenerRef != null).append('|')
        append(hasNotifPermission()).append('|')
        append(runCatching { PolicyStore.from(requireContext()).get().fingerprintForModals }
            .getOrDefault(false)).append('|')
        append(runCatching { PolicyStore.from(requireContext()).get().let { it.global.name + it.rules.size } }
            .getOrDefault("unreadable")).append('|')
        append(ApprovalGate.canUseFullScreenIntent(ctx)).append('|')
        append(Settings.canDrawOverlays(ctx)).append('|')
        append(com.mido.phonebridge.ui.SystemAccess.batteryExempt(ctx)).append('|')
        append(SecretVault.isUnlocked()).append('|')
        append(com.mido.phonebridge.ai.AiGateway.enabled(ctx)).append('|')
        append(com.mido.phonebridge.ai.ModelProvisioner.running).append('|')
        append(com.mido.phonebridge.ai.ModelProvisioner.progress?.percent ?: -1).append('|')
        append(com.mido.phonebridge.ai.ModelRegistry.readiness(ctx).ready).append('|')
        append(AuthManager.isLoggedIn(ctx)).append('|')
        append(AuthManager.accountEmail ?: "").append('|')
        append(AuditHealth.reason ?: "").append('|')
        append(Crash.last(ctx)?.length ?: -1).append('|')
        append(Stall.last(ctx)?.length ?: -1).append('|')
        append(tapeSnapshot().first).append('|')
        // The update check is part of what this screen displays: without these a check that
        // finished while Home was visible left the card on "checking…" (reported 2026-11-10).
        append(Updater.checking).append('|')
        append(Updater.lastOutcome?.javaClass?.simpleName ?: "").append('|')
    }

    private val uiTick = object : Runnable {
        override fun run() {
            val signature = renderSignature()
            if (signature != lastRenderSignature) {
                lastRenderSignature = signature
                render()
            }
            uiTickHandler.postDelayed(this, UI_TICK_MS)
        }
    }

    /** Repaint everything, including the update section the shell asks about. */
    override fun repaint() {
        render()
        if (::updatesHost.isInitialized) refreshUpdateSection()
    }

    // --- rendering -----------------------------------------------------------------------------

    private fun render() {
        // Every render path re-baselines, so the tick cannot undo an explicitly requested repaint.
        lastRenderSignature = renderSignature()
        // The update section is NOT rebuilt here, on purpose (see [updatesHost]).
        renderDegraded()
        renderCrash()
        renderBridge()
        renderGate()
        renderTape()
    }

    private fun renderDegraded() {
        val reason = AuditHealth.reason
        if (reason == lastDegraded && degradedHost.childCount == (if (reason == null) 0 else 1)) return
        lastDegraded = reason
        degradedHost.removeAllViews()
        if (reason == null) return
        val panel = ErrorBlock.create(
            ctx, "Request log is degraded", "Requests are being refused until this is fixed.",
            details = reason,
        )
        panel.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        degradedHost.addView(panel)
    }

    /**
     * The app's own crash or freeze, on the screen that is always reachable.
     *
     * `GET /debug` reports both, but that route needs the bridge running, and these failures are
     * exactly the ones that stop it serving. Tapping copies the whole trace, so it travels by
     * paste instead of another round trip.
     */
    private fun renderCrash() {
        crashHost.removeAllViews()
        val model = CrashPanelModel.of(Crash.last(ctx), Stall.last(ctx)) ?: return
        val panel = Panel.create(ctx, Panel.Tone.CAUTION)
        panel.addView(
            StatusRow.create(
                ctx,
                label = model.label,
                value = "Copy trace",
                tone = StatusRow.Tone.CAUTION,
                detail = model.headline,
                led = Led.State.CAUTION,
                onClick = {
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(model.clipName, model.detail),
                    )
                    toast("copied — paste it to whoever is fixing this")
                },
            ),
        )
        if (model.isStall) {
            // A freeze record never clears by itself within a build; the reader dismisses it.
            panel.addView(
                StatusRow.create(
                    ctx,
                    label = "Dismiss",
                    value = "",
                    detail = "Hide this freeze report once the trace is copied.",
                    onClick = {
                        Stall.clear(ctx)
                        render()
                    },
                ),
            )
        }
        crashHost.addView(panel, matchWrapParams().apply { bottomMargin = ctx.dp(Dimens.MODULE_GAP) })
    }

    private fun renderBridge() {
        val p = Theme.palette(ctx)
        val listenerOk = BridgeService.listenerRef != null
        val notifOk = hasNotifPermission()
        // The precedence lives in `ServerStatus` (pure, unit-tested): a failed start must be
        // checked BEFORE the stopped state.
        val state = ServerStatus.state(
            serverUp = BridgeService.serverUp,
            alive = BridgeService.isVerifiedAlive(),
            listenerOk = listenerOk,
            notifOk = notifOk,
            lastError = BridgeService.lastError,
        )

        // Pending until the real state reaches the direction the user asked for; the timeout covers
        // a start or stop that never lands. `startForegroundService`/`stopService` are asynchronous,
        // so right after the tap `up` still shows the old state.
        val up = BridgeService.serverUp
        val want = pendingWant
        val pending = isBridgePending(
            want = want,
            up = up,
            since = pendingSince,
            now = android.os.SystemClock.elapsedRealtime(),
            lastError = BridgeService.lastError,
        )
        val starting = want == true
        if (pending) {
            serverRocker.pending = true
        } else {
            pendingSince = 0L
            pendingWant = null
            serverRocker.pending = false
            if (serverRocker.checked != up) serverRocker.setCheckedAnimated(up)
        }

        bridgeLed.state = if (pending) Led.State.PENDING else when (state) {
            ServerStatus.State.STOPPED -> Led.State.OFF
            ServerStatus.State.RUNNING_HEALTHY -> Led.State.OK
            ServerStatus.State.RUNNING_ATTENTION,
            ServerStatus.State.UNRESPONSIVE,
            ServerStatus.State.FAILED -> Led.State.CAUTION
        }
        bridgeTitle.text = if (pending) ServerStatus.pendingTitle(starting = starting) else ServerStatus.title(state)
        bridgeTitle.setTextColor(
            when (state) {
                ServerStatus.State.STOPPED, ServerStatus.State.RUNNING_HEALTHY -> p.ink
                else -> p.cautionText
            },
        )
        bridgeTitle.accessibilityLiveRegion =
            if (pendingSince > 0L) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE

        val attention = ServerStatus.attention(listenerOk, notifOk)
        val key = "$state|${BridgeService.bindAddress}|${BridgeService.PORT}|${BridgeService.lastError}|$attention|$pending"
        if (key == lastBridgeKey) return
        lastBridgeKey = key
        if (pending) {
            bridgeDetailHost.removeAllViews()
            bridgeDetailHost.addView(
                Type.text(ctx, ServerStatus.pendingDetail(starting = starting), TypeRole.DETAIL, p.etched),
                matchWrapParams(),
            )
            return
        }
        fillBridgeDetail(p, state, attention)
    }

    private fun fillBridgeDetail(p: Palette, state: ServerStatus.State, attention: List<ServerStatus.Attention>) {
        val host = bridgeDetailHost
        host.removeAllViews()
        val port = BridgeService.PORT
        val bind = BridgeService.bindAddress
        when (state) {
            ServerStatus.State.STOPPED -> host.addView(
                Type.text(ctx, ServerStatus.detail(state, "", bind, port), TypeRole.DETAIL, p.etched),
                matchWrapParams(),
            )
            ServerStatus.State.RUNNING_HEALTHY, ServerStatus.State.RUNNING_ATTENTION -> {
                val address = Readout.create(
                    ctx, ":$port", Readout.Kind.DISPLAY,
                    prompt = if (bind.isBlank()) "address unknown" else "address $bind",
                ).root
                host.addView(address, matchWrapParams().apply { bottomMargin = 0 })
                for (a in attention) {
                    host.addView(
                        StatusRow.create(
                            ctx,
                            label = ServerStatus.attentionTitle(a),
                            value = ServerStatus.attentionAction(a),
                            tone = StatusRow.Tone.CAUTION,
                            detail = ServerStatus.attentionDetail(a),
                            led = Led.State.CAUTION,
                            onClick = { onAttentionTap(a) },
                        ),
                    )
                }
            }
            ServerStatus.State.UNRESPONSIVE -> host.addView(
                Type.text(ctx, ServerStatus.detail(state, "", bind, port), TypeRole.DETAIL, p.ink),
                matchWrapParams(),
            )
            ServerStatus.State.FAILED -> {
                host.addView(
                    Readout.create(ctx, BridgeService.lastError, Readout.Kind.BODY).root,
                    matchWrapParams().apply { bottomMargin = 0 },
                )
                host.addView(
                    KeyButton.create(ctx, "Try again", KeyButton.Kind.PANEL, KeyButton.Size.SMALL) {
                        startBridge()
                        render()
                    },
                    wrapParams().apply { topMargin = ctx.dp(10) },
                )
            }
        }
    }

    private fun onAttentionTap(a: ServerStatus.Attention) {
        when (a) {
            ServerStatus.Attention.LISTENER_OFF -> openNotifAccessSettings()
            ServerStatus.Attention.NOTIFICATIONS_BLOCKED -> {
                val prefs = ctx.getSharedPreferences(PERMISSION_ASKS, Context.MODE_PRIVATE)
                val permission = ScreenHost.POST_NOTIFICATIONS
                val permanent = prefs.getBoolean("asked:$permission", false) &&
                    prefs.getBoolean("denied:$permission", false) &&
                    !shouldShowRequestPermissionRationale(permission)
                if (permanent) openAppNotificationSettings() else requestNotifPermission()
            }
        }
    }

    private fun openAppNotificationSettings() {
        try {
            go(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        } catch (e: Exception) {
            Debug.log("cannot open app notification settings: ${e.message}")
            try {
                go(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            } catch (e2: Exception) {
                Debug.log("cannot open app details: ${e2.message}")
            }
        }
    }

    // --- gate ----------------------------------------------------------------------------------

    private fun renderGate() {
        val p = Theme.palette(ctx)
        val policy = try {
            PolicyStore.from(requireContext()).get()
        } catch (e: PolicyException) {
            renderGateUnreadable(p, e)
            return
        }
        gateKnob.visibility = View.VISIBLE
        gateKnob.select(GATE_ORDER.indexOf(policy.global), animate = true)

        val key = "${policy.global}|${policy.rules.size}"
        if (key != lastGateKey) {
            lastGateKey = key
            fillGateDetail(p, policy)
        }
        renderFingerprint(policy, p)
    }

    private fun fillGateDetail(p: Palette, policy: Policy) {
        gateDetailHost.removeAllViews()
        gateExtrasHost.removeAllViews()
        gateExtrasHost.visibility = View.VISIBLE
        when (policy.global) {
            GlobalMode.WHITELIST -> gateDetailHost.addView(
                Type.text(ctx, rulesSummary(policy), TypeRole.DETAIL, p.etched), matchWrapParams(),
            )
            GlobalMode.PARANOID -> gateDetailHost.addView(
                Type.text(ctx, GlobalMode.PARANOID.blurb, TypeRole.DETAIL, p.etched), matchWrapParams(),
            )
            GlobalMode.DONT_DISTURB -> {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(Led(ctx).apply { state = Led.State.CAUTION }, LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply {
                    marginEnd = ctx.dp(10)
                    topMargin = ctx.dp(3)
                })
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                col.addView(Type.text(ctx, "Silence approves", TypeRole.ROW_LABEL, p.cautionText), matchWrapParams())
                col.addView(Type.text(ctx, GlobalMode.DONT_DISTURB.blurb, TypeRole.DETAIL, p.ink), matchWrapParams())
                row.addView(col, weightParams())
                gateDetailHost.addView(row, matchWrapParams())
            }
        }
        // Exceptions only apply in Whitelist: in Paranoid and Don't disturb the key would edit
        // something that is ignored, so it is not offered there (R3).
        if (policy.global == GlobalMode.WHITELIST) {
            gateExtrasHost.addView(
                KeyButton.create(ctx, "Edit exceptions", KeyButton.Kind.PANEL, KeyButton.Size.SMALL) {
                    go(Intent(ctx, ExceptionsActivity::class.java))
                },
                wrapParams().apply { topMargin = ctx.dp(Dimens.SPACE_M) },
            )
        }
        val caption = when (policy.global) {
            GlobalMode.WHITELIST -> null
            GlobalMode.PARANOID -> "Exceptions are ignored in Paranoid mode."
            GlobalMode.DONT_DISTURB -> "Exceptions are ignored in Don't disturb mode."
        }
        if (caption != null) {
            gateExtrasHost.addView(
                Type.text(ctx, caption, TypeRole.DETAIL, p.etched),
                matchWrapParams().apply { topMargin = ctx.dp(Dimens.SPACE_XS) },
            )
        }
    }

    /**
     * An unreadable policy is not a quiet fallback: say it, and hide the knob, the Edit key and the
     * fingerprint row, because the app does not know what it is enforcing.
     */
    private fun renderGateUnreadable(p: Palette, e: PolicyException) {
        lastGateKey = "unreadable|${e.message}"
        gateKnob.visibility = View.GONE
        gateDetailHost.removeAllViews()
        gateExtrasHost.removeAllViews()
        gateExtrasHost.visibility = View.GONE
        gateDetailHost.addView(
            ErrorBlock.create(
                ctx, "Policy unreadable", "STMP refuses requests until this is fixed.",
                details = e.message ?: "The policy file could not be read.",
                recovery = KeyButton.Spec("Try again", "Read the policy again") { lastGateKey = null; render() },
                framed = false,
            ),
            matchWrapParams(),
        )
        fingerprintRule.visibility = View.GONE
        fingerprintRow.visibility = View.GONE
        fingerprintWhy.visibility = View.GONE
    }

    private fun renderFingerprint(policy: Policy, p: Palette) {
        fingerprintRule.visibility = View.VISIBLE
        fingerprintRow.visibility = View.VISIBLE
        fingerprintWhy.visibility = View.GONE
        // Can this phone even ask for a fingerprint? Same check the modal and the vault use.
        when (val status = Fingerprint.status(hostActivity)) {
            is Fingerprint.Status.PermanentlyUnusable -> {
                // Forced OFF, not merely greyed: with the policy ON and no usable biometric,
                // APPROVE never enables, so every Paranoid request would become unapprovable.
                FingerprintSetting.forceOff(requireContext(), status.reason, source = "home")
                fingerprintRocker.setCheckedImmediately(false)
                fingerprintRocker.isEnabled = false
                fingerprintWhy.text = status.reason
                fingerprintWhy.setTextColor(p.cautionText)
                fingerprintWhy.visibility = View.VISIBLE
            }
            is Fingerprint.Status.TemporarilyUnavailable -> {
                // A sensor fault or lockout is not a decision: render writes NOTHING here. The
                // switch shows what is stored and can still be turned off with the phone PIN.
                fingerprintRocker.isEnabled = true
                val stored = policy.fingerprintForModals
                if (fingerprintRocker.checked != stored) fingerprintRocker.setCheckedAnimated(stored)
                fingerprintWhy.text = status.reason
                fingerprintWhy.setTextColor(p.cautionText)
                fingerprintWhy.visibility = View.VISIBLE
            }
            Fingerprint.Status.Usable -> {
                fingerprintRocker.isEnabled = true
                val stored = policy.fingerprintForModals
                if (fingerprintRocker.checked != stored) fingerprintRocker.setCheckedAnimated(stored)
            }
        }
    }

    /** The fingerprint flow lives in [FingerprintSetting] (D66); Home only repaints afterwards. */
    private fun requestFingerprintChange(want: Boolean) {
        FingerprintSetting.request(
            this, want, source = "home",
            onError = { showProblemDialog("Couldn't change the setting", it) },
        ) { render() }
    }

    /**
     * Apply a knob change. Don't disturb gets one confirmation, because it is the only value where
     * doing nothing approves a request.
     */
    private fun requestGate(next: GlobalMode) {
        val store = PolicyStore.from(requireContext())
        val current = try {
            store.get().global
        } catch (e: PolicyException) {
            showProblemDialog("Policy can't be read", e.message ?: "The policy file could not be read.")
            return
        }
        if (next == current) return
        if (next == GlobalMode.DONT_DISTURB) {
            confirm(
                title = "Switch to Don't disturb?",
                message = "Every request shows a small overlay for 5 seconds. Doing nothing " +
                    "approves it. Tap ✕ to deny.\n\nYour exceptions are ignored while this is selected.",
                positive = "Use Don't disturb",
                negative = "Cancel",
            ) { applyGate(store, current, next) }
        } else {
            applyGate(store, current, next)
        }
    }

    /** One line of truth about the exception table, for the Whitelist caption. */
    private fun rulesSummary(policy: Policy): String = when (val n = policy.rules.size) {
        0 -> "Exceptions decide. None set yet, so everything asks for your stamp."
        1 -> "Exceptions decide. 1 exception set."
        else -> "Exceptions decide. $n exceptions set."
    }

    /** The app's only writer of the global mode. */
    private fun applyGate(store: PolicyStore, before: GlobalMode, after: GlobalMode) {
        try {
            // Carry EVERYTHING across: `Policy(after)` alone would silently delete every exception.
            val existing = store.get()
            store.set(existing.withGlobal(after))
            BridgeService.refreshForegroundNotice(requireContext(), fromApp = true)
        } catch (e: Exception) {
            showProblemDialog("Couldn't save the gate mode", e.message ?: e.javaClass.simpleName)
            return
        }
        try {
            AuditLog.from(requireContext()).append(PolicyAudit.record(before, after, "settings"))
        } catch (e: Exception) {
            AuditHealth.markDegraded("policy change not recorded: ${e.message}")
        }
        render()
    }

    // --- recent requests -----------------------------------------------------------------------

    /** The key that changes when a new request lands, and the rows to show. */
    private fun tapeSnapshot(): Pair<String, List<Pair<AuditHeader, Verb>>> {
        val headers = try {
            AuditLog.from(requireContext()).headers()
        } catch (e: Exception) {
            Debug.log("tape: ${e.message}")
            emptyList()
        }
        val rows = HomeCopy.recent(headers, TAPE_ROWS)
        val key = (rows.firstOrNull()?.first?.id ?: "") + ":" + headers.count { !it.noise }
        return key to rows
    }

    private fun renderTape() {
        val (key, rows) = tapeSnapshot()
        if (key == lastTapeKey) return
        lastTapeKey = key
        tapeHost.removeAllViews()
        if (rows.isEmpty()) return
        val panel = Panel.create(ctx)
        panel.addView(SectionHeader.create(ctx, "Recent requests", inPanel = true))
        for ((header, verb) in rows) {
            val text = AuditCopy.summary(header.outcome, header.summary, header.label)
            val row = Tape.row(ctx, verb, text, header.ts) {
                go(Intent(ctx, LogDetailActivity::class.java).putExtra(LogDetailActivity.EXTRA_ID, header.id))
            }
            // D50: a gate change has no verb chip. Its column stays empty so the text keeps its left edge.
            if (!AuditCopy.outcome(header.outcome).allowsChip && row is ViewGroup) {
                val width = row.getChildAt(0).layoutParams.width
                row.removeViewAt(0)
                row.addView(View(ctx), 0, LinearLayout.LayoutParams(width, 1))
                row.contentDescription = row.contentDescription?.toString()?.substringAfter(", ")
            }
            panel.addView(row)
        }
        panel.addView(
            KeyButton.create(ctx, "All requests", KeyButton.Kind.PANEL, KeyButton.Size.SMALL) {
                go(Intent(ctx, LogActivity::class.java))
            },
            wrapParams().apply { topMargin = ctx.dp(10) },
        )
        tapeHost.addView(panel, matchWrapParams())
    }

    // --- actions -------------------------------------------------------------------------------

    private fun hasNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return hasPermission(ScreenHost.POST_NOTIFICATIONS)
    }

    private fun openNotifAccessSettings() {
        try {
            go(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            Debug.log("opened notification-access settings")
        } catch (e: Exception) {
            Debug.log("cannot open notif settings: ${e.message}")
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Debug.log("requesting POST_NOTIFICATIONS")
            askPermission(ScreenHost.POST_NOTIFICATIONS, REQ_POST_NOTIF)
        } else {
            Debug.log("POST_NOTIFICATIONS not needed on this API level")
        }
    }

    /**
     * `Fragment.onRequestPermissionsResult` is deprecated alongside `requestPermissions`
     * ([BridgeFragment] explains why the pair stays). The result is also recorded in the shared
     * `permission_asks` file, in the shape `PluginDetailActivity` uses, so both screens agree on
     * "permanently denied".
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIF) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Debug.log("POST_NOTIFICATIONS result: ${if (granted) "granted" else "DENIED"}")
            ctx.getSharedPreferences(PERMISSION_ASKS, Context.MODE_PRIVATE).edit()
                .putBoolean("asked:${ScreenHost.POST_NOTIFICATIONS}", true)
                .putBoolean("denied:${ScreenHost.POST_NOTIFICATIONS}", !granted)
                .apply()
            render()
        }
    }

    override fun startBridge() {
        Debug.log("server start requested")
        // Clear the previous failure so a retry cannot display a stale reason.
        BridgeService.lastError = ""
        if (!hasNotifPermission()) {
            Debug.log("no POST_NOTIFICATIONS yet — requesting first")
            requestNotifPermission()
        }
        val i = Intent(ctx, BridgeService::class.java).apply {
            setClass(ctx, BridgeService::class.java)
        }
        try {
            // minSdk is 26, so startForegroundService is always available.
            ctx.startForegroundService(i)
            Debug.log("startForegroundService dispatched")
        } catch (e: Exception) {
            Debug.log("FAILED to dispatch service: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun stopBridge() {
        Debug.log("stop requested")
        ctx.stopService(Intent(ctx, BridgeService::class.java))
    }

    // --- updates -------------------------------------------------------------------------------

    /**
     * The Updates module, built from `Updater`'s last OUTCOME, never from a stored flag: a cached
     * "up to date" is a claim about a question answered once.
     */
    private fun updateBlock(): View {
        val p = Theme.palette(ctx)
        val panel = Panel.create(ctx)
        panel.addView(SectionHeader.create(ctx, "Updates", inPanel = true))
        val outcome = Updater.lastOutcome
        val checking = Updater.checking
        val channel = UpdatePrefs.channel(requireContext())
        val installing = outcome is Updater.Outcome.Available && Updater.installing && !checking
        val line = HomeCopy.updateLine(checking, outcome, BuildConfig.VERSION_NAME)

        // 1. Status row
        val status = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status.addView(Led(ctx).apply { state = line.led }, LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply {
            marginEnd = ctx.dp(10)
        })
        val statusColor = when (line.tone) {
            HomeCopy.UpdateTone.OK -> p.okText
            HomeCopy.UpdateTone.CAUTION -> p.cautionText
            HomeCopy.UpdateTone.NEUTRAL -> p.ink
        }
        status.addView(Type.text(ctx, line.text, TypeRole.ROW_LABEL, statusColor), weightParams())
        panel.addView(status, matchWrapParams())

        // 2. Installed: always shown, so the number does not depend on a network call.
        panel.addView(
            Type.text(ctx, "Installed v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", TypeRole.DETAIL, p.etched),
            matchWrapParams().apply { topMargin = ctx.dp(Dimens.SPACE_XS) },
        )

        // 3. Channel row
        val beta = channel == UpdatePrefs.Channel.BETA
        panel.addView(hairline(p), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1),
        ).apply { topMargin = ctx.dp(10) })
        val channelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(56)
            setPadding(0, ctx.dp(10), 0, 0)
        }
        val channelText = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        channelText.addView(Type.text(ctx, "Beta channel", TypeRole.ROW_LABEL, p.ink), matchWrapParams())
        channelText.addView(
            Type.text(
                ctx,
                if (beta) "Every push is offered." else "Only promoted releases are offered.",
                TypeRole.DETAIL, p.etched,
            ),
            matchWrapParams(),
        )
        channelRow.addView(channelText, weightParams())
        channelRow.addView(
            Theme.rocker(ctx, on = beta, label = "Beta channel") { want ->
                UpdatePrefs.setChannel(
                    requireContext(),
                    if (want) UpdatePrefs.Channel.BETA else UpdatePrefs.Channel.STABLE,
                )
                // The channel changes what counts as an update, so the old answer is void.
                Updater.lastOutcome = null
                // NOT `render()`: it would tear down this rocker while its click is dispatched.
                checkForUpdates()
            },
            wrapParams().apply { marginStart = ctx.dp(Dimens.SPACE_S) },
        )
        panel.addView(channelRow, matchWrapParams())

        // 4. Release notes
        if (outcome is Updater.Outcome.Available) {
            val notes = Updater.shortNotes(outcome.notes)
            if (notes.isNotEmpty()) {
                panel.addView(
                    Type.text(ctx, notes, TypeRole.DETAIL, p.etched),
                    matchWrapParams().apply { topMargin = ctx.dp(Dimens.SPACE_S) },
                )
            }
        }

        // 5. Primary action
        val primary = when {
            checking -> KeyButton.create(ctx, "Check now", KeyButton.Kind.PANEL, KeyButton.Size.SMALL) { }
                .apply { isEnabled = false }
            installing -> KeyButton.create(ctx, "Cancel", KeyButton.Kind.OUTLINE, KeyButton.Size.SMALL) {
                // Offered while the download is running; once Android's own dialog is up the
                // system owns the decision and this disappears from the next repaint.
                Updater.installCancelled = true
                Updater.installing = false
                Updater.installingVersion = null
                Debug.log("update: cancelled by the human")
                refreshUpdateSection()
            }
            outcome is Updater.Outcome.Available -> KeyButton.create(
                ctx, "Update to v${outcome.version}", KeyButton.Kind.INK, KeyButton.Size.REGULAR,
            ) { installUpdate(outcome) }
            else -> KeyButton.create(ctx, "Check now", KeyButton.Kind.PANEL, KeyButton.Size.SMALL) { checkForUpdates() }
        }
        panel.addView(
            primary,
            (if (outcome is Updater.Outcome.Available && !installing && !checking) matchWrapParams() else wrapParams())
                .apply { topMargin = ctx.dp(10) },
        )

        // 6. Last checked
        val lastCheck = UpdatePrefs.lastCheckAt(requireContext())
        if (lastCheck > 0L) {
            panel.addView(
                Type.text(ctx, "Last checked ${agoText(lastCheck)}", TypeRole.DETAIL, p.etched),
                matchWrapParams().apply { topMargin = ctx.dp(Dimens.SPACE_S) },
            )
        }

        // 7. The unreachable case says what will actually make it work.
        val failed = outcome is Updater.Outcome.Unavailable
        if (failed) {
            panel.addView(
                Type.text(
                    ctx,
                    "This is a network or GitHub-side problem, not a setting. The button below " +
                        "opens the releases page in your browser, which is the same APK.",
                    TypeRole.DETAIL, p.ink,
                ),
                matchWrapParams().apply { topMargin = ctx.dp(Dimens.SPACE_S) },
            )
        }

        // 8. The way out when this mechanism fails: handed to the browser, not fetched.
        if (!installing) {
            val releases = if (failed) {
                KeyButton.create(ctx, "Get it from GitHub", KeyButton.Kind.INK, KeyButton.Size.REGULAR) { openReleasesPage() }
            } else {
                KeyButton.create(ctx, "Open releases page", KeyButton.Kind.PANEL, KeyButton.Size.SMALL) { openReleasesPage() }
            }
            panel.addView(
                releases,
                (if (failed) matchWrapParams() else wrapParams()).apply { topMargin = ctx.dp(10) },
            )
        }
        return panel
    }

    private fun openReleasesPage() {
        val url = RELEASES_URL.format(Updater.REPO_OWNER, Updater.REPO_NAME)
        try {
            go(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No browser installed: the one case where a dead key would look like the updater failing again.
            Debug.log("update: cannot open releases page: ${e.message}")
            showProblemDialog("Can't open the releases page", "No browser is installed on this phone. The address is:\n$url")
        }
    }

    /**
     * Rebuild ONLY the update section, posted off the current call stack: the controls live inside
     * [updatesHost], so rebuilding from their own click handler would remove the view whose click
     * is still being dispatched (why the channel switch once refused to flip).
     */
    private fun refreshUpdateSection() {
        val host = updatesHost
        host.post {
            if (!::updatesHost.isInitialized) return@post
            host.removeAllViews()
            host.addView(updateBlock())
        }
    }

    private fun checkForUpdates() {
        Updater.lastOutcome = null
        Updater.checking = true
        refreshUpdateSection()

        // The context is taken HERE, on the main thread: `requireContext()` inside the worker
        // throws when the fragment is detached around an install, and a throw before `check()`
        // would leave `checking` stuck at true (reported 2026-11-10).
        val appContext = requireContext().applicationContext

        Thread({
            try {
                val outcome = Updater.check(appContext)
                Debug.log("update check: ${outcome.javaClass.simpleName}")
                ui { refreshUpdateSection() }
            } finally {
                // If anything above throws before `check()` sets its own `finally`, the flag
                // must still come down: a spinner that never stops hides the Check key too.
                Updater.checking = false
                ui { refreshUpdateSection() }
            }
        }, "update-check").apply { isDaemon = true }.start()
    }

    /**
     * Download, verify, then hand to the installer. Verification is not optional: a digest
     * mismatch deletes the file and refuses.
     */
    private fun installUpdate(update: Updater.Outcome.Available) {
        // Answered BEFORE the download, not after it (2026-11-04).
        if (!ApkInstaller.canInstall(requireContext())) {
            showInstallPermissionDialog { installUpdate(update) }
            return
        }

        // Set BEFORE the worker starts, so the loader is on screen at the next frame.
        Updater.installCancelled = false
        Updater.installingVersion = update.version
        Updater.installing = true
        refreshUpdateSection()

        Thread({
            try {
                val got = Updater.download(requireContext().applicationContext, update) { _ -> }
                // A cancel during the download means the bytes are not wanted.
                if (Updater.installCancelled) {
                    got.file.delete()
                    Debug.log("update: cancelled mid-download; file removed")
                    return@Thread
                }
                val expected = update.sha256
                if (expected != null) {
                    val normalized = expected.trim().lowercase().substringAfterLast(" ")
                    if (!got.sha256.equals(normalized, ignoreCase = true)) {
                        got.file.delete()
                        Debug.log("update: digest mismatch, refusing")
                        ui {
                            Updater.installing = false
                            Updater.installingVersion = null
                            showProblemDialog(
                                "Download was corrupted",
                                "The file did not match its published checksum, so it was deleted and nothing was installed. Try again, or use the releases page.",
                            )
                            refreshUpdateSection()
                        }
                        return@Thread
                    }
                    Debug.log("update: digest verified")
                } else {
                    Debug.log("update: no published digest; installing unverified")
                }
                ui {
                    ApkInstaller.install(requireContext(), got.file) { result ->
                        ui {
                            when (result) {
                                is ApkInstaller.Result.ConfirmationShown ->
                                    toast("Confirm the install in the dialog")
                                // Revoked between the pre-check and the commit, or a managed
                                // device forbids it: same dialog, the fix is a settings toggle.
                                is ApkInstaller.Result.NeedsPermission ->
                                    showInstallPermissionDialog { installUpdate(update) }
                                is ApkInstaller.Result.Refused -> {
                                    // No install is happening, so the loader must stop.
                                    Updater.installing = false
                                    Updater.installingVersion = null
                                    showProblemDialog("Update could not be installed", result.reason)
                                }
                            }
                            render()
                        }
                    }
                }
            } catch (e: Exception) {
                Debug.log("update: download failed: ${e.message}")
                ui {
                    Updater.installing = false
                    Updater.installingVersion = null
                    showProblemDialog("Download failed", e.message ?: e.javaClass.simpleName)
                    refreshUpdateSection()
                }
            }
        }, "update-download").apply { isDaemon = true }.start()
    }

    /**
     * The "allow installs for this app" dialog. A dialog, not a toast (2026-11-04): it explains
     * WHY, names the exact switch, and carries a button that opens that page. `retry` runs after
     * the human returns, so granting the permission continues the install they already tapped.
     */
    private fun showInstallPermissionDialog(retry: () -> Unit) {
        confirm(
            title = "Allow STMP to install updates",
            message = "Android needs your permission once before it will let an app install its " +
                "own update.\n\n" +
                "Tap Open settings, then turn on " +
                "\"Allow from this source\". Come back here and the install carries on " +
                "by itself.\n\n" +
                "This does not let anything else install on your phone — the permission is " +
                "only for STMP.",
            positive = "Open settings",
            negative = "Not now",
        ) {
            // Remembered BEFORE settings opens: the human may grant it and come straight back.
            pendingAfterResume = retry
            if (!ApkInstaller.openInstallSettings(requireContext())) {
                pendingAfterResume = null
                showProblemDialog(
                    "Cannot open settings",
                    "This build of Android would not open the install-permission page. Go to " +
                        "Settings → Apps → STMP → Install unknown apps and allow it there.",
                )
            } else {
                Debug.log("update: opened install permission settings")
            }
        }
    }

    /** A plain failure dialog with one key. Long text belongs in a dialog, never a toast. */
    @androidx.annotation.VisibleForTesting
    internal fun showProblemDialog(title: String, message: String) {
        StmpDialog.builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /** A short, human "how long ago". Coarse on purpose. */
    private fun agoText(at: Long): String {
        val minutes = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0L)
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes min ago"
            minutes < 60L * 24L -> "${minutes / 60L} h ago"
            else -> "${minutes / (60L * 24L)} d ago"
        }
    }
}

package com.mido.phonebridge

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.ApprovalGate.ApproveGesture
import com.mido.phonebridge.callers.CallerState
import com.mido.phonebridge.callers.CallerStore
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.gate.ArmPhase
import com.mido.phonebridge.gate.GateArming
import com.mido.phonebridge.gate.GateCallbacks
import com.mido.phonebridge.gate.GateClock
import com.mido.phonebridge.gate.GateCopy
import com.mido.phonebridge.gate.GateInput
import com.mido.phonebridge.gate.GateReceipt
import com.mido.phonebridge.gate.GateScreen
import com.mido.phonebridge.gate.GateViews
import com.mido.phonebridge.ui.kit.PunchView
import com.mido.phonebridge.ui.kit.Receipt
import com.mido.phonebridge.ui.kit.StmpDialog
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import com.mido.phonebridge.ui.tokens.Motion

/**
 * Full-screen approval UI. Shows every detail of the pending request and blocks until the human
 * chooses approve or deny. The views are built by [GateScreen]; this Activity keeps the lifecycle,
 * the decisions, the biometric, the permission and back.
 */
class ApprovalActivity : FragmentActivity() {

    /**
     * The password field, when this request needs one; null otherwise.
     *
     * A FIELD rather than a local because [approveNow] is a method and has to read it. Cleared in
     * [onDestroy] so a finishing Activity does not keep a reference to a view holding a secret.
     */
    private var passwordField: android.widget.EditText? = null

    /** Whether this request needs a sudo password. Read once, from the gate's view. */
    private var needsPassword = false

    /**
     * Whether approving this request requires READ_CONTACTS, and it is not granted yet.
     *
     * Reported 2026-11-02, with a screenshot of the approval card: *"all i want is when clicking
     * approve here I give the fucking permission"*. Approving used to run the request, the plugin
     * then found no permission, and the call answered `503 contacts_unavailable` after the human
     * had done exactly what was asked. This Activity is the only surface that CAN ask: a route
     * handler has no Activity, and the permission dialog needs one.
     */
    private var needsContactsPermission = false

    /** Guard so a second tap cannot stack a second system dialog. */
    private var contactsPromptRunning = false

    /** How the approve that waits on the contacts prompt was performed, for the receipt. */
    private var contactsBy = "tap"

    private lateinit var views: GateViews
    private var gesture = ApproveGesture.HOLD
    private var requesterName = ""
    private var verb: Verb = Verb.EXECUTE
    private var requiresBiometric = false
    private var bioStatus = BioStatus.Usable
    private var lastBioCheckMs = 0L
    private var ticksArmed = false
    private var exceptionArmed = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * True from the moment a decision is taken until the receipt has finished.
     *
     * The 120 ms tick calls `finish()` as soon as `ApprovalGate.isCurrent(id)` is false — which is
     * the INSTANT a decision resolves the request, before the punch has drawn a single frame
     * (Mido, 2026-10-27: *"it dismisses too fast to see the animation"*). The tick must not decide
     * the modal is dead while the punch plays: the punch and the receipt ARE the exit.
     */
    private var stamping = false

    /** True once the receipt is on screen, so back can close it early (and never decide twice). */
    private var receiptShown = false

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_REMOTE = "remote"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_TIMEOUT_SECONDS = "timeoutSeconds"
        const val EXTRA_CREATED_AT = "createdAt"
        const val EXTRA_WAITING_BEHIND = "waitingBehind"

        /** READ_CONTACTS, asked from the card itself when APPROVE is tapped. */
        const val REQ_CONTACTS = 2001

        private const val TICK_MS = 120L
        private const val TAG = "ApprovalActivity"

        /**
         * Test seam: stands in for `BiometricManager.canAuthenticate` in [bioStatus] only.
         * Robolectric's shadow models only "available" or "unavailable", so NONE_ENROLLED and the
         * lockout code cannot be produced otherwise (TESTING 5). Null in production.
         */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var canAuthenticateForTest: Int? = null

        /** Test seam: the authenticators of the last PIN-fallback prompt this Activity built. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var lastCredentialPromptForTest: Fingerprint.PromptSpec? = null
    }

    private val requestId: Long get() = intent.getLongExtra(EXTRA_ID, -1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw over other apps / show over the lockscreen, turn the screen on, and keep it on
        // while the human decides. This is what makes the modal appear *immediately* regardless
        // of what is in the foreground.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // FLAG_SECURE whenever this modal can hold a password: without it the typed password
        // appears in the recents thumbnail and is capturable by a screen recorder. Set
        // unconditionally for a password request — the flag cannot be added later without
        // re-creating the window.
        val wantsPassword = ApprovalGate.currentView()?.needsPassword == true
        if (wantsPassword) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        val id = requestId
        val remote = intent.getStringExtra(EXTRA_REMOTE) ?: "?"
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: "?"
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: "?"
        val timeoutSeconds = intent.getLongExtra(EXTRA_TIMEOUT_SECONDS, ApprovalGate.TIMEOUT_SECONDS)

        // Two sources of truth: the view is the RESOLVED, human-readable request (built by the
        // caller from the live data); the detail is the raw JSON, shown collapsed.
        val view = ApprovalGate.currentView()
        // The gate decides whether this request needs a biometric, a password and a hold; the UI
        // only renders those decisions. Read once, here.
        requiresBiometric = view?.requiresBiometric == true
        needsPassword = view?.needsPassword == true
        // No view means no verb to judge by: the hold is the safe default.
        gesture = view?.approveGesture ?: ApproveGesture.HOLD
        verb = view?.verb ?: Verb.EXECUTE
        requesterName = view?.requester ?: remote
        // The exception offer is the gate's (`exceptionOfferFor`, which never offers one on a
        // password request). Dropped here too for a password request: the exception tap carries
        // no password, so it must never render beside a password field — the gate would refuse
        // it anyway (ApprovalGate.resolveWithException).
        val offer = if (needsPassword) null else view?.exceptionOffer

        // Derived from the ENDPOINT, because that is what the human is looking at: `GET /contacts`.
        // The plugin cannot help — it only runs AFTER approval — so the decision is made here.
        needsContactsPermission = endpoint.contains("/contacts") &&
            checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

        title = GateCopy.windowTitle(com.mido.phonebridge.ui.UntrustedText.escape(requesterName))
        views = GateScreen.build(
            this,
            GateInput(
                id = id, remote = remote, endpoint = endpoint, summary = summary,
                detail = ApprovalGate.currentDetailJson(), view = view, timeoutSeconds = timeoutSeconds,
                offer = offer, gesture = gesture, caller = callerKind(remote), needsPassword = needsPassword,
            ),
            GateCallbacks(
                onDeny = { finishWith(id, approved = false, by = "tap") },
                onApprove = { by -> onApprove(id, by) },
                onExceptionTap = { onExceptionTap(id) },
                onPasswordChanged = { tick.run() },
            ),
        )
        passwordField = views.passwordField
        installTouchGuard(id)
        bioStatus = if (requiresBiometric) bioStatus() else BioStatus.Usable
        lastBioCheckMs = SystemClock.uptimeMillis()
        renderBio()
        setContentView(views.guard)
        handler.post(tick)
    }

    /** D17: touches in a gesture another window fully covers never reach the gate. */
    private fun installTouchGuard(id: Long) {
        views.guard.onObscuredTouch = {
            // The banner says why, and the keys look as dead as they are (D17).
            views.setObscured(true)
            Haptics.perform(views.guard, HapticEvent.REJECT)
            Debug.log("approval[$id] touch ignored: window obscured")
        }
        views.guard.onClearTouch = { views.setObscured(false) }
    }

    /** What the caller registry knows about [remote]. A registry that cannot be read is "unverified". */
    private fun callerKind(remote: String): GateCopy.CallerKind {
        if (GateCopy.isLoopback(remote)) return GateCopy.CallerKind.THIS_PHONE
        val state = try {
            CallerStore.from(this).stateOf(remote)
        } catch (e: Exception) {
            Log.w(TAG, "caller registry unreadable: ${e.message}")
            null
        }
        return when (state) {
            is CallerState.Verified -> GateCopy.CallerKind.VERIFIED
            CallerState.Denied -> GateCopy.CallerKind.BLOCKED
            null -> GateCopy.CallerKind.UNVERIFIED
        }
    }

    /**
     * The modal's heartbeat, every 120 ms. The AI block and the arm phase are LIVE: read from the
     * gate every tick rather than trusted from build time, because the summary arrives after this
     * view is built (Mido, 2026-10-19). The gate's live state is the ONLY enable trigger — no
     * timer — and the misclick guard is part of that state, not a second condition here.
     */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (isFinishing || stamping || !::views.isInitialized) return
            val now = GateClock.nowMs()
            views.ai.render(ApprovalGate.currentAiState(), Motion.reduced(this@ApprovalActivity))
            applyPhase(GateArming.phase(ApprovalGate.currentEnabledAtMs(), now, timeoutSeconds()), now)
            recheckBio()
            // Live every tick, so it appears the moment a follower queues. Never a "0 waiting" line.
            val behind = ApprovalGate.waitingBehindCurrent()
            views.queueLine.visibility = if (behind > 0) View.VISIBLE else View.GONE
            if (behind > 0) views.queueLine.text = GateCopy.queue(behind)
            if (ApprovalGate.isCurrent(requestId)) {
                handler.postDelayed(this, TICK_MS)
            } else {
                // The gate gave up (window expired) or the request was replaced. The server has
                // already answered, so a modal still asking would be a lie. Not while stamping:
                // a resolved request is exactly what starts the punch.
                finish()
            }
        }
    }

    private fun timeoutSeconds(): Long = intent.getLongExtra(EXTRA_TIMEOUT_SECONDS, ApprovalGate.TIMEOUT_SECONDS)

    /**
     * THREE phases, not two. `enabledAtMs` is an ARM TIME that can sit in the future (the misclick
     * guard), so "is it stamped" is not the same question as "may the human tap". DENY is live in
     * every phase: denying is fail-closed, and making someone wait to refuse is backwards.
     */
    private fun applyPhase(phase: ArmPhase, now: Long) {
        val ticks = views.ticks
        val secondsUntilArmed = when (phase) {
            ArmPhase.SummaryPending -> {
                val deadline = ApprovalGate.currentSummaryDeadlineMs()
                maxOf(1L, (deadline - now + 999L) / 1000L).toInt()
            }
            is ArmPhase.Guard -> phase.secondsLeft.toInt()
            is ArmPhase.Armed -> 0
        }
        if (phase is ArmPhase.Armed) {
            if (!ticksArmed) {
                // Set once, on the transition: the ticks are clock-driven from here on.
                val enabledAt = ApprovalGate.currentEnabledAtMs()
                ticks.deadlineUptimeMs = SystemClock.uptimeMillis() +
                    maxOf(0L, enabledAt + timeoutSeconds() * 1000L - now)
                ticks.armed = true
                ticksArmed = true
            }
        } else {
            val text = GateArming.text(phase)
            if (ticks.unarmedText != text) ticks.unarmedText = text
            if (ticks.armed) ticks.armed = false
        }
        // A password request is not approvable on an empty field: sudo would reject it seconds
        // later with a message about a terminal rather than about the typo.
        val passwordReady = !needsPassword || (passwordField?.text?.isNotEmpty() == true)
        val bioBlocked = gesture == ApproveGesture.TAP_THEN_BIOMETRIC && !bioStatus.canApprove
        views.setApproveArmed(phase is ArmPhase.Armed && !bioBlocked && passwordReady, secondsUntilArmed)
    }

    /** D20: a temporary sensor fault clears by itself, so the screen re-checks it and the banner follows. */
    private fun recheckBio() {
        if (bioStatus != BioStatus.NoFallback && bioStatus != BioStatus.UsePin) return
        val now = SystemClock.uptimeMillis()
        if (now - lastBioCheckMs < GateArming.BIO_RECHECK_MS) return
        lastBioCheckMs = now
        val fresh = bioStatus()
        if (fresh != bioStatus) {
            bioStatus = fresh
            renderBio()
        }
    }

    /** The banner names the CAUSE, because each one needs a different action. */
    private fun renderBio() {
        val show = gesture == ApproveGesture.TAP_THEN_BIOMETRIC && bioStatus != BioStatus.Usable
        views.setBioShown(show)
        if (!show) return
        val (title, body) = when (bioStatus) {
            BioStatus.Usable -> return
            BioStatus.NotEnrolled -> GateCopy.BIO_UNAVAILABLE_TITLE to GateCopy.BIO_NOT_ENROLLED
            BioStatus.UsePin -> GateCopy.BIO_PIN_TITLE to GateCopy.BIO_USE_PIN
            BioStatus.NoFallback -> GateCopy.BIO_LOCKED_TITLE to GateCopy.BIO_LOCKED
            BioStatus.VaultBroken -> GateCopy.BIO_UNAVAILABLE_TITLE to GateCopy.BIO_VAULT_BROKEN
        }
        views.bioTitle.text = title
        views.bioBody.text = body
    }

    /**
     * Approve. The gesture decides first (D13): when the gate requires the fingerprint, nothing
     * else runs until it succeeds — not even the contacts permission, whose grant would otherwise
     * approve on its own. Then [afterGesture] takes over.
     */
    private fun onApprove(id: Long, by: String) {
        if (stamping) return
        if (gesture == ApproveGesture.TAP_THEN_BIOMETRIC) {
            promptBiometric { viaPin -> afterGesture(id, if (viaPin) GateCopy.BY_DEVICE_CREDENTIAL else by) }
        } else {
            afterGesture(id, by)
        }
    }

    /**
     * The approve gesture is complete. In order: the contacts permission (the request stays
     * PENDING while the system dialog runs, and the grant comes back here), then an armed
     * exception (HOLD gates, D58), then the plain or password approve.
     */
    private fun afterGesture(id: Long, by: String) {
        if (stamping) return
        when {
            needsContactsPermission -> { contactsBy = by; requestContactsThenApprove(id) }
            exceptionArmed -> approveWithException(id, by)
            else -> approveNow(id, by)
        }
    }

    /**
     * The exception key. On a HOLD gate a tap only arms the exception and the hold still guards
     * the approval (D58). Otherwise the tap approves and saves the exception at once, behind the
     * same fingerprint as APPROVE — and nothing finishes until the decision is actually resolved,
     * because `promptBiometric` is ASYNCHRONOUS. (An earlier version finished right after starting
     * the prompt, which destroyed the Activity before the fingerprint ran: *"modal kept coming
     * back"*.) A dismissed prompt does nothing, so the request stays pending for a retry.
     */
    private fun onExceptionTap(id: Long) {
        if (stamping) return
        when (gesture) {
            ApproveGesture.HOLD -> {
                exceptionArmed = !exceptionArmed
                views.exception?.toggle()
                views.exception?.keyView?.let { Haptics.perform(it, HapticEvent.TOGGLE) }
            }
            ApproveGesture.TAP_THEN_BIOMETRIC -> promptBiometric { viaPin ->
                approveWithException(id, if (viaPin) GateCopy.BY_DEVICE_CREDENTIAL else "biometric")
            }
            ApproveGesture.TAP -> approveWithException(id, "tap")
        }
    }

    /** Approve and write the exception in one gesture; every such approval shows the punch (D59). */
    private fun approveWithException(id: Long, by: String) {
        val rule = views.exception?.offer?.rule ?: return approveNow(id, by)
        if (!ApprovalGate.resolveWithException(id, rule)) {
            Log.w(TAG, "request $id refused the exception, already resolved, or expired")
            views.holdKey?.reset()
            return
        }
        showStamp(approved = true, by = by)
    }

    private fun finishWith(id: Long, approved: Boolean, by: String) {
        val resolved = ApprovalGate.resolve(id, approved)
        if (!resolved) {
            Log.w(TAG, "request $id already resolved/expired")
            // Nothing was decided by this tap, so there is nothing to stamp: an APPROVED punch for
            // a request that timed out, was denied or was replaced would be a lie. Re-arm the hold
            // key and let the tick close the Activity once it sees the request is gone.
            views.holdKey?.reset()
            return
        }
        // The punch fires on the human's decision — approve AND deny — never on arrival.
        showStamp(approved, by)
    }

    /**
     * The punch, then the receipt (D16). The gate is already resolved, so the only thing this
     * delays is the modal's disappearance — never the decision itself. The thud plays from the
     * punch at impact; a sound failure is logged there, never substituted and never a toast.
     */
    private fun showStamp(approved: Boolean, by: String) {
        // Set BEFORE the punch is added, so the tick cannot finish the Activity in between — and
        // so it stops instead of re-arming the controls behind the punch.
        stamping = true
        handler.removeCallbacks(tick)
        val decidedAtMs = GateClock.nowMs()
        views.ticks.armed = false
        views.ticks.deadlineUptimeMs = 0L
        views.ticks.visibility = View.INVISIBLE
        views.disableKeys()
        val root = findViewById<ViewGroup>(android.R.id.content)
        val id = requestId
        val punch = PunchView(this, scrimAlpha = 0x66).apply {
            meta = GateCopy.punchMeta(id, decidedAtMs)
            holdAfterSettle = true
            joltTarget = views.outer
            onFinished = { showReceipt(root, approved, by, decidedAtMs) }
        }
        root.addView(punch, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        punch.stamp(if (approved) PunchView.Verdict.APPROVED else PunchView.Verdict.DENIED)
    }

    private fun showReceipt(root: ViewGroup, approved: Boolean, by: String, decidedAtMs: Long) {
        if (isFinishing) return
        val id = requestId
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: "?"
        val receipt = Receipt(
            this,
            GateReceipt.lines(id, requesterName, verb, GateReceipt.pathOf(endpoint), decidedAtMs, approved, by),
            entryNo = id.toString(),
            status = GateReceipt.status(approved),
        ).apply { onDone = { finish() } }
        root.addView(receipt)
        receiptShown = true
        receipt.feed()
    }

    /**
     * Ask for READ_CONTACTS, then approve the request that needed it. The human tapped APPROVE on
     * `GET /contacts`, so they meant "run it" and "you may read my contacts"; asking here means the
     * answer arrives while they are still looking at the card that asked. `contactsPromptRunning`
     * stops a double tap stacking two system dialogs, which on some OEM builds drops the first result.
     */
    private fun requestContactsThenApprove(id: Long) {
        if (contactsPromptRunning) {
            Log.i(TAG, "contacts prompt already running; ignoring second tap")
            return
        }
        contactsPromptRunning = true
        Log.i(TAG, "APPROVE: requesting READ_CONTACTS before resolving $id")
        requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CONTACTS) return
        // Only a result for a prompt this screen asked for, after the gesture, can approve.
        if (!contactsPromptRunning) {
            Log.w(TAG, "READ_CONTACTS result with no prompt running; ignored")
            return
        }
        contactsPromptRunning = false
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "READ_CONTACTS from card: ${if (granted) "granted" else "DENIED"}")
        if (granted) {
            // Now the approval means what it says: the handler will find the permission. The
            // gesture (and the fingerprint, when required) already ran before the prompt, so this
            // continues where it left off, an armed exception included.
            needsContactsPermission = false
            afterGesture(requestId, contactsBy)
            return
        }
        // Declined: leave the request PENDING and say so ON THE CARD. A 503 the human never sees
        // is how this looked like a dead button for four releases.
        needsContactsPermission = false
        showPermissionDeniedNotice()
    }

    /** Tell the human, on this card, that the request cannot run without the permission. */
    private fun showPermissionDeniedNotice() {
        val canAsk = shouldShowRequestPermissionRationale(android.Manifest.permission.READ_CONTACTS)
        StmpDialog.builder(this)
            .setTitle(GateCopy.CONTACTS_TITLE)
            .setMessage(if (canAsk) GateCopy.CONTACTS_CAN_ASK else GateCopy.CONTACTS_CANNOT_ASK)
            .setPositiveButton(if (canAsk) GateCopy.CONTACTS_OK else GateCopy.CONTACTS_OPEN_SETTINGS) { _, _ ->
                if (!canAsk) {
                    try {
                        startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "cannot open settings: ${e.message}")
                    }
                }
                // Re-arm so a retry is possible without leaving the card.
                needsContactsPermission = true
            }
            .setNegativeButton(GateCopy.CONTACTS_CANCEL, null)
            .show()
    }

    /**
     * Approve, taking the password path when this request needs one. Reads the chars straight out
     * of the `Editable` rather than through `text.toString()`: a String cannot be wiped.
     */
    private fun approveNow(id: Long, by: String) {
        val field = passwordField
        if (!needsPassword || field == null) {
            finishWith(id, approved = true, by = by)
            return
        }
        val chars = CharArray(field.length())
        field.text.getChars(0, field.length(), chars, 0)
        finishWithPassword(id, chars, by)
    }

    /**
     * Approve a request that needed a password, handing the chars to the gate. **The caller's
     * array is wiped immediately after the hand-off**: the gate keeps its own copy, and a
     * finishing Activity is exactly the kind of object whose memory sits around afterwards.
     */
    private fun finishWithPassword(id: Long, password: CharArray, by: String) {
        val resolved = try {
            ApprovalGate.resolveWithPassword(id, password)
        } finally {
            // Wipe first, before anything can throw or delay.
            password.fill('\u0000')
        }
        if (!resolved) {
            Log.w(TAG, "request $id does not need a password, or expired")
            // Nothing was approved, so there is no decision to stamp. Leave the modal up so the
            // human can retry rather than dismissing on a silent failure.
            views.holdKey?.reset()
            return
        }
        // **The punch fires here too.** This path once called `finish()` directly, so the sudo
        // modal dismissed with no stamp (Mido, 2026-10-27: *"no fucking stamp sound and
        // immediately dismissed"*).
        showStamp(approved = true, by = by)
    }

    /**
     * Bind APPROVE to a live biometric.
     *
     * When a vault exists the prompt carries the vault's `CryptoObject` (decision A: every scan is
     * crypto-bound). The approval is then unforgeable — no plugin, bug or stray callback can
     * approve without a live finger on a hardware key — and if the vault is locked, the SAME
     * authorised cipher unwraps it, so one modal both unlocks and approves. Fingerprint-only, by
     * platform rule: a crypto-bound prompt cannot accept `DEVICE_CREDENTIAL`.
     *
     * Before the vault exists there is nothing to bind to, so the prompt falls back to the plain
     * form (STRONG or device credential). There is no secret on the device yet.
     *
     * On success [onApproved] fires and the request resolves. On failure or dismiss we do NOTHING
     * to the request: it stays pending on its own countdown, APPROVE stays live, and tapping it
     * again re-prompts — the window is the only limit.
     */
    private fun promptBiometric(onApproved: (viaPin: Boolean) -> Unit) {
        // The sensor itself decides the route, never a stale banner: a temporary fault asks for the
        // phone PIN (no vault cipher: a crypto-bound prompt cannot take a credential); anything else
        // that is not Usable fails closed with no prompt and no approval.
        when (bioStatus()) {
            BioStatus.Usable -> Unit
            BioStatus.UsePin -> return promptDeviceCredential { onApproved(true) }
            else -> {
                Log.w(TAG, "approval not possible right now; not approving")
                return
            }
        }
        // Prepare the vault cipher FIRST: if the key was invalidated there is no prompt worth
        // showing. Failing closed here is the whole point.
        val cipher = try {
            if (SecretVault.exists(this)) SecretVault.prepareUnlockCipher(this) else null
        } catch (e: Exception) {
            Log.w(TAG, "vault cipher unavailable (${e.message}); not approving")
            return
        }
        // bioStatus() above already ruled out an unusable sensor; the crypto-bound prompt re-checks
        // STRONG itself, because a vault prompt can never take a credential.
        if (cipher != null) {
            val can = BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (can != BiometricManager.BIOMETRIC_SUCCESS) {
                Log.w(TAG, "biometric unavailable (code=$can); not approving")
                return
            }
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cipher != null) {
                        val authorised = result.cryptoObject?.cipher
                        if (authorised == null) {
                            // Impossible in principle: we passed a CryptoObject. Refuse rather
                            // than treat "authenticated" as sufficient.
                            Log.w(TAG, "no authorised cipher returned; not approving")
                            return
                        }
                        if (!SecretVault.isUnlocked()) {
                            try {
                                // One modal does both (decision A): the finger that approves also
                                // opens the vault, so a SECURE call on a locked vault is not a dead end.
                                SecretVault.unlock(this@ApprovalActivity, authorised)
                                Log.i(TAG, "vault unlocked by the approval prompt")
                            } catch (e: Exception) {
                                Log.w(TAG, "vault unlock failed: ${e.message}")
                                return
                            }
                        }
                    }
                    // Success wins the race: resolve() is idempotent — if the window DID close
                    // first, it is a no-op and logged, never a wrong approval.
                    onApproved(false)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Dismissed / locked out: leave the request pending; APPROVE stays live.
                    Log.i(TAG, "biometric error $errorCode: $errString")
                }
                override fun onAuthenticationFailed() {
                    // A single bad read: the prompt stays up for a retry; nothing to do.
                    Log.i(TAG, "biometric failed (retryable)")
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (cipher != null) "Unlock and approve" else "Approve request")
            .setSubtitle(endpointLabel())
        if (cipher != null) {
            info.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            // Before the vault exists there is nothing to bind to; the API-level split lives in one place.
            Fingerprint.applyFallback(info)
        }
        // A STRONG-only prompt REQUIRES a negative button (the platform throws without one). With
        // device credential allowed the system supplies its own, so adding one would be an error.
        if (cipher != null) info.setNegativeButtonText("Cancel")
        if (cipher != null) {
            prompt.authenticate(info.build(), BiometricPrompt.CryptoObject(cipher))
        } else {
            prompt.authenticate(info.build())
        }
    }

    /**
     * The sensor is temporarily away: approve with the phone PIN (or a fingerprint that comes
     * back mid-prompt). Not crypto-bound, so the vault stays as it was. Only a success approves;
     * cancel and every error leave the request pending on its own timeout.
     */
    private fun promptDeviceCredential(onApproved: () -> Unit) {
        val spec = Fingerprint.fallbackSpec()
        lastCredentialPromptForTest = spec
        val info = Fingerprint.applyFallback(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Approve with your phone PIN")
                .setSubtitle(endpointLabel()),
            spec,
        ).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), Fingerprint.CredentialCallback(onApproved))
            .authenticate(info)
    }

    private fun endpointLabel(): String =
        GateCopy.bioSubtitle(intent.getStringExtra(EXTRA_ENDPOINT))

    /**
     * Why approval is or is not possible right now. **A boolean was not enough, and the modal
     * lied because of it**: one line for every cause sent a human with an invalidated vault key
     * to Settings to add a fingerprint they already had. Naming the cause is what lets the screen
     * say the right thing.
     */
    private enum class BioStatus(val canApprove: Boolean) {
        /** The prompt will work if the human passes it. */
        Usable(true),

        /** Nothing is enrolled: the device cannot satisfy it until Settings is used. */
        NotEnrolled(false),

        /** The sensor is temporarily away (lockout, fault) but the phone has a PIN: approve with it. */
        UsePin(true),

        /** The sensor is temporarily away and there is no screen lock: nothing can approve. Fail closed. */
        NoFallback(false),

        /** The vault key is gone or invalidated. The vault has to be recreated; APPROVE cannot. */
        VaultBroken(false),
    }

    /**
     * Resolve the cause, from the same [Fingerprint.classify] the Home and Settings switch uses, so
     * the banner, the prompt and the switch can never disagree about what is possible.
     */
    private fun bioStatus(): BioStatus {
        val hasVault = SecretVault.exists(this)
        if (hasVault) {
            try {
                // Preparing the cipher is the real test: an invalidated key throws here.
                SecretVault.prepareUnlockCipher(this)
            } catch (e: Exception) {
                Log.w(TAG, "vault cipher unavailable: ${e.message}")
                return BioStatus.VaultBroken
            }
        }
        val code = canAuthenticateForTest
            ?: BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return when (val sensor = Fingerprint.classify(code)) {
            Fingerprint.Status.Usable -> BioStatus.Usable
            is Fingerprint.Status.PermanentlyUnusable -> BioStatus.NotEnrolled
            // Lockout, a hardware fault, an update pending: all clear on their own, so the PIN is
            // the fallback, and without a screen lock nothing can approve.
            is Fingerprint.Status.TemporarilyUnavailable ->
                if (Fingerprint.deviceCredentialReady(this)) BioStatus.UsePin else BioStatus.NoFallback
        }
    }

    @Deprecated("Deprecated in the platform; kept for back-compat behavior on older devices.")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // During the punch the decision is already made: back may close the receipt early, and
        // can never resolve a second time or start a second punch (D20).
        if (stamping) {
            if (receiptShown) finish()
            return
        }
        // Back == deny. Never leave a request dangling open by accident. No `super`: it would
        // finish the Activity at once and the DENIED punch and receipt would never show (D16).
        // The receipt's `onDone` finishes, exactly as for a DENY tap.
        finishWith(requestId, approved = false, by = "back")
    }

    /**
     * Drop the reference to the password field, and wipe whatever it still holds. Clearing the
     * `Editable` first means the chars are gone even if a view reference survives somewhere.
     */
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        passwordField?.text?.clear()
        passwordField = null
        super.onDestroy()
    }
}

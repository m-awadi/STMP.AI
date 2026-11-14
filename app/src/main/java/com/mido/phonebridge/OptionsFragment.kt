package com.mido.phonebridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.ai.AiGateway
import com.mido.phonebridge.ai.ExternalModels
import com.mido.phonebridge.ai.ModelCatalogue
import com.mido.phonebridge.ai.ModelProvisioner
import com.mido.phonebridge.ai.ModelReadiness
import com.mido.phonebridge.ai.ModelRegistry
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.callers.CallerEntry
import com.mido.phonebridge.callers.CallerState
import com.mido.phonebridge.callers.CallerStore
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.policy.AiBackend
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.ui.FingerprintSetting
import com.mido.phonebridge.ui.LocalModelState
import com.mido.phonebridge.ui.NotificationAccess
import com.mido.phonebridge.ui.RemoteState
import com.mido.phonebridge.ui.SettingsState
import com.mido.phonebridge.ui.SystemAccess
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.VaultRow
import com.mido.phonebridge.ui.kit.ActionRow
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.Field
import com.mido.phonebridge.ui.UntrustedText
import com.mido.phonebridge.ui.kit.Hints
import com.mido.phonebridge.ui.kit.ErrorBlock
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.KnobSelector
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.kit.StateBlock
import com.mido.phonebridge.ui.kit.StatusRow
import com.mido.phonebridge.ui.kit.StmpDialog
import com.mido.phonebridge.ui.kit.Toasts
import com.mido.phonebridge.ui.kit.ToggleRow
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** What the Remote server form holds between repaints, so typing is never lost (WP07 4.3.1). */
internal data class RemoteDraft(
    val url: String? = null,
    val token: String? = null,
    val listed: ModelCatalogue? = null,
    val picked: String? = null,
)

/**
 * The Settings tab: device-level PREFERENCES, not security decisions.
 *
 * **Deliberately separate from [ExceptionsActivity].** That screen authors the gate: which
 * `plugin x verb` pairs behave differently from "ask". Every write there decides whether a human
 * is asked, so it is strict, audited as `POLICY_CHANGED`, and a broken file refuses to start the
 * server. These toggles are the opposite: a preference, cheap to flip, and a broken value must
 * never be able to take the bridge down. They share `policy.json` but are parsed on different
 * terms, see `Policy.lenientFlag`.
 *
 * The class keeps its name (the tab is referenced by `MainActivity`); the page is called Settings
 * everywhere the user can see it.
 */
class OptionsFragment : BridgeFragment() {

    private lateinit var container: LinearLayout

    /** Work to run when this screen comes back to the front. */
    private var pendingAfterResume: (() -> Unit)? = null

    override val tab = Theme.Tab.SETTINGS

    /** S2: why the last preference change was not stored. Cleared by the next save. */
    private var saveError: String? = null

    /** S4: why the last "forget" failed. */
    private var callerError: String? = null

    @androidx.annotation.VisibleForTesting
    internal var draft = RemoteDraft()

    private var urlError: String? = null
    private var modelProblem: String? = null
    private var refreshing = false

    // Download progress bookkeeping: repaint only when something visible changed.
    private var shownRunning = false
    private var shownPercent = -1
    // True only while the page draws the "On this phone" body; elsewhere percent changes are invisible.
    private var isLocalBodyShown = false
    private val announced = HashSet<Int>()
    private val progressTick = object : Runnable {
        override fun run() {
            if (!isAdded || view == null) return
            val running = ModelProvisioner.running
            if (running != shownRunning || percentChangedOnScreen()) render()
            if (ModelProvisioner.running) container.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    private fun percentChangedOnScreen() =
        isLocalBodyShown && (ModelProvisioner.progress?.percent ?: 0) != shownPercent

    override fun buildContent(container: LinearLayout) {
        this.container = container
        val basePad = container.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            v.updatePadding(bottom = basePad + insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        render()
    }

    override fun repaint() = render()

    /** A coalescing flag so a burst of repaint requests becomes one rebuild. */
    private var renderQueued = false

    /**
     * Rebuild on the NEXT loop, never inside the tap that asked for it. A synchronous `render()`
     * at the end of a toggle or save tore the screen down while the click was still being
     * dispatched, so the press never completed ("hangs on preference click").
     */
    private fun renderPosted() {
        if (renderQueued) return
        renderQueued = true
        container.post {
            renderQueued = false
            if (isAdded) render()
        }
    }

    override fun onResume() {
        super.onResume()
        // Repaint on the way back, ALWAYS: a permission granted in Android settings must show up
        // without a manual refresh (2026-11-10: "permission was given but indicator was still off").
        render()
        val pending = pendingAfterResume ?: return
        pendingAfterResume = null
        container.post(pending)
    }

    override fun onPause() {
        container.removeCallbacks(progressTick)
        super.onPause()
    }

    override fun onDestroyView() {
        draft = RemoteDraft()
        super.onDestroyView()
    }

    private fun uiSafe(block: () -> Unit) {
        activity?.runOnUiThread { if (isAdded && view != null) block() }
    }

    // --- page ------------------------------------------------------------------------------------

    private fun render() {
        if (!isAdded) return
        container.removeAllViews()
        val p = Theme.palette(ctx)

        container.addView(
            Type.text(ctx, "Settings", TypeRole.TITLE, p.ink).apply {
                ViewCompat.setAccessibilityHeading(this, true)
                setPadding(0, 0, 0, ctx.dp(Dimens.SPACE_XS))
            }
        )

        saveError?.let {
            container.addView(errorBlock(
                "Could not save", "The change was not stored. The screen shows what is stored now.", it,
            ))
        }

        val store = PolicyStore.from(requireContext())
        val policy: Policy? = try {
            store.get()
        } catch (e: PolicyException) {
            container.addView(errorBlock(
                "Settings can't be read",
                "The policy file is unreadable, so the bridge will not start until it is fixed or removed.",
                e.message ?: "unreadable",
                retry = true,
            ))
            null
        }

        if (policy != null) {
            bridgeSection(store, policy)
            aiSection(store, policy)
            approvalsSection(policy)
        }
        permissionsSection()
        vaultSection()
        toolsSection(policy)
        callersSection(policy)
        debugSection()

        container.addView(
            Type.text(
                ctx,
                "These are preferences, not exceptions. To change when you are asked, use " +
                    "Exceptions under Tools.",
                TypeRole.CAPTION, p.etched,
            ).apply { setPadding(0, ctx.dp(Dimens.SPACE_L), 0, 0) }
        )
    }

    private fun header(text: String) = container.addView(SectionHeader.create(ctx, text))

    private fun caption(text: String, color: Int) =
        Type.text(ctx, text, TypeRole.CAPTION, color).apply { setPadding(0, ctx.dp(6), 0, 0) }

    /** An inline error: a block with the reason, optionally a retry key, and the raw message in a readout. */
    private fun errorBlock(title: String, body: String, message: String, retry: Boolean = false): View {
        return ErrorBlock.create(
            ctx, title, body, details = message,
            recovery = if (retry) KeyButton.Spec("Try again", "Read the settings again") { render() } else null,
        )
    }

    // --- bridge ----------------------------------------------------------------------------------

    private fun bridgeSection(store: PolicyStore, policy: Policy) {
        header("Bridge")
        val panel = Panel.create(ctx)
        panel.addView(ToggleRow.create(
            ctx, "Auto-start on boot",
            if (policy.autoStartOnBoot) {
                "The bridge starts itself after a reboot, after an app update, and when the app " +
                    "opens. Tailscale can take a few seconds to come up, so it retries for about " +
                    "3 minutes and tells you if it gives up."
            } else {
                "Nothing starts on its own. Open the app and switch the bridge on. After a " +
                    "reboot the bridge stays down until you do."
            },
            policy.autoStartOnBoot,
        ) { save(store, policy.withAutoStartOnBoot(!policy.autoStartOnBoot)) })
        panel.addView(ToggleRow.create(
            ctx, "Sound effects",
            if (policy.soundEffects) {
                "A short sound when an approval appears and when you switch tabs. Plugin sounds " +
                    "are not affected."
            } else {
                "Silent. Approvals still vibrate, and nothing a plugin plays is affected."
            },
            policy.soundEffects,
        ) { save(store, policy.withSoundEffects(!policy.soundEffects)) })
        container.addView(panel)
    }

    // --- approvals (D66) -------------------------------------------------------------------------

    private fun approvalsSection(policy: Policy) {
        header("Approvals")
        val status = Fingerprint.status(hostActivity)
        if (status is Fingerprint.Status.PermanentlyUnusable && policy.fingerprintForModals) {
            // A phone that can never read a fingerprint never keeps a gate it cannot approve. A
            // temporary fault is NOT this: opening Settings must not write the setting.
            FingerprintSetting.forceOff(ctx, status.reason, source = "settings")
        }
        val panel = Panel.create(ctx)
        val row = when (status) {
            is Fingerprint.Status.PermanentlyUnusable -> ToggleRow.create(
                ctx, "Fingerprint for approvals", status.reason, on = false, enabled = false,
            ) { }
            is Fingerprint.Status.TemporarilyUnavailable -> fingerprintRow(policy, status.reason)
            Fingerprint.Status.Usable -> fingerprintRow(
                policy,
                if (policy.fingerprintForModals) {
                    "In Paranoid mode, approving a request asks for your fingerprint after the tap."
                } else {
                    "In Paranoid mode, approving is a tap, or a hold for actions that run or delete."
                },
            )
        }
        panel.addView(row)
        container.addView(panel)
    }

    private fun fingerprintRow(policy: Policy, description: String) = ToggleRow.create(
        ctx, "Fingerprint for approvals", description, policy.fingerprintForModals,
    ) { want ->
        FingerprintSetting.request(this, want, onError = { saveError = it }) {
            renderPosted()
        }
    }

    // --- permissions -----------------------------------------------------------------------------

    private fun permissionsSection() {
        header("Permissions")
        val panel = Panel.create(ctx)
        val ok = StatusRow.Tone.OK
        val caution = StatusRow.Tone.CAUTION

        when (SettingsState.notificationAccess(
            SystemAccess.notificationAccessGranted(ctx), BridgeService.listenerRef != null,
        )) {
            NotificationAccess.GRANTED -> panel.addView(StatusRow.create(
                ctx, "Notification access", "Granted", ok,
                "STMP can read and act on your notifications for the Notifications plugin.",
                Led.State.OK, { SystemAccess.openNotificationAccessSettings(ctx) },
            ))
            NotificationAccess.NOT_CONNECTED -> panel.addView(StatusRow.create(
                ctx, "Notification access", "Not connected", caution,
                "Access is on but the listener is not connected. Open the settings, switch STMP " +
                    "off and on again.",
                Led.State.CAUTION, { SystemAccess.openNotificationAccessSettings(ctx) },
            ))
            NotificationAccess.NEEDED -> panel.addView(StatusRow.create(
                ctx, "Notification access", "Needed", caution,
                "Tap to turn it on. Without it the Notifications plugin sees nothing, and Home " +
                    "reports that the bridge needs action.",
                Led.State.CAUTION, { SystemAccess.openNotificationAccessSettings(ctx) },
            ))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                panel.addView(StatusRow.create(
                    ctx, "STMP notifications", "Granted", ok,
                    "Lets STMP show its status notification and the alert when the bridge cannot start.",
                    Led.State.OK, { SystemAccess.openAppNotificationSettings(ctx) },
                ))
            } else {
                panel.addView(StatusRow.create(
                    ctx, "STMP notifications", "Needed", caution,
                    "Tap to allow. Without it STMP cannot tell you when the bridge fails to start.",
                    Led.State.CAUTION, { SystemAccess.openAppNotificationSettings(ctx) },
                ))
            }
        }

        if (SystemAccess.overlayGranted(ctx)) {
            panel.addView(StatusRow.create(
                ctx, "Draw over apps", "Granted", ok,
                "Lets the Don't-disturb card appear over other apps.",
                Led.State.OK, { SystemAccess.openOverlaySettings(ctx) },
            ))
        } else {
            panel.addView(StatusRow.create(
                ctx, "Draw over apps", "Needed", caution,
                "Tap to grant. Without it the Don't-disturb card cannot appear.",
                Led.State.CAUTION, { SystemAccess.openOverlaySettings(ctx) },
            ))
        }

        if (ApprovalGate.canUseFullScreenIntent(ctx)) {
            panel.addView(StatusRow.create(
                ctx, "Lock-screen popup", "Granted", ok,
                "Wakes a locked screen when an approval arrives.",
                Led.State.OK, { SystemAccess.openFullScreenIntentSettings(ctx) },
            ))
        } else {
            panel.addView(StatusRow.create(
                ctx, "Lock-screen popup", "Optional", StatusRow.Tone.NEUTRAL,
                "Tap to allow approvals to wake a locked screen. Approvals still arrive without it.",
                Led.State.OFF, { SystemAccess.openFullScreenIntentSettings(ctx) },
            ))
        }

        if (SystemAccess.batteryExempt(ctx)) {
            panel.addView(StatusRow.create(
                ctx, "Battery optimization", "Granted", ok,
                "Android will not pause the bridge while the screen is off.",
                Led.State.OK, { SystemAccess.requestBatteryExemption(ctx) },
            ))
        } else {
            panel.addView(StatusRow.create(
                ctx, "Battery optimization", "Needed", caution,
                "Tap to allow background running. Without it Android can pause the bridge when " +
                    "the screen is off.",
                Led.State.CAUTION, { SystemAccess.requestBatteryExemption(ctx) },
            ))
        }

        if (SystemAccess.phoneDndOn(ctx)) {
            panel.addView(StatusRow.create(
                ctx, "Android Do Not Disturb", "On", caution,
                "Android may silence the approval sound and hold back the new-device prompt. The " +
                    "approval screen itself still opens. Tap to change Do Not Disturb.",
                Led.State.CAUTION, { SystemAccess.openDndSettings(ctx) },
            ))
        } else {
            panel.addView(StatusRow.create(
                ctx, "Android Do Not Disturb", "Off", ok,
                "Android is not silencing anything, so approval sounds and the new-device prompt " +
                    "come through.",
                Led.State.OK, { SystemAccess.openDndSettings(ctx) },
            ))
        }
        container.addView(panel)
    }

    /** The secret vault is not an Android permission, so it no longer sits among them (R3). */
    private fun vaultSection() {
        header("Secret vault")
        val panel = Panel.create(ctx)
        panel.addView(vaultRow())
        container.addView(panel)
    }

    private fun vaultRow(): View {
        val failure = VaultUnlock.lastFailure?.let { " Last attempt failed: $it. The debug log below has more." } ?: ""
        val unlock = { VaultUnlock.ensureUnlocked(hostActivity, force = true) { uiSafe { renderPosted() } } }
        return when (SettingsState.vault(
            SecretVault.isUnlocked(), SecretVault.isUnrecoverable(ctx), SecretVault.isOrphaned(ctx),
            SecretVault.exists(ctx),
        )) {
            VaultRow.UNLOCKED -> StatusRow.create(
                ctx, "Secret vault", "Unlocked", StatusRow.Tone.OK,
                "Open for this session. It locks again when the app restarts.", Led.State.OK,
            )
            VaultRow.NEEDS_RESET -> StatusRow.create(
                ctx, "Secret vault", "Needs reset", StatusRow.Tone.CAUTION,
                "The vault's key is gone, so its secrets cannot be opened. Tap to reset it and " +
                    "start fresh.",
                Led.State.CAUTION, unlock,
            )
            VaultRow.NOT_CREATED -> StatusRow.create(
                ctx, "Secret vault", "Not set up", StatusRow.Tone.CAUTION,
                "Tap to create it with your fingerprint.$failure", Led.State.CAUTION, unlock,
            )
            VaultRow.LOCKED -> StatusRow.create(
                ctx, "Secret vault", "Locked", StatusRow.Tone.CAUTION,
                "Tap to unlock with your fingerprint.$failure", Led.State.CAUTION, unlock,
            )
        }
    }

    // --- tools -----------------------------------------------------------------------------------

    private fun toolsSection(policy: Policy?) {
        header("Tools")
        val panel = Panel.create(ctx)
        panel.addView(StatusRow.create(
            ctx, "Request log", "", detail = "Every request the bridge handled, newest first.",
            onClick = { go(Intent(ctx, LogActivity::class.java)) },
        ))
        panel.addView(StatusRow.create(
            ctx, "Exceptions", policy?.let { SettingsState.rulesCount(it.rules.size) } ?: "",
            detail = "Per-plugin exceptions used in Whitelist mode.",
            onClick = { go(Intent(ctx, ExceptionsActivity::class.java)) },
        ))
        panel.addView(StatusRow.create(
            ctx, "SSH", "", detail = "Saved connections and keys.",
            onClick = { go(Intent(ctx, SshActivity::class.java)) },
        ))
        container.addView(panel)
    }

    // --- debug -----------------------------------------------------------------------------------

    private fun debugSection() {
        header("Debug")
        val panel = Panel.create(ctx)
        val open = DebugLogOpen.open
        val toggle = KeyButton.create(ctx, if (open) "Hide log" else "Show log", KeyButton.Kind.PANEL) {
            DebugLogOpen.open = !DebugLogOpen.open
            render()
        }
        val copy = KeyButton.create(
            ctx, "Copy log", KeyButton.Kind.PANEL, describe = "Copy the debug log to the clipboard",
        ) { copyLog() }
        // The Copy slot is kept while the log is closed (invisible side by side, gone when stacked),
        // so the Show/Hide key never changes size or position.
        copy.visibility = if (open) View.VISIBLE else if (keysSideBySide()) View.INVISIBLE else View.GONE
        panel.addView(keyLine(listOf(toggle, copy), equalSlots = true))
        if (open) {
            val readout = Readout.create(ctx, Debug.dump(), Readout.Kind.LOG)
            readout.root.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(Dimens.SPACE_M) }
            panel.addView(readout.root)
            readout.root.post { (readout.root as NestedScrollView).fullScroll(View.FOCUS_DOWN) }
        }
        container.addView(panel)
    }

    private fun copyLog() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("STMP debug log", Debug.dump()))
        // Android 13+ shows its own clipboard confirmation; two would stack.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toasts.show(ctx, "Copied")
    }

    private fun keysSideBySide() = ctx.resources.configuration.fontScale <= STACK_ABOVE_SCALE

    /**
     * Keys on one line when they fit (font scale at most 1.15), else stacked full width, 8dp apart.
     * With [equalSlots] each side-by-side key gets half the row, so a hidden second key keeps its
     * slot and the first key looks the same either way.
     */
    private fun keyLine(keys: List<MaterialButton>, equalSlots: Boolean = false): LinearLayout {
        val sideBySide = keysSideBySide()
        val line = LinearLayout(ctx).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        keys.forEachIndexed { i, key ->
            val lp = when {
                sideBySide && equalSlots -> LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                sideBySide -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                else -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            if (i > 0) {
                if (sideBySide) lp.marginStart = ctx.dp(Dimens.TARGET_GAP) else lp.topMargin = ctx.dp(Dimens.TARGET_GAP)
            }
            line.addView(key, lp)
        }
        return line
    }

    // --- callers ---------------------------------------------------------------------------------

    private fun callersSection(policy: Policy?) {
        header("Callers")
        callerError?.let { container.addView(StateBlock.create(ctx, StateBlock.Kind.ERROR, it)) }
        val entries = try {
            CallerStore.from(requireContext()).all()
        } catch (e: Exception) {
            container.addView(errorBlock(
                "Caller list can't be read",
                "Every request is refused until this file is fixed or removed.",
                e.message ?: "unreadable", retry = true,
            ))
            return
        }
        if (entries.isEmpty()) {
            container.addView(StateBlock.create(
                ctx, StateBlock.Kind.EMPTY, "No callers yet",
                "The first request from another machine will ask you to name it.",
            ))
            return
        }
        val panel = Panel.create(ctx)
        for (entry in entries.values.sortedBy { it.ip }) {
            val title = UntrustedText.escape((entry.state as? CallerState.Verified)?.name ?: entry.ip)
            val detail = when (entry.state) {
                is CallerState.Verified -> "${UntrustedText.escape(entry.ip)} · allowed"
                CallerState.Denied -> "Blocked. Requests from this address are refused without asking."
            }
            panel.addView(ActionRow(
                ctx, title, detail, emptyList(),
                KeyButton.Spec("Forget", "Forget $title") { confirmForget(entry, title, policy) },
                led = if (entry.state is CallerState.Denied) Led.State.OFF else Led.State.OK,
            ))
        }
        container.addView(panel)
    }

    private fun confirmForget(entry: CallerEntry, title: String, policy: Policy?) {
        com.mido.phonebridge.ui.kit.StmpDialog.confirmDelete(
            ctx,
            title = "Forget $title?",
            message = "Its next request will be refused until you name it again. This cannot be undone.",
            deleteLabel = "Forget",
            keepLabel = "Keep",
        ) { forget(entry, policy) }.show()
    }

    private fun forget(entry: CallerEntry, policy: Policy?) {
        callerError = null
        try {
            CallerStore.from(requireContext()).forget(entry.ip)
        } catch (e: Exception) {
            callerError = "Could not forget this caller: ${e.message}"
            render()
            return
        }
        if (policy == null) {
            Debug.log("caller forgotten; policy unreadable, not audited")
        } else {
            try {
                AuditLog.from(requireContext()).append(PolicyAudit.record(policy.global, policy.global, "settings"))
            } catch (e: Exception) {
                AuditHealth.markDegraded("caller change not recorded: ${e.message}")
            }
        }
        render()
    }

    // --- saving ----------------------------------------------------------------------------------

    private fun save(store: PolicyStore, next: Policy): Boolean {
        saveError = null
        try {
            store.set(next)
        } catch (e: Exception) {
            saveError = e.message ?: e.javaClass.simpleName
            renderPosted()
            return false
        }
        try {
            AuditLog.from(requireContext()).append(PolicyAudit.record(next.global, next.global, "settings"))
        } catch (e: Exception) {
            AuditHealth.markDegraded("preference change not recorded: ${e.message}")
        }
        renderPosted()
        return true
    }

    // --- AI --------------------------------------------------------------------------------------

    private fun aiSection(store: PolicyStore, policy: Policy) {
        header("AI")
        val panel = Panel.create(ctx)
        panel.addView(Etched.label(ctx, "AI summaries"))
        val order = listOf(AiBackend.OFF, AiBackend.LOCAL, AiBackend.EXTERNAL)
        val knob = KnobSelector(ctx, "AI summaries", listOf("Off", "On this phone", "Remote server"))
        knob.select(order.indexOf(policy.aiBackend).coerceAtLeast(0), animate = false)
        knob.onSelect = { i ->
            val target = order[i]
            if (target == AiBackend.OFF) AiGateway.release()
            if (target != AiBackend.EXTERNAL) resetRemoteForm()
            // Selecting "On this phone" never starts a download; that waits for the tap on the key.
            save(store, policy.withAiBackend(target))
        }
        panel.addView(knob, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        panel.addView(Theme.spacer(ctx, Dimens.SPACE_M))

        isLocalBodyShown = policy.aiBackend == AiBackend.LOCAL
        when (policy.aiBackend) {
            // The knob above already says Off: one explanatory sentence, no second heading or status.
            AiBackend.OFF -> panel.addView(Type.text(
                ctx,
                "Nothing is described. Requests still show the exact target and payload; they are " +
                    "built without a model. This also frees the memory the model holds.",
                TypeRole.DETAIL, Theme.palette(ctx).etched,
            ))
            AiBackend.LOCAL -> localBody(panel)
            AiBackend.EXTERNAL -> remoteBody(panel, store, policy)
        }
        container.addView(panel)
    }

    private fun resetRemoteForm() {
        draft = RemoteDraft()
        urlError = null
        modelProblem = null
    }

    private fun localBody(panel: LinearLayout) {
        val expected = ModelRegistry.EXPECTED_SIZE
        val file = ModelRegistry.modelFile(ctx)
        val exists = file.exists()
        val running = ModelProvisioner.running
        val percent = ModelProvisioner.progress?.percent ?: 0
        val state = LocalModelState.of(
            exists, if (exists) file.length() else 0L, expected, running, percent, ModelProvisioner.lastError,
        )
        val label = "Model on this phone"
        val caution = StatusRow.Tone.CAUTION
        fun mb(bytes: Long) = ModelReadiness.mb(bytes)

        if (shownRunning && !running && state is LocalModelState.Ready) Toasts.show(ctx, "Model ready")
        shownRunning = running
        shownPercent = if (running) percent else -1
        if (!running) announced.clear()
        if (running) {
            container.removeCallbacks(progressTick)
            container.postDelayed(progressTick, PROGRESS_TICK_MS)
            for (mark in ANNOUNCE_AT) if (percent >= mark && announced.add(mark)) {
                container.announceForAccessibility("Downloading $mark percent")
            }
        }

        fun download() = KeyButton.create(
            ctx, "Download model", KeyButton.Kind.INK,
            describe = "Download the on-device AI model, ${mb(expected)}",
        ) { startModelDownload() }

        when (state) {
            is LocalModelState.Ready -> {
                panel.addView(StatusRow.create(
                    ctx, label, "Ready", StatusRow.Tone.OK,
                    "Downloaded and verified, ${mb(expected)}. Summaries are written on this phone; " +
                        "nothing leaves it.", Led.State.OK,
                ))
                panel.addView(key(KeyButton.create(
                    ctx, "Re-download", KeyButton.Kind.PANEL, KeyButton.Size.SMALL,
                    describe = "Download the AI model again",
                ) { confirmRedownload() }))
            }
            is LocalModelState.NotDownloaded -> {
                panel.addView(StatusRow.create(
                    ctx, label, "Not downloaded", caution,
                    "A one-time download of ${mb(expected)}. Until it finishes, the bridge refuses " +
                        "requests that need a summary.", Led.State.CAUTION,
                ))
                panel.addView(key(download()))
            }
            is LocalModelState.Incomplete -> {
                panel.addView(StatusRow.create(
                    ctx, label, "Incomplete", caution,
                    "Only ${mb(state.have)} of ${mb(state.expected)} is on the phone. Download again to finish.",
                    Led.State.CAUTION,
                ))
                panel.addView(key(download()))
            }
            is LocalModelState.WrongFile -> {
                panel.addView(StatusRow.create(
                    ctx, label, "Wrong file", caution,
                    SettingsState.wrongFile(state.have, state.expected, ::mb) + " Download again to replace it.",
                    Led.State.CAUTION,
                ))
                panel.addView(key(download()))
            }
            is LocalModelState.Downloading -> {
                panel.addView(StatusRow.create(
                    ctx, label, "Downloading", StatusRow.Tone.NEUTRAL,
                    "${state.percent}% of ${mb(state.expected)}. You can leave this screen; the " +
                        "download carries on.", Led.State.PENDING,
                ))
                panel.addView(key(KeyButton.progress(ctx, "Downloading ${state.percent}%", state.percent)))
            }
            is LocalModelState.Failed -> {
                panel.addView(ErrorBlock.create(
                    ctx, "Download failed",
                    "The last download did not finish. Nothing is wrong with the phone; try again.",
                    details = state.reason,
                    recovery = KeyButton.Spec("Try again", "Download the model again") { startModelDownload() },
                    framed = false,
                ))
            }
        }
    }

    private fun key(button: MaterialButton): View = button.apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ctx.dp(Dimens.TARGET_GAP) }
    }

    private fun confirmRedownload() {
        confirm(
            title = "Re-download the AI model?",
            message = "The model is already downloaded and verified. Re-downloading fetches " +
                "${ModelReadiness.mb(ModelRegistry.EXPECTED_SIZE)} again and replaces the working copy.",
            positive = "Re-download",
            negative = "Keep",
            onNegative = { Debug.log("re-download declined") },
        ) { startModelDownload(force = true) }
    }

    private fun startModelDownload(force: Boolean = false) {
        Debug.log("model download requested (force=$force)")
        val app = requireContext().applicationContext
        Thread({
            val result = ModelProvisioner.provision(
                app, force = force,
                onProgress = { uiSafe { if (percentChangedOnScreen()) renderPosted() } },
            )
            uiSafe {
                Debug.log("model provision: ${result.detail}")
                render()
            }
        }, "model-download").apply { isDaemon = true }.start()
        // The worker flips `running` a moment after it starts; repaint once it has.
        container.postDelayed({ if (isAdded && view != null) render() }, START_REPAINT_MS)
    }

    // --- remote server ---------------------------------------------------------------------------

    private fun remoteBody(panel: LinearLayout, store: PolicyStore, policy: Policy) {
        val state = SettingsState.remote(policy.aiExternalUrl, policy.aiExternalDialect, policy.aiExternalModel)
        val p = Theme.palette(ctx)
        val url = draft.url ?: policy.aiExternalUrl
        val token = draft.token ?: policy.aiExternalToken
        val stored = policy.aiExternalModel
        val listed = draft.listed
        val chosen = draft.picked
            ?: stored.takeIf { it.isNotBlank() && (listed == null || it in listed.models) }

        panel.addView(when (state) {
            RemoteState.NOT_SET -> StatusRow.create(
                ctx, "Model server", "Not set", StatusRow.Tone.CAUTION,
                "No server set, so remote summaries refuse every request. Enter the address below.",
                Led.State.CAUTION,
            )
            RemoteState.NOT_LISTED -> StatusRow.create(
                ctx, "Model server", "Not listed", StatusRow.Tone.CAUTION,
                "The server has not been asked yet. Refresh the models.", Led.State.CAUTION,
            )
            RemoteState.NO_MODEL -> StatusRow.create(
                ctx, "Model server", "No model", StatusRow.Tone.CAUTION,
                "Pick a model, then save.", Led.State.CAUTION,
            )
            RemoteState.READY -> StatusRow.create(
                ctx, "Model server", "Ready", StatusRow.Tone.OK,
                "Using $stored (${policy.aiExternalDialect?.label}) at ${policy.aiExternalUrl}. If " +
                    "the server is unreachable, requests are refused, never approved without a description.",
                Led.State.OK,
            )
        })

        val urlField = Field.create(
            ctx, "Server URL", url, Hints.SERVER_URL, Field.Kind.URL,
            onChange = { draft = draft.copy(url = it); urlError = null },
        )
        urlError?.let { urlField.setError(it) }
        panel.addView(spaced(urlField.root))
        panel.addView(caption(
            "No trailing slash, no path. Examples: http://192.168.1.9:8888 for Unsloth Studio, " +
                "http://100.101.102.103:11434 for Ollama. The address must be one this phone can " +
                "reach; a tailnet address usually works from anywhere.", p.etched,
        ))
        val tokenField = Field.create(
            ctx, "Token (optional)", token, Hints.TOKEN, Field.Kind.SECRET,
            onChange = { draft = draft.copy(token = it) },
        )
        panel.addView(spaced(tokenField.root))

        val statusLine = when {
            refreshing -> "Asking ${url.trim()} …"
            listed != null -> "${listed.models.size} model(s) from a ${listed.dialect.label} server. Pick one, then save."
            stored.isBlank() -> "No model selected. Refresh the models and pick one."
            else -> "Using $stored" + (policy.aiExternalDialect?.let { " (${it.label})" } ?: "")
        }
        val modelRow = StatusRow.create(
            ctx, "Model", chosen ?: "none",
            if (chosen == null) StatusRow.Tone.CAUTION else StatusRow.Tone.NEUTRAL,
            statusLine, onClick = if (listed != null) ({ pickModel() }) else null,
        ).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        panel.addView(spaced(modelRow))
        modelProblem?.let {
            panel.addView(Type.text(ctx, it, TypeRole.DETAIL, p.cautionText).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
        }

        val refresh = KeyButton.create(
            ctx, if (refreshing) "Refreshing" else "Refresh models", KeyButton.Kind.PANEL,
            describe = "Ask the server which models it offers",
        ) { refreshModels(liveUrl(policy), liveToken(policy)) }
        if (refreshing) {
            refresh.isEnabled = false
            refresh.alpha = DISABLED_ALPHA
        }
        val saveKey = KeyButton.create(ctx, "Save", KeyButton.Kind.INK) {
            saveRemote(store, policy, liveUrl(policy), liveToken(policy), chosen)
        }
        panel.addView(spaced(keyLine(listOf(refresh, saveKey))))
    }

    // The form is not redrawn on each keystroke, so the keys read what was typed, not what was drawn.
    private fun liveUrl(policy: Policy): String = (draft.url ?: policy.aiExternalUrl).trim()

    private fun liveToken(policy: Policy): String = (draft.token ?: policy.aiExternalToken).trim()

    private fun spaced(v: View): View = v.apply {
        val lp = (layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = ctx.dp(Dimens.SPACE_M)
        layoutParams = lp
    }

    private fun refreshModels(url: String, token: String) {
        modelProblem = null
        validateEndpoint(url)?.let { urlError = it; render(); return }
        if (url.isBlank()) {
            urlError = "Enter the server URL first, then refresh."
            render()
            return
        }
        urlError = null
        refreshing = true
        render()
        Thread {
            val outcome: Result<ModelCatalogue> = try {
                Result.success(ExternalModels.fetch(url, token))
            } catch (e: Exception) {
                Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName, e))
            }
            uiSafe {
                refreshing = false
                val catalogue = outcome.getOrNull()
                if (catalogue == null) {
                    val why = outcome.exceptionOrNull()?.message ?: "no answer"
                    modelProblem = "Could not list models: $why"
                    draft = draft.copy(listed = null)
                    render()
                } else {
                    val stored = try { PolicyStore.from(ctx).get().aiExternalModel } catch (e: Exception) { "" }
                    val keep = stored.takeIf { it in catalogue.models }
                        ?: draft.picked?.takeIf { it in catalogue.models }
                    draft = draft.copy(listed = catalogue, picked = keep)
                    render()
                    pickModel()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun pickModel() {
        val models = draft.listed?.models ?: return
        val checked = models.indexOf(draft.picked)
        StmpDialog.builder(ctx)
            .setTitle("Pick a model")
            .setSingleChoiceItems(models.toTypedArray(), checked) { dialog, which ->
                draft = draft.copy(picked = models[which])
                modelProblem = null
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveRemote(store: PolicyStore, policy: Policy, url: String, token: String, chosen: String?) {
        modelProblem = null
        validateEndpoint(url)?.let { urlError = it; render(); return }
        if (chosen.isNullOrBlank()) {
            modelProblem = "Refresh the models and pick one. Remote summaries cannot describe " +
                "requests without a model."
            render()
            return
        }
        val dialect = draft.listed?.dialect ?: policy.aiExternalDialect
        if (dialect == null) {
            modelProblem = "Refresh the models first, so the app knows which protocol the server speaks."
            render()
            return
        }
        urlError = null
        val saved = save(
            store,
            policy.withAiExternalUrl(url).withAiExternalToken(token).withAiExternalModel(chosen, dialect),
        )
        if (saved) {
            draft = RemoteDraft()
            Toasts.show(ctx, "Saved")
            render()
        }
    }

    private fun validateEndpoint(raw: String): String? {
        if (raw.isBlank()) return null
        val parsed = try {
            java.net.URI(raw)
        } catch (e: Exception) {
            return "Not a valid URL: ${e.message}"
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "It must start with http:// or https://"
        if (parsed.host.isNullOrBlank()) return "There is no host in that URL."
        if (raw.endsWith("/")) return "Drop the trailing slash."
        return null
    }

    private companion object {
        const val PROGRESS_TICK_MS = 1000L
        const val START_REPAINT_MS = 400L
        const val STACK_ABOVE_SCALE = 1.15f
        const val DISABLED_ALPHA = 0.38f
        val ANNOUNCE_AT = listOf(25, 50, 75)
    }
}

private fun Context.dp(v: Int): Int = Theme.dp(this, v)

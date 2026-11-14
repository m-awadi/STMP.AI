package com.mido.phonebridge.screenshots

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.biometric.BiometricManager
import androidx.core.widget.NestedScrollView
import com.mido.phonebridge.ApprovalActivity
import com.mido.phonebridge.Fingerprint
import com.mido.phonebridge.ApprovalGate
import com.mido.phonebridge.ApprovalGate.Row
import com.mido.phonebridge.ai.RequestSummary
import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import com.mido.phonebridge.ui.kit.MaxHeightScrollView
import org.junit.After
import org.junit.Test

/** BiometricManager's BIOMETRIC_ERROR_LOCKOUT; the gate maps it to LockedOut. */
internal const val BIOMETRIC_ERROR_LOCKOUT = 7

/** The gate's page scroll (not a readout's own scroll). */
internal fun gateScroll(v: View): NestedScrollView? {
    if (v is NestedScrollView && v !is MaxHeightScrollView) return v
    if (v is ViewGroup) for (i in 0 until v.childCount) gateScroll(v.getChildAt(i))?.let { return it }
    return null
}

/** A tap on the gate's touch guard whose window another app fully covers. */
internal fun obscuredTap(a: ApprovalActivity, x: Float = 50f, y: Float = 50f) {
    val guard = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
    val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
    val t = android.os.SystemClock.uptimeMillis()
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        guard.dispatchTouchEvent(
            MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, MotionEvent.FLAG_WINDOW_IS_OBSCURED),
        )
    }
}

/** The Instrument gate in every state of WP03a section 18 (approval/). */
class ApprovalScreenshotTest(variant: Variant) : ShotTest(variant) {

    @After
    fun clearGate() = GateFixtures.reset()

    private fun shot(a: ApprovalActivity, state: String, full: Boolean = false) {
        Shots.window(a, "approval", state)
        if (!full) return
        val scroll = gateScroll(a.window.decorView) ?: return
        val child = scroll.getChildAt(0) ?: return
        if (child.height > scroll.height) Shots.full(child, "approval", state)
    }

    private fun contacts(
        state: String,
        ai: ApprovalGate.AiState = ApprovalGate.AiState.NotNeeded,
        queued: Int = 0,
        view: ApprovalGate.ApprovalView = GateFixtures.contacts(),
        enabled: Long? = GateFixtures.ARMED_13S,
    ) = GateFixtures.launch(view, "GET /contacts", ai = ai, queued = queued, enabledAtOffsetMs = enabled).also { shot(it, state) }

    private fun summary(risk: Risk) = ApprovalGate.AiState.Ready(
        RequestSummary("Looks up one contact by name.", risk, "Read-only search; nothing is changed."),
    )

    private val sshDanger = "rm -rf ~/builds/stmp/app/build && ./gradlew :app:assembleDebug --no-daemon | tee log > out.txt"
    private val offer = ApprovalGate.ExceptionOffer(Rule("contacts", Verb.READ, Mode.DONT_DISTURB), "ALWAYS NOTIFY")
    private val sshOffer = ApprovalGate.ExceptionOffer(Rule("ssh", Verb.EXECUTE, Mode.DONT_DISTURB), "ALWAYS NOTIFY")

    // --- WP00 states, re-recorded -----------------------------------------------------------------

    @Test fun ai_loading() { contacts("ai_loading", ai = ApprovalGate.AiState.Loading, enabled = null) }
    @Test fun ai_ready_low() { contacts("ai_ready_low", ai = summary(Risk.LOW)) }
    @Test fun ai_ready_medium() { contacts("ai_ready_medium", ai = summary(Risk.MEDIUM)) }
    @Test fun ai_ready_high() {
        contacts(
            "ai_ready_high",
            ai = ApprovalGate.AiState.Ready(
                RequestSummary(
                    "Reads every contact with notes and sends all of it back in one response.", Risk.HIGH,
                    "Large export of personal data to whoever holds the token.",
                ),
            ),
        )
    }
    @Test fun ai_failed() { contacts("ai_failed", ai = ApprovalGate.AiState.Failed("the model did not answer in time")) }
    @Test fun not_needed() { contacts("not_needed") }

    @Test fun password() {
        shot(GateFixtures.launch(GateFixtures.ssh("sudo -S apt update", sudo = true), "POST /ssh/kvm-box"), "password")
    }

    @Test fun bio_not_enrolled() {
        ApprovalActivity.canAuthenticateForTest = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        contacts("bio_not_enrolled", view = GateFixtures.contacts(biometric = true))
    }

    /** The sensor is locked out and the phone has no screen lock: nothing can approve. */
    @Test fun bio_locked_out() {
        ApprovalActivity.canAuthenticateForTest = BIOMETRIC_ERROR_LOCKOUT
        Fingerprint.deviceSecureForTest = false
        contacts("bio_locked_out", view = GateFixtures.contacts(biometric = true))
    }

    /** The sensor is locked out and the phone has a PIN: Approve asks for the PIN. */
    @Test fun bio_use_pin() {
        ApprovalActivity.canAuthenticateForTest = BIOMETRIC_ERROR_LOCKOUT
        Fingerprint.deviceSecureForTest = true
        contacts("bio_use_pin", view = GateFixtures.contacts(biometric = true))
    }

    @Test fun vault_broken() {
        GateFixtures.vaultBroken()
        contacts("vault_broken", view = GateFixtures.contacts(biometric = true))
    }

    @Test fun exception_offer() { contacts("exception_offer", view = GateFixtures.contacts(offer = offer)) }
    @Test fun queued_2() { contacts("queued_2", queued = 2) }

    @Test fun details_open() {
        val a = GateFixtures.launch(GateFixtures.contacts(), "GET /contacts")
        findByDescription(a.window.decorView, "Raw request, SHOW")!!.performClick()
        shot(a, "details_open")
    }

    // --- WP03a states -------------------------------------------------------------------------------

    @Test fun summary_pending() {
        val a = GateFixtures.launch(
            GateFixtures.ssh(sshDanger), "POST /ssh/kvm-box", ai = ApprovalGate.AiState.Loading, enabledAtOffsetMs = null,
        )
        shot(a, "summary_pending")
    }

    @Test fun guard_ready_1s() {
        val a = GateFixtures.launch(GateFixtures.ssh(sshDanger), "POST /ssh/kvm-box", enabledAtOffsetMs = 1_000L, clockOffsetMs = 200L)
        shot(a, "guard_ready_1s")
    }

    @Test fun hold_ssh_danger() { shot(GateFixtures.launch(GateFixtures.ssh(sshDanger), "POST /ssh/kvm-box"), "hold_ssh_danger") }

    @Test fun hold_ssh_long() {
        val long = buildString { while (length < 1_200) append("cp -r ~/src/module-$length /mnt/backup/ && ") }.take(1_200)
        shot(GateFixtures.launch(GateFixtures.ssh(long), "POST /ssh/kvm-box"), "hold_ssh_long", full = true)
    }

    /** Long tokens and a URL: wraps only at spaces, each wrap marked with the continuation glyph. */
    @Test fun hold_ssh_wrap_marks() {
        val cmd = "curl -fsSL https://downloads.example.com/releases/stmp/2026/11/installer-linux-arm64.tar.gz " +
            "-o /tmp/installer.tgz && tar -xzf /tmp/installer.tgz -C /opt/stmp --strip-components=1 && rm -rf /tmp/installer.tgz"
        shot(GateFixtures.launch(GateFixtures.ssh(cmd), "POST /ssh/kvm-box"), "hold_ssh_wrap_marks")
    }

    /** FIX4: a command with bidi controls, a zero-width space and a literal wrap glyph shows each as a token. */
    @Test fun hold_ssh_hidden_chars() {
        val cmd = "echo safe \u202Etxt.exe\u202C && curl http://ok\u200B.example.com/\u2066x \u21B5 && rm\u00AD -rf /tmp/build"
        shot(GateFixtures.launch(GateFixtures.ssh(cmd), "POST /ssh/kvm-box"), "hold_ssh_hidden_chars")
    }

    @Test fun tap_probe_read() {
        val v = GateFixtures.view(Verb.READ, "GET /probe", actionLabel = "Check that the bridge answers")
        shot(GateFixtures.launch(v, "GET /probe"), "tap_probe_read")
    }

    @Test fun tap_biometric() {
        ApprovalActivity.canAuthenticateForTest = BiometricManager.BIOMETRIC_SUCCESS
        contacts("tap_biometric", view = GateFixtures.contacts(biometric = true))
    }

    @Test fun delete_audit_signal() {
        val v = GateFixtures.view(Verb.DELETE, "DELETE /audit", actionLabel = "Delete the request log")
        shot(GateFixtures.launch(v, "DELETE /audit"), "delete_audit_signal")
    }

    @Test fun secrets_json_body() {
        val v = GateFixtures.view(
            Verb.WRITE, "POST /secrets/todoist/call",
            listOf(
                Row("SECRET", "todoist", Row.Style.NORMAL),
                Row("TARGET", "POST https://api.todoist.com/rest/v2/tasks", Row.Style.MONO),
                Row("BODY", """{"content":"Buy milk","due_string":"tomorrow","priority":2,"labels":["home"]}""", Row.Style.LONG),
            ),
            actionLabel = "Call an API with a stored secret",
        )
        shot(GateFixtures.launch(v, "POST /secrets/todoist/call"), "secrets_json_body")
    }

    @Test fun gmail_send() {
        val v = GateFixtures.view(
            Verb.WRITE, "POST /gmail/messages/send",
            listOf(
                Row("TO", "sara@example.com", Row.Style.WARN),
                Row("CC", "team@example.com", Row.Style.WARN),
                Row("SUBJECT", "Friday plan", Row.Style.WARN),
                Row("BODY", "Hi Sara, the build is green. Shall we ship on Friday?", Row.Style.LONG),
            ),
            actionLabel = "Send an email",
        )
        shot(GateFixtures.launch(v, "POST /gmail/messages/send"), "gmail_send")
    }

    @Test fun notification_action_reply() {
        val v = GateFixtures.view(
            Verb.EXECUTE, "POST /notifications/{id}/action",
            listOf(Row("ACTION", "Reply", Row.Style.WARN), Row("TITLE", "Sara"), Row("REPLY", "On my way, ten minutes.", Row.Style.LONG)),
            actionLabel = "Reply", notifTitle = "Sara", notifSender = "Family", notifText = "Where are you?",
            messageCount = 3, options = listOf("Mark as read", "Mute"),
        )
        shot(GateFixtures.launch(v, "POST /notifications/n1/action"), "notification_action_reply")
    }

    @Test fun dismiss_exempt_tap() {
        val v = GateFixtures.view(
            Verb.EXECUTE, "POST /notifications/{id}/dismiss",
            listOf(Row("DISMISS", "notification from Chat", Row.Style.NORMAL), Row("TITLE", "Sara")),
            actionLabel = "Dismiss a notification", holdExempt = true, notifTitle = "Sara", notifText = "Where are you?",
        )
        shot(GateFixtures.launch(v, "POST /notifications/n1/dismiss"), "dismiss_exempt_tap")
    }

    @Test fun exception_offer_hold_on() {
        val a = GateFixtures.launch(GateFixtures.ssh("ls -la ~/builds", offer = sshOffer), "POST /ssh/kvm-box")
        findByText(a.window.decorView, "ALSO ADD EXCEPTION")!!.performClick()
        shot(a, "exception_offer_hold_on")
    }

    @Test fun password_bio_locked_out() {
        ApprovalActivity.canAuthenticateForTest = BIOMETRIC_ERROR_LOCKOUT
        Fingerprint.deviceSecureForTest = false
        val a = GateFixtures.launch(GateFixtures.ssh("sudo -S apt update", sudo = true, biometric = true), "POST /ssh/kvm-box")
        obscuredTap(a)
        shot(a, "password_bio_locked_out")
    }

    @Test fun obscured_banner() {
        val a = GateFixtures.launch(GateFixtures.contacts(), "GET /contacts")
        obscuredTap(a)
        shot(a, "obscured_banner")
    }

    @Test fun reduced_motion_ai_loading() {
        GateFixtures.reducedMotion(true)
        contacts("reduced_motion_ai_loading", ai = ApprovalGate.AiState.Loading, enabled = null)
    }

    @Test fun no_view_fallback() { shot(GateFixtures.launch(null, "GET /logs"), "no_view_fallback") }
}

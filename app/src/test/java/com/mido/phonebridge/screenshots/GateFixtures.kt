package com.mido.phonebridge.screenshots

import android.content.Intent
import android.provider.Settings
import com.mido.phonebridge.ApprovalActivity
import com.mido.phonebridge.Fingerprint
import com.mido.phonebridge.ApprovalGate
import com.mido.phonebridge.ApprovalGate.Row
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.gate.GateClock
import org.robolectric.Robolectric
import java.util.concurrent.CountDownLatch

/**
 * WP03a: gate fixtures for the approval screenshots and behaviour tests. Everything set here is
 * in-memory; [reset] puts every seam back.
 */
object GateFixtures {
    const val ID = 4242L
    const val REMOTE = "100.64.0.7"
    val NOW = Fixtures.NOW_MS

    /** Arm time 13 s in the past: the ticks read "auto-deny in 0:47". */
    const val ARMED_13S = -13_000L

    /** The Pending the last [launch] installed, so a test can read its decision. */
    var lastPending: ApprovalGate.Pending? = null
        private set

    fun view(
        verb: Verb,
        routeKey: String,
        rows: List<Row> = emptyList(),
        actionLabel: String = "Search your contacts",
        biometric: Boolean = false,
        offer: ApprovalGate.ExceptionOffer? = null,
        needsPassword: Boolean = false,
        holdExempt: Boolean = false,
        notifTitle: String = "",
        notifSender: String = "",
        notifText: String = "",
        messageCount: Int = 0,
        replyText: String? = null,
        options: List<String> = emptyList(),
        sections: List<ApprovalGate.Section> = emptyList(),
    ) = ApprovalGate.ApprovalView(
        requester = "Work laptop",
        account = "me@example.com",
        actionLabel = actionLabel,
        appLabel = "",
        notifTitle = notifTitle,
        notifSender = notifSender,
        notifText = notifText,
        messageCount = messageCount,
        replyText = replyText,
        options = options,
        destructive = rows,
        sections = sections,
        requiresBiometric = biometric,
        exceptionOffer = offer,
        needsPassword = needsPassword,
        verb = verb,
        routeKey = routeKey,
        holdExempt = holdExempt,
    )

    fun contacts(offer: ApprovalGate.ExceptionOffer? = null, biometric: Boolean = false) = view(
        Verb.READ, "GET /contacts", listOf(Row("SEARCH", "sara", Row.Style.MONO)), offer = offer, biometric = biometric,
    )

    fun ssh(
        command: String,
        sudo: Boolean = false,
        biometric: Boolean = false,
        offer: ApprovalGate.ExceptionOffer? = null,
    ) = view(
        Verb.EXECUTE, "POST /ssh/{name}",
        listOf(
            Row("CONNECTION", "kvm-box", Row.Style.NORMAL),
            Row("HOST", "mido@kvm-box:22", Row.Style.MONO),
            Row("COMMAND", command, Row.Style.CODE),
        ),
        actionLabel = "Run a command over SSH", needsPassword = sudo, biometric = biometric, offer = offer,
    )

    /**
     * Installs [view] as the gate's current request and launches the Activity resumed.
     * [enabledAtOffsetMs] is the arm time relative to [NOW] (null = no arm time yet);
     * [clockOffsetMs] moves the gate clock.
     */
    fun launch(
        view: ApprovalGate.ApprovalView?,
        endpoint: String,
        ai: ApprovalGate.AiState = ApprovalGate.AiState.NotNeeded,
        enabledAtOffsetMs: Long? = ARMED_13S,
        clockOffsetMs: Long = 0L,
        queued: Int = 0,
        detail: org.json.JSONObject? = org.json.JSONObject().put("method", endpoint.substringBefore(' '))
            .put("path", endpoint.substringAfter(' ')),
    ): ApprovalActivity {
        GateClock.nowMs = { NOW + clockOffsetMs }
        val pending = ApprovalGate.Pending(
            requestId = ID,
            latch = CountDownLatch(1),
            detail = detail,
            view = view,
            remote = REMOTE,
            endpoint = endpoint,
            summary = "A request from the work laptop",
            account = "me@example.com",
            createdAtMs = NOW,
            shownAtMs = NOW,
            aiState = ai,
            enabledAtMs = enabledAtOffsetMs?.let { NOW + it } ?: 0L,
            needsPassword = view?.needsPassword == true,
        )
        lastPending = pending
        ApprovalGate.setCurrentForTest(pending)
        queue().clear()
        queue().add(pending)
        repeat(queued) { i -> queue().add(ApprovalGate.Pending(requestId = 5000L + i, latch = CountDownLatch(1), createdAtMs = NOW)) }
        val intent = Intent(Fixtures.app, ApprovalActivity::class.java)
            .putExtra(ApprovalActivity.EXTRA_ID, ID)
            .putExtra(ApprovalActivity.EXTRA_REMOTE, REMOTE)
            .putExtra(ApprovalActivity.EXTRA_ENDPOINT, endpoint)
            .putExtra(ApprovalActivity.EXTRA_SUMMARY, "A request from the work laptop")
            .putExtra(ApprovalActivity.EXTRA_ACCOUNT, "me@example.com")
            .putExtra(ApprovalActivity.EXTRA_CREATED_AT, NOW)
        return Robolectric.buildActivity(ApprovalActivity::class.java, intent).setup().get()
    }

    fun vaultBroken() {
        // A stored wrapped key makes `SecretVault.exists` true; Robolectric has no AndroidKeyStore,
        // so preparing the unlock cipher throws, which is exactly the VaultBroken state.
        Fixtures.app.getSharedPreferences("phonebridge_vault", 0).edit().putString("vault_dk_wrapped", "AAAA").commit()
    }

    fun reducedMotion(on: Boolean) {
        Settings.Global.putFloat(Fixtures.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (on) 0f else 1f)
    }

    @Suppress("UNCHECKED_CAST")
    private fun queue(): java.util.ArrayDeque<ApprovalGate.Pending> {
        val f = ApprovalGate::class.java.getDeclaredField("queue").apply { isAccessible = true }
        return f.get(ApprovalGate) as java.util.ArrayDeque<ApprovalGate.Pending>
    }

    fun reset() {
        ApprovalGate.setCurrentForTest(null)
        queue().clear()
        GateClock.nowMs = { System.currentTimeMillis() }
        ApprovalActivity.canAuthenticateForTest = null
        ApprovalActivity.lastCredentialPromptForTest = null
        Fingerprint.canAuthenticateForTest = null
        Fingerprint.deviceSecureForTest = null
        reducedMotion(false)
        Fixtures.app.getSharedPreferences("phonebridge_vault", 0).edit().clear().commit()
    }
}

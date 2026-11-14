package com.mido.phonebridge.screenshots

import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.BridgeService
import com.mido.phonebridge.HomeFragment
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Test

/** Home: every bridge, gate, recent-requests, request-log and Updates state (WP04 section 11.2). */
class HomeScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun capture(state: String) =
        capture(hostTab(HomeFragment::class.java, Theme.Tab.HOME), "home", state)

    // --- bridge --------------------------------------------------------------------------------
    @Test fun stopped() { Fixtures.server(Fixtures.Server.STOPPED); capture("stopped") }
    @Test fun running_healthy() { Fixtures.server(Fixtures.Server.RUNNING_HEALTHY); capture("running_healthy") }
    @Test fun running_attention() { Fixtures.server(Fixtures.Server.RUNNING_ATTENTION); capture("running_attention") }
    @Test fun running_attention_both() { Fixtures.serverAttention(listenerOk = false, notifOk = false); capture("running_attention_both") }
    @Test fun running_attention_listener() { Fixtures.serverAttention(listenerOk = false, notifOk = true); capture("running_attention_listener") }
    @Test fun running_attention_notifications() { Fixtures.serverAttention(listenerOk = true, notifOk = false); capture("running_attention_notifications") }
    @Test fun unresponsive() { Fixtures.server(Fixtures.Server.UNRESPONSIVE); capture("unresponsive") }
    @Test fun failed() { Fixtures.server(Fixtures.Server.FAILED); capture("failed") }

    /** Stop tapped: the service has not stopped yet, so the rocker and `serverUp` still say ON. */
    @Test fun pending() {
        Fixtures.server(Fixtures.Server.RUNNING_HEALTHY)
        val activity = hostTab(HomeFragment::class.java, Theme.Tab.HOME)
        val home = home(activity)
        home.pendingSince = SystemClock.elapsedRealtime()
        home.pendingWant = false
        home.repaint()
        assertEquals("Stopping\u2026", home.bridgeTitleText)
        capture(activity, "home", "pending")
    }

    /** Start tapped: the service has not come up yet, so the rocker and `serverUp` still say OFF. */
    @Test fun pending_start() {
        Fixtures.server(Fixtures.Server.STOPPED)
        val activity = hostTab(HomeFragment::class.java, Theme.Tab.HOME)
        val home = home(activity)
        home.pendingSince = SystemClock.elapsedRealtime()
        home.pendingWant = true
        home.repaint()
        assertEquals("Starting\u2026", home.bridgeTitleText)
        capture(activity, "home", "pending_start")
    }

    /** Once the service reaches the requested state the pending look ends and the final title shows. */
    @Test fun pending_ends_when_service_reaches_requested_state() {
        Fixtures.server(Fixtures.Server.STOPPED)
        val activity = hostTab(HomeFragment::class.java, Theme.Tab.HOME)
        val home = home(activity)
        home.pendingSince = SystemClock.elapsedRealtime()
        home.pendingWant = true
        home.repaint()
        assertEquals("Starting\u2026", home.bridgeTitleText)
        BridgeService.serverUp = true
        home.repaint()
        assertEquals(null, home.pendingWant)
        assert(!home.bridgeTitleText.startsWith("Starting"))
    }

    @Test fun audit_degraded() { Fixtures.auditDegraded(); capture("audit_degraded") }

    // --- gate ----------------------------------------------------------------------------------
    @Test fun gate_whitelist() { Fixtures.gate(GlobalMode.WHITELIST); capture("gate_whitelist") }

    @Test fun gate_whitelist_exceptions() {
        Fixtures.gate(GlobalMode.WHITELIST)
        Fixtures.exceptions(
            Rule("contacts", Verb.READ, Mode.DONT_DISTURB),
            Rule("tts", null, Mode.DONT_DISTURB),
            Rule("sms", Verb.READ, Mode.DONT_DISTURB),
        )
        capture("gate_whitelist_exceptions")
    }

    @Test fun gate_paranoid() { Fixtures.gate(GlobalMode.PARANOID); capture("gate_paranoid") }
    @Test fun gate_dont_disturb() { Fixtures.gate(GlobalMode.DONT_DISTURB); capture("gate_dont_disturb") }
    @Test fun gate_unreadable() { Fixtures.gateUnreadable(); capture("gate_unreadable") }
    @After fun resetFingerprint() = Fixtures.resetFingerprint()

    @Test fun gate_fp_temporary() { Fixtures.fingerprintTemporarilyUnavailable(); capture("gate_fp_temporary") }
    @Test fun gate_fp_unusable() { Fixtures.fingerprintUnusable(); capture("gate_fp_unusable") }

    // --- recent requests -----------------------------------------------------------------------
    @Test fun tape_three() { Fixtures.auditTape(); capture("tape_three") }
    @Test fun tape_gate_changes() { Fixtures.auditTapeGateChanges(); capture("tape_gate_changes") }
    @Test fun tape_empty() { capture("tape_empty") }

    // --- crash / freeze panel --------------------------------------------------------------------
    @Test fun crash_only() { Fixtures.selfReports(crash = true, stall = false); capture("crash_only") }
    @Test fun stall_only() { Fixtures.selfReports(crash = false, stall = true); capture("stall_only") }
    @Test fun crash_and_stall() { Fixtures.selfReports(crash = true, stall = true); capture("crash_and_stall") }

    // --- updates -------------------------------------------------------------------------------
    @Test fun updates_up_to_date() { Fixtures.updatesUpToDate(); capture("updates_up_to_date") }
    @Test fun updates_available() { Fixtures.updatesAvailable(); capture("updates_available") }
    @Test fun updates_available_beta() { Fixtures.updatesAvailableBeta(); capture("updates_available_beta") }
    @Test fun updates_checking() { Fixtures.updatesChecking(); capture("updates_checking") }
    @Test fun updates_installing() { Fixtures.updatesInstalling(); capture("updates_installing") }
    @Test fun updates_unavailable() { Fixtures.updatesUnavailable(); capture("updates_unavailable") }
    @Test fun updates_never_checked() { capture("updates_never_checked") }

    // --- dialogs -------------------------------------------------------------------------------
    @Test fun dialog_problem() {
        val activity = hostTab(HomeFragment::class.java, Theme.Tab.HOME)
        home(activity).showProblemDialog(
            "Download failed",
            "The connection dropped before the file finished downloading.",
        )
        Shots.screen("home", "dialog_problem")
    }

    @Test fun dialog_dont_disturb_confirm() {
        val activity = hostTab(HomeFragment::class.java, Theme.Tab.HOME)
        home(activity).confirm(
            title = "Switch to Don't disturb?",
            message = "Every request shows a small overlay for 5 seconds. Doing nothing " +
                "approves it. Tap ✕ to deny.\n\nYour exceptions are ignored while this is selected.",
            positive = "Use Don't disturb",
            negative = "Cancel",
        ) { }
        Shots.screen("home", "dialog_dont_disturb")
    }

    // --- reduced motion ------------------------------------------------------------------------
    @Test fun reduced_motion_pending() {
        android.provider.Settings.Global.putFloat(
            Fixtures.app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f,
        )
        Fixtures.server(Fixtures.Server.RUNNING_HEALTHY)
        capture("reduced_motion")
    }

    private fun home(activity: FragmentActivity): HomeFragment =
        activity.supportFragmentManager.fragments.filterIsInstance<HomeFragment>().first()
}

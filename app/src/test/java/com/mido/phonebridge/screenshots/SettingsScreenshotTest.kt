package com.mido.phonebridge.screenshots

import android.app.Activity
import com.mido.phonebridge.OptionsFragment
import com.mido.phonebridge.RemoteDraft
import com.mido.phonebridge.ai.ModelCatalogue
import com.mido.phonebridge.ai.ModelProvisioner
import com.mido.phonebridge.ai.ModelRegistry
import com.mido.phonebridge.callers.CallerState
import com.mido.phonebridge.policy.AiBackend
import com.mido.phonebridge.policy.ExternalDialect
import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/** Settings tab (OptionsFragment): every state of WP07 section 10, in four variants each. */
class SettingsScreenshotTest(variant: Variant) : ShotTest(variant) {

    private val expected = ModelRegistry.EXPECTED_SIZE
    private val dialect get() = ExternalDialect.entries.first()

    private fun open(): Activity = hostTab(OptionsFragment::class.java, Theme.Tab.SETTINGS)

    private fun shoot(state: String) = capture(open(), "settings", state)

    private fun fragmentOf(a: Activity): OptionsFragment =
        (a as androidx.fragment.app.FragmentActivity).supportFragmentManager.fragments
            .filterIsInstance<OptionsFragment>().first()

    private fun click(a: Activity, text: String) {
        val v = findByTextIgnoreCase(a.window.decorView, text)
        assertNotNull("no view with text $text", v)
        v!!.performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @After fun resetFingerprint() = Fixtures.resetFingerprint()

    @Test fun fingerprint_temporary() { Fixtures.fingerprintTemporarilyUnavailable(); shoot("fingerprint_temporary") }

    @Test fun default_ai_off() = shoot("default_ai_off")

    @Test fun bridge_toggles_off() { Fixtures.autoStartAndSound(false, false); shoot("bridge_toggles_off") }

    @Test fun ai_local_ready() { Fixtures.aiBackend(AiBackend.LOCAL); Fixtures.modelFile(expected); shoot("ai_local_ready") }

    @Test fun ai_local_not_downloaded() { Fixtures.aiBackend(AiBackend.LOCAL); shoot("ai_local_not_downloaded") }

    @Test fun ai_local_incomplete() {
        Fixtures.aiBackend(AiBackend.LOCAL); Fixtures.modelFile(120L * 1024 * 1024); shoot("ai_local_incomplete")
    }

    @Test fun ai_local_wrong_file() {
        Fixtures.aiBackend(AiBackend.LOCAL); Fixtures.modelFile(expected + 1); shoot("ai_local_wrong_file")
    }

    @Test fun ai_local_downloading() {
        Fixtures.aiBackend(AiBackend.LOCAL)
        Fixtures.provisioner(true, ModelProvisioner.Progress(expected * 37 / 100, expected), "")
        shoot("ai_local_downloading")
    }

    @Test fun ai_local_failed() {
        Fixtures.aiBackend(AiBackend.LOCAL)
        Fixtures.provisioner(false, null, "HTTP 503 from huggingface.co")
        shoot("ai_local_failed")
    }

    @Test fun ai_remote_not_set() { Fixtures.aiBackend(AiBackend.EXTERNAL); shoot("ai_remote_not_set") }

    @Test fun ai_remote_not_listed() {
        Fixtures.externalModel("http://192.168.1.9:8888", "", null); shoot("ai_remote_not_listed")
    }

    @Test fun ai_remote_no_model() {
        Fixtures.aiBackend(AiBackend.EXTERNAL, "http://192.168.1.9:8888")
        val store = com.mido.phonebridge.policy.PolicyStore.from(Fixtures.app)
        // A dialect is stored together with a model; a blank model keeps the dialect and drops the name.
        store.set(store.get().withAiExternalModel("tmp", dialect).withAiExternalModel("", dialect))
        shoot("ai_remote_no_model")
    }

    @Test fun ai_remote_ready() {
        Fixtures.externalModel("http://192.168.1.9:8888", "qwen3:8b", dialect); shoot("ai_remote_ready")
    }

    @Test fun ai_remote_url_error() {
        Fixtures.aiBackend(AiBackend.EXTERNAL, "http://host:80/")
        val a = open()
        click(a, "Save")
        capture(a, "settings", "ai_remote_url_error")
    }

    @Test fun permissions_all_ok() {
        Fixtures.notificationListener(granted = true, connected = true)
        Fixtures.systemAccess(overlay = true, batteryExempt = true, postNotifications = true, phoneDnd = false)
        shoot("permissions_all_ok")
    }

    @Test fun permissions_attention() {
        Fixtures.notificationListener(granted = false, connected = false)
        Fixtures.systemAccess(overlay = false, batteryExempt = false, postNotifications = false, phoneDnd = true)
        shoot("permissions_attention")
    }

    @Test fun permissions_listener_not_connected() {
        Fixtures.notificationListener(granted = true, connected = false)
        Fixtures.systemAccess(overlay = true, batteryExempt = true, postNotifications = true, phoneDnd = false)
        shoot("permissions_listener_not_connected")
    }

    @Test fun debug_log_open() {
        Fixtures.debugLog(listOf(
            "09:40:00  server started on 192.168.1.100:8787",
            "09:40:01  gate mode PARANOID",
            "09:40:03  GET /help -> 200",
            "09:40:10  caller 100.64.0.7 verified as Work laptop",
            "09:40:21  GET /probe -> approval requested",
            "09:40:23  approved",
            "09:40:41  POST /contacts/search -> approval requested",
            "09:40:44  approved",
            "09:41:02  model provision: ready (328 MB)",
            "09:41:30  notification listener connected",
            "09:42:00  watchdog tick ok",
            "09:43:00  watchdog tick ok",
        ))
        shoot("debug_log_open")
    }

    @Test fun callers_empty() = shoot("callers_empty")

    @Test fun callers_list() {
        Fixtures.callers(
            "192.168.1.25" to CallerState.Verified("home laptop"),
            "192.168.1.77" to CallerState.Denied,
        )
        shoot("callers_list")
    }

    @Test fun callers_unreadable() { Fixtures.callersUnreadable(); shoot("callers_unreadable") }

    @Test fun policy_unreadable() { Fixtures.gateUnreadable(); shoot("policy_unreadable") }

    @Test fun confirm_redownload() {
        Fixtures.aiBackend(AiBackend.LOCAL); Fixtures.modelFile(expected)
        val a = open()
        click(a, "Re-download")
        Shots.screen("settings", "confirm_redownload")
    }

    @Test fun confirm_forget() {
        Fixtures.callers(
            "192.168.1.25" to CallerState.Verified("home laptop"),
            "192.168.1.77" to CallerState.Denied,
        )
        val a = open()
        click(a, "Forget")
        Shots.screen("settings", "confirm_forget")
    }

    @Test fun model_picker() {
        Fixtures.externalModel("http://192.168.1.9:8888", "", null)
        val a = open()
        val f = fragmentOf(a)
        f.draft = RemoteDraft(listed = ModelCatalogue(dialect, listOf("qwen3:8b", "llama3.1:8b", "mistral:7b")))
        f.repaint()
        click(a, "Model")
        Shots.screen("settings", "model_picker")
    }

    @Suppress("unused")
    private val keep = shadowOf(Fixtures.app)
}

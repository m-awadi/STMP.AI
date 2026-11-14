package com.mido.phonebridge.gate

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.mido.phonebridge.ApprovalActivity
import com.mido.phonebridge.Fingerprint
import com.mido.phonebridge.ApprovalGate
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import com.mido.phonebridge.screenshots.Fixtures
import com.mido.phonebridge.screenshots.GateFixtures
import com.mido.phonebridge.screenshots.findByText
import com.mido.phonebridge.screenshots.obscuredTap
import com.mido.phonebridge.ui.kit.HoldKey
import com.mido.phonebridge.ui.kit.ObscuredTouchGuard
import com.mido.phonebridge.ui.kit.PunchView
import com.mido.phonebridge.ui.tokens.Palettes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The gate's behaviour on the real Activity (WP03a section 18). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ApprovalActivityBehaviourTest {

    @Before fun utc() = Fixtures.freezeClock()
    @After fun reset() { GateFixtures.reset(); Fixtures.reset() }

    private val ssh get() = GateFixtures.ssh("ls -la")
    private val probe get() = GateFixtures.view(Verb.READ, "GET /probe")

    private fun all(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun ApprovalActivity.views() = all(window.decorView)
    private fun ApprovalActivity.text(s: String) = findByText(window.decorView, s)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private val pending get() = GateFixtures.lastPending!!

    @Test
    fun `HOLD shows a hold key and no APPROVE key`() {
        val a = GateFixtures.launch(ssh, "POST /ssh/kvm-box")
        assertEquals(1, a.views().count { it is HoldKey })
        assertNull(a.text("APPROVE"))
    }

    @Test
    fun `TAP shows an APPROVE key and no hold key`() {
        val a = GateFixtures.launch(probe, "GET /probe")
        assertNotNull(a.text("APPROVE"))
        assertEquals(0, a.views().count { it is HoldKey })
    }

    @Test
    fun `TAP_THEN_BIOMETRIC shows APPROVE and a tap does not resolve`() {
        ApprovalActivity.canAuthenticateForTest = BiometricManager.BIOMETRIC_SUCCESS
        val a = GateFixtures.launch(GateFixtures.ssh("ls", biometric = true), "POST /ssh/kvm-box")
        assertEquals(0, a.views().count { it is HoldKey })
        a.text("APPROVE")!!.performClick()
        idle()
        assertNull(pending.approved)
        assertTrue(ApprovalGate.isCurrent(GateFixtures.ID))
    }

    @Test
    fun `approving a request that already expired shows no APPROVED stamp`() {
        val a = GateFixtures.launch(probe, "GET /probe")
        // The request ends elsewhere (timeout / deny / replaced) before the tap lands.
        ApprovalGate.setCurrentForTest(null)
        a.text("APPROVE")!!.performClick()
        idle()
        assertEquals(0, a.views().count { it is PunchView })
        assertNull(pending.approved)
    }

    @Test
    fun `deny resolves with one tap`() {
        val a = GateFixtures.launch(ssh, "POST /ssh/kvm-box")
        a.text("DENY")!!.performClick()
        assertEquals(false, pending.approved)
        assertEquals(1, a.views().count { it is PunchView })
    }

    @Test
    fun `deny is signal for execute and delete, outline otherwise`() {
        val p = Palettes.of(Fixtures.app)
        fun fill(a: ApprovalActivity): Int? {
            val face = a.text("DENY")!!.background.current
            if (face !is LayerDrawable) return null
            return (face.getDrawable(face.numberOfLayers - 1) as GradientDrawable).color?.defaultColor
        }
        assertEquals(p.signal, fill(GateFixtures.launch(ssh, "POST /ssh/kvm-box")))
        GateFixtures.reset()
        assertEquals(p.signal, fill(GateFixtures.launch(GateFixtures.view(Verb.DELETE, "DELETE /audit"), "DELETE /audit")))
        GateFixtures.reset()
        val outline = GateFixtures.launch(probe, "GET /probe").text("DENY")!!.background.current
        assertTrue("an outline key has no filled face", outline is GradientDrawable)
    }

    @Test
    fun `deny is never smaller than approve`() {
        for ((view, endpoint) in listOf(ssh to "POST /ssh/kvm-box", probe to "GET /probe")) {
            val a = GateFixtures.launch(view, endpoint)
            val deny = a.text("DENY")!!
            val approve = a.views().firstOrNull { it is HoldKey } ?: a.text("APPROVE")!!
            assertTrue("$endpoint: deny ${deny.width}x${deny.height} vs ${approve.width}x${approve.height}",
                deny.width.toLong() * deny.height >= approve.width.toLong() * approve.height)
            GateFixtures.reset()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `back denies once, and back during the punch never decides again`() {
        val a = GateFixtures.launch(ssh, "POST /ssh/kvm-box")
        a.onBackPressed()
        assertEquals(false, pending.approved)
        assertFalse("back-deny must stay on screen for the DENIED punch and receipt (D16)", a.isFinishing)
        val decidedAt = pending.decidedAtMs
        a.onBackPressed()
        assertEquals(false, pending.approved)
        assertEquals("a second back must not resolve again", decidedAt, pending.decidedAtMs)
        assertEquals(1, a.views().count { it is PunchView })
        assertFalse(a.isFinishing)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `back-deny shows the receipt and then closes`() {
        val a = GateFixtures.launch(ssh, "POST /ssh/kvm-box")
        a.onBackPressed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RECEIPT_DONE_MS))
        assertTrue("the receipt's onDone finishes the gate", a.isFinishing)
    }

    @Test
    fun `a fingerprint gate asks for nothing before the fingerprint, and a stray permission result does not approve`() {
        ApprovalActivity.canAuthenticateForTest = BiometricManager.BIOMETRIC_SUCCESS
        val a = GateFixtures.launch(GateFixtures.contacts(biometric = true), "GET /contacts")
        a.text("APPROVE")!!.performClick()
        idle()
        assertNull("no contacts prompt before the fingerprint (D13)", shadowOf(a).lastRequestedPermission)
        a.onRequestPermissionsResult(
            ApprovalActivity.REQ_CONTACTS,
            arrayOf(android.Manifest.permission.READ_CONTACTS),
            intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED),
        )
        idle()
        assertNull(pending.approved)
        assertTrue(ApprovalGate.isCurrent(GateFixtures.ID))
    }

    @Test
    fun `on a tap gate the contacts grant approves`() {
        val a = GateFixtures.launch(GateFixtures.contacts(), "GET /contacts")
        a.text("APPROVE")!!.performClick()
        assertNotNull(shadowOf(a).lastRequestedPermission)
        a.onRequestPermissionsResult(
            ApprovalActivity.REQ_CONTACTS,
            arrayOf(android.Manifest.permission.READ_CONTACTS),
            intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED),
        )
        assertEquals(true, pending.approved)
    }

    private companion object {
        /** Punch 720 ms + receipt feed 240 ms + dwell 1 500 ms, with margin. */
        const val RECEIPT_DONE_MS = 4_000L
    }

    @Test
    @Suppress("DEPRECATION")
    fun `back while the punch plays does not resolve again`() {
        val a = GateFixtures.launch(probe, "GET /probe")
        a.text("APPROVE")!!.performClick()
        assertEquals(true, pending.approved)
        a.onBackPressed()
        assertEquals(true, pending.approved)
        assertEquals(1, a.views().count { it is PunchView })
        assertFalse(a.isFinishing)
    }

    @Test
    fun `a password request is secure and has no exception row`() {
        val offer = ApprovalGate.ExceptionOffer(Rule("ssh", Verb.EXECUTE, Mode.DONT_DISTURB), "ALWAYS NOTIFY")
        val a = GateFixtures.launch(GateFixtures.ssh("sudo -S true", sudo = true, offer = offer), "POST /ssh/kvm-box")
        assertTrue((a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
        assertNull(a.text("ALSO ADD EXCEPTION"))
    }

    @Test
    fun `on a hold gate the exception tap only arms, and the hold approves with it`() {
        val rule = Rule("ssh", Verb.EXECUTE, Mode.DONT_DISTURB)
        val a = GateFixtures.launch(GateFixtures.ssh("ls", offer = ApprovalGate.ExceptionOffer(rule, "ALWAYS NOTIFY")), "POST /ssh/kvm-box")
        a.text("ALSO ADD EXCEPTION")!!.performClick()
        assertNull(pending.approved)
        assertNotNull(a.text("ALSO ADD EXCEPTION · ON"))
        val hold = a.views().first { it is HoldKey }
        assertTrue(hold.performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, null))
        assertEquals(true, pending.approved)
        assertEquals(rule, pending.exceptionRule)
        assertEquals(1, a.views().count { it is PunchView })
    }

    @Test
    fun `an obscured touch on deny does not resolve and shows the banner`() {
        val a = GateFixtures.launch(ssh, "POST /ssh/kvm-box")
        val deny = a.text("DENY")!!
        val loc = IntArray(2).also { deny.getLocationInWindow(it) }
        obscuredTap(a, loc[0] + deny.width / 2f, loc[1] + deny.height / 2f)
        idle()
        assertNull(pending.approved)
        assertTrue(a.text(ObscuredTouchGuard.BANNER_TEXT)!!.isShown)
    }

    @Test
    fun `while another app covers the screen the keys look disabled, and come back when it clears`() {
        val a = GateFixtures.launch(ssh, "POST /ssh/kvm-box")
        val keyRow = a.text("DENY")!!.parent as View
        assertEquals(1f, keyRow.alpha, 0f)
        obscuredTap(a)
        idle()
        assertEquals(GateScreen.OBSCURED_KEY_ALPHA, keyRow.alpha, 0f)
        val guard = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0 })
        val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { x = 5f; y = 5f; pressure = 1f; size = 1f })
        val t = android.os.SystemClock.uptimeMillis()
        guard.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0))
        idle()
        assertEquals(1f, keyRow.alpha, 0f)
    }

    @Test
    fun `the exception key says what it does`() {
        val rule = Rule("contacts", Verb.READ, Mode.DONT_DISTURB)
        val tap = GateFixtures.launch(
            GateFixtures.contacts(offer = ApprovalGate.ExceptionOffer(rule, "ALWAYS NOTIFY")), "GET /contacts",
        )
        assertNotNull(tap.text(GateCopy.EXCEPTION_APPROVE_KEY))
        assertNull(tap.text("ALWAYS NOTIFY"))
    }

    @Test
    fun `a hold whose release is covered by another window does not approve`() {
        val a = GateFixtures.launch(ssh, "POST /ssh/kvm-box")
        val hold = a.views().first { it is HoldKey }
        val guard = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val loc = IntArray(2).also { hold.getLocationInWindow(it) }
        val x = loc[0] + hold.width / 2f
        val y = loc[1] + hold.height / 2f
        val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0 })
        val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val t = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, flags: Int) = guard.dispatchTouchEvent(
            android.view.MotionEvent.obtain(t, android.os.SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags),
        )
        send(android.view.MotionEvent.ACTION_DOWN, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        send(android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertNull(pending.approved)
        assertTrue(ApprovalGate.isCurrent(GateFixtures.ID))
        assertTrue(a.text(ObscuredTouchGuard.BANNER_TEXT)!!.isShown)
    }

    // --- BIO: a sensor fault never downgrades a fingerprint approval ------------------------------

    private fun fingerprintGate(code: Int, deviceSecure: Boolean): ApprovalActivity {
        ApprovalActivity.canAuthenticateForTest = code
        Fingerprint.deviceSecureForTest = deviceSecure
        return GateFixtures.launch(GateFixtures.contacts(biometric = true), "GET /contacts")
    }

    @Test
    fun `a locked-out sensor with a phone PIN says to use the PIN and keeps APPROVE live`() {
        val a = fingerprintGate(Fingerprint.BIOMETRIC_ERROR_LOCKOUT, deviceSecure = true)
        assertTrue(a.text(GateCopy.BIO_USE_PIN)!!.isShown)
        assertNull("no plain-tap wording", a.text("plain tap"))
    }

    @Test
    fun `APPROVE in that state asks for BIOMETRIC_STRONG or DEVICE_CREDENTIAL and does not approve by itself`() {
        val a = fingerprintGate(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE, deviceSecure = true)
        a.text("APPROVE")!!.performClick()
        idle()
        assertEquals(Fingerprint.fallbackSpec(), ApprovalActivity.lastCredentialPromptForTest)
        assertNull("asking is not approving", pending.approved)
        assertTrue(ApprovalGate.isCurrent(GateFixtures.ID))
    }

    @Test
    fun `with no screen lock a faulting sensor fails closed with no prompt and no approval`() {
        val a = fingerprintGate(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE, deviceSecure = false)
        assertTrue(a.text(GateCopy.BIO_LOCKED)!!.isShown)
        a.text("APPROVE")!!.performClick()
        idle()
        assertNull(ApprovalActivity.lastCredentialPromptForTest)
        assertNull(pending.approved)
    }

    @Test
    fun `a locked-out fingerprint is re-checked and the banner goes when it returns`() {
        val a = fingerprintGate(Fingerprint.BIOMETRIC_ERROR_LOCKOUT, deviceSecure = true)
        assertTrue(a.text(GateCopy.BIO_USE_PIN)!!.isShown)
        ApprovalActivity.canAuthenticateForTest = BiometricManager.BIOMETRIC_SUCCESS
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GateArming.BIO_RECHECK_MS + 500))
        assertFalse(a.text(GateCopy.BIO_USE_PIN)!!.isShown)
    }

    @Test
    fun `the timer reads ready then auto-deny`() {
        val a = GateFixtures.launch(probe, "GET /probe", enabledAtOffsetMs = 1_000L, clockOffsetMs = 0L)
        assertNotNull(a.text("ready in 1 s"))
        GateClock.nowMs = { GateFixtures.NOW + 1_000L }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertNotNull(a.text("auto-deny in 1:00"))
    }
}

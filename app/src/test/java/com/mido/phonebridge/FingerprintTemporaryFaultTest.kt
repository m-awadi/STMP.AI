package com.mido.phonebridge

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.debug.ScreenshotHostActivity
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.screenshots.Fixtures
import com.mido.phonebridge.ui.Theme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BIO: merely opening Home or Settings while the sensor is locked out or faulting used to turn
 * "Fingerprint for approvals" off for good. These fail on the old code (the stored value flips).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class FingerprintTemporaryFaultTest {

    @Before fun before() { Fixtures.dropSingletons(); Fixtures.freezeClock() }

    @After fun after() {
        Fingerprint.canAuthenticateForTest = null
        Fingerprint.deviceSecureForTest = null
        Fixtures.reset()
    }

    private fun open(fragment: Class<*>, tab: Theme.Tab): FragmentActivity {
        val intent = Intent(Fixtures.app, ScreenshotHostActivity::class.java)
            .putExtra(ScreenshotHostActivity.EXTRA_FRAGMENT, fragment.name)
            .putExtra(ScreenshotHostActivity.EXTRA_TAB, tab.name)
        return Robolectric.buildActivity(ScreenshotHostActivity::class.java, intent).setup().get()
    }

    private fun View.all(): Sequence<View> {
        val self = this
        return sequence {
            yield(self)
            if (self is ViewGroup) for (i in 0 until self.childCount) yieldAll(self.getChildAt(i).all())
        }
    }

    private fun texts(a: FragmentActivity) =
        a.window.decorView.all().filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private val temporaryCodes = listOf(
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        Fingerprint.BIOMETRIC_ERROR_LOCKOUT,
        Fingerprint.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
    )


    @Test
    fun `Home in a temporary fault leaves the setting on and writes nothing`() {
        for (code in temporaryCodes) {
            Fixtures.dropSingletons()
            Fingerprint.canAuthenticateForTest = code
            val store = PolicyStore.from(Fixtures.app)
            store.set(store.get().withFingerprintForModals(true))
            val a = open(HomeFragment::class.java, Theme.Tab.HOME)
            assertEquals("code $code", true, PolicyStore.from(Fixtures.app).get().fingerprintForModals)
            assertTrue(texts(a).any { it.contains("Use your phone PIN to approve.") })
            a.finish()
        }
    }

    @Test
    fun `Settings in a temporary fault leaves the setting on and writes nothing`() {
        for (code in temporaryCodes) {
            Fixtures.dropSingletons()
            Fingerprint.canAuthenticateForTest = code
            val store = PolicyStore.from(Fixtures.app)
            store.set(store.get().withFingerprintForModals(true))
            val a = open(OptionsFragment::class.java, Theme.Tab.SETTINGS)
            assertEquals("code $code", true, PolicyStore.from(Fixtures.app).get().fingerprintForModals)
            assertTrue(texts(a).any { it.contains("Use your phone PIN to approve.") })
            a.finish()
        }
    }

    @Test
    fun `Home and Settings still force the setting off when the phone can never read a fingerprint`() {
        for (code in listOf(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)) {
            for (page in listOf(HomeFragment::class.java to Theme.Tab.HOME, OptionsFragment::class.java to Theme.Tab.SETTINGS)) {
                Fixtures.dropSingletons()
                Fingerprint.canAuthenticateForTest = code
                val store = PolicyStore.from(Fixtures.app)
                store.set(store.get().withFingerprintForModals(true))
                open(page.first, page.second).finish()
                assertEquals("code $code on ${page.first.simpleName}", false, PolicyStore.from(Fixtures.app).get().fingerprintForModals)
            }
        }
    }

    private fun requestOnHome(want: Boolean, stored: Boolean, deviceSecure: Boolean): List<String> {
        Fixtures.dropSingletons()
        Fingerprint.canAuthenticateForTest = Fingerprint.BIOMETRIC_ERROR_LOCKOUT
        Fingerprint.deviceSecureForTest = deviceSecure
        val store = PolicyStore.from(Fixtures.app)
        store.set(store.get().withFingerprintForModals(stored))
        val a = open(HomeFragment::class.java, Theme.Tab.HOME)
        val fragment = a.supportFragmentManager.fragments.first()
        val errors = mutableListOf<String>()
        com.mido.phonebridge.ui.FingerprintSetting.request(fragment, want, onError = { errors += it }) {}
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        return errors
    }

    @Test
    fun `turning it ON while the sensor is away is refused and writes nothing`() {
        val errors = requestOnHome(want = true, stored = false, deviceSecure = true)
        assertEquals(listOf(Fingerprint.TURN_ON_NEEDS_FINGERPRINT), errors)
        assertEquals(false, PolicyStore.from(Fixtures.app).get().fingerprintForModals)
    }

    @Test
    fun `turning it OFF with no screen lock says so and leaves it on`() {
        val errors = requestOnHome(want = false, stored = true, deviceSecure = false)
        assertEquals(listOf(Fingerprint.NO_SCREEN_LOCK), errors)
        assertEquals(true, PolicyStore.from(Fixtures.app).get().fingerprintForModals)
    }

    @Test
    fun `turning it OFF with a PIN asks for the PIN and does not change the setting until it succeeds`() {
        val errors = requestOnHome(want = false, stored = true, deviceSecure = true)
        assertEquals(emptyList<String>(), errors)
        assertEquals(true, PolicyStore.from(Fixtures.app).get().fingerprintForModals)
    }
}

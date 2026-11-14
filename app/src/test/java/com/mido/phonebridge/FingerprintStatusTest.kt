package com.mido.phonebridge

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.mido.phonebridge.Fingerprint.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** BIO: the status split. Only "never" is permanent; everything that can clear is temporary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FingerprintStatusTest {

    private fun temporary(code: Int) {
        val s = Fingerprint.classify(code)
        assertTrue("code $code must be temporary but was $s", s is Status.TemporarilyUnavailable)
    }

    @Test fun `success is usable`() = assertEquals(Status.Usable, Fingerprint.classify(BiometricManager.BIOMETRIC_SUCCESS))

    @Test fun `nothing enrolled is permanent`() {
        assertTrue(Fingerprint.classify(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) is Status.PermanentlyUnusable)
    }

    @Test fun `no hardware is permanent`() {
        assertTrue(Fingerprint.classify(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) is Status.PermanentlyUnusable)
    }

    @Test fun `hardware unavailable is temporary`() = temporary(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
    @Test fun `lockout is temporary`() = temporary(Fingerprint.BIOMETRIC_ERROR_LOCKOUT)
    @Test fun `permanent lockout is temporary`() = temporary(Fingerprint.BIOMETRIC_ERROR_LOCKOUT_PERMANENT)
    @Test fun `security update required is temporary`() = temporary(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED)
    @Test fun `unsupported is temporary`() = temporary(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED)
    @Test fun `status unknown is temporary`() = temporary(BiometricManager.BIOMETRIC_STATUS_UNKNOWN)
    @Test fun `a code this build has never heard of is temporary`() = temporary(4242)

    @Test fun `lockout codes are the platform values`() {
        assertEquals(7, Fingerprint.BIOMETRIC_ERROR_LOCKOUT)
        assertEquals(9, Fingerprint.BIOMETRIC_ERROR_LOCKOUT_PERMANENT)
        assertEquals(BiometricPrompt.ERROR_LOCKOUT, Fingerprint.BIOMETRIC_ERROR_LOCKOUT)
        assertEquals(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, Fingerprint.BIOMETRIC_ERROR_LOCKOUT_PERMANENT)
    }

    @Test fun `an exception is temporary when the probe does not say no hardware`() {
        assertTrue(Fingerprint.classify(null) { BiometricManager.BIOMETRIC_SUCCESS } is Status.TemporarilyUnavailable)
        assertTrue(Fingerprint.classify(null) { null } is Status.TemporarilyUnavailable)
        assertTrue(Fingerprint.classify(null) { BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE } is Status.TemporarilyUnavailable)
    }

    @Test fun `an exception is permanent only when the probe also says no hardware`() {
        assertTrue(Fingerprint.classify(null) { BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE } is Status.PermanentlyUnusable)
    }

    @Test fun `every temporary reason says to use the phone PIN and never a plain tap`() {
        val codes = listOf(
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE, Fingerprint.BIOMETRIC_ERROR_LOCKOUT,
            Fingerprint.BIOMETRIC_ERROR_LOCKOUT_PERMANENT, 4242,
        )
        for (c in codes) {
            val reason = Fingerprint.reasonOf(Fingerprint.classify(c))!!
            assertTrue(reason, reason.contains("Use your phone PIN to approve."))
            assertFalse(reason, reason.contains("plain tap"))
        }
    }

    @Test fun `the permanent reasons keep the plain-tap rule of D66`() {
        assertTrue(Fingerprint.reasonOf(Fingerprint.classify(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))!!.contains("plain tap"))
        assertTrue(Fingerprint.reasonOf(Fingerprint.classify(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))!!.contains("plain tap"))
        assertNull(Fingerprint.reasonOf(Status.Usable))
    }

    @Test fun `the fallback asks for a strong biometric or the device credential`() {
        val want = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        assertEquals(want, Fingerprint.FALLBACK_AUTHENTICATORS)
        for (sdk in listOf(Build.VERSION_CODES.O, Build.VERSION_CODES.O_MR1, Build.VERSION_CODES.R, 33, 35)) {
            assertEquals(Fingerprint.PromptSpec(want, false), Fingerprint.fallbackSpec(sdk))
        }
    }

    @Test fun `on Android 9 and 10 the legacy credential flag is used instead`() {
        for (sdk in listOf(Build.VERSION_CODES.P, Build.VERSION_CODES.Q)) {
            assertEquals(Fingerprint.PromptSpec(null, true), Fingerprint.fallbackSpec(sdk))
        }
    }

    @Test fun `a cancelled or failed credential prompt never approves`() {
        var approved = 0
        val reasons = mutableListOf<String>()
        val cb = Fingerprint.CredentialCallback({ approved++ }) { reasons += it }
        for (code in listOf(
            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_TIMEOUT,
        )) cb.onAuthenticationError(code, "e$code")
        cb.onAuthenticationFailed()
        assertEquals(0, approved)
        assertEquals(8, reasons.size)
    }
}

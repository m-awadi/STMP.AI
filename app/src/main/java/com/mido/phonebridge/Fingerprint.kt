package com.mido.phonebridge

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The fingerprint the approval modal asks for — availability, and the prompt itself.
 *
 * **Why this is its own object.** Three places need to know whether a fingerprint can be asked
 * for: this screen (to enable or disable the switch), the modal (to approve), and the service (to
 * decide whether a SECURE request can demand one at all). Each was doing its own
 * `canAuthenticate` call with its own reading of the error codes, which is how two screens end up
 * disagreeing about whether the phone has a fingerprint.
 *
 * **Three states, not two.** A phone with no sensor, or no enrolled finger, will not get one by
 * waiting: that is [Status.PermanentlyUnusable], and only that may switch the setting off (D66).
 * A locked-out or faulting sensor comes back by itself: that is [Status.TemporarilyUnavailable],
 * and it must NEVER write the setting. Treating it as permanent let anyone who could open Home
 * after five wrong fingers turn off "Fingerprint for approvals" and make every SECURE approval a
 * plain tap. While the sensor is away the owner approves with the phone PIN instead
 * ([FALLBACK_AUTHENTICATORS]); with no screen lock at all there is nothing to fall back on, so it
 * fails closed.
 *
 * **`BIOMETRIC_STRONG` for the fingerprint itself, and that is deliberate.** This is the same
 * authenticator set the vault and the approval modal use, so the three cannot disagree. It also
 * means a device with only a weak (Class 2) sensor reports unavailable rather than offering a
 * prompt the vault would reject.
 */
object Fingerprint {

    private const val TAG = "Fingerprint"

    /** `BiometricPrompt.ERROR_LOCKOUT`: too many attempts, clears by itself. */
    const val BIOMETRIC_ERROR_LOCKOUT = 7

    /** `BiometricPrompt.ERROR_LOCKOUT_PERMANENT`: locked until the phone is unlocked with the PIN. */
    const val BIOMETRIC_ERROR_LOCKOUT_PERMANENT = 9

    /** The sensor is away: a fingerprint OR the phone PIN. Never the PIN alone while the sensor works. */
    const val FALLBACK_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    const val UNAVAILABLE_USE_PIN =
        "Fingerprint is unavailable right now. Use your phone PIN to approve."
    const val LOCKED_USE_PIN =
        "Too many failed fingerprint attempts, so the sensor is locked for a moment. " +
            "Use your phone PIN to approve."
    const val NO_SCREEN_LOCK =
        "Fingerprint is unavailable right now, and this phone has no PIN, pattern or password " +
            "to use instead. Set a screen lock in Android settings, or deny."
    const val TURN_ON_NEEDS_FINGERPRINT =
        "Fingerprint is unavailable right now, so it cannot be turned on yet. Try again in a moment."

    private const val NOT_ENROLLED =
        "No fingerprint is set up on this phone. Add one in Android settings, then " +
            "this switch becomes usable. Until then approvals use a plain tap."
    private const val NO_SENSOR =
        "This phone has no fingerprint sensor, so \"Fingerprint for approvals\" cannot be turned on. " +
            "Approvals use a plain tap."

    /** Whether a usable fingerprint exists, and if not, the sentence a human can act on. */
    sealed interface Status {
        /** A strong biometric is enrolled and the hardware is answering. */
        data object Usable : Status

        /**
         * This phone cannot read a fingerprint and waiting will not change that (nothing enrolled,
         * no sensor). The only state that may force the setting off.
         */
        data class PermanentlyUnusable(val reason: String) : Status

        /**
         * The sensor is locked out or faulting and comes back by itself. The setting stays as it
         * is; approvals ask for the phone PIN meanwhile. [reason] says so in plain words.
         */
        data class TemporarilyUnavailable(val reason: String) : Status
    }

    /**
     * Test seams: stand in for `canAuthenticate(BIOMETRIC_STRONG)`, for the probe used after an
     * exception, and for "does the phone have a screen lock". Null in production.
     */
    @androidx.annotation.VisibleForTesting
    @Volatile internal var canAuthenticateForTest: Int? = null

    @androidx.annotation.VisibleForTesting
    @Volatile internal var deviceSecureForTest: Boolean? = null

    /**
     * The one mapping from a `canAuthenticate` code to a [Status]. [code] is null when the call
     * threw (some OEM builds throw instead of returning a code); [weakProbe] is then asked, and
     * only a "no hardware" answer from it makes the fault permanent.
     */
    fun classify(code: Int?, weakProbe: () -> Int? = { null }): Status {
        if (code == null) {
            return if (weakProbe() == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
                Status.PermanentlyUnusable(NO_SENSOR)
            } else {
                Status.TemporarilyUnavailable(UNAVAILABLE_USE_PIN)
            }
        }
        return when (code) {
            BiometricManager.BIOMETRIC_SUCCESS -> Status.Usable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Status.PermanentlyUnusable(NOT_ENROLLED)
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Status.PermanentlyUnusable(NO_SENSOR)
            BIOMETRIC_ERROR_LOCKOUT, BIOMETRIC_ERROR_LOCKOUT_PERMANENT ->
                Status.TemporarilyUnavailable(LOCKED_USE_PIN)
            // HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, UNSUPPORTED, STATUS_UNKNOWN and any code
            // this build has never heard of: not proof that a fingerprint can never work.
            else -> Status.TemporarilyUnavailable(UNAVAILABLE_USE_PIN)
        }
    }

    /** Read the current capability. Cheap enough to call from `render()`. Never writes anything. */
    fun status(activity: FragmentActivity): Status {
        val seam = canAuthenticateForTest
        if (seam != null) return classify(seam)
        val code = try {
            BiometricManager.from(activity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } catch (e: Exception) {
            Log.w(TAG, "canAuthenticate threw: ${e.message}")
            null
        }
        return classify(code) {
            try {
                BiometricManager.from(activity)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** True when the phone has a PIN, pattern or password the system prompt can ask for. */
    fun deviceCredentialReady(ctx: Context): Boolean {
        deviceSecureForTest?.let { return it }
        val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard?.isDeviceSecure == true
    }

    /**
     * How a prompt that accepts the fingerprint OR the PIN is asked for on this API level.
     * Android 9 and 10 reject the STRONG-or-credential combination, so there the older
     * "device credential allowed" flag is used instead.
     */
    data class PromptSpec(val authenticators: Int?, val legacyCredentialFlag: Boolean)

    fun fallbackSpec(sdk: Int = Build.VERSION.SDK_INT): PromptSpec =
        if (sdk == Build.VERSION_CODES.P || sdk == Build.VERSION_CODES.Q) {
            PromptSpec(authenticators = null, legacyCredentialFlag = true)
        } else {
            PromptSpec(authenticators = FALLBACK_AUTHENTICATORS, legacyCredentialFlag = false)
        }

    /** Apply [fallbackSpec] to a prompt builder. A credential prompt carries no negative button. */
    @Suppress("DEPRECATION")
    fun applyFallback(builder: BiometricPrompt.PromptInfo.Builder, spec: PromptSpec = fallbackSpec()):
        BiometricPrompt.PromptInfo.Builder {
        if (spec.legacyCredentialFlag) builder.setDeviceCredentialAllowed(true)
        spec.authenticators?.let { builder.setAllowedAuthenticators(it) }
        return builder
    }

    /**
     * Only a fingerprint or the phone PIN actually succeeding approves. Cancel, a dismissed
     * prompt, lockout and every other error do nothing: the request keeps waiting for its own
     * timeout, and nothing here ever turns an error into an approval.
     */
    class CredentialCallback(
        private val onApproved: () -> Unit,
        private val onNotApproved: (String) -> Unit = {},
    ) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onApproved()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            Log.i(TAG, "credential prompt error $errorCode: $errString")
            onNotApproved(errString.toString())
        }

        override fun onAuthenticationFailed() {
            Log.i(TAG, "credential prompt: read not recognised, retry available")
        }
    }

    /**
     * Ask for the fingerprint, and only run [onConfirmed] when one was actually read.
     *
     * With [allowDeviceCredential] (the sensor is temporarily unavailable) the phone PIN also
     * counts, and a phone with no screen lock fails closed. Only the OFF direction is ever asked
     * this way: turning the setting ON still needs a working fingerprint.
     *
     * **No `CryptoObject` here, unlike the vault.** This prompt authorises a SETTING rather than
     * unwrapping a key, so there is no cipher to bind. That is what makes it safe to also allow
     * turning the setting OFF: the same prompt answers both directions.
     */
    fun confirm(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onConfirmed: () -> Unit,
        onFailed: (String) -> Unit,
        allowDeviceCredential: Boolean = false,
    ) {
        if (allowDeviceCredential) {
            if (!deviceCredentialReady(activity)) {
                onFailed(NO_SCREEN_LOCK)
                return
            }
            val info = applyFallback(
                BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle),
            ).build()
            BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                CredentialCallback(onConfirmed) { why -> onFailed(why) },
            ).authenticate(info)
            return
        }
        val unusable = status(activity)
        if (unusable != Status.Usable) {
            // Fails closed, and never shows a prompt that could not succeed.
            Log.w(TAG, "confirm: biometric unavailable ($unusable)")
            onFailed(reasonOf(unusable) ?: "fingerprint unavailable")
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    onConfirmed()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // A cancelled prompt is a decision, not a fault: the setting simply does not
                    // change, and saying anything would be nagging about something they cancelled.
                    Log.i(TAG, "confirm error $errorCode: $errString")
                    onFailed(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // One bad read: the prompt stays up for a retry, so there is nothing to say.
                    Log.i(TAG, "confirm: read not recognised, retry available")
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info)
    }

    /** The sentence for a state, or null when it is [Status.Usable]. */
    fun reasonOf(status: Status): String? = when (status) {
        Status.Usable -> null
        is Status.PermanentlyUnusable -> status.reason
        is Status.TemporarilyUnavailable -> status.reason
    }
}

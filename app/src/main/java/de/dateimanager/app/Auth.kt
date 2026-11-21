package de.dateimanager.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi

object Auth {

    fun activityOf(ctx: Context): Activity? {
        var c: Context = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    fun biometricAvailable(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)

    @RequiresApi(28)
    fun biometric(activity: Activity, title: String, onOk: () -> Unit, onFail: () -> Unit) {
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setNegativeButton("PIN verwenden", activity.mainExecutor) { _, _ -> onFail() }
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                    onOk()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onFail()
                }
            }
        )
    }

    /** Bestätigung über die Bildschirmsperre des Handys (PIN, Muster, Passwort, Fingerabdruck). */
    fun deviceCredentialIntent(ctx: Context): Intent? {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) return null
        return km.createConfirmDeviceCredentialIntent(
            "Tresor",
            "Bestätige mit der Sperre deines Handys, um eine neue Tresor-PIN zu setzen."
        )
    }
}

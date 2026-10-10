package com.jarvis.telefono.cassaforte

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager

/**
 * Quanto dura uno sblocco: dopo l'impronta o il PIN i segreti si possono MOSTRARE per [durataMs], poi si richiede.
 * Pura (orologio sostituibile), provata sulla JVM.
 */
class FinestraSblocco(private val durataMs: Long = 60_000L, private val adesso: () -> Long = System::currentTimeMillis) {
    @Volatile private var fino = 0L
    fun sbloccato(): Boolean = adesso() < fino
    fun segnaSbloccato() { fino = adesso() + durataMs }
    fun blocca() { fino = 0L }
}

/**
 * Impronta o PIN del telefono prima di mostrare un segreto, con le API di sistema (nessuna libreria in più:
 * androidx.biometric non c'è nel progetto). Android 10+ BiometricPrompt con il PIN come alternativa;
 * Android 8-9 la schermata di conferma del blocco schermo ([intentConfermaVecchi], da lanciare con startActivityForResult).
 */
object Sblocco {
    val finestra = FinestraSblocco()

    /** Il telefono ha un blocco schermo (PIN, sequenza, password)? Senza, i segreti non si mostrano. */
    fun telefonoProtetto(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Chiede impronta o PIN. [ok] sul thread principale se riuscito; [no] con il motivo in italiano.
     * Su Android 8-9 chiama [no] con «vecchio»: la schermata usa [intentConfermaVecchi].
     */
    fun chiedi(activity: Activity, titolo: String, ok: () -> Unit, no: (String) -> Unit) {
        if (finestra.sbloccato()) { ok(); return }
        if (!telefonoProtetto(activity)) { no("Imposta un blocco schermo (PIN o impronta) per vedere i segreti."); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { no("vecchio"); return }
        val b = android.hardware.biometrics.BiometricPrompt.Builder(activity).setTitle(titolo)
            .setDescription("JBoss: per mostrare password e token")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            b.setDeviceCredentialAllowed(true)
        }
        // 0.4.0: mai chiudere l'app per uno sblocco: se il sistema rifiuta (permesso, telefono senza sensore) si dice perché.
        runCatching { b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                finestra.segnaSbloccato(); ok()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                no(errString?.toString() ?: "Sblocco annullato.")
            }
        }) }.onFailure { no("Lo sblocco con impronta o PIN non è partito (${it.javaClass.simpleName}).") }
    }

    /** Android 8-9: la conferma del blocco schermo. Al RESULT_OK chiamare [finestra].segnaSbloccato(). */
    @Suppress("DEPRECATION")
    fun intentConfermaVecchi(context: Context, titolo: String): Intent? =
        context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(titolo, "JBoss: per mostrare password e token")

    /** Da chiamare in onCreate delle schermate con segreti: niente screenshot, niente anteprima nelle app recenti. */
    fun proteggiSchermo(activity: Activity) {
        activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(false)
    }
}

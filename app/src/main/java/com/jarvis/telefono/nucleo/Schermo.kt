package com.jarvis.telefono.nucleo

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * 0.4.2 (2026-10-08, Boss: «non esegue le richieste»). Prova del banco: «apri WhatsApp» a schermo spento finiva in
 * errore dopo 10 s, perché un'app non viene in primo piano con lo schermo spento. Prima di ogni azione che usa lo
 * schermo JBoss lo accende (wake lock con ACQUIRE_CAUSES_WAKEUP, 15 s, poi lo schermo segue il timeout di sistema).
 * Un blocco sicuro (PIN, impronta) JBoss non lo apre: lo dice a Boss invece di fallire in silenzio.
 */
object Schermo {
    private const val TAG = "JarvisNucleo"

    /** Le azioni che non hanno bisogno dello schermo acceso. */
    private val SENZA_SCHERMO = setOf(
        "emergenza", "registro", "registro_azioni", "stato_tecnico", "modo_tecnico", "elenca_app", "cerca_contatto", "attendi",
    )

    fun serve(azione: String): Boolean = azione.isNotEmpty() && azione !in SENZA_SCHERMO

    /** true = lo schermo era spento ed è stato acceso adesso. */
    @Suppress("DEPRECATION")
    fun accendi(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        if (pm.isInteractive) return false
        return runCatching {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "jboss:sveglia",
            ).acquire(15_000L)
            Log.i(TAG, "schermo spento: lo accendo per l'azione")
            true
        }.getOrDefault(false)
    }

    /** Il telefono è bloccato con PIN/impronta (le app non vengono in primo piano finché Boss non lo sblocca). */
    fun bloccato(context: Context): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java) ?: return false
        return km.isKeyguardLocked && km.isDeviceSecure
    }

    const val MESSAGGIO_BLOCCATO = "Il telefono è bloccato: sbloccalo e ridimmelo, da bloccato le app non si aprono."
}

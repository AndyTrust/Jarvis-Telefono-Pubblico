package com.jarvis.telefono

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Riaccende Jarvis dopo che il telefono si è riavviato.
 *
 * Serve perché l'app deve vivere dentro il telefono: se l'utente riavvia lo S24 e
 * Jarvis non torna su da solo, l'app non è autonoma — è un programma che
 * qualcuno deve ricordarsi di aprire.
 *
 * C'è un limite di Android che non si aggira, e va detto invece di nascosto.
 * Da Android 14 un servizio in primo piano di tipo `microphone` **non può**
 * partire da un avvio di sistema: il sistema rifiuta, ed è giusto così, è la
 * regola che impedisce a un'app di accendersi il microfono da sola appena il
 * telefono si accende. Quando succede non si insiste: si lascia una notifica
 * che l'utente tocca per riaccenderlo. Meglio una notifica onesta che un'app che
 * crede di essere accesa e non lo è.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        // Se l'utente l'aveva spento, resta spento: un riavvio non è un suo ordine.
        if (!Prefs.isAttivo(context)) return

        val riuscito = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java))
        }.isSuccess

        // Conta il microfono: senza, il servizio resta in GUASTO.
        if (!riuscito || !Permessi.microfono(context)) {
            Log.i(TAG, "Jarvis non riparte da solo dopo il riavvio: avviso l'utente")
            avvisa(context)
        }
    }

    /** Una notifica sola, che si tocca e apre l'app. */
    private fun avvisa(context: Context) = runCatching {
        val gestore = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            gestore.createNotificationChannel(
                NotificationChannel(CANALE, "JBoss da riaccendere", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val apri = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        gestore.notify(
            ID_AVVISO,
            NotificationCompat.Builder(context, CANALE)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("JBoss è spento")
                .setContentText("Il telefono si è riavviato. Tocca per riaccenderlo.")
                .setAutoCancel(true)
                .setContentIntent(apri)
                .build()
        )
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val CANALE = "jarvis_riaccendi"
        const val ID_AVVISO = 9021
    }
}

package com.jarvis.telefono

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.telefono.nucleo.Comandi
import com.jarvis.telefono.nucleo.RegistroComandi

/**
 * JBoss 0.7.0 (2026-10-08): tiene vivo un comando della chat quando l'app va in secondo piano e la voce
 * (JarvisService, già in primo piano) è spenta. Servizio in primo piano di tipo dataSync con una notifica
 * «JBoss sta lavorando»: parte con il comando, si ferma da solo quando [RegistroComandi.attivi] torna a zero.
 * Se c'è da confermare, la notifica lo dice e un tocco riapre la chat con il box Conferma / Annulla.
 */
class ServizioComando : Service() {

    private val principale = Handler(Looper.getMainLooper())
    private var smetti: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        canale(this)
        val n = notifica(RegistroComandi.riga(Comandi.registro.istantanea()) ?: getString(R.string.comando_in_corso))
        val partito = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        }
        if (partito.isFailure) {
            Log.w(TAG, "primo piano rifiutato: ${partito.exceptionOrNull()?.javaClass?.simpleName}")
            stopSelf(); return
        }
        smetti = Comandi.registro.osserva { i -> principale.post { aggiorna(i) } }
        principale.postDelayed(controllo, CONTROLLO_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        aggiorna(Comandi.registro.istantanea())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        smetti?.invoke(); smetti = null
        principale.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Anche senza eventi: i comandi scaduti si chiudono e il servizio si ferma. */
    private val controllo = object : Runnable {
        override fun run() {
            aggiorna(Comandi.registro.istantanea())
            principale.postDelayed(this, CONTROLLO_MS)
        }
    }

    private fun aggiorna(i: RegistroComandi.Istantanea) {
        if (Comandi.registro.attivi() == 0) {
            Log.i(TAG, "nessun comando aperto: mi fermo")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val riga = RegistroComandi.riga(i) ?: getString(R.string.comando_in_corso)
        runCatching { getSystemService(NotificationManager::class.java).notify(ID, notifica(riga)) }
    }

    private fun notifica(riga: String): Notification {
        val apri = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CANALE)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(getString(R.string.comando_notifica_titolo))
            .setContentText(riga)
            .setStyle(NotificationCompat.BigTextStyle().bigText(riga))
            .setContentIntent(apri)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "JarvisComando"
        private const val CANALE = "jarvis_comandi"
        private const val ID = 4207
        private const val CONTROLLO_MS = 15_000L

        /**
         * Parte con un comando, se serve: con la voce accesa JarvisService è già in primo piano e basta lui.
         * Da qualunque thread. Un avvio rifiutato da Android (app in secondo piano) non ferma il comando.
         */
        fun avvia(context: Context) {
            if (JarvisService.instance != null) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ServizioComando::class.java)) }
                .onFailure { Log.w(TAG, "avvio rifiutato: ${it.javaClass.simpleName}") }
        }

        private fun canale(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val c = NotificationChannel(CANALE, context.getString(R.string.comando_canale), NotificationManager.IMPORTANCE_LOW)
            c.setShowBadge(false)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }
}

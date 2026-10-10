package com.jarvis.telefono.collegamento

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Le notifiche di Jarvis dentro JBoss (0.6.0): report del Command Center e del Postino, avvisi fuori turno.
 * Arrivano qui SOLO dopo [CollegamentoJarvis.passaDallaCoda] (doppioni e contesti in silenzio fermati prima).
 * Un canale solo, «Jarvis: report e avvisi»; il tocco apre la webapp sul filo giusto (`?filo=postino#chat`).
 * Le conferme Invia/Annulla dei lavori restano sul loro canale (NotificheVps): non sono avvisi, sono domande.
 */
object NotificheJarvis {
    const val CANALE = "jboss_jarvis_report"

    private fun canale(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANALE, "Jarvis: report e avvisi", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "I report di Jarvis e del Postino dal Command Center, una volta sola"
            },
        )
    }

    @Suppress("MissingPermission")
    fun mostra(c: Context, n: CodaNotifiche.Notifica) {
        canale(c)
        val id = 80_000 + ((n.chiave.ifBlank { CodaNotifiche.impronta(n) }).hashCode() and 0x3FFF)
        // Boss 08/10: il tocco porta a QUELLA notifica, dentro JBoss (fid = id del Command Center); «indietro» torna a JBoss.
        val fid = n.chiave.takeIf { it.startsWith("ponte:") }?.removePrefix("ponte:")
        val apri = CollegamentoJarvis.intentWebapp(c, n.filo, null, fid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Stessa affinità di JBoss: la webapp si apre nel task di JBoss, sopra la Home (MainActivity è singleTask).
        val pi = PendingIntent.getActivity(c, id, apri, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(c, CANALE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(n.titolo)
            .setContentText(n.testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.testo))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
        runCatching { NotificationManagerCompat.from(c).notify(id, b.build()) }
            .onFailure { Log.w(CollegamentoJarvis.TAG, "notifica non mostrata: ${it.javaClass.simpleName}") }
    }
}

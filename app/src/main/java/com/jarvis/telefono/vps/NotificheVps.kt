package com.jarvis.telefono.vps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Le notifiche del modulo VPS: due canali dedicati.
 * - «Lavori sulla VPS»: una notifica a fine lavoro (Finito / Non riuscito / Annullato), il tocco apre il lavoro.
 * - «Conferme della VPS» (alta priorità): un lavoro è fermo e aspetta Boss; Invia e Annulla direttamente nella
 *   notifica, così la scelta si fa anche con l'app chiusa o a schermo bloccato.
 */
object NotificheVps {
    const val CANALE_LAVORI = "vps_lavori"
    const val CANALE_CONFERME = "vps_conferme"
    const val EXTRA_LAVORO = "lavoro_id"
    private const val EXTRA_AZIONE = "azione_id"
    private const val EXTRA_SCELTA = "scelta"
    const val AZIONE_SCELTA = "com.jarvis.telefono.vps.SCELTA"

    private fun canali(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANALE_LAVORI, "Lavori sulla VPS", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avviso quando un lavoro mandato alla VPS è finito"
        })
        nm.createNotificationChannel(NotificationChannel(CANALE_CONFERME, "Conferme della VPS", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Un lavoro sulla VPS aspetta il tuo sì (Invia o Annulla)"
        })
    }

    private fun idFine(lavoroId: String) = 40_000 + (lavoroId.hashCode() and 0x3FFF)
    private fun idConferma(lavoroId: String) = 60_000 + (lavoroId.hashCode() and 0x3FFF)

    private fun apriLavoro(context: Context, lavoroId: String, codice: Int): PendingIntent =
        PendingIntent.getActivity(
            context, codice,
            Intent(context, TerminaleVpsActivity::class.java).putExtra(EXTRA_LAVORO, lavoroId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    @Suppress("MissingPermission")
    private fun pubblica(context: Context, id: Int, b: NotificationCompat.Builder) {
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
            .onFailure { Log.w("JarvisVps", "notifica non mostrata: ${it.javaClass.simpleName}") }
    }

    fun fine(context: Context, l: LavoroLocale) {
        canali(context)
        val titolo = when (l.esito) {
            "ok" -> "Finito: ${l.titolo}"
            "annullato" -> "Annullato: ${l.titolo}"
            else -> "Non riuscito: ${l.titolo}"
        }
        // 0.6.0: anche i lavori finiti passano dalla coda unica (lo stesso report non arriva due volte dal Command Center).
        val n = com.jarvis.telefono.collegamento.CodaNotifiche.Notifica(
            "lavoro:${l.id}", "lavoro", "${ModuloVpsUi.nomeAgente(l.agente)} · $titolo", l.riassunto.orEmpty(), System.currentTimeMillis(),
            if (l.agente == "postino") "postino" else l.agente, importante = true,
        )
        if (!com.jarvis.telefono.collegamento.CollegamentoJarvis.passaDallaCoda(context, n)) return
        pubblica(context, idFine(l.id), NotificationCompat.Builder(context, CANALE_LAVORI)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("${ModuloVpsUi.nomeAgente(l.agente)} · VPS")
            .setContentText(titolo)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$titolo\n${l.riassunto.orEmpty()}"))
            .setContentIntent(apriLavoro(context, l.id, idFine(l.id)))
            .setAutoCancel(true)
            .addAction(0, "Apri", apriLavoro(context, l.id, idFine(l.id) + 1)))
    }

    fun conferma(context: Context, c: ConfermaVps, collegato: Boolean) {
        canali(context)
        fun scelta(s: String, codice: Int) = PendingIntent.getBroadcast(
            context, codice,
            Intent(context, AzioneVpsReceiver::class.java).setAction(AZIONE_SCELTA)
                .putExtra(EXTRA_LAVORO, c.lavoroId).putExtra(EXTRA_AZIONE, c.azioneId).putExtra(EXTRA_SCELTA, s),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val id = idConferma(c.lavoroId)
        pubblica(context, id, NotificationCompat.Builder(context, CANALE_CONFERME)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(c.titolo())
            .setContentText(c.domanda.ifBlank { c.motivo })
            .setStyle(NotificationCompat.BigTextStyle().bigText(ModuloVpsUi.corpoConferma(c)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter((c.scadeTs - System.currentTimeMillis()).coerceIn(10_000L, 15 * 60_000L))
            .setContentIntent(apriLavoro(context, c.lavoroId, id))
            .addAction(0, "Annulla", scelta(ProtocolloVps.ANNULLA, id + 1))
            .addAction(0, "Invia", scelta(ProtocolloVps.INVIA, id + 2)))
    }

    fun togliConferma(context: Context, lavoroId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(idConferma(lavoroId)) }
    }

    internal fun leggiScelta(i: Intent): Triple<String, String, String>? {
        val l = i.getStringExtra(EXTRA_LAVORO) ?: return null
        val a = i.getStringExtra(EXTRA_AZIONE) ?: return null
        val s = i.getStringExtra(EXTRA_SCELTA) ?: return null
        return Triple(l, a, s)
    }
}

/** I pulsanti Invia/Annulla della notifica di conferma. Non esportato: lo usa solo la notifica dell'app. */
class AzioneVpsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificheVps.AZIONE_SCELTA) return
        val (lavoro, azione, scelta) = NotificheVps.leggiScelta(intent) ?: return
        val r = goAsync()
        Thread {
            runCatching { ModuloVps.scegli(context.applicationContext, lavoro, azione, scelta) }
            r.finish()
        }.start()
    }
}

/**
 * Banco ADB del modulo VPS (scripts/configura-vps.sh): scrive indirizzo e token in filesDir/modulo-vps.json.
 * Protetto da DUMP: solo la shell di ADB lo può chiamare. Il token non finisce mai nel log.
 */
class ConfiguraVpsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val esito = runCatching {
            when (intent.getStringExtra("azione")) {
                "togli" -> { ConfigVps.togli(context); "modulo VPS: configurazione tolta" }
                "accendi" -> { com.jarvis.telefono.collegamento.CollegamentoJarvis.accendi(context, true); "collegamento Jarvis acceso" }
                "spegni" -> { com.jarvis.telefono.collegamento.CollegamentoJarvis.accendi(context, false); "collegamento Jarvis spento" }
                "stato" -> "modulo VPS: ${ModuloVps.statoTesto(context)}, traffico ${ModuloVps.traffico()}"
                else -> {
                    val b64 = intent.getStringExtra("base64") ?: error("manca base64")
                    ConfigVps.scrivi(context, String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8))
                }
            }
        }.getOrElse { "modulo VPS: configurazione NON salvata (${it.message ?: it.javaClass.simpleName})" }
        Log.i("JarvisProva", esito)
    }
}

package com.jarvis.telefono.sveglia

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jarvis.telefono.JarvisService
import com.jarvis.telefono.Prefs
import com.jarvis.telefono.vps.ConfigVps
import com.jarvis.telefono.vps.ManiVps
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La sveglia della VPS (JBoss 0.6.1, 2026-10-08). Firebase consegna il messaggio ad alta priorità `tipo: "sveglia"`
 * anche a schermo spento; qui si apre il canale delle mani verso la VPS per 3 minuti ([RegoleSveglia.FINESTRA_MS]).
 * Se JarvisService era acceso e il sistema l'ha chiuso, si riaccende (un messaggio ad alta priorità permette di
 * avviare un servizio in primo piano da sottofondo). Nessun ciclo, nessun wakelock: il canale si chiude da solo.
 * Il token FCM non va mai nei log.
 */
class SvegliaFcm : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Sveglia.salvaToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (RegoleSveglia.decidi(message.data, System.currentTimeMillis())) {
            RegoleSveglia.Decisione.NON_E_SVEGLIA -> Log.i(Sveglia.TAG, "messaggio FCM che non è una sveglia: ignorato")
            RegoleSveglia.Decisione.SCADUTA -> Log.i(Sveglia.TAG, "sveglia arrivata troppo tardi: ignorata")
            RegoleSveglia.Decisione.SVEGLIA -> Sveglia.svegliati(applicationContext, message.priority, message.originalPriority)
        }
    }
}

object Sveglia {
    const val TAG = "JarvisSveglia"
    private const val PREFS = "sveglia_fcm"
    private const val TOKEN = "token"

    @Volatile var ultimaSveglia: String = "mai"
        private set

    /** Il token salvato (null se Firebase non l'ha ancora dato o non è configurato). */
    fun token(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN, null)

    fun salvaToken(context: Context, token: String) {
        if (!RegoleSveglia.tokenValido(token)) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN, token).apply()
        Log.i(TAG, "token FCM aggiornato (lo mando alla VPS al prossimo collegamento)")
    }

    /** All'avvio di JarvisService: chiede il token a Firebase. Senza google-services.json Firebase non c'è: si dice e basta. */
    fun preparaToken(context: Context) {
        val app = context.applicationContext
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { salvaToken(app, it) }
                .addOnFailureListener { Log.w(TAG, "token FCM non disponibile: ${it.javaClass.simpleName}") }
        }.onFailure { Log.w(TAG, "Firebase non configurato in questo APK: sveglia spenta (${it.javaClass.simpleName})") }
    }

    /**
     * Gira sul thread di Firebase, che concede una decina di secondi: si aspetta al massimo 9 s il collegamento.
     * Poi il canale resta aperto da solo per la finestra e si chiude col riposo.
     */
    fun svegliati(context: Context, priorita: Int, prioritaOriginale: Int) {
        val app = context.applicationContext
        val inizio = System.currentTimeMillis()
        Log.i(TAG, "sveglia dalla VPS (priorità $priorita, chiesta $prioritaOriginale)")
        ManiVps.disponibilita(app) // indirizzo o token cambiati: il canale riparte pulito
        if (!ConfigVps.acceso(app) || !ConfigVps.dati(app).completa) {
            ultimaSveglia = "ignorata: modulo VPS spento o non configurato"
            Log.i(TAG, ultimaSveglia)
            return
        }
        // JarvisService acceso da Boss ma chiuso dal sistema: si riaccende (resta acceso come sempre, non per 3 minuti).
        if (Prefs.isAttivo(app) && !JarvisService.isRunning) {
            runCatching {
                val i = Intent(app, JarvisService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
            }.onFailure { Log.w(TAG, "JarvisService non riparte dalla sveglia: ${it.javaClass.simpleName}") }
        }
        val errore = runBlocking { withTimeoutOrNull(9_000L) { ManiVps.canale(app).apriPerSveglia() ?: "ok" } }
        val ms = System.currentTimeMillis() - inizio
        ultimaSveglia = when (errore) {
            "ok" -> "collegato in $ms ms"
            null -> "collegamento oltre 9 s"
            else -> "non collegato: ${(errore as? com.jarvis.telefono.vps.CanaleMani.Esito.Fallito)?.motivo ?: errore}"
        }
        Log.i(TAG, "sveglia: $ultimaSveglia")
    }
}

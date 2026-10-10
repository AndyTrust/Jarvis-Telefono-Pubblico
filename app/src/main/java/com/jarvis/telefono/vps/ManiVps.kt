package com.jarvis.telefono.vps

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.telefono.PhoneActionExecutor
import com.jarvis.telefono.nucleo.CervelloVps

/**
 * Il canale «mani» del modulo VPS sul telefono (JBoss 0.3.4): stesso interruttore, stesso indirizzo e stesso token
 * del ruolo «lavori» (ConfigVps: cassaforte cifrata), nessun segreto nel codice o nei log.
 * Si apre solo quando una frase va al cervello della VPS ([CanaleMani]).
 */
object ManiVps {
    private const val TAG = "JarvisMani"

    @Volatile private var canale: CanaleMani? = null
    @Volatile private var firma = 0
    private val principale by lazy { Handler(Looper.getMainLooper()) }

    fun canale(context: Context): CanaleMani {
        canale?.let { return it }
        synchronized(this) {
            canale?.let { return it }
            val app = context.applicationContext
            return CanaleMani(
                url = { ConfigVps.dati(app).url },
                token = { ConfigVps.dati(app).token },
                log = { Log.i(TAG, it) },
                // Boss scrive nel sito mentre una bozza aspetta: decide il cancello d'invio del telefono.
                suRispostaConferma = { t -> principale.post { PhoneActionExecutor.rispostaDiBoss(t) } },
                // 0.4.2: la risposta di una frase scaduta o tenuta da parte dalla VPS: si dice comunque.
                suRispostaTardiva = { t, errore -> principale.post { com.jarvis.telefono.nucleo.Nucleo.rispostaTardiva(app, t, errore) } },
                // 0.6.0: un avviso fuori turno di Jarvis va nella coda unica delle notifiche (mai detto come risposta).
                suNotifica = { t -> Thread { com.jarvis.telefono.collegamento.CollegamentoJarvis.avvisoSottofondo(app, t) }.start() },
                // 0.6.1: sveglia FCM della VPS (sveglia/SvegliaFcm.kt): il token a ogni collegamento, e gli strumenti
                // chiesti dalla VPS nella finestra della sveglia con le stesse mani delle frasi.
                tokenFcm = { com.jarvis.telefono.sveglia.Sveglia.token(app) },
                strumentoLibero = { comando -> com.jarvis.telefono.nucleo.Nucleo.eseguiPerSveglia(comando) },
            ).also { canale = it }
        }
    }

    fun disponibilita(context: Context): CervelloVps.Disponibilita {
        val app = context.applicationContext
        if (!ConfigVps.acceso(app)) return CervelloVps.Disponibilita.SPENTO
        val d = ConfigVps.dati(app)
        if (!d.completa) return CervelloVps.Disponibilita.NON_CONFIGURATO
        // Indirizzo o token cambiati (abbinamento, cassaforte): si riparte da capo, anche dopo un token rifiutato.
        val f = (d.url + "\u0000" + d.token).hashCode()
        if (f != firma) { firma = f; canale?.configurazioneCambiata() }
        if (!ModuloVps.haRete(app)) return CervelloVps.Disponibilita.SENZA_RETE
        return CervelloVps.Disponibilita.PRONTO
    }

    /** Modulo spento: il canale si chiude subito. */
    fun chiudi() {
        canale?.chiudi()
    }

    fun stato(): String {
        val c = canale ?: return "mani: mai aperte"
        return when {
            c.collegato && c.svegliato -> "mani: collegate (sveglia della VPS)"
            c.collegato -> "mani: collegate"
            c.sostituito -> "mani: un altro telefono ha preso il posto"
            c.tokenRifiutato -> "mani: token rifiutato"
            c.ultimoErrore.isNotEmpty() -> "mani: a riposo (${c.ultimoErrore})"
            else -> "mani: a riposo"
        }
    }
}

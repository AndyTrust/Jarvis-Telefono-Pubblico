package com.jarvis.telefono.configura

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jarvis.telefono.cassaforte.ProtocolloAccount
import com.jarvis.telefono.cassaforte.RispostaAccount
import com.jarvis.telefono.vps.ConfigVps
import com.jarvis.telefono.vps.ModuloVps

/**
 * I messaggi account_* verso la VPS dalle schermate (lista, anteprima, salva, elimina), sopra il modulo VPS già
 * esistente: apre il collegamento finché la schermata è aperta ([apri]/[chiudi]), aspetta che sia autenticato,
 * manda e consegna la risposta con lo stesso richiesta_id. Le risposte arrivano sul thread principale.
 * Il messaggio può contenere una password: non si logga mai.
 */
class CanaleAccount(private val c: Context) {
    private val principale = Handler(Looper.getMainLooper())
    private var smetti: (() -> Unit)? = null
    private var aperto = false
    private val attese = HashMap<String, (RispostaAccount?) -> Unit>()
    private var attesaLista: ((RispostaAccount.Lista?) -> Unit)? = null

    /** Perché non si può parlare con la VPS (null = si può). */
    fun motivoNo(): String? = when {
        !ConfigVps.dati(c).completa -> "La VPS non è ancora abbinata: Cervello → La mia VPS → Scansiona QR."
        !ModuloVps.acceso(c) -> "Il Collegamento Jarvis è spento: accendilo in Impostazioni, Collegamento Jarvis."
        else -> null
    }

    fun apri() {
        if (aperto || motivoNo() != null) return
        aperto = true
        ModuloVps.uiAperta(c)
        smetti = ModuloVps.ascoltaGrezzi { o ->
            val r = ProtocolloAccount.leggi(o) ?: return@ascoltaGrezzi
            if (r is RispostaAccount.Lista) { attesaLista?.invoke(r); attesaLista = null; return@ascoltaGrezzi }
            attese.remove(r.richiestaId)?.invoke(r)
        }
    }

    fun chiudi() {
        smetti?.invoke(); smetti = null
        if (aperto) ModuloVps.uiChiusa()
        aperto = false
        attese.clear(); attesaLista = null
    }

    /** Manda appena il collegamento è pronto (fino a [attesaMs]); [poi](null) = non collegato o nessuna risposta. */
    fun manda(messaggio: String, richiestaId: String, attesaMs: Long = 15_000, poi: (RispostaAccount?) -> Unit) {
        motivoNo()?.let { poi(null); return }
        apri()
        attese[richiestaId] = poi
        val inizio = System.currentTimeMillis()
        fun prova() {
            if (!aperto) return
            if (ModuloVps.collegato() && ModuloVps.mandaAccount(c, messaggio)) {
                principale.postDelayed({ attese.remove(richiestaId)?.invoke(null) }, attesaMs)
                return
            }
            if (System.currentTimeMillis() - inizio > attesaMs) { attese.remove(richiestaId)?.invoke(null); return }
            principale.postDelayed({ prova() }, 400)
        }
        prova()
    }

    fun lista(attesaMs: Long = 15_000, poi: (RispostaAccount.Lista?) -> Unit) {
        motivoNo()?.let { poi(null); return }
        apri()
        attesaLista = poi
        val inizio = System.currentTimeMillis()
        fun prova() {
            if (!aperto) return
            if (ModuloVps.collegato() && ModuloVps.mandaAccount(c, ProtocolloAccount.lista())) {
                principale.postDelayed({ attesaLista?.invoke(null); attesaLista = null }, attesaMs)
                return
            }
            if (System.currentTimeMillis() - inizio > attesaMs) { attesaLista?.invoke(null); attesaLista = null; return }
            principale.postDelayed({ prova() }, 400)
        }
        prova()
    }

    companion object {
        fun nuovoId(): String = "r" + System.currentTimeMillis().toString(36) + (100..999).random()
    }
}

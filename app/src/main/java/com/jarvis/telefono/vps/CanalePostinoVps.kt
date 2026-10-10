package com.jarvis.telefono.vps

import android.content.Context
import com.jarvis.telefono.postino.CanalePostino
import com.jarvis.telefono.postino.FornitoreCanale
import org.json.JSONObject

/**
 * L'innesto 2 di docs/POSTINO-NUMERI.md (0.3.0): la chat Postino a numeri usa il socket del modulo VPS,
 * nessuna socket sua. Il modulo spento o senza indirizzo e token = nessun canale: la chat dice «collega la VPS».
 *
 * 09/10 (Boss: «ci sono interferenze, JBoss dovrebbe subito collegarsi al Postino»): il canale è UNO per tutta l'app
 * ([com.jarvis.telefono.postino.PostaCondivisa]) e ascolta sempre, anche a pagina chiusa. Prima ne nasceva uno a ogni
 * apertura della pagina e si staccava in onDestroy: gli eventi arrivati a pagina chiusa andavano persi. Il socket resta
 * su solo finché qualcuno lo usa ([tieniAperto]); le conferme le mostra la pagina solo quando si vede ([paginaVisibile]).
 */
class CanalePostinoVps(private val ctx: Context) : CanalePostino {
    override val collegato get() = ModuloVps.collegato()
    override var ascoltatore: ((JSONObject) -> Unit)? = null
    override var suCollegamento: ((Boolean) -> Unit)? = null
    private val smetti = listOf(
        ModuloVps.ascoltaGrezzi { ascoltatore?.invoke(it) },
        ModuloVps.ascoltaCollegamento { suCollegamento?.invoke(it) },
    )
    private var tenuto = false
    private var pagina = false

    override fun avvia(idLavoro: String, testo: String, opzioni: JSONObject) {
        val m = JSONObject().put("type", "job_start").put("id", idLavoro).put("agente", "postino").put("testo", testo).put("opzioni", opzioni)
        if (!ModuloVps.mandaGrezzo(ctx, m)) ascoltatore?.invoke(JSONObject().put("type", "job_errore").put("id", idLavoro).put("motivo", "La VPS non è collegata: non è partito niente."))
    }

    override fun conferma(idLavoro: String, azioneId: String, scelta: String) = ModuloVps.scegli(ctx, idLavoro, azioneId, scelta)

    override fun segui(idLavoro: String, ultimoEvento: Int) { ModuloVps.segui(idLavoro, ultimoEvento) }

    override fun tieniAperto(si: Boolean) {
        if (si == tenuto) return
        tenuto = si
        if (si) ModuloVps.uiAperta(ctx) else ModuloVps.uiChiusa()
    }

    override fun paginaVisibile(si: Boolean) {
        if (si == pagina) return
        pagina = si
        ModuloVps.postinoVisibile = si
        ModuloVps.confermeMostrateDallaSchermata(si)
    }

    override fun chiudi() {
        smetti.forEach { it() }
        paginaVisibile(false)
        tieniAperto(false)
    }

    companion object {
        /** All'avvio dell'app: il Postino prende il canale da qui, solo con il modulo pronto. */
        fun registra() {
            FornitoreCanale.fabbrica = { c -> if (ModuloVps.pronto(c)) CanalePostinoVps(c.applicationContext) else null }
        }
    }
}

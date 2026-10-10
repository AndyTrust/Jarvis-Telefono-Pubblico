package com.jarvis.telefono.postino

import org.json.JSONObject

/**
 * Il filo fra la chat Postino e il modulo VPS (PROTOCOLLO-VPS.md, ruolo «lavori»).
 *
 * La chat non apre socket sue: usa il collegamento del modulo VPS (vps/ModuloVps.kt, agente
 * telefono-vps). All'innesto basta un adattatore di poche righe che implementa questa interfaccia
 * su ModuloVps e lo registra in [FornitoreCanale.fabbrica] (vedi docs/POSTINO-NUMERI.md).
 * Finché nessuno lo registra la chat mostra «collega la VPS» e non finge niente.
 */
interface CanalePostino {
    /** true se il socket «lavori» è collegato e autenticato. */
    val collegato: Boolean

    /** job_start {id, agente:"postino", testo, opzioni:{modo:"numeri", report_id?}}. */
    fun avvia(idLavoro: String, testo: String, opzioni: JSONObject)

    /** conferma {id, azione_id, scelta:"invia"|"annulla"}: l'unica strada per un invio. */
    fun conferma(idLavoro: String, azioneId: String, scelta: String)

    /** job_segui {id, ultimo_evento}: dopo una riconnessione. */
    fun segui(idLavoro: String, ultimoEvento: Int)

    /** Riceve OGNI messaggio del modulo (job_event, job_done, job_errore, job_stato…), sul thread principale. */
    var ascoltatore: ((JSONObject) -> Unit)?

    /** Collegato / scollegato, sul thread principale. */
    var suCollegamento: ((Boolean) -> Unit)?

    fun chiudi() {}

    /**
     * 09/10 (Boss: «JBoss deve collegarsi SUBITO al Postino»): il canale è uno solo per tutta l'app ([PostaCondivisa]).
     * [si] = qualcuno lo usa adesso (la pagina del Postino, la chat di JBoss, un comando da mandare): il socket resta su.
     */
    fun tieniAperto(si: Boolean) {}

    /** La pagina del Postino è in primo piano: le conferme le mostra lei e i suoi lavori finiti non fanno notifica. */
    fun paginaVisibile(si: Boolean) {}
}

/** Dove la chat prende il canale. Lo imposta l'innesto del modulo VPS (o la prova con dati finti). */
object FornitoreCanale {
    @Volatile
    var fabbrica: ((android.content.Context) -> CanalePostino?)? = null
}

/** Le opzioni del job_start per il Postino a numeri. */
fun opzioniPostino(reportId: String?): JSONObject = JSONObject().apply {
    put("modo", "numeri")
    put("titolo", "Postino")
    put("max_min", 30)
    if (!reportId.isNullOrEmpty()) put("report_id", reportId)
}

/** Un id di lavoro valido per il protocollo (8-64 caratteri [A-Za-z0-9_-]). */
fun nuovoIdLavoro(ora: Long = System.currentTimeMillis()): String =
    "postino-$ora-${(1000..9999).random()}"

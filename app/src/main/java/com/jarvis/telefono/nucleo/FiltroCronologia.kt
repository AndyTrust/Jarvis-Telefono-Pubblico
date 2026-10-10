package com.jarvis.telefono.nucleo

/**
 * Filtro e ricerca della cronologia (0.2.0, Boss 07/10: «la cronologia deve occupare quasi tutta
 * la Home, con filtro per agente e ricerca»). Funzioni pure: si provano sulla JVM.
 *
 * La cronologia si legge a scambi: la frase di Boss e le risposte che la seguono. Un filtro per
 * agente tiene lo scambio intero se una risposta è di quell'agente, o se Boss ha scritto nella
 * chat di quell'agente (origine «chat-<id>»).
 *
 * In Home i filtri sono i due titolari (Boss 07/10): «Postino» = tutta la posta (scambi del
 * Postino); «Jarvis» = tutto il resto (quello che fa lui e quello che passa agli altri agenti).
 */
object FiltroCronologia {

    const val TUTTI = ""
    const val JARVIS = "jarvis"
    const val POSTINO = "postino"
    /** 0.6.1 (layout approvato 08/10): gli scambi andati male, in un tocco. */
    const val ERRORI = "errori"

    /**
     * 09/10 (Boss: «la chat JBoss e la pagina Postino devono mostrare lo stesso stato»): il filo della chat di JBoss.
     * Tiene tutto, posta compresa (letture, cancellazioni, avvisi delle mail nuove), tranne quello scritto nella chat di
     * un altro agente. Il chip «JBoss» della Home resta com'era (tutto tranne la posta, decisione del 07/10).
     */
    const val FILO_JBOSS = "filo-jboss"

    /** 09/10: una riga d'avviso (mail nuove) non è la risposta alla frase di prima: apre uno scambio suo. */
    const val AVVISO = "avviso"

    /** I filtri della Home, nell'ordine dei chip: Tutto · JBoss · Postino · Errori. */
    val FILTRI_HOME = listOf(TUTTI to "Tutto", JARVIS to "JBoss", POSTINO to "Postino", ERRORI to "Errori")

    /** Boss 2026-10-09: il filo di JBoss tiene gli ultimi 60 scambi, poi i più vecchi escono. */
    const val STORICO_JBOSS = 60

    /** Gli esiti che contano come errore. */
    private val ESITI_ERRORE = setOf("errore", "non riuscito")

    /** Quanti scambi ha ogni filtro (per il numero sul chip). */
    fun conteggi(voci: List<Cronologia.Voce>, filtri: List<String>): Map<String, Int> {
        val sc = scambi(voci)
        return filtri.associateWith { f -> if (f == TUTTI) sc.size else sc.count { diAgente(it, f) } }
    }

    /** L'origine che una frase scritta nella chat di un agente porta nella cronologia. */
    fun origineChat(agente: String) = "chat-$agente"

    data class Scambio(val voci: List<Cronologia.Voce>)

    fun scambi(voci: List<Cronologia.Voce>): List<Scambio> {
        val out = ArrayList<Scambio>()
        var corrente = ArrayList<Cronologia.Voce>()
        for (v in voci) {
            val apre = v.chi == Cronologia.BOSS || v.esito == AVVISO
            if (apre && corrente.isNotEmpty()) { out += Scambio(corrente); corrente = ArrayList() }
            corrente += v
        }
        if (corrente.isNotEmpty()) out += Scambio(corrente)
        return out
    }

    fun diAgente(s: Scambio, agente: String): Boolean {
        val risposte = s.voci.filter { it.chi != Cronologia.BOSS }
        if (agente == ERRORI) return risposte.any { it.esito in ESITI_ERRORE }
        if (agente == JARVIS) return !diAgente(s, POSTINO)
        if (agente == FILO_JBOSS) return s.voci.none { it.chi == Cronologia.BOSS && it.esito.startsWith("chat-") }
        return risposte.any { it.agenteEsecutore == agente } ||
            s.voci.any { it.chi == Cronologia.BOSS && it.esito == origineChat(agente) }
    }

    /** Le voci da mostrare con [agente] (TUTTI = nessun filtro) e [testo] (vuoto = nessuna ricerca). */
    fun filtra(voci: List<Cronologia.Voce>, agente: String = TUTTI, testo: String = ""): List<Cronologia.Voce> {
        val q = testo.trim().lowercase()
        if (agente == TUTTI && q.isEmpty()) return voci
        return scambi(voci).filter { s ->
            (agente == TUTTI || diAgente(s, agente)) &&
                (q.isEmpty() || s.voci.any { it.testo.lowercase().contains(q) })
        }.flatMap { it.voci }
    }

    /**
     * Le voci del filo di una chat. [storico] = filo di JBoss: niente filtro di sessione, solo gli ultimi
     * [STORICO_JBOSS] scambi. Altrimenti (le chat degli altri agenti) solo da [inizioSessione] in poi.
     */
    fun perFilo(voci: List<Cronologia.Voce>, agente: String, testo: String, inizioSessione: Long, storico: Boolean): List<Cronologia.Voce> {
        val f = filtra(voci, agente, testo)
        return if (storico) scambi(f).takeLast(STORICO_JBOSS).flatMap { it.voci } else f.filter { it.quando >= inizioSessione }
    }

    /** Da quando partono le schede delle deleghe nel filo con lo storico: dal primo scambio mostrato (0 = filo vuoto). */
    fun inizioStorico(voci: List<Cronologia.Voce>): Long = voci.firstOrNull()?.quando ?: 0L
}

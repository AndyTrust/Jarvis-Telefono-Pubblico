package com.jarvis.telefono.postino

import org.json.JSONObject

/**
 * La conversazione con il Postino: le bolle, il resoconto ([StatoPostino]) e i lavori mandati alla
 * VPS. Codice puro (provato sulla JVM); vive finché vive il processo, così ruotare il telefono o
 * riaprire la schermata non perde il resoconto.
 *
 * Ogni comando di Boss diventa UN lavoro del modulo VPS (job_start, agente «postino», modo «numeri»).
 * Gli eventi arrivano con un numero progressivo per lavoro: quelli già visti si scartano (ripresa
 * dopo una disconnessione, PROTOCOLLO-VPS.md §5).
 */
class SessionePostino(private val ora: () -> Long = System::currentTimeMillis) {

    data class Bolla(val daBoss: Boolean, var testo: String, val idLavoro: String? = null, var errore: Boolean = false)

    val stato = StatoPostino()
    val bolle = mutableListOf<Bolla>()
    /** L'indice della bolla dopo cui stanno le schede del resoconto attuale. */
    var dopoBolla: Int = -1
        private set
    /** id lavoro → ultimo evento visto. */
    val ultimi = linkedMapOf<String, Int>()
    val inCorso = linkedSetOf<String>()

    /** 0.6.6: i comandi che partono DOPO un lavoro finito bene (Fatto = «letta N», poi «sposta N in …»). */
    private data class Seguito(val numeri: List<Int>, val verbo: String, val comandi: List<String>)
    private val poi = linkedMapOf<String, Seguito>()
    private val pronti = mutableListOf<List<String>>()

    /**
     * Dopo il lavoro [id] partono [seguito] (uno alla volta, in ordine), ma solo se per OGNI numero
     * di [numeri] la VPS ha scritto lo stato verificato di [verbo] con esito ok. Un lavoro «ok» con
     * l'azione annullata o in errore ferma la catena: il resto non parte.
     */
    fun accoda(id: String, numeri: List<Int>, verbo: String, seguito: List<String>) {
        if (seguito.isNotEmpty()) poi[id] = Seguito(numeri, verbo, seguito)
    }

    /** Le catene da far partire adesso: il primo comando subito, gli altri dopo, con [accoda]. */
    fun prendiPronti(): List<List<String>> = pronti.toList().also { pronti.clear() }

    /**
     * Prepara un comando: lo capisce sul telefono (errore subito, niente viaggio a vuoto) e torna
     * (id del lavoro, testo, opzioni) da mandare con [CanalePostino.avvia].
     */
    fun prepara(testo: String, nuovoId: () -> String = { nuovoIdLavoro(ora()) }): Triple<String, String, JSONObject> {
        val pulito = testo.trim()
        val richieste = ComandiPostino.capisci(pulito)      // solleva ComandiPostino.Errore
        val resoconto = richieste.all { it.verbo == "resoconto" }
        if (!resoconto && stato.reportId == null) {
            throw ComandiPostino.Errore("prima serve il resoconto: scrivi «controlla la posta».")
        }
        val id = nuovoId()
        bolle.add(Bolla(true, pulito))
        bolle.add(Bolla(false, if (resoconto) "Leggo tutte le caselle sulla VPS…" else ComandiPostino.anteprima(richieste), id))
        if (resoconto) dopoBolla = bolle.size - 1
        ultimi[id] = 0
        inCorso.add(id)
        return Triple(id, pulito, opzioniPostino(if (resoconto) null else stato.reportId))
    }

    /**
     * 09/10: una frase libera detta al Postino nella sua pagina («leggi le mail», «cancella questa») e la sua risposta,
     * che il telefono sa senza viaggio alla VPS. Non è un lavoro: niente id, le schede restano dove sono.
     */
    fun dialogo(domanda: String, risposta: String, errore: Boolean = false) {
        bolle.add(Bolla(true, domanda.trim()))
        bolle.add(Bolla(false, StatoPostino.italiano(risposta), null, errore))
    }

    /** Un messaggio del modulo VPS. true se ha cambiato qualcosa da ridisegnare. */
    fun ricevi(m: JSONObject): Boolean {
        val id = m.optString("id")
        val n = m.optInt("n", 0)
        if (id.isNotEmpty() && n > 0) {
            val visto = ultimi[id] ?: return false      // un lavoro non nostro (un'altra chat): non qui
            if (n <= visto) return false                // già visto (ripresa)
            ultimi[id] = n
        } else if (id.isNotEmpty() && id !in ultimi) {
            return false
        }
        val cambiato = stato.applicaMessaggio(m)
        val bolla = bolle.lastOrNull { it.idLavoro == id }
        when (m.optString("type")) {
            "job_event" -> {
                val kind = m.optString("kind")
                val tipo = m.optJSONObject("dati")?.optString("tipo")
                if (bolla != null && (kind == "testo" || kind == "errore" ||
                        (kind == "postino" && tipo in setOf("passo", "totali", "fine", "bozza")))) {
                    bolla.testo = stato.ultimaFrase
                    if (kind == "errore") bolla.errore = true
                }
            }
            "job_done" -> {
                inCorso.remove(id)
                // il seguito parte solo se il lavoro è andato bene; con errore o annullato si ferma
                poi.remove(id)?.let { q ->
                    val tutteOk = q.numeri.all { n -> stato.voce(n)?.stato?.let { it.fatto && it.verbo == q.verbo } == true }
                    if (m.optString("esito") == "ok" && tutteOk) pronti.add(q.comandi)
                }
                bolla?.let {
                    it.testo = stato.ultimaFrase.ifEmpty { StatoPostino.italiano(m.optString("riassunto")) }
                    it.errore = m.optString("esito") == "errore"
                    if (m.optString("esito") == "annullato") it.testo = "Annullato."
                }
            }
            "job_errore" -> {
                inCorso.remove(id)
                poi.remove(id)
                bolla?.let { it.testo = m.optString("motivo"); it.errore = true }
            }
        }
        return cambiato || bolla != null
    }

    /** Dopo una riconnessione: i lavori non finiti da seguire, con l'ultimo evento visto. */
    fun daSeguire(): List<Pair<String, Int>> = inCorso.map { it to (ultimi[it] ?: 0) }
}

package com.jarvis.telefono.nucleo

import com.jarvis.telefono.vps.CanaleMani

/**
 * Regole prima, VPS se serve (JBoss 0.3.4, 2026-10-07).
 *
 *   frase → regole (millisecondi, sul telefono)
 *     capita e semplice                      → piano delle regole (come prima, nessuna rete)
 *     NON capita, o «complessa» ([Complessita]) e modulo VPS acceso con rete
 *                                            → cervello della VPS ([CervelloVps]): decide e fa le azioni
 *         la VPS non risponde e non ha fatto niente → piano delle regole se c'era, altrimenti «non ho capito» + perché
 *         la VPS ha già fatto qualcosa e poi tace     → errore onesto (mai rifare le azioni con le regole)
 *     modulo spento / senza rete             → regole o «Non ho capito» onesto, come la 0.3.3
 *
 * Boss parla mentre la VPS lavora: la richiesta vecchia si ferma; «basta», «stop», «lascia perdere» si fermano lì.
 * Kotlin puro: CervelloVpsTest.
 */
class CervelloCatena(
    private val regole: Cervello,
    private val vps: CervelloVps,
) : Cervello {
    override val nome = "regole+vps"

    override suspend fun capisci(frase: String, contesto: Contesto): Piano {
        if (vps.occupato) {
            vps.annulla()
            if (Complessita.stop(frase)) return Piano.solo("Fermato: la richiesta alla VPS non va avanti.", "stop").copy(cervello = CervelloVps.NOME)
        }
        val p = runCatching { regole.capisci(frase, contesto) }.getOrElse { Piano.nonCapito(frase) }.let {
            if (it.cervello.isEmpty()) it.copy(cervello = regole.nome) else it
        }
        // 0.5.0: sveglia e timer che le regole sanno già mettere (ora o durata nella frase) restano sul telefono.
        val orologio = p.azioni.any { it.action == "componi" && it["tipo"]?.toString() in setOf("sveglia", "timer") }
        val complessa = (if (orologio) Complessita.complessaSenzaSveglia(frase) else Complessita.complessa(frase)) || Complessita.pianoDebole(p)
            // 0.7.0: un acquisto ha molti passi (app, carrello, cassa, riepilogo): lo guida la VPS.
            || RegoleConferma.perFrase(frase) == RegoleConferma.Livello.ACQUISTO
        if (p.capito && !complessa) return p
        when (vps.disponibile()) {
            CervelloVps.Disponibilita.PRONTO -> {}
            CervelloVps.Disponibilita.SPENTO -> return p
            CervelloVps.Disponibilita.NON_CONFIGURATO ->
                return if (p.capito) p else p.copy(dire = p.dire + " Il cervello della VPS non è configurato.")
            CervelloVps.Disponibilita.SENZA_RETE ->
                return if (p.capito) p else p.copy(dire = p.dire + " Senza rete non posso chiederlo alla VPS.")
        }
        val e = vps.chiedi(frase)
        if (e is CanaleMani.Esito.Fallito && e.strumenti.isEmpty()) {
            // Niente è stato fatto sul telefono: le regole, se avevano capito, vanno ancora bene.
            if (p.capito) return p
            return p.copy(dire = "Non l'ho capito da solo e la VPS non mi aiuta: ${e.motivo}", cervello = CervelloVps.NOME)
        }
        return vps.piano(e)
    }
}

/**
 * Quando una frase capita dalle regole va comunque alla VPS: più passi in fila o una risposta da leggere
 * («cerca … e dimmi il primo risultato», «trovami un ristorante … e aprimi le indicazioni»), cose «vicino a me».
 * Le regole farebbero solo il primo pezzo. Kotlin puro.
 */
object Complessita {
    private val PIU_PASSI = Regex(
        """\b(e poi|e dopo|dopodich[eé]|e dimmi|e dimmelo|e dimmela|e dimmeli|e leggimelo|e leggimela|e leggimi|e aprimi|e apri|e mandami|e manda|e scrivi|e scrivigli|e chiama|""" +
            """e cerca|e fai|e fammi|e metti|e salva|e inoltra|e rispondi)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DA_LEGGERE = Regex(
        """\b(dimmi (il|la|i|le|cosa|quanto|quando|se|chi|dove|come)|leggimi|riassumi|riassunto|trovami|vicino a me|qui vicino|""" +
            """nei dintorni|quanto costa|quanto costano|qual [eè]|quali sono|il migliore|la migliore|confronta|cosa c'[eè]|""" +
            """che tempo fa|meteo|controlla se|verifica se)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val STOP = Regex(
        """^\s*(j\.?\s?boss[,.!]?\s*)?(basta|stop|fermati|ferma|lascia perdere|lascia stare|annulla|niente|non importa)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val SVEGLIA = Regex("""\b(sveglia|timer|promemoria|ricordami|ricordamelo)\b""", RegexOption.IGNORE_CASE)

    fun complessa(frase: String): Boolean =
        complessaSenzaSveglia(frase) || SVEGLIA.containsMatchIn(frase)

    /** Più passi o una risposta da leggere, senza contare «sveglia/timer» (0.5.0: quelle le mettono le regole). */
    fun complessaSenzaSveglia(frase: String): Boolean =
        PIU_PASSI.containsMatchIn(frase) || DA_LEGGERE.containsMatchIn(frase)

    /**
     * Il piano delle regole fa solo metà del lavoro: un'app «YouTube e cerca un video…» (il resto della frase è finito
     * nel nome), una domanda di chiarimento senza azioni («A chi lo mando?»), un «non lo so ancora fare».
     */
    fun pianoDebole(p: Piano): Boolean {
        if (!p.capito || p.giaEseguito) return false
        if (p.azioni.isEmpty() && p.dire.contains("?")) return true
        if (p.dire.contains("non la so ancora") || p.dire.contains("non lo so ancora") || p.dire.contains("non so ancora")) return true
        return p.azioni.any { a ->
            a.action in setOf("apri_app", "open_app") && (a["nome"] ?: a["app"])?.toString().orEmpty().let { n ->
                n.contains(" e ") || n.trim().split(Regex("\\s+")).size > 4
            }
        }
    }

    fun stop(frase: String): Boolean = STOP.containsMatchIn(frase)
}

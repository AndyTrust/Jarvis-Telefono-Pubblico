package com.jarvis.telefono.vps

/**
 * La scheda di una delega a Jarvis sulla VPS, nel filo della chat di JBoss (accordo con Boss del 2026-10-09).
 * Kotlin puro, provato in SchedaDelegaTest.
 *
 * Ogni compito mandato alla VPS ha la sua scheda: la richiesta in una riga, lo stato in parole (in corso, fatto,
 * errore, annullato), l'esito in una riga e, solo quando la VPS lo manda, la riga «Browser: aperto/chiuso» con la
 * nota di cosa fa. La scheda non mostra mai comandi, log, stack trace o nomi interni: quello che sembra tecnico si
 * sostituisce con una frase semplice.
 *
 * Dove si vede (la regola del 08/10 dei comandi, [com.jarvis.telefono.nucleo.RegistroComandi.diChat]): nel filo di
 * JBoss le deleghe che non sono partite dalla chat di un altro agente (origine «chat-…»). Le altre chat non hanno
 * schede. Come il filo, la chat mostra la sessione: deleghe nate o finite da quando è aperta, più quelle ancora aperte.
 */
data class SchedaDelega(
    val id: String,
    val agente: String,
    /** «Delega a Jarvis sulla VPS», «Delega a Ricercatore web sulla VPS». */
    val titolo: String,
    val richiesta: String,
    val fase: Fase,
    /** Lo stato in parole: «In corso», «In corso: aspetta il tuo sì», «Fatto», «Errore», «Annullato». */
    val testoStato: String,
    val esito: String,
    /** null = la VPS non ha mandato lo stato del browser: la riga non si mostra. */
    val browser: StatoBrowser?,
    /** Per metterla nel filo: quando è partita. */
    val quando: Long,
    /** Lo stato del lavoro (per l'anello dell'avatar). */
    val statoLavoro: String,
) {
    enum class Fase { IN_CORSO, FATTO, ERRORE, ANNULLATO }

    /** La riga del browser, o null se il dato non c'è. */
    val rigaBrowser: String? get() = rigaBrowser(browser)

    companion object {
        /** L'origine dei lavori mandati dalle schermate dei lavori VPS (Terminale, Lavori): sono di JBoss. */
        const val ORIGINE_SCHERMATA = "schermata-vps"
        private const val PREFISSO_CHAT = "chat-"

        /**
         * La delega si vede nel filo di JBoss? Sì, se non è partita dalla chat di un altro agente. 09/10: mai i lavori
         * della posta unica, anche senza origine (il Postino scrive già il resoconto nel filo: sarebbe un doppione).
         */
        fun diJBoss(l: LavoroLocale): Boolean = !l.origine.startsWith(PREFISSO_CHAT) && !l.dellaPostaUnica

        /** Nella chat aperta da [inizioMs]: nata o finita da allora, o ancora aperta. */
        fun nellaSessione(l: LavoroLocale, inizioMs: Long): Boolean =
            l.aperto || l.creato >= inizioMs || (l.finito > 0 && l.finito >= inizioMs)

        /** Le schede del filo di JBoss, dalla più vecchia. [browserDi]: lo stato della finestra per lavoro, se la VPS lo manda. */
        fun perJBoss(
            lavori: List<LavoroLocale>,
            inizioMs: Long,
            browserDi: (String) -> StatoBrowser? = { null },
        ): List<SchedaDelega> = lavori
            .filter { diJBoss(it) && nellaSessione(it, inizioMs) }
            .sortedBy { it.creato }
            .map { da(it, browserDi(it.id)) }

        fun da(l: LavoroLocale, browser: StatoBrowser? = null): SchedaDelega {
            val fase = fase(l)
            return SchedaDelega(
                id = l.id,
                agente = l.agente,
                titolo = "Delega a ${nomeDelegato(l.agente)} sulla VPS",
                richiesta = richiesta(l),
                fase = fase,
                testoStato = testoStato(l),
                esito = esito(l, fase),
                browser = browser,
                quando = l.creato,
                statoLavoro = l.stato,
            )
        }

        /** «generico» è Jarvis stesso; gli altri con il loro nome. */
        fun nomeDelegato(agente: String): String =
            if (agente == "generico" || agente.isBlank()) "Jarvis" else ModuloVpsUi.nomeAgente(agente)

        fun fase(l: LavoroLocale): Fase = when {
            l.stato != LavoroLocale.FINITO -> Fase.IN_CORSO
            l.esito == "ok" -> Fase.FATTO
            l.esito == "annullato" -> Fase.ANNULLATO
            else -> Fase.ERRORE
        }

        /** Lo stato in parole semplici. */
        fun testoStato(l: LavoroLocale): String = when (l.stato) {
            LavoroLocale.DA_MANDARE -> "In corso: parte appena c'è la rete"
            LavoroLocale.INVIATO -> "In corso: mandato a Jarvis"
            LavoroLocale.CODA -> "In corso: in fila sulla VPS"
            LavoroLocale.ATTESA -> "In corso: aspetta il tuo sì"
            LavoroLocale.LAVORO -> "In corso"
            else -> when (fase(l)) {
                Fase.FATTO -> "Fatto"
                Fase.ANNULLATO -> "Annullato"
                else -> "Errore"
            }
        }

        /** La richiesta come l'ha detta Boss, in una riga. */
        fun richiesta(l: LavoroLocale): String {
            // I contesti seguiti (CRM, Patrimonio) mandano le regole davanti: la frase di Boss sta dopo «Richiesta di Boss:».
            val daTesto = l.testo.substringAfter("Richiesta di Boss:", "").trim()
            val base = when {
                l.titolo.isNotBlank() && !l.titolo.trimStart().startsWith("[") -> l.titolo
                daTesto.isNotBlank() -> daTesto
                else -> l.testo
            }
            return rigaSemplice(base) ?: "La richiesta è nel dettaglio del lavoro"
        }

        /** L'esito in una riga: quello che ha detto la VPS se è testo semplice, altrimenti una frase onesta. */
        fun esito(l: LavoroLocale, fase: Fase = fase(l)): String = when (fase) {
            Fase.IN_CORSO -> if (l.stato == LavoroLocale.ATTESA) "Ferma finché non scegli Invia o Annulla" else "Ancora nessuno: ti avviso quando ha finito"
            Fase.FATTO -> rigaSemplice(l.riassunto) ?: "Finito: il resoconto è nel dettaglio del lavoro"
            Fase.ANNULLATO -> rigaSemplice(l.riassunto) ?: "Fermato prima della fine"
            Fase.ERRORE -> rigaSemplice(l.riassunto) ?: "Non è riuscito: tocca la scheda per sapere perché"
        }

        /** «Browser: aperto · Cerca i bandi sul sito della Regione», o null se la VPS non l'ha detto. */
        fun rigaBrowser(b: StatoBrowser?): String? {
            b ?: return null
            val stato = if (b.aperto) "aperto" else "chiuso"
            val nota = rigaSemplice(b.nota)
            return if (nota != null) "Browser: $stato · $nota" else "Browser: $stato"
        }

        private const val MAX_RIGA = 140

        // Quello che non va mai sulla scheda: comandi, percorsi, stack trace, JSON, nomi interni del protocollo.
        private val TECNICO = Regex(
            """(Exception|Error:|Traceback|\bat [a-z]+\.[a-zA-Z.]+\(|\.(kt|js|py|java|sh|json):\d|[{}<>]|\$\(|""" +
                """(^|\s)/(root|tmp|opt|usr|home|etc|var)/|\b(job|tool|conferma|azione)_[a-z]+\b|\bnull\b|""" +
                """^\s*(sudo|curl|ssh|scp|python3?|node|npm|git|bash|cat|grep|ls|cd|rm|docker)\b|&&|\|\|)""",
        )
        private val MARKDOWN = Regex("""(\*\*|__|`+|^#+\s*|^[-*]\s+)""")

        /** La prima riga utile in parole semplici, tagliata; null se è vuota o sembra codice. */
        fun rigaSemplice(testo: String?): String? {
            val riga = testo.orEmpty().lineSequence()
                .map { it.replace(MARKDOWN, "").trim() }
                .firstOrNull { it.isNotBlank() } ?: return null
            if (TECNICO.containsMatchIn(riga)) return null
            return if (riga.length <= MAX_RIGA) riga else riga.take(MAX_RIGA - 1).trimEnd() + "…"
        }
    }
}

/**
 * La finestra del browser che un agente sulla VPS usa per il compito: aperta o chiusa, e cosa sta facendo, in
 * parole semplici. Il protocollo v1 non la manda ancora (docs/PROTOCOLLO-VPS.md §3): finché non c'è, nessuna
 * scheda ne ha una e la riga «Browser» non compare.
 */
data class StatoBrowser(val aperto: Boolean, val nota: String = "")

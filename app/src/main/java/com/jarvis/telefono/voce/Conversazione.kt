package com.jarvis.telefono.voce

/**
 * L'ascolto continuo col popup aperto (Boss, 2026-10-09: «Jarvis rimane in ascolto con il popup attivo.
 * Posso interromperlo e chiedere altro, oppure aggiungere richieste alla domanda precedente»).
 *
 * Il servizio la usa così:
 * - [apri] quando il popup si apre (parola, tocco, tasto laterale) con la voce accesa;
 * - [nuovaFrase] per ogni frase trascritta: dice cosa mandare al nucleo e se è un'aggiunta;
 * - [risposta] quando arriva una risposta: false = è la risposta a una domanda poi allungata, non si dice;
 * - [interrotta] quando Boss parla sopra JBoss (parola o tocco mentre la voce parla);
 * - [silenzio] quando una cattura finisce senza parlato: true = si riascolta, false = il popup si chiude.
 * La risposta a una conferma in attesa passa com'è e non cambia la domanda.
 *
 * Regole dell'accodamento (le parole nuove vanno sulla domanda di prima invece di aprirne un'altra):
 * 1. la domanda di prima non ha ancora risposta (JBoss ci pensa): si accoda sempre;
 * 2. la risposta è arrivata (o è stata interrotta) da meno di [finestraAggiuntaMs] e la frase nuova
 *    comincia con una parola d'aggiunta («e», «anche», «aggiungi», «poi», «inoltre», «in più»…): si accoda;
 * 3. altrimenti è una domanda nuova.
 * Mai accodate: le risposte a una conferma in attesa («invia», «annulla», «paga», …) e la frase
 * con cui Boss chiude il popup. Le conferme restano dove sono: questa classe non conferma niente.
 *
 * Pura e senza Android: si prova sulla JVM (ConversazioneTest). Va usata da un thread solo (il principale).
 */
class Conversazione(
    private val finestraAggiuntaMs: Long = 30_000L,
    /** Quante catture senza parlato di fila, a domanda chiusa, prima di chiudere il popup. */
    private val silenziMax: Int = 2,
    /** Oltre questo tempo senza risposta la domanda si considera chiusa (il popup può chiudersi). */
    private val attesaRispostaMaxMs: Long = 180_000L,
) {
    data class Decisione(
        /** Il testo da mandare al nucleo (la frase nuova, o la domanda di prima con l'aggiunta). */
        val testo: String,
        /** Vero = le parole nuove si sono accodate alla domanda di prima. */
        val accodata: Boolean,
        /** Vero = la risposta in arrivo alla domanda di prima non va detta (ne arriva una per il testo intero). */
        val scartaRispostaPrecedente: Boolean,
    )

    var aperta: Boolean = false
        private set

    /** La domanda in corso (con le aggiunte), null se non ce n'è. */
    var domanda: String? = null
        private set

    /** Le ultime parole sentite, per il popup. */
    var ultimeParole: String = ""
        private set

    /** Vero fra una domanda mandata e la sua risposta. */
    var inAttesaRisposta: Boolean = false
        private set

    private var rispostaMs: Long = Long.MIN_VALUE / 2
    private var domandaMs: Long = 0L
    private var risposteDaScartare = 0
    private var silenzi = 0

    fun apri() {
        if (aperta) return
        aperta = true
        silenzi = 0
    }

    /** Chiude il popup: si dimentica la domanda. Una risposta già in viaggio si dirà comunque. */
    fun chiudi() {
        aperta = false
        domanda = null
        ultimeParole = ""
        silenzi = 0
        risposteDaScartare = 0
        inAttesaRisposta = false
        rispostaMs = Long.MIN_VALUE / 2
    }

    /**
     * Una frase trascritta. [confermaInAttesa] = c'è una bozza o un pagamento che aspetta il sì di Boss:
     * la frase è la sua risposta e passa com'è.
     */
    fun nuovaFrase(frase: String, adessoMs: Long, confermaInAttesa: Boolean): Decisione {
        val f = frase.trim()
        silenzi = 0
        if (f.isNotEmpty()) ultimeParole = f
        // La risposta a una conferma passa com'è e non tocca la domanda: la gestisce chi ha chiesto la conferma.
        if (confermaInAttesa) return Decisione(f, accodata = false, scartaRispostaPrecedente = false)
        val prima = domanda
        if (!aperta || prima.isNullOrBlank() || eRispostaAConferma(f)) {
            return nuova(f, adessoMs)
        }
        if (inAttesaRisposta) {
            // Regola 1: JBoss ci sta ancora pensando, le parole nuove allungano la domanda.
            risposteDaScartare++
            return accoda(prima, f, scarta = true, adessoMs)
        }
        if (adessoMs - rispostaMs <= finestraAggiuntaMs && eAggiunta(f)) {
            // Regola 2: «e anche…» subito dopo la risposta.
            return accoda(prima, f, scarta = false, adessoMs)
        }
        return nuova(f, adessoMs)
    }

    private fun nuova(f: String, adessoMs: Long): Decisione {
        domanda = f
        inAttesaRisposta = f.isNotEmpty()
        domandaMs = adessoMs
        return Decisione(f, accodata = false, scartaRispostaPrecedente = false)
    }

    private fun accoda(prima: String, f: String, scarta: Boolean, adessoMs: Long): Decisione {
        val unita = unisci(prima, f)
        domanda = unita
        inAttesaRisposta = true
        domandaMs = adessoMs
        return Decisione(unita, accodata = true, scartaRispostaPrecedente = scarta)
    }

    /** È arrivata una risposta. false = è quella di una domanda poi allungata: non si dice. */
    fun risposta(adessoMs: Long): Boolean {
        if (risposteDaScartare > 0) {
            risposteDaScartare--
            return false
        }
        inAttesaRisposta = false
        rispostaMs = adessoMs
        return true
    }

    /** Boss ha parlato sopra JBoss: la risposta è finita lì, la finestra per le aggiunte parte adesso. */
    fun interrotta(adessoMs: Long) {
        silenzi = 0
        if (!inAttesaRisposta) rispostaMs = adessoMs
    }

    /**
     * Una cattura è finita senza parlato. Con una domanda in attesa di risposta si riascolta sempre
     * (Boss può aggiungere mentre JBoss pensa); a domanda chiusa, dopo [silenziMax] di fila il popup si chiude.
     * true = riapri l'ascolto, false = chiudi il popup.
     */
    fun silenzio(adessoMs: Long): Boolean {
        if (!aperta) return false
        // Una risposta che non arriva più (nucleo fermo) non tiene aperto il popup per sempre.
        if (inAttesaRisposta && adessoMs - domandaMs > attesaRispostaMaxMs) {
            inAttesaRisposta = false
            risposteDaScartare = 0
        }
        if (inAttesaRisposta) return true
        silenzi++
        return silenzi < silenziMax
    }

    companion object {
        /** Le parole che aprono un'aggiunta alla domanda di prima. */
        private val AGGIUNTE = listOf(
            "e ", "ed ", "e poi", "e anche", "anche", "aggiungi", "aggiungo", "inoltre", "in più", "poi ",
            "pure", "ah e", "ah ed", "oltre a", "ancora", "e inoltre", "e magari", "magari anche",
        )

        /** Le risposte a una conferma: non si accodano mai a una domanda. */
        private val CONFERME = setOf(
            "invia", "inviala", "invialo", "manda", "mandala", "mandalo", "conferma", "confermo", "annulla",
            "lascia stare", "no", "sì", "si", "ok", "paga", "procedi", "cancella", "elimina",
        )

        /** Le frasi che chiudono il popup a voce. */
        private val CHIUSURE = setOf(
            "basta", "basta così", "basta cosi", "chiudi", "chiudi pure", "stop", "fine", "grazie basta",
            "va bene grazie", "ok grazie", "grazie è tutto", "grazie e tutto", "è tutto", "e tutto", "niente",
        )

        private fun pulita(f: String): String =
            f.lowercase().replace(Regex("[.,;:!?«»\"]"), " ").replace(Regex("\\s+"), " ").trim()

        fun eAggiunta(f: String): Boolean {
            val p = pulita(f) + " "
            return AGGIUNTE.any { p.startsWith(if (it.endsWith(" ")) it else "$it ") }
        }

        fun eRispostaAConferma(f: String): Boolean = pulita(f) in CONFERME

        fun chiudeIlPopup(f: String): Boolean = pulita(f) in CHIUSURE

        /** «apri WhatsApp» + «e scrivi a Marco» = «apri WhatsApp, e scrivi a Marco». */
        fun unisci(prima: String, aggiunta: String): String {
            val a = prima.trim().trimEnd('.', ',', ';')
            val b = aggiunta.trim()
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            return "$a, $b"
        }
    }
}

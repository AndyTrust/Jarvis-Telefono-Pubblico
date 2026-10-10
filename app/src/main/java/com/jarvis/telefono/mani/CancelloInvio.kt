package com.jarvis.telefono.mani

/**
 * La regola di Boss portata NEL CODICE (1.2.0): prima dell'invio finale di un messaggio o di
 * una mail Jarvis mostra la bozza e aspetta «invia». Prima era solo una frase nel prompt della
 * VPS: un modello distratto, o un testo ostile letto sullo schermo, poteva toccare «Invia»
 * senza chiedere. Adesso ogni tocco su un pulsante d'invio passa di qui, e senza un permesso
 * dato da Boss (a voce o con un tocco) viene rifiutato dal telefono stesso.
 *
 * Per tutte le altre azioni non c'è nessuna conferma (decisione di Boss).
 * Niente Android qui dentro: si prova sulla JVM. Da usare dal thread principale.
 */
class CancelloInvio(
    private val orologio: () -> Long = System::currentTimeMillis,
    /** Quanto vale un «sì» dato con request_send_confirmation per il tocco successivo. */
    private val durataPermessoMs: Long = 60_000L,
) {
    data class Richiesta(
        val id: String,
        val app: String,
        val destinatario: String,
        val bozza: String,
        /** L'app in primo piano quando la bozza è stata proposta: l'invio si fa solo lì. */
        val pacchetto: String?,
        /** true = alla conferma il telefono tocca Invia da solo; false = dà solo il permesso (vecchio flusso). */
        val toccaDaSolo: Boolean,
        val creataMs: Long,
        /** 0.7.0: un pagamento o un ordine: parte solo con un tocco di Boss (mai a voce), dopo il riepilogo. */
        val acquisto: Boolean = false,
    )

    enum class Esito { INVIA, ANNULLA, ALTRO }

    private var sospesa: Richiesta? = null
    private var permessoFinoA = 0L

    /** La richiesta in attesa di Boss, se c'è. */
    fun sospesa(): Richiesta? = sospesa

    /** Mette in attesa una richiesta. Una sola alla volta: quella di prima, se c'era, torna indietro (va annullata). */
    fun proponi(r: Richiesta): Richiesta? {
        val prima = sospesa
        sospesa = r
        return prima
    }

    /** Toglie la richiesta [id] (o quella in attesa se [id] è null) e la restituisce. */
    fun chiudi(id: String? = null): Richiesta? {
        val r = sospesa ?: return null
        if (id != null && r.id != id) return null
        sospesa = null
        return r
    }

    /** Boss ha detto sì nel vecchio flusso: il prossimo tocco su «Invia» passa, una volta sola. */
    fun concediPermesso() {
        permessoFinoA = orologio() + durataPermessoMs
    }

    /** Consuma il permesso se c'è ancora. */
    fun consumaPermesso(): Boolean {
        val ok = orologio() <= permessoFinoA
        permessoFinoA = 0L
        return ok
    }

    fun permessoValido(): Boolean = orologio() <= permessoFinoA

    /** L'interruttore di emergenza: niente in attesa, niente permessi. */
    fun azzera(): Richiesta? {
        permessoFinoA = 0L
        return chiudi()
    }

    private var bloccataFinoA = 0L

    /**
     * 1.2.4: Boss ha detto o toccato «Annulla» (prima era «Ferma»). Toglie la bozza in attesa
     * (la restituisce, va risposta), cancella i permessi e rifiuta le azioni di QUESTA richiesta
     * per [durataMs], o finché arriva [nuovaRichiesta].
     */
    fun annullaRichiesta(durataMs: Long): Richiesta? {
        bloccataFinoA = orologio() + durataMs
        return azzera()
    }

    /** Le azioni della richiesta annullata si rifiutano ancora? */
    fun richiestaBloccata(): Boolean = orologio() < bloccataFinoA

    /** Una richiesta nuova di Boss, o la fine di quella annullata: le azioni ripartono. */
    fun nuovaRichiesta() {
        bloccataFinoA = 0L
    }

    companion object {
        /**
         * 1.2.4 (Boss 07/10: «Ferma non serve, rischiamo solo di creare confusione»): le sole
         * scelte di una conferma d'invio, da sinistra a destra, nel pannello e nella notifica.
         */
        val SCELTE = listOf("Annulla", "Invia")

        /**
         * Dopo il tocco su Invia: è partito? 1.2.1 diceva «il campo non si è svuotato» quando il
         * campo vuoto di WhatsApp mostrava il suggerimento «Messaggio» (07/10 16:17): il modello
         * allora rileggeva lo schermo e riapriva l'app, 20 s in più. Conta se la bozza è sparita.
         */
        fun verificaInvio(appCambiata: Boolean, campiPrima: List<String>, campiDopo: List<String>, bozza: String): String {
            val b = bozza.trim()
            return when {
                appCambiata -> "l'app ha chiuso la bozza"
                campiDopo.all { it.isBlank() } -> "il campo del testo si è svuotato"
                campiPrima.isNotEmpty() && campiDopo.size < campiPrima.size -> "il campo del testo si è svuotato"
                b.length >= 3 && campiDopo.none { it.contains(b.take(40)) } -> "la bozza non è più nel campo: partita"
                else -> "toccato Invia, ma la bozza è ancora nel campo: controlla con read_screen"
            }
        }

        private val SI = setOf(
            "invia", "inviala", "invialo", "inviali", "invio", "manda", "mandala", "mandalo", "spedisci",
            "conferma", "confermo", "confermato", "ok", "okay", "si", "sì", "vai", "procedi", "pure", "certo",
            "perfetto", "send", "yes", "esatto", "giusto", "va", "bene", "pronto", "fallo", "ora", "adesso",
            // 0.5.0: la conferma di una chiamata.
            "chiama", "chiamalo", "chiamala", "chiamami",
        )
        private val PAROLE_SI_FORTI = setOf(
            "invia", "inviala", "invialo", "inviali", "invio", "manda", "mandala", "mandalo", "spedisci",
            "conferma", "confermo", "confermato", "ok", "okay", "si", "sì", "vai", "procedi", "send", "yes", "fallo", "bene",
            "chiama", "chiamalo", "chiamala",
        )
        private val NO = setOf(
            "no", "annulla", "annullala", "non", "inviare", "mandare", "inviarla", "mandarla", "lascia", "stare",
            "ferma", "fermati", "stop", "cancella", "aspetta", "niente", "lasciala", "perdere", "grazie",
        )
        private val PAROLE_NO_FORTI = setOf("no", "annulla", "annullala", "non", "ferma", "fermati", "stop", "cancella", "aspetta", "niente", "lascia", "lasciala")
        private val SALUTI = setOf("jarvis", "jboss", "hey", "ehi", "ei", "ok jarvis", "ok jboss")
        private val NON_PAROLA = Regex("[^a-z0-9àèéìòù' ]")

        /**
         * Cosa ha detto Boss mentre una bozza aspetta. Solo frasi corte fatte di parole note
         * valgono come sì o no: «sì ma cambia l'ora» è [Esito.ALTRO] e torna al modello con le
         * parole di Boss, che così può correggere la bozza nello stesso giro.
         */
        fun interpreta(testo: String): Esito {
            val parole = testo.lowercase()
                .replace(NON_PAROLA, " ")
                // Whisper spezza «invia» in «In via.» (07/10): si ricuce prima di contare le parole.
                .replace(Regex("\\bin via(l[aoei])?\\b")) { "invia" + (it.groupValues[1]) }
                .split(' ', '\'')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it !in SALUTI }
            if (parole.isEmpty()) return Esito.ALTRO
            if (parole.size <= 5 && parole.all { it in NO } && parole.any { it in PAROLE_NO_FORTI }) return Esito.ANNULLA
            if (parole.any { it == "non" }) return Esito.ALTRO
            if (parole.size <= 5 && parole.all { it in SI } && parole.any { it in PAROLE_SI_FORTI }) return Esito.INVIA
            return Esito.ALTRO
        }
    }
}

/**
 * Un elemento dello schermo ridotto a stringhe, per decidere senza Android se è un pulsante
 * d'invio e quale pulsante premere.
 */
data class NodoInfo(
    val id: String? = null,
    val testo: String? = null,
    val descrizione: String? = null,
    val cliccabile: Boolean = true,
    val abilitato: Boolean = true,
)

object ParoleInvio {
    /**
     * App dove «Invia» non manda niente a una persona (un prompt a Gemini, una ricerca):
     * lì il cancello non chiede conferma. Decisione di Boss: conferma solo per messaggi e mail.
     */
    val SENZA_CONFERMA = setOf(
        ComponiIntent.GEMINI, ComponiIntent.GOOGLE_APP,
        "com.openai.chatgpt", "com.anthropic.claude", "ai.perplexity.app.android", "ai.x.grok", "ai.x.grok.bot",
    )

    // \b ASCII: niente UNICODE_CHARACTER_CLASS (Android non lo regge, crash della 1.1.0).
    // Inizio d'etichetta: «Invia messaggio», «Pubblica storia», «Conferma pagamento»…
    private val ETICHETTA = Regex(
        "^(invia|invio|send|spedisci|manda|pubblica|publish|share now|condividi ora|tweet|posta ora|" +
            "conferma pagamento|conferma e paga|conferma ordine|conferma acquisto|acquista ora|compra ora|buy now|place order|" +
            "paga ora|pay now|invia denaro|send money|effettua pagamento|effettua ordine|procedi al pagamento|completa l.acquisto|completa ordine)\\b"
    )
    // Parole corte che valgono solo come etichetta intera: «Posta» (X) sì, «Posta in arrivo» (Gmail) no.
    private val ESATTE = setOf("post", "posta", "paga", "pay", "compra", "acquista", "ordina", "rispondi e invia", "reply", "commenta")
    private val ID_INVIO = setOf(
        "send", "send_button", "sendbutton", "send_btn", "btn_send", "button_send", "compose_send",
        "menu_send", "send_message", "send_icon", "sendbtn", "send_container", "conversation_send",
        "share_button_post", "button_post", "post_button", "tweet_button", "composer_post_button", "pay_button", "buy_button", "place_order_button",
    )

    private val ID_COMPOSER = setOf(
        "entry", "message_edit_text", "compose_message_text", "row_thread_composer_edittext", "chat_input",
        "composer_edit_text", "message_input", "edit_text_message", "body", "wc_body", "editor", "compose_edit_text",
    )

    /** 0.5.0: la pagina si sta ancora riempiendo (Google «Elaborazione in corso…», «Caricamento…»)? */
    fun paginaInCaricamento(testi: List<String>): Boolean = testi.any {
        val t = it.trim().lowercase()
        t.startsWith("elaborazione in corso") || t.startsWith("caricamento") || t == "loading" || t.startsWith("loading…") || t.startsWith("generazione in corso")
    }

    /** È il campo dove si scrive un messaggio o una mail (non una ricerca)? */
    fun campoMessaggio(id: String?): Boolean = nomeId(id) in ID_COMPOSER

    /** La parte dopo «:id/» di un id di risorsa, minuscola. */
    fun nomeId(id: String?): String? = id?.substringAfterLast(":id/")?.substringAfterLast('/')?.lowercase()?.takeIf { it.isNotBlank() }

    /**
     * È un'etichetta da pulsante d'invio finale (messaggio, mail, post, commento, story,
     * pagamento, tweet)? «Inviato» e «Posta in arrivo» no. Boss 2026-10-07: conferma solo lì.
     */
    fun etichettaDiInvio(etichetta: String?): Boolean {
        val e = etichetta?.trim()?.lowercase() ?: return false
        if (e.isEmpty() || e.length > 40) return false
        return e in ESATTE || ETICHETTA.containsMatchIn(e)
    }

    fun eInvio(n: NodoInfo): Boolean =
        nomeId(n.id) in ID_INVIO || etichettaDiInvio(n.descrizione) || etichettaDiInvio(n.testo)

    /**
     * Fra gli elementi visibili, quale è il pulsante d'invio da premere: prima l'id
     * (stabile), poi la descrizione, poi il testo. -1 se non c'è.
     */
    fun trovaPulsante(nodi: List<NodoInfo>, testoPreferito: String? = null): Int {
        if (!testoPreferito.isNullOrBlank()) {
            val p = testoPreferito.trim().lowercase()
            nodi.indexOfFirst { it.abilitato && (it.descrizione?.trim()?.lowercase() == p || it.testo?.trim()?.lowercase() == p) }
                .takeIf { it >= 0 }?.let { return it }
        }
        val perId = nodi.indexOfFirst { it.abilitato && nomeId(it.id) in ID_INVIO }
        if (perId >= 0) return perId
        val perDescrizione = nodi.indexOfFirst { it.abilitato && etichettaDiInvio(it.descrizione) }
        if (perDescrizione >= 0) return perDescrizione
        return nodi.indexOfFirst { it.abilitato && etichettaDiInvio(it.testo) }
    }
}

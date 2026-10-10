package com.jarvis.telefono.bolla

/**
 * Cosa scrive la bolla di stato (1.2.2, Boss 2026-10-07: «quando dico Hey Jarvis si deve
 * aprire una bolla, in modo che io capisca che sta lavorando, che sta aprendo l'app che ho
 * chiesto»). Funzioni pure: si provano sulla JVM (BollaTest) senza telefono.
 *
 * Il giro: «Ti ascolto…» → «Ho sentito» → «Sto lavorando…» → il nome dell'azione presa dallo
 * strumento che la VPS chiama («Apro WhatsApp…») → «Aspetto il tuo «invia»» → «Fatto» o
 * «Errore: …».
 */
object TestiBolla {

    /** Il colore della bolla, cioè che tipo di momento è. */
    enum class Tono { ASCOLTO, LAVORO, ATTESA, FATTO, ERRORE }

    /** [agente]: 0.2.0, l'id dell'agente che sta lavorando (avatar nella bolla); null = Jarvis. */
    data class Riga(
        val titolo: String,
        val dettaglio: String = "",
        val tono: Tono,
        val agente: String? = null,
        /** 0.3.4: lo ha deciso il cervello della VPS («JBoss (VPS) → Mani: Apro WhatsApp…»). */
        val daVps: Boolean = false,
    )

    const val MAX_DETTAGLIO = 90

    /**
     * 0.2.0: il titolo con la delega. Jarvis è il capo: se un agente esegue, la bolla dice
     * «Jarvis → Postino: Apro la mail…»; se fa Jarvis stesso, il titolo resta com'è.
     */
    fun titolo(riga: Riga): String {
        val conAgente = riga.agente != null && com.jarvis.telefono.agenti.CatalogoAgenti.predefinito(riga.agente) != null
        if (!conAgente && !riga.daVps) return riga.titolo
        return "${com.jarvis.telefono.agenti.Competenze.delega(if (conAgente) riga.agente else null, riga.daVps)}: ${riga.titolo}"
    }

    /** 0.3.4: la frase è andata al cervello della VPS e si aspetta la sua prima mossa. */
    /** 0.4.2: la VPS lavora con i suoi strumenti («comando sulla VPS: …», «cerco sul web»). */
    fun vpsAlLavoro(testo: String) = Riga("Al lavoro sulla VPS…", accorcia(testo.ifBlank { "ci sto lavorando" }, 70), Tono.LAVORO, daVps = true)

    fun allaVps(frase: String) = Riga("Ci penso…", if (frase.isBlank()) "chiedo al cervello della VPS" else "«${accorcia(frase, 60)}» · cervello della VPS", Tono.LAVORO, daVps = true)

    val TI_ASCOLTO = Riga("Ti ascolto…", "parla pure", Tono.ASCOLTO)
    val TRASCRIVO = Riga("Ho capito, trascrivo…", "", Tono.LAVORO)
    val NON_SENTITO = Riga("Non ho sentito niente", "ridì «JBoss» quando vuoi", Tono.ERRORE)
    val NON_COLLEGATO = Riga("Errore: non sono collegato al server", "riprova tra poco", Tono.ERRORE)
    val ASPETTO_INVIA = Riga("Aspetto il tuo «invia»", "di' «invia» o «annulla»", Tono.ATTESA)

    fun accorcia(testo: String, max: Int = MAX_DETTAGLIO): String {
        val t = testo.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    fun hoSentito(frase: String) = Riga("Ho sentito", "«${accorcia(frase)}»", Tono.LAVORO)

    fun stoLavorando(frase: String) = Riga("Sto lavorando…", if (frase.isBlank()) "" else "«${accorcia(frase)}»", Tono.LAVORO)

    /**
     * L'azione di uno strumento della VPS, in parole. [arg] legge un campo del comando
     * (stringa vuota se manca). null = non cambia la bolla (attese, stato tecnico).
     */
    fun perStrumento(azione: String, arg: (String) -> String): Riga? {
        fun lavoro(t: String, d: String = "") = Riga(t, d, Tono.LAVORO)
        return when (azione) {
            "componi" -> when (arg("tipo").lowercase()) {
                "whatsapp" -> lavoro("Apro WhatsApp…", destinatario(arg))
                "mail", "email" -> lavoro("Apro la mail…", destinatario(arg))
                "sms" -> lavoro("Apro i messaggi…", destinatario(arg))
                "chiama", "telefono" -> lavoro("Preparo la chiamata…", destinatario(arg))
                "telegram" -> lavoro("Apro Telegram…")
                "mappe", "maps", "naviga" -> lavoro("Apro le mappe…", accorcia(arg("dove")))
                "calendario", "evento" -> lavoro("Apro il calendario…", accorcia(arg("titolo")))
                "link", "url" -> lavoro("Apro il link…")
                "google", "cerca" -> lavoro("Cerco su Google…", accorcia(arg("domanda")))
                else -> lavoro("Preparo la bozza…")
            }
            "cerca_google" -> lavoro("Cerco su Google…", accorcia(arg("domanda")))
            "gemini_chiedi" -> lavoro("Chiedo a Gemini…", accorcia(arg("domanda")))
            "apri_app", "open_app" -> {
                val nome = arg("nome").ifEmpty { arg("app") }.ifEmpty { arg("pacchetto") }
                lavoro(if (nome.isBlank()) "Apro l'app…" else "Apro ${nomeApp(nome)}…")
            }
            "cerca_in_app" -> {
                val app = arg("app")
                lavoro(if (app.isBlank()) "Cerco nell'app…" else "Cerco in ${nomeApp(app)}…", accorcia(arg("testo")))
            }
            "cerca_contatto" -> lavoro("Cerco in rubrica…", accorcia(arg("nome")))
            "elenca_app" -> lavoro("Guardo le app installate…")
            "read_screen", "leggi_schermo", "screenshot" -> lavoro("Guardo lo schermo…")
            "tap", "tocca", "tocca_elemento", "pressione_lunga" -> lavoro("Tocco sullo schermo…")
            "type_text", "scrivi" -> lavoro("Scrivo…")
            "scorri", "swipe" -> lavoro("Scorro…")
            "key" -> lavoro("Premo ${arg("name").lowercase()}…")
            "invio_tastiera" -> lavoro("Premo invio…")
            "compila_accesso" -> lavoro("Faccio l'accesso…")
            "invia_bozza", "request_send_confirmation" -> {
                val a = arg("app"); val d = arg("destinatario")
                val su = listOf(a, d).filter { it.isNotBlank() }.joinToString(" · ")
                Riga(ASPETTO_INVIA.titolo, if (su.isBlank()) ASPETTO_INVIA.dettaglio else accorcia("$su — di' «invia» o «annulla»"), Tono.ATTESA)
            }
            "emergenza" -> Riga("Mani ferme", "interruttore di emergenza", Tono.ERRORE)
            "attendi", "registro", "modo_tecnico", "stato_tecnico" -> null
            else -> lavoro("Sto lavorando…")
        }
    }

    private fun destinatario(arg: (String) -> String): String {
        val chi = arg("nome").ifEmpty { arg("a") }.ifEmpty { arg("numero") }
        return if (chi.isBlank()) "" else accorcia("a $chi")
    }

    /** «whatsapp» → «WhatsApp», «com.google.android.gm» → «com.google.android.gm» (resta com'è). */
    fun nomeApp(nome: String): String {
        val n = nome.trim()
        if (n.contains('.')) return n
        return NOMI[n.lowercase()] ?: n.replaceFirstChar { it.uppercase() }
    }

    private val NOMI = mapOf(
        "whatsapp" to "WhatsApp", "gmail" to "Gmail", "youtube" to "YouTube", "telegram" to "Telegram",
        "instagram" to "Instagram", "facebook" to "Facebook", "spotify" to "Spotify", "chrome" to "Chrome",
        "mail" to "la mail", "email" to "la mail", "e-mail" to "la mail", "maps" to "Maps", "linkedin" to "LinkedIn",
        "x" to "X", "twitter" to "X", "revolut" to "Revolut", "gemini" to "Gemini",
    )

    /** Esito di uno strumento d'invio, dopo il «invia» di Boss. */
    fun invio(esito: String): Riga = when (esito) {
        "invia" -> Riga("Invio…", "", Tono.LAVORO)
        "annulla" -> Riga("Annullato", "niente inviato · JBoss resta in ascolto", Tono.ERRORE)
        "scaduta" -> Riga("Conferma scaduta", "non ho mandato niente", Tono.ERRORE)
        else -> Riga("Non ho inviato", "ho girato a JBoss quello che hai detto", Tono.LAVORO)
    }

    /**
     * La risposta finale di Jarvis. Una domanda resta aperta (aspetta Boss); un errore del
     * ponte è rosso; il resto è «Fatto», con l'inizio della risposta sotto.
     */
    fun perRisposta(testo: String, errore: Boolean): Riga {
        val t = testo.trim()
        if (errore) return Riga("Errore", accorcia(t.removePrefix("Ho avuto un problema a ragionarci:").trim()), Tono.ERRORE)
        val primaFrase = t.split(Regex("(?<=[.!?])\\s+")).firstOrNull { it.isNotBlank() } ?: t
        if (t.endsWith("?")) return Riga("Ti chiedo una cosa", accorcia(t.split(Regex("(?<=[.!])\\s+")).last()), Tono.ATTESA)
        return Riga("Fatto", accorcia(primaFrase), Tono.FATTO)
    }
}

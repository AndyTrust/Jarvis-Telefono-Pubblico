package com.jarvis.telefono.vps

/**
 * Locale o VPS (regola approvata da Boss nel layout del 07/10, «UI agenti Jarvis Telefono», 3f).
 *
 * In quest'ordine:
 * 1. Parole esplicite all'inizio della frase: «sulla VPS …» manda alla VPS, «qui …» resta sul telefono.
 * 2. La scelta per agente: Postino e Ricercatore «automatico», Social «sulla VPS», Mani e Scrittore «qui».
 * 3. Automatico: un compito che si annuncia lungo (ricerche, fonti, arretrato, 20 elementi o più) va alla VPS;
 *    [promuovi] dice quando un compito partito qui si è mostrato lungo (oltre 20 s o 20 elementi).
 * Un invio vero (messaggio, mail) parte sempre dal telefono, dopo il sì di Boss: la VPS prepara soltanto.
 * Se la VPS serve ma il modulo è spento o manca la rete, la risposta lo dice: mai un finto «fatto».
 * Kotlin puro, provato in InstradamentoTest.
 */
object Instradamento {

    enum class Dove { QUI, VPS, AUTOMATICO }

    sealed class Esito {
        /** Resta sul telefono; [frase] senza la parola «qui». */
        data class Locale(val frase: String) : Esito()
        /** Va alla VPS adesso. */
        data class Vps(val frase: String, val agente: String, val motivo: String) : Esito()
        /** Modulo acceso ma niente rete: in coda, parte appena torna la rete. */
        data class InCoda(val frase: String, val agente: String, val messaggio: String) : Esito()
        /** Serve la VPS e il modulo è spento: si dice e basta. */
        data class Rifiuto(val messaggio: String) : Esito()
    }

    val PREDEFINITI: Map<String, Dove> = mapOf(
        "postino" to Dove.AUTOMATICO,
        "ricercatore" to Dove.AUTOMATICO,
        "social" to Dove.VPS,
        "mani" to Dove.QUI,
        "scrittore" to Dove.QUI,
    )

    const val SPENTO = "Il Collegamento Jarvis è spento: accendilo in Impostazioni, Collegamento Jarvis. Non l'ho mandato."

    /** Oltre questi limiti un compito partito qui si manda alla VPS. */
    const val SOGLIA_MS = 20_000L
    const val SOGLIA_ELEMENTI = 20

    private val SULLA_VPS = Regex("""^\s*(sulla|alla|in|nella)\s+vps\b[\s,:;.-]*""", RegexOption.IGNORE_CASE)
    private val QUI = Regex("""^\s*(qui|sul telefono|in locale)\b[\s,:;.-]*""", RegexOption.IGNORE_CASE)

    private val LUNGO = Regex(
        """\b(ricerca|ricerche|cerca (le |delle )?fonti|approfondisci|approfondimento|confronta|analizza|analisi|""" +
            """bandi|normativa|leggi (le |tutte le )?fonti|arretrato|tutte le (mail|email|pec)|smista|smistamento|""" +
            """report|rapporto completo|scarica tutt|elenca tutt|riassumi (gli |le )?(articoli|fonti|notizie))""",
        RegexOption.IGNORE_CASE,
    )
    private val ELEMENTI = Regex(
        """\b(\d{2,5})\s+(mail|email|e-mail|messaggi|elementi|file|fonti|righe|pagine|articoli|contatti|foto|documenti|fatture|pec|notizie|risultati|siti|link)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val POSTA = Regex("""\b(mail|email|e-mail|pec|posta|caselle?)\b""", RegexOption.IGNORE_CASE)
    private val SOCIAL = Regex("""\b(post|tweet|thread|linkedin|social|x\.com|instagram)\b""", RegexOption.IGNORE_CASE)
    /** 0.5.0: «apri Instagram» apre l'app sul telefono (le mani), non è un lavoro del Social sulla VPS. */
    private val APRI = Regex("""^\s*(apri|aprimi|apra|riapri|avvia|avviami|lancia|lanciami|mi apri|puoi aprire|vai su|vai in|entra in)\b""", RegexOption.IGNORE_CASE)
    private val SUL_TELEFONO = Regex("""\b(notifich[ea]|messaggi (nuovi|non letti|arrivati)|nuovi messaggi|chi mi ha scritto|ho ricevuto|mi hanno scritto|non lett[ie])\b""", RegexOption.IGNORE_CASE)
    private val INVIO = Regex("""^\s*(manda|invia|inoltra|rispondi|scrivi (un |una )?(messaggio|whatsapp|sms|mail|email)|chiama)\b""", RegexOption.IGNORE_CASE)

    /** L'agente per una frase senza agente scelto: dalle parole. */
    fun agentePer(frase: String): String = when {
        POSTA.containsMatchIn(frase) -> "postino"
        SOCIAL.containsMatchIn(frase) -> "social"
        LUNGO.containsMatchIn(frase) -> "ricercatore"
        else -> "generico"
    }

    /** La frase annuncia un compito lungo? Parole da ricerca/arretrato, o 20 elementi e più («240 mail»). */
    fun sembraLungo(frase: String): Boolean {
        if (LUNGO.containsMatchIn(frase)) return true
        return ELEMENTI.findAll(frase).any { (it.groupValues[1].toIntOrNull() ?: 0) >= SOGLIA_ELEMENTI }
    }

    /** Un compito partito qui è diventato lungo: si manda alla VPS. */
    fun promuovi(durataMs: Long, elementi: Int): Boolean = durataMs > SOGLIA_MS || elementi > SOGLIA_ELEMENTI

    /**
     * @param agente l'agente scelto (null = dalle parole)
     * @param preferenza la scelta di Boss per quell'agente (null = predefinito)
     */
    fun decidi(
        frase: String,
        agente: String? = null,
        preferenza: Dove? = null,
        moduloAcceso: Boolean,
        rete: Boolean,
    ): Esito {
        val f = frase.trim()
        val forzaVps = SULLA_VPS.containsMatchIn(f)
        val forzaQui = !forzaVps && QUI.containsMatchIn(f)
        val pulita = when {
            forzaVps -> f.replaceFirst(SULLA_VPS, "").trim()
            forzaQui -> f.replaceFirst(QUI, "").trim()
            else -> f
        }
        if (forzaQui) return Esito.Locale(pulita)
        val ag = agente ?: agentePer(pulita)
        // Un invio parte sempre da qui, salvo che Boss chieda esplicitamente la VPS (che comunque si ferma sulla conferma).
        if (!forzaVps && INVIO.containsMatchIn(pulita)) return Esito.Locale(pulita)
        // 0.5.0 (KO del 08/10: «apri Instagram» e «apri LinkedIn» finivano al Social sulla VPS): aprire un'app è delle mani.
        if (!forzaVps && APRI.containsMatchIn(pulita)) return Esito.Locale(pulita)
        // 0.6.0 (Boss 08/10): CRM di lavoro e Patrimonio si seguono in sola lettura, e le credenziali stanno solo sulla
        // VPS: la domanda va sempre là, con le regole del contesto davanti (collegamento/Contesti.kt).
        com.jarvis.telefono.collegamento.Contesti.di(pulita)?.let { c ->
            if (!moduloAcceso) return Esito.Rifiuto("Per ${c.nome} serve il Collegamento Jarvis: accendilo in Impostazioni. Non l'ho mandato.")
            val testo = com.jarvis.telefono.collegamento.Contesti.lavoro(c, pulita)
            if (!rete) return Esito.InCoda(testo, c.id, "Non c'è rete: la domanda su ${c.nome} resta in coda e parte appena torna. Non è ancora partita.")
            return Esito.Vps(testo, c.id, "contesto seguito: ${c.nome}, sola lettura")
        }
        // 0.5.0: notifiche e messaggi arrivati nelle app si leggono sul telefono (le mani, col cervello della VPS se serve):
        // il Social dei lavori sulla VPS non vede il telefono.
        if (!forzaVps && SUL_TELEFONO.containsMatchIn(pulita)) return Esito.Locale(pulita)
        val dove = when {
            forzaVps -> Dove.VPS
            else -> preferenza ?: PREDEFINITI[ag] ?: Dove.AUTOMATICO
        }
        val vps = when (dove) {
            Dove.QUI -> false
            Dove.VPS -> true
            Dove.AUTOMATICO -> sembraLungo(pulita)
        }
        if (!vps) return Esito.Locale(pulita)
        val motivo = when {
            forzaVps -> "chiesto «sulla VPS»"
            dove == Dove.VPS -> "agente $ag: sempre sulla VPS"
            else -> "compito lungo"
        }
        if (!moduloAcceso) {
            // Chiesto esplicitamente (parole o agente «sulla VPS»): si dice che non parte. Automatico: si prova qui,
            // dove il cervello del telefono lo fa o dice onestamente che non sa farlo.
            return if (forzaVps || dove == Dove.VPS) Esito.Rifiuto(SPENTO)
            else Esito.Locale(pulita)
        }
        if (!rete) return Esito.InCoda(pulita, ag, "Non c'è rete: lo tengo in coda e lo mando alla VPS appena torna. Non è ancora partito.")
        return Esito.Vps(pulita, ag, motivo)
    }

    /** 2026-10-10: l'esito della frase dell'utente in cronologia: l'origine della chat dell'agente, o «alla VPS». */
    fun esitoFrase(origine: String): String = if (origine.startsWith("chat-")) origine else "alla VPS"
}

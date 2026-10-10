package com.jarvis.telefono.configura

/** Le parole delle schermate di configurazione (0.4.0): frasi da persona normale, niente gergo. Pure. */
object TestiConfigura {

    const val BENVENUTO = "Ciao, sono JBoss. Per aiutarti mi servono alcuni permessi del telefono. " +
        "Ogni riga dice a cosa serve; quelle con il cerchio vuoto puoi farle anche dopo."

    val SPIEGA_PERMESSO = mapOf(
        "microfono" to "per sentirti quando dici «Hey Boss»",
        "notifiche" to "per dirti quando un lavoro è finito o serve il tuo sì",
        "accessibilita" to "le «mani»: aprire app, leggere lo schermo, preparare messaggi",
        "rubrica" to "per trovare il numero quando dici «scrivi a Marco»",
        "batteria" to "perché il risparmio batteria non mi spenga di notte",
        "blocco" to "serve per vedere password e token (PIN o impronta)",
    )

    /**
     * Cosa premere nella pagina di Android. Da Android 14 la pagina Accessibilità ha due schermate prima
     * dell'interruttore; su Samsung (One UI) la voce si chiama «App installate», su altri «Servizi installati».
     */
    fun guidaAccessibilita(sdk: Int, samsung: Boolean): String =
        guidaAccessibilita(sdk, if (samsung) Marca.SAMSUNG else Marca.ALTRA)

    /** La marca del telefono, per i testi di aiuto. Solo testi: il codice dell'app è uguale per tutte. */
    enum class Marca(val nome: String, val pagina: String) {
        SAMSUNG("Samsung", "samsung"),
        PIXEL("Google Pixel", "google"),
        XIAOMI("Xiaomi / Redmi / POCO", "xiaomi"),
        ONEPLUS("OnePlus", "oneplus"),
        MOTOROLA("Motorola", "motorola"),
        HUAWEI("Huawei / Honor", "huawei"),
        OPPO("OPPO / Realme", "oppo"),
        ALTRA("Android", "");

        /** La pagina di dontkillmyapp.com per questa marca (solo un link, da aprire nel browser). */
        val link: String get() = if (pagina.isEmpty()) "https://dontkillmyapp.com" else "https://dontkillmyapp.com/$pagina"

        companion object {
            /** Da Build.MANUFACTURER e Build.BRAND. */
            fun da(produttore: String?, marchio: String? = null): Marca {
                val m = ((produttore ?: "") + " " + (marchio ?: "")).lowercase()
                return when {
                    "samsung" in m -> SAMSUNG
                    "google" in m -> PIXEL
                    "xiaomi" in m || "redmi" in m || "poco" in m -> XIAOMI
                    "oneplus" in m -> ONEPLUS
                    "motorola" in m || "lenovo" in m -> MOTOROLA
                    "huawei" in m || "honor" in m -> HUAWEI
                    "oppo" in m || "realme" in m -> OPPO
                    else -> ALTRA
                }
            }
        }
    }

    fun guidaAccessibilita(sdk: Int, marca: Marca): String {
        val elenco = when (marca) {
            Marca.SAMSUNG -> "«App installate»"
            Marca.XIAOMI -> "«App scaricate»"
            Marca.HUAWEI -> "«Servizi installati» (o «App scaricate»)"
            else -> "«Servizi installati» (o «App scaricate»)"
        }
        return buildString {
            append("Ti porto alla pagina Accessibilità di Android. Lì:\n")
            if (sdk >= 34) {
                append("1. Tocca ").append(elenco).append(".\n")
                append("2. Nella seconda schermata tocca «JBoss».\n")
            } else {
                append("1. Cerca ").append(elenco).append(" e tocca «JBoss».\n")
                append("2. (Se non lo vedi, scorri in fondo alla pagina.)\n")
            }
            append("3. Accendi l'interruttore e conferma con «Consenti».\n")
            append("4. Torna qui con il tasto indietro: la riga diventa verde da sola.\n\n")
            append("Se l'interruttore è grigio e dice «Impostazione con restrizioni»: apri «Informazioni app» ")
            append("(il pulsante qui sotto), tocca i tre puntini in alto a destra, poi «Consenti impostazioni con restrizioni», ")
            append("e riprova. Android lo chiede alle app installate da un file invece che dal Play Store.")
        }
    }

    /**
     * Come non far spegnere JBoss dal risparmio batteria, marca per marca. Il primo passo (l'esclusione di Android)
     * lo apre l'app; gli altri sono menu della marca che l'app non può aprire da sola in modo sicuro.
     * Fonte dei percorsi: dontkillmyapp.com (solo link) e prove sui telefoni dove indicato in docs/COMPATIBILITA.md.
     */
    fun guidaBatteria(marca: Marca): String = buildString {
        append("1. Tocca «Escludi ora» e rispondi «Consenti»: Android non mette più JBoss in sospensione.\n")
        when (marca) {
            Marca.SAMSUNG -> append("2. Impostazioni → Batteria → Limiti utilizzo in background → «App mai in sospensione» → aggiungi JBoss.\n")
            Marca.PIXEL -> append("2. Di solito basta il passo 1. Se la voce si ferma: Impostazioni → App → JBoss → Utilizzo batteria app → «Senza restrizioni».\n")
            Marca.XIAOMI -> {
                append("2. Impostazioni → App → Gestisci app → JBoss → «Avvio automatico»: acceso.\n")
                append("3. Nella stessa pagina: Risparmio batteria → «Nessuna restrizione».\n")
                append("4. Nelle app recenti tieni premuta JBoss e tocca il lucchetto.\n")
            }
            Marca.ONEPLUS, Marca.OPPO -> {
                append("2. Impostazioni → Batteria → Ottimizzazione batteria → JBoss → «Non ottimizzare».\n")
                append("3. Impostazioni → App → JBoss → Utilizzo batteria → accendi «Consenti attività in background» e «Avvio automatico».\n")
            }
            Marca.MOTOROLA -> append("2. Impostazioni → App → JBoss → Batteria app → «Senza restrizioni».\n")
            Marca.HUAWEI -> {
                append("2. Impostazioni → Batteria → Avvio app → JBoss → gestione manuale, accendi tutti e tre gli interruttori.\n")
                append("Nota: senza i servizi Google alcune parti (QR, voce di Google) non ci sono: vedi la guida.\n")
            }
            Marca.ALTRA -> append("2. Cerca nelle impostazioni «batteria» o «avvio automatico» e togli le restrizioni a JBoss.\n")
        }
        append("\nGuida per ").append(marca.nome).append(": ").append(marca.link)
    }

    const val MODELLI = "Per capire quello che dici senza internet servono due file grandi (circa 415 MB): " +
        "il riconoscimento della voce e l'impronta della tua voce. Si scaricano una volta, solo con il Wi-Fi."

    const val CERVELLO_DOMANDA = "Come pensa JBoss?"
    const val CERVELLO_VPS = "La mia VPS (codice QR)"
    const val CERVELLO_API = "Chiave API (Anthropic o OpenAI)"
    const val CERVELLO_CODEX = "Codex / OpenAI sulla VPS"
    const val CERVELLO_ABBONAMENTO = "Il tuo abbonamento Claude non si condivide con altri: ognuno usa il suo, sulla sua VPS."
    const val VPS_SPIEGA = "La VPS è il tuo computer sempre acceso su internet: lì JBoss fa i lavori lunghi e legge la posta. " +
        "Per collegarla: apri il codice QR di Jarvis sul computer, poi qui tocca «Scansiona QR». Il QR vale 5 minuti, una volta."
    const val RIAVVIO_DECIDE_BOSS = "Scritto sulla VPS. Jarvis lo userà dopo che lo avrà applicato e riavviato: " +
        "il riavvio lo decidi tu, JBoss non lo fa da solo."

    const val POSTA = "Le caselle che JBoss può leggere e preparare. La password resta cifrata nel telefono; " +
        "va alla VPS solo se tocchi «Invia al Postino» e confermi con l'impronta."
    const val PRESET_RIGA = "Preset: Gmail · Outlook · iCloud · Libero · Aruba · PEC · altro"

    const val PROVA = "Ultimo passo: tre frasi per vedere che tutto funziona. Parla normalmente, JBoss ti ascolta."

    const val GOOGLE_ONESTO = "JBoss non chiede mai la password di Google e non si collega al tuo account Google da solo. " +
        "Usa l'account che c'è già sul telefono, aprendo le app Google come faresti tu. " +
        "Per Gmail sulla VPS serve una «password per app», che puoi togliere quando vuoi."

    const val SICUREZZA_MOSTRA = "Il valore si vede per 30 secondi, poi si nasconde. Niente screenshot su questa schermata."
}

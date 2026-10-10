package com.jarvis.telefono.configura

/**
 * L'indice delle Impostazioni, 0.6.1 (layout approvato da Boss il 08/10): 7 gruppi divisi per uso, ogni
 * impostazione in UN posto solo, e la ricerca in alto che porta a qualunque voce in due tocchi.
 * Kotlin puro: la ricerca e «un posto solo» si provano sulla JVM (IndiceImpostazioniTest).
 */
object IndiceImpostazioni {

    enum class Gruppo(val chiave: String, val titolo: String, val descrizione: String) {
        VOCE("voce", "Voce", "ascolto, parola, fine frase"),
        POSTA("posta", "Postino e mail", "caselle, Gmail"),
        COLLEGAMENTO("collegamento", "Collegamento Jarvis", "VPS, lavori, QR"),
        ACCOUNT("account", "Account e accessi", "cervello, accessi siti, Google"),
        SICUREZZA("sicurezza", "Sicurezza", "blocco schermo, segreti, copia cifrata"),
        ASPETTO("aspetto", "Aspetto", "tema, animazioni"),
        INFO("info", "Informazioni", "versione, licenze");

        companion object {
            fun da(chiave: String?): Gruppo? = values().firstOrNull { it.chiave == chiave }
        }
    }

    /** Una voce che si può cercare: il titolo che si vede, il gruppo dove sta, le parole che la trovano. */
    data class Voce(val titolo: String, val gruppo: Gruppo, val parole: List<String> = emptyList())

    val VOCI: List<Voce> = listOf(
        // Voce
        Voce("JBoss acceso", Gruppo.VOCE, listOf("accendi", "spegni", "servizio")),
        Voce("Ascolta «Hey Boss» sempre", Gruppo.VOCE, listOf("parola", "attivazione", "hey boss", "hey jboss", "ascolto")),
        Voce("Risposte brevi a voce", Gruppo.VOCE, listOf("voce sintetica", "breve", "parla")),
        Voce("Solo la mia voce", Gruppo.VOCE, listOf("impronta", "filtro", "riconosce")),
        Voce("Fine frase", Gruppo.VOCE, listOf("silenzio", "secondi", "pausa")),
        Voce("Volume dei segnali", Gruppo.VOCE, listOf("suono", "tin", "volume")),
        Voce("App della posta", Gruppo.VOCE, listOf("samsung", "gmail", "mail predefinita")),
        Voce("WhatsApp a me stesso", Gruppo.VOCE, listOf("numero", "whatsapp", "me stesso")),
        Voce("Chi scrive quello che dico", Gruppo.VOCE, listOf("trascrizione", "google", "senza rete", "trascrittore")),
        Voce("Voce senza rete (modelli)", Gruppo.VOCE, listOf("modelli", "scarica", "offline", "file")),
        Voce("Permessi", Gruppo.VOCE, listOf("microfono", "notifiche", "accessibilità", "batteria", "rubrica")),
        // Postino e mail
        Voce("Caselle di posta", Gruppo.POSTA, listOf("mail", "casella", "aggiungi", "pec", "postino", "imap")),
        Voce("App Google", Gruppo.POSTA, listOf("gmail", "calendar", "drive")),
        // Collegamento
        Voce("Collegamento Jarvis acceso", Gruppo.COLLEGAMENTO, listOf("vps", "modulo", "interruttore")),
        Voce("Abbina con QR", Gruppo.COLLEGAMENTO, listOf("qr", "codice", "abbina", "incolla")),
        Voce("Lavori sulla VPS", Gruppo.COLLEGAMENTO, listOf("terminale", "lavori")),
        Voce("Webapp e memoria condivisa", Gruppo.COLLEGAMENTO, listOf("webapp", "memoria", "notifiche jarvis")),
        // Account e accessi
        Voce("Cervello", Gruppo.ACCOUNT, listOf("chiave", "token", "claude", "openai", "codex")),
        Voce("Accessi siti", Gruppo.ACCOUNT, listOf("password", "siti", "login", "utente")),
        Voce("Account Google", Gruppo.ACCOUNT, listOf("google", "account")),
        // Sicurezza
        Voce("Blocco schermo", Gruppo.SICUREZZA, listOf("pin", "impronta digitale")),
        Voce("Segreti nel telefono", Gruppo.SICUREZZA, listOf("cassaforte", "mostra")),
        Voce("Copia cifrata", Gruppo.SICUREZZA, listOf("esporta", "importa", "cambio telefono", "svuota")),
        Voce("Registro degli accessi", Gruppo.SICUREZZA, listOf("registro")),
        Voce("Modo tecnico", Gruppo.SICUREZZA, listOf("diagnosi", "tecnico")),
        // Aspetto
        Voce("Tema", Gruppo.ASPETTO, listOf("chiaro", "scuro", "sistema", "colori")),
        Voce("Animazioni ridotte", Gruppo.ASPETTO, listOf("animazioni", "movimento")),
        // Informazioni
        Voce("Versione", Gruppo.INFO, listOf("versione", "aggiornamento")),
        Voce("Info e licenze", Gruppo.INFO, listOf("licenze", "mit", "personaggi", "avatar")),
    )

    private fun normalizza(s: String): String = s.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e').replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
        .replace(Regex("[«»\"'.,]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Le voci che contengono tutte le parole cercate (titolo, parole o nome del gruppo). Vuoto = niente. */
    fun cerca(testo: String): List<Voce> {
        val q = normalizza(testo)
        if (q.length < 2) return emptyList()
        val pezzi = q.split(' ')
        return VOCI.filter { v ->
            val dove = normalizza((listOf(v.titolo, v.gruppo.titolo) + v.parole).joinToString(" "))
            pezzi.all { dove.contains(it) }
        }.sortedBy { if (normalizza(it.titolo).startsWith(q)) 0 else 1 }
    }
}

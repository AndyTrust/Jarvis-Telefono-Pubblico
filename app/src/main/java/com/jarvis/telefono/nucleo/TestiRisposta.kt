package com.jarvis.telefono.nucleo

/**
 * Le mani rispondono con testi pensati per un modello (l'app 1.2.x li mandava a Claude sulla VPS):
 * qui diventano la frase breve che Jarvis dice a Boss. Kotlin puro, provato sulla JVM.
 * Stringa vuota = non dire niente (qualcun altro l'ha già detto, per esempio la conferma scaduta).
 */
object TestiRisposta {

    fun dopoErrore(action: String, errore: String, app: String = ""): String {
        val e = errore.lowercase()
        return when {
            e.contains("boss ha annullato") || e.contains("boss ha fermato") -> "Annullato: non ho mandato niente."
            e.contains("entro 2 minuti") -> ""
            e.contains("sostituita da una nuova bozza") -> ""
            e.contains("non ha confermato") -> "Non ho inviato niente."
            e.contains("bloccato dal telefono") -> "Il telefono ha bloccato l'invio senza la tua conferma."
            e.contains("accessibilità di jarvis non è attivo") || e.contains("accessibilità di jboss non è attivo") || e.contains("accessibilità spenta") ->
                "Mi serve l'accessibilità accesa: Impostazioni, Accessibilità, JBoss."
            e.contains("permesso della rubrica") -> "Mi serve il permesso della rubrica: te l'ho chiesto sullo schermo."
            e.contains("telefono è bloccato") -> Schermo.MESSAGGIO_BLOCCATO
            action == "invia_bozza" && e.contains("pulsante invia") -> "Ho preparato la bozza ma non trovo il pulsante Invia: non ho mandato niente."
            action == "apri_app" || action == "open_app" ->
                "Non trovo l'app" + (if (app.isNotBlank()) " $app" else "") + " sul telefono."
            action == "cerca_in_app" && e.contains("campo di ricerca") -> "Non trovo dove cercare in $app."
            e.contains("gemini") && e.contains("non è installata") -> "Gemini non è installata."
            e.contains("operazione fermata") || e.contains("richiesta annullata") -> "Annullato."
            else -> "Non ci sono riuscito: " + primaFrase(errore)
        }
    }

    /** La prima frase di un errore, senza lo «Schermo:» che segue certi messaggi. */
    fun primaFrase(errore: String): String {
        val t = errore.substringBefore("Schermo:").trim()
        val fine = Regex("[.!?](\\s|$)").find(t)?.range?.first
        return (if (fine != null) t.substring(0, fine + 1) else t).take(160).ifEmpty { "errore sconosciuto." }
    }
}

package com.jarvis.telefono.postino

/**
 * Le scorciatoie della chat Postino (Boss, 2026-10-08): comandi brevi, scritti o detti, che si fanno
 * SUL TELEFONO senza viaggio alla VPS: «successiva», «precedente», «chiudi», «archivia», «rispondi»,
 * «invia» sulla mail aperta; «seleziona tutte le fatture», «deseleziona» sulla lista.
 * Codice puro (provato sulla JVM). Quello che non è una scorciatoia torna null: sulla mail aperta
 * diventa una frase per JBoss («istruisci N: …»), sulla lista un comando per numero.
 */
object ComandiBrevi {

    sealed class Breve {
        object Successiva : Breve()
        object Precedente : Breve()
        object Chiudi : Breve()
        object Archivia : Breve()
        object Rispondi : Breve()
        object Invia : Breve()
        object Cestina : Breve()
        /** 0.6.6: sulla mail aperta, come i tasti Spam (con conferma), Fatto, Dopo. */
        object Spam : Breve()
        object Fatto : Breve()
        object Dopo : Breve()
        object Deseleziona : Breve()
        /** livelli di posta.py: fatture 7,3; promozioni 5,6; notifiche 4; vuoto = tutte. */
        data class Seleziona(val livelli: Set<Int>, val nome: String) : Breve()
    }

    private fun pulito(t: String) = ComandiPostino.senzaAccenti(t.lowercase()).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val SU_MAIL = listOf(
        Regex("^(successiva|prossima|avanti|la prossima|vai avanti|next)$") to Breve.Successiva,
        Regex("^(precedente|indietro|quella prima|torna indietro)$") to Breve.Precedente,
        Regex("^(chiudi|torna alla lista|lista|esci)$") to Breve.Chiudi,
        Regex("^(archivia|archiviala|archivia questa)$") to Breve.Archivia,
        Regex("^(rispondi|rispondigli|rispondile|scrivi la risposta)$") to Breve.Rispondi,
        Regex("^(invia|inviala|manda|mandala|spedisci)$") to Breve.Invia,
        Regex("^(cestina|cestinala|buttala|elimina|eliminala)$") to Breve.Cestina,
        Regex("^(spam|e spam|segna spam|segnala come spam)$") to Breve.Spam,
        Regex("^(fatto|fatta|gestita|chiusa)$") to Breve.Fatto,
        Regex("^(dopo|piu tardi|rimanda|la vedo dopo)$") to Breve.Dopo,
    )

    /** La scorciatoia, o null se il testo è altro (numeri, due punti, una frase lunga). */
    fun capisci(testo: String, mailAperta: Boolean): Breve? {
        if (testo.contains(':')) return null
        val t = pulito(testo)
        if (t.isEmpty()) return null
        if (Regex("^(deseleziona|togli (la )?selezione|annulla (la )?selezione)").containsMatchIn(t)) return Breve.Deseleziona
        Regex("^seleziona (tutte|tutto|tutti)( (le|la|i|gli))?( ?(.*))?$").find(t)?.let { m ->
            val cosa = m.groupValues[5].trim()
            return when {
                cosa.isEmpty() -> Breve.Seleziona(emptySet(), "tutte")
                cosa.startsWith("fattur") -> Breve.Seleziona(setOf(7, 3), "le fatture")
                cosa.startsWith("promo") || cosa.startsWith("pubblicit") || cosa.startsWith("newsletter") || cosa.startsWith("spam") ->
                    Breve.Seleziona(setOf(5, 6), "le promozioni")
                cosa.startsWith("notific") || cosa.startsWith("ricevut") -> Breve.Seleziona(setOf(4), "le notifiche")
                cosa.startsWith("da agire") || cosa.startsWith("urgent") -> Breve.Seleziona(setOf(1), "da agire")
                cosa.startsWith("da leggere") -> Breve.Seleziona(setOf(2), "da leggere")
                else -> null
            }
        }
        if (!mailAperta) return null
        return SU_MAIL.firstOrNull { (re, _) -> re.matches(t) }?.second
    }

    /**
     * 09/10 (verificatore sul telefono: «resoconto» con la mail 1 aperta diventava «istruisci 1: resoconto» e JBoss
     * preparava una bozza): una frase scritta sulla mail aperta [n] diventa una bozza («istruisci N: …») SOLO se è un
     * testo di risposta chiaro:
     * - Boss ha toccato Rispondi ([attesaRisposta]) e scrive cosa rispondere, almeno due parole;
     * - oppure la frase lo dice: «rispondi che…», «rispondigli…», «digli che…», «scrivi che…», «risposta: …».
     * Tutto il resto torna null: nessuna bozza, la pagina risponde «non ho capito».
     */
    fun istruzione(testo: String, n: Int?, attesaRisposta: Boolean): String? {
        if (n == null) return null
        val grezzo = testo.trim()
        if (grezzo.isEmpty()) return null
        val t = pulito(grezzo)
        val parole = t.split(' ').filter { it.isNotBlank() }
        val chiara = RISPOSTA.find(t)?.let { m -> t.substring(m.range.last + 1).trim().isNotEmpty() } == true
        if (chiara || (attesaRisposta && parole.size >= 2)) return "istruisci $n: $grezzo"
        return null
    }

    /** Le frasi che dicono da sole «è una risposta»: dopo il verbo deve esserci il contenuto. */
    private val RISPOSTA = Regex("^(rispondi(gli|le)?|risposta|scrivi(gli|le)?|digli|dille|di che|fagli sapere|falle sapere|" +
        "fai sapere|prepara (la |una )?(risposta|bozza))( (che|di|dicendo|cosi))?\\b")

    /** I numeri da selezionare per «seleziona tutte le …» fra le mail date (numero → livello). */
    fun numeri(b: Breve.Seleziona, voci: List<StatoPostino.Voce>): List<Int> =
        voci.filter { b.livelli.isEmpty() || it.livello in b.livelli }.map { it.numero }
}

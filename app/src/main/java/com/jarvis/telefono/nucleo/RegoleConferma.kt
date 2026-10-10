package com.jarvis.telefono.nucleo

import com.jarvis.telefono.mani.NodoInfo

/**
 * Quali comandi chiedono il sì di Boss sul telefono (JBoss 0.7.0, 2026-10-08).
 *
 * - LIBERA: si esegue (aprire app, leggere, cercare, sveglie, preparare bozze, riempire il carrello).
 * - CONFERMA: irreversibile verso altre persone (invio di messaggi e mail, chiamata): parte solo dopo
 *   Conferma (box in chat, pannello sopra l'app, notifica o «invia» a voce). La fa rispettare
 *   PhoneActionExecutor con CancelloInvio.
 * - ACQUISTO (regole di Boss del 08/10): ordini e acquisti su tutte le app, senza tetto di spesa. JBoss fa tutto il
 *   percorso; il pulsante finale che paga o ordina si preme solo con invia_bozza, dopo che Boss ha visto il riepilogo
 *   dettagliato letto dallo schermo ([riepilogoAcquisto]: totale, articoli, indirizzo, pagamento) e ha TOCCATO Paga
 *   (box in chat, pannello sopra l'app o notifica). A voce un pagamento non parte.
 *
 * Kotlin puro: provato in ComandiTest.
 */
object RegoleConferma {

    enum class Livello { LIBERA, CONFERMA, ACQUISTO }

    /** Le azioni delle mani che toccano altre persone e non si possono ritirare. */
    val AZIONI_CON_CONFERMA = setOf("invia_bozza", "request_send_confirmation", "invio_tastiera")

    /** Al modello che tocca da solo un pulsante di pagamento: la strada giusta è invia_bozza. */
    const val MSG_USA_INVIA_BOZZA =
        "BLOCCATO DAL TELEFONO: quello è un pulsante di pagamento o d'ordine. Usa invia_bozza con pulsante uguale alla sua " +
            "etichetta: il telefono mostra a Boss il riepilogo (totale, articoli, indirizzo, pagamento) e paga solo dopo il suo tocco su Paga."

    fun perAzione(action: String): Livello =
        if (action.trim().lowercase() in AZIONI_CON_CONFERMA) Livello.CONFERMA else Livello.LIBERA

    // \b ASCII come nel resto delle regole (Android non regge UNICODE_CHARACTER_CLASS).
    private val ACQUISTO = Regex(
        """\b(compra|comprami|comprare|compralo|comprala|acquista|acquistami|acquistare|""" +
            """fai (un |l')?ordine|fammi (un |l')?ordine|effettua (un |l')?ordine|metti nel carrello|aggiungi al carrello|""" +
            """paga (il|la|lo|con|subito|ora)|pagami|fai (un |il )?pagamento|fai un bonifico|manda (dei |i )?soldi|invia (dei |i )?soldi)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ORDINA = Regex("""\b(ordina|ordinami|ordiniamo|ordinare|prenota e paga)\b""", RegexOption.IGNORE_CASE)
    private val DOMICILIO = Regex(
        """\b(a domicilio|consegna|delivery|asporto|pizza|pizze|sushi|cena|pranzo|colazione|panino|panini|hamburger|kebab|""" +
            """poke|spesa|cibo|glovo|deliveroo|just ?eat|uber ?eats|amazon|esselunga|carrefour|conad)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** La frase chiede un acquisto o un ordine a domicilio? «apri Glovo» e «ordina le mail» restano liberi. */
    fun perFrase(frase: String): Livello {
        val f = frase.trim()
        if (ACQUISTO.containsMatchIn(f)) return Livello.ACQUISTO
        if (ORDINA.containsMatchIn(f) && DOMICILIO.containsMatchIn(f)) return Livello.ACQUISTO
        return Livello.LIBERA
    }

    /** Solo il pulsante FINALE (paga, ordina, conferma ordine): carrello e cassa restano liberi. */
    private val PULSANTE_ACQUISTO = Regex(
        """^(conferma pagamento|conferma e paga|conferma ordine|conferma acquisto|acquista|acquista ora|compra|compra ora|buy|buy now|""" +
            """place order|paga|paga ora|pay|pay now|invia denaro|send money|effettua pagamento|effettua ordine|procedi al pagamento|""" +
            """completa l.acquisto|completa ordine|ordina|ordina ora|order now|invia ordine)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ID_ACQUISTO = setOf("pay_button", "buy_button", "place_order_button")

    /** Un pulsante che paga o ordina: si preme solo dopo il riepilogo e il tocco di Boss. */
    fun pulsanteDiAcquisto(n: NodoInfo): Boolean {
        val id = n.id?.substringAfterLast('/')?.lowercase()
        if (id != null && id in ID_ACQUISTO) return true
        return listOfNotNull(n.testo, n.descrizione).any { PULSANTE_ACQUISTO.containsMatchIn(it.trim()) }
    }

    private val SOLDI = Regex("""(€|\beur\b|\beuro\b|\d+[.,]\d{2}\b)""", RegexOption.IGNORE_CASE)
    private val QUANTITA = Regex("""(\b\d+\s?[x\u00D7]\s)|(\s[x\u00D7]\s?\d+\b)""", RegexOption.IGNORE_CASE)
    private val TOTALE = Regex("""\b(totale|total|da pagare)\b""", RegexOption.IGNORE_CASE)
    private val VOCI = Regex(
        """\b(totale|subtotale|total|spedizione|consegna|delivery|indirizzo|address|via|viale|piazza|corso|""" +
            """pagamento|paga con|carta|visa|mastercard|paypal|satispay|contanti|apple pay|google pay|quantit|qta|""" +
            """articol|prodott|ordine|mancia|commissione|sconto|coupon|iva|arrivo|orario|entro)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Il riepilogo di un acquisto per Boss, dai testi visibili sullo schermo della cassa: il totale in testa, poi le righe
     * con importi, articoli, indirizzo, pagamento e consegna, in ordine, al massimo [max]. [nota] è quello che il cervello
     * ha già scritto. Le cifre sono quelle dello schermo, senza calcoli.
     */
    fun riepilogoAcquisto(testi: List<String>, nota: String = "", max: Int = 18): String {
        val pulite = testi.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        var totale: String? = null
        for ((i, t) in pulite.withIndex()) {
            if (!TOTALE.containsMatchIn(t) || t.lowercase().startsWith("sub")) continue
            totale = if (SOLDI.containsMatchIn(t)) t else pulite.getOrNull(i + 1)?.takeIf { SOLDI.containsMatchIn(it) }?.let { "$t $it" }
            if (totale != null) break
        }
        val righe = pulite.filter { it.length in 2..160 && (SOLDI.containsMatchIn(it) || VOCI.containsMatchIn(it) || QUANTITA.containsMatchIn(it)) }.distinct().take(max)
        return buildString {
            if (nota.isNotBlank()) append(nota.trim()).append("\n\n")
            append("TOTALE: ").append(totale ?: "non leggibile, controlla lo schermo").append("\n")
            if (righe.isEmpty()) append("(nessun dettaglio leggibile sullo schermo: controlla l'app prima di pagare)")
            else append(righe.joinToString("\n"))
        }.trim()
    }
}

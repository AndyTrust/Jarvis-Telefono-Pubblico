package com.jarvis.telefono.agenti

import com.jarvis.telefono.nucleo.Azione
import com.jarvis.telefono.nucleo.Piano

/**
 * Chi esegue cosa (0.2.0, Boss 07/10: «Jarvis è il capo: tutte le richieste si fanno a Jarvis, e
 * lui le smista agli agenti in base alle competenze»). L'unico posto con la mappa:
 *
 *   mail, posta, PEC, bozze                         → Postino
 *   social, feed, post, commenti                    → Social
 *   apri app, tocca, scorri, WhatsApp, SMS, chiama,
 *   rubrica, navigazione                            → Mani
 *   cerca, Google, Gemini, YouTube, ricerca         → Ricercatore web
 *   scrivi, riscrivi, componi un testo              → Scrittore
 *   il resto (ore, saluti, non capito)              → Jarvis stesso (null)
 *
 * [perPiano] decide per le azioni che il cervello ha scelto davvero (è quello che si mostra in
 * bolla e cronologia); [perFrase] legge la frase com'è detta ed è la prima scelta che userà il
 * cervello cloud prima di avere un piano. Le due tabelle stanno qui insieme e si provano insieme
 * (CompetenzeTest). Niente Android.
 */
object Competenze {

    /** Il nome del capo (Boss 07/10: «tutto diventa JBoss»). */
    const val CAPO = "JBoss"

    // ------------------------------------------------------------ dalle azioni del cervello

    private val APP_DI_RICERCA = setOf("youtube", "google", "chrome", "gemini")

    /** L'agente di un'azione delle mani; null = nessuno in particolare (resta a Jarvis). */
    fun perAzione(a: Azione): String? {
        val tipo = a["tipo"]?.toString()?.lowercase().orEmpty()
        return when (a.action) {
            "componi" -> when (tipo) {
                "mail", "email", "e-mail", "posta" -> CatalogoAgenti.POSTINO
                "google", "cerca", "youtube" -> CatalogoAgenti.RICERCATORE
                else -> CatalogoAgenti.MANI // whatsapp, sms, chiama, telegram, mappe, naviga, calendario, link
            }
            "cerca_google", "gemini_chiedi" -> CatalogoAgenti.RICERCATORE
            "cerca_in_app" -> {
                val app = a["app"]?.toString()?.lowercase().orEmpty()
                if (APP_DI_RICERCA.any { app.contains(it) }) CatalogoAgenti.RICERCATORE else CatalogoAgenti.MANI
            }
            "apri_app", "open_app", "cerca_contatto", "elenca_app", "read_screen", "leggi_schermo", "screenshot",
            "tap", "tocca", "tocca_elemento", "pressione_lunga", "type_text", "scrivi", "scorri", "swipe",
            "key", "invio_tastiera", "compila_accesso" -> CatalogoAgenti.MANI
            // invia_bozza conferma quello che un altro ha preparato: non decide chi esegue.
            else -> null
        }
    }

    /** L'agente di tutto il piano: il primo che ha una competenza, saltando le azioni neutre. */
    fun perPiano(p: Piano): String? {
        if (!p.capito) return null
        return p.azioni.firstOrNull { it.action != "invia_bozza" && it.action != "key" }?.let { perAzione(it) }
            ?: p.azioni.firstNotNullOfOrNull { perAzione(it) }
    }

    // ------------------------------------------------------------ dalla frase

    private fun parole(vararg p: String) = Regex("\\b(" + p.joinToString("|") + ")", RegexOption.IGNORE_CASE)

    private val APRI = Regex("^(apri|aprimi|avvia|lancia|mi apri|puoi aprire)\\b", RegexOption.IGNORE_CASE)
    private val POSTINO = parole("mail", "e-mail", "email", "posta", "pec", "casella", "bozz[ae]", "inbox", "allegat")
    private val SOCIAL = parole("social", "instagram", "facebook", "tiktok", "linkedin", "feed", "post\\b", "commenti", "follower", "stories", "storia su")
    private val MANI = parole(
        "whatsapp", "whats app", "sms", "messaggi[oa]?\\b", "chiama", "telefona", "rubrica", "contatt", "numero",
        "naviga", "portami", "indicazioni", "mappe", "maps", "telegram", "tocca", "premi", "scorri", "torna indietro",
    )
    private val RICERCATORE = parole("cerca", "cercami", "google", "gemini", "youtube", "ricerca", "trova", "notizie", "fonti", "informazioni su")
    private val SCRITTORE = parole("scrivi", "scrivimi", "riscrivi", "componi", "correggi", "riassumi", "traduci", "testo")

    /** Toglie «JBoss» (anche «jay boss», «gei boss»…), «Jarvis», «Hey …», «ok …» davanti: si parla a lui, non è una competenza. */
    fun pulisci(frase: String): String =
        frase.trim().replace(Regex("^(hey|ehi|ok|okay)?\\s*(jarvis|j\\.?\\s?boss|jay\\s?boss|gei\\s?boss|jei\\s?boss|ci\\s?boss|g\\s?boss)[,.!:]?\\s*", RegexOption.IGNORE_CASE), "").trim()

    /** L'agente per la frase com'è detta; null = la tiene Jarvis. L'ordine conta (frasi ambigue). */
    fun perFrase(frase: String): String? {
        val t = pulisci(frase)
        return when {
            t.isEmpty() -> null
            APRI.containsMatchIn(t) -> CatalogoAgenti.MANI
            POSTINO.containsMatchIn(t) -> CatalogoAgenti.POSTINO
            SOCIAL.containsMatchIn(t) -> CatalogoAgenti.SOCIAL
            MANI.containsMatchIn(t) -> CatalogoAgenti.MANI
            RICERCATORE.containsMatchIn(t) -> CatalogoAgenti.RICERCATORE
            SCRITTORE.containsMatchIn(t) -> CatalogoAgenti.SCRITTORE
            else -> null
        }
    }

    /** Le competenze in parole, per la scheda di Jarvis. */
    val IN_PAROLE: List<Pair<String, String>> = listOf(
        CatalogoAgenti.POSTINO to "mail, posta, PEC, bozze",
        CatalogoAgenti.RICERCATORE to "cerca, Google, Gemini, YouTube",
        CatalogoAgenti.SOCIAL to "social, feed, post, commenti",
        CatalogoAgenti.MANI to "apre le app, tocca, WhatsApp, SMS, chiama, mappe",
        CatalogoAgenti.SCRITTORE to "scrive e riscrive testi",
    )

    /** Il nome corto per la bolla e la cronologia: «Ricercatore web» → «Ricercatore». */
    fun nomeCorto(id: String?): String =
        id?.let { CatalogoAgenti.predefinito(it)?.nome?.substringBefore(' ') } ?: CAPO

    /** «JBoss → Mani», o «JBoss» se l'ha fatto lui. 0.3.4: [vps] = deciso dal cervello della VPS, «JBoss (VPS) → Mani». */
    fun delega(id: String?, vps: Boolean = false): String {
        val capo = if (vps) "$CAPO (VPS)" else CAPO
        return if (id == null || CatalogoAgenti.predefinito(id) == null) capo else "$capo → ${nomeCorto(id)}"
    }
}

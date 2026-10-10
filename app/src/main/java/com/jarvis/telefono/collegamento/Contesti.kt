package com.jarvis.telefono.collegamento

/**
 * Cosa JBoss segue oltre al telefono (0.6.0). Kotlin puro, provato in CollegamentoTest.
 *
 * - CRM di lavoro: un gestionale (per esempio Odoo) sulla VPS, database «crm»; credenziali solo sulla VPS.
 * - Patrimonio: database «patrimonio», una pagina del gestionale.
 * Sono due esempi neutri: nomi, database e indirizzi veri non stanno nell'APK, arrivano con la memoria condivisa
 * della VPS (GET /memoria/profilo, «contesti»). Senza VPS questa parte non fa niente.
 *
 * Regole uguali per tutti e due: SOLA LETTURA; avvisi solo quando Boss chiede o per un evento importante
 * (CodaNotifiche lascia in silenzio il resto); nessuna azione su clienti, preventivi, email o ordini senza il sì di
 * Boss. Il lavoro lo fa la VPS (agente «crm» o «patrimonio» in server/lavori.js): il telefono non tiene credenziali.
 */
object Contesti {

    data class Contesto(
        val id: String,
        val nome: String,
        val database: String,
        /** Le parole che lo riconoscono in una frase (già senza accenti, minuscole). */
        val parole: Regex,
    )

    val CRM = Contesto(
        "crm", "CRM di lavoro", "crm",
        Regex("""\b(crm|gestionale|clienti nuovi|richieste (dei )?clienti|preventivi? (aperti|nuovi|in corso))\b"""),
    )
    val PATRIMONIO = Contesto(
        "patrimonio", "Patrimonio", "patrimonio",
        Regex("""\b(patrimonio|patrimonia|portafoglio|investimenti|i miei soldi|conto titoli|reddito passivo)\b"""),
    )
    val TUTTI = listOf(CRM, PATRIMONIO)
    val SEGUITI: Set<String> = TUTTI.map { it.id }.toSet()

    const val REGOLE = "SOLA LETTURA. Nessuna azione su clienti, preventivi, email o ordini senza il sì di Boss: " +
        "se serve un'azione, scrivila come proposta. Numeri con fonte e data. Rispondi breve: cosa è cambiato e cosa è importante."

    /** Le azioni che su un contesto seguito non si fanno da JBoss: si propongono e basta. */
    private val AZIONE = Regex("""\b(scrivi|manda|invia|rispondi|cancella|elimina|modifica|cambia|aggiorna il|crea|compra|vendi|ordina|conferma il preventivo|accetta)\b""")

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Il contesto nominato nella frase, o null. «apri …» resta delle mani (apre l'app o il sito). */
    fun di(frase: String): Contesto? {
        val f = norm(frase)
        if (Regex("""^\s*(apri|aprimi|vai su|vai in)\b""").containsMatchIn(f)) return null
        return TUTTI.firstOrNull { it.parole.containsMatchIn(f) }
    }

    /** La frase chiede di FARE qualcosa (scrivere, mandare, cambiare) su un contesto seguito? */
    fun chiedeAzione(frase: String): Boolean = AZIONE.containsMatchIn(norm(frase))

    /** Il testo del lavoro per la VPS: la richiesta di Boss con le regole del contesto davanti. */
    fun lavoro(c: Contesto, frase: String): String =
        "[${c.nome} · database ${c.database}] $REGOLE\nRichiesta di Boss: ${frase.trim()}"
}

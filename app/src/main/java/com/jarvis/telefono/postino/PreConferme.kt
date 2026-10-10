package com.jarvis.telefono.postino

/**
 * Una sola conferma nel Postino (2026-10-10). Prima un invio chiedeva due tocchi: «Invia…» sulla bozza, poi il
 * riquadro Invia/Annulla della VPS (e lo stesso riquadro compariva anche nel filo di JBoss, nella notifica e nel
 * pannello di sistema). Una cancellazione di più di 15 mail chiedeva «Elimina» sul telefono e poi la conferma della VPS.
 *
 * Adesso il tocco che l'utente fa SUL TELEFONO, guardando la bozza intera o l'elenco delle mail, vale come la conferma.
 * Quando la VPS chiede il sì per QUEL lavoro, il telefono risponde da solo «invia», ma solo se:
 * - il lavoro è quello partito da quel tocco (stesso id), entro [VALIDITA_MS];
 * - il tipo è lo stesso («invio» o «cancellazione»);
 * - per un invio, il testo che la VPS sta per spedire comincia con il testo che l'utente ha visto, e il destinatario
 *   (se l'utente l'ha scritto) è quello.
 * Se uno di questi controlli non torna, la conferma resta da fare: si mostra UN riquadro Invia/Annulla, come prima.
 * Un permesso vale una volta sola. La regola «nessun invio senza il sì dal telefono» resta: il sì è il tocco su Invia.
 *
 * Kotlin puro, provato in PreConfermeTest.
 */
object PreConferme {

    const val VALIDITA_MS = 3 * 60_000L

    data class Permesso(
        val lavoroId: String,
        val azione: String,
        val testo: String?,
        val destinatario: String?,
        val numero: Int?,
        val scade: Long,
    )

    enum class Esito {
        /** Nessun permesso: si mostra il riquadro Invia/Annulla. */
        DA_CONFERMARE,
        /** Confermata adesso dal tocco di prima: chi chiama manda «invia» alla VPS (una volta sola). */
        CONFERMATA_ORA,
        /** Già confermata da un altro punto dell'app: non si manda niente e non si mostra niente. */
        GIA_CONFERMATA,
    }

    private val permessi = HashMap<String, Permesso>()
    private val usate = LinkedHashSet<String>()

    @Synchronized
    fun registra(
        lavoroId: String,
        azione: String,
        testo: String? = null,
        destinatario: String? = null,
        numero: Int? = null,
        ora: Long = System.currentTimeMillis(),
    ) {
        if (lavoroId.isBlank()) return
        permessi[lavoroId] = Permesso(lavoroId, azione, testo?.takeIf { it.isNotBlank() }, destinatario?.takeIf { it.isNotBlank() }, numero, ora + VALIDITA_MS)
    }

    @Synchronized
    fun dimentica(lavoroId: String) { permessi.remove(lavoroId) }

    @Synchronized
    fun azzera() { permessi.clear(); usate.clear() }

    /** La VPS chiede il sì per [azioneId] del lavoro [lavoroId]. */
    @Synchronized
    fun verifica(
        lavoroId: String,
        azioneId: String,
        azione: String,
        destinatario: String,
        anteprima: String,
        motivo: String,
        ora: Long = System.currentTimeMillis(),
    ): Esito {
        if (azioneId.isNotEmpty() && azioneId in usate) return Esito.GIA_CONFERMATA
        val p = permessi[lavoroId] ?: return Esito.DA_CONFERMARE
        if (ora > p.scade) { permessi.remove(lavoroId); return Esito.DA_CONFERMARE }
        if (!p.azione.equals(azione, ignoreCase = true)) return Esito.DA_CONFERMARE
        if (azione.equals("invio", ignoreCase = true) && !testoUguale(p, destinatario, anteprima, motivo)) return Esito.DA_CONFERMARE
        permessi.remove(lavoroId)
        if (azioneId.isNotEmpty()) {
            usate += azioneId
            while (usate.size > 50) usate.remove(usate.first())
        }
        return Esito.CONFERMATA_ORA
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim().lowercase()

    private fun testoUguale(p: Permesso, destinatario: String, anteprima: String, motivo: String): Boolean {
        val visto = p.testo ?: return false
        val inVia = norm(anteprima)
        if (inVia.isEmpty() || !inVia.startsWith(norm(visto))) return false
        p.destinatario?.let { d -> if (!norm(destinatario).contains(norm(d))) return false }
        p.numero?.let { n -> if (Regex("numero \\d+").containsMatchIn(motivo) && !Regex("numero $n\\b").containsMatchIn(motivo)) return false }
        return true
    }
}

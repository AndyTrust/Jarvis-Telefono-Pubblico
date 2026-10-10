package com.jarvis.telefono.collegamento

/**
 * Il solo punto che decide chi risponde (JBoss 0.6.0, Boss 08/10: «un'app unica, sennò rischiamo conflitti anche con
 * comunicazioni e notifiche»). Kotlin puro, provato in ArbitroTest.
 *
 * La VPS ha UNA sessione per il telefono e per il sito (chat del Command Center, Mac e Windows). Ogni risposta che
 * arriva sul canale «mani» porta `origine` («telefono», «sito», «sottofondo») e, per le frasi di JBoss, il `rid` che
 * JBoss aveva mandato. Le regole, in quest'ordine:
 *
 * 1. `origine = sito`        → IGNORA: la risposta è già nella webapp; JBoss non la dice e non la notifica.
 * 2. `origine = sottofondo`  → NOTIFICA: un avviso di Jarvis fuori turno, va nella coda unica delle notifiche.
 * 3. `tardiva`               → TARDIVA (tenuta da parte dalla VPS mentre il telefono era scollegato).
 * 4. con `rid`               → la frase in corso se è lei; scartata se Boss l'aveva annullata; altrimenti TARDIVA.
 * 5. senza `rid` (VPS vecchia) → come la 0.5.0: debito, frase in corso, tardiva, altrimenti IGNORA.
 *
 * Gli strumenti (`tool_call`) seguono già la stessa regola in CanaleMani: JBoss esegue solo quelli delle sue frasi.
 */
object Arbitro {

    enum class Chi {
        /** La risposta della frase che JBoss sta aspettando: la dice JBoss. */
        FRASE_IN_CORSO,
        /** La risposta di una frase già chiusa (scaduta): JBoss la dice comunque, una volta. */
        TARDIVA,
        /** Un avviso fuori turno: una notifica nella coda unica, nessuna voce. */
        NOTIFICA,
        /** La risposta di una frase che Boss ha annullato: si butta. */
        SCARTA,
        /** Non è di JBoss (chat del sito): la mostra la webapp, JBoss tace. */
        IGNORA,
    }

    const val ORIGINE_TELEFONO = "telefono"
    const val ORIGINE_SITO = "sito"
    const val ORIGINE_SOTTOFONDO = "sottofondo"

    /**
     * @param ridInCorso il rid della frase che JBoss aspetta adesso (null = nessuna)
     * @param annullati i rid delle frasi annullate da Boss
     * @param debiti / [tardive] i contatori della 0.5.0, per una VPS che non manda il rid
     */
    fun risposta(
        origine: String?,
        rid: String?,
        tardiva: Boolean,
        ridInCorso: String?,
        annullati: Set<String>,
        debiti: Int,
        tardive: Int,
    ): Chi {
        when (origine) {
            ORIGINE_SITO -> return Chi.IGNORA
            ORIGINE_SOTTOFONDO -> return Chi.NOTIFICA
        }
        if (tardiva) return if (rid != null && rid in annullati) Chi.SCARTA else Chi.TARDIVA
        if (!rid.isNullOrBlank()) {
            return when {
                rid == ridInCorso -> Chi.FRASE_IN_CORSO
                rid in annullati -> Chi.SCARTA
                else -> Chi.TARDIVA
            }
        }
        return when {
            debiti > 0 -> Chi.SCARTA
            ridInCorso != null -> Chi.FRASE_IN_CORSO
            tardive > 0 -> Chi.TARDIVA
            else -> Chi.IGNORA
        }
    }

    /** Un rid corto e senza dati personali: ora + contatore. */
    fun nuovoRid(contatore: Int, oraMs: Long = System.currentTimeMillis()): String = "jb-${oraMs.toString(36)}-$contatore"
}

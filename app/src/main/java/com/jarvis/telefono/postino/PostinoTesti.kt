package com.jarvis.telefono.postino

/**
 * I testi delle tre azioni verso JBoss nella pagina del Postino (2026-10-10). JBoss è l'app del telefono; Jarvis è la
 * webapp sul computer: qui si parla solo di JBoss. Kotlin puro, provato in PostinoTestiTest.
 */
object PostinoTesti {
    const val PARLA = "JBoss ti ascolta: di' cosa vuoi fare (per esempio «leggi la prossima» o «rispondi che arrivo»)."

    /** [presenza] = il nome di PresenzaJBoss (ACCESO, IN_AVVIO, PAUSA, SPENTO…). */
    fun voceNonPronta(presenza: String): String = when (presenza) {
        "PAUSA" -> "La voce di JBoss è in pausa: tocca Riprendi nella notifica, poi riprova."
        "IN_AVVIO" -> "La voce di JBoss si sta accendendo: riprova tra qualche secondo."
        "GUASTO" -> "La voce di JBoss non riesce a partire: guarda in Impostazioni → Voce cosa manca."
        else -> "La voce di JBoss è spenta: accendila in Impostazioni → Voce. Intanto puoi scrivere qui sotto."
    }
}

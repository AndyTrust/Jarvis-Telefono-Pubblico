package com.jarvis.telefono.configura

import com.jarvis.telefono.voce.SceltaTrascrittore

/**
 * Le regole della pagina Impostazioni → Voce (0.6.1, Boss 08/10: «voce regolabile dall'app: sì»), senza Android:
 * limiti e passi dei valori, le parole che si vedono. Provate in VoceImpostazioniTest.
 */
object RegoleVoce {
    /** Fine frase: da 1,0 a 3,0 secondi, a passi di 0,1 (approvato nel layout). */
    const val FINE_MIN = 1.0
    const val FINE_MAX = 3.0
    const val FINE_PASSO = 0.1

    fun fineFrase(attuale: Double, passi: Int): Double {
        val v = Math.round((attuale + passi * FINE_PASSO) * 10.0) / 10.0
        return v.coerceIn(FINE_MIN, FINE_MAX)
    }

    /** Il volume dei segnali in percento (0-100, a passi di 10) e come si salva (0..1). */
    fun volumePercento(v: Float?): Int = (((v ?: 1f).coerceIn(0f, 1f) * 10).let { Math.round(it) } * 10)
    fun volumeDaPercento(p: Int): Double = (p.coerceIn(0, 100) / 10) / 10.0

    /** L'app della posta: chiave salvata → nome che si vede. */
    val APP_POSTA = listOf("predefinita" to "Quella del telefono", "gmail" to "Gmail", "samsung" to "Samsung Email")

    /** Vuoto = «predefinita»: l'app di posta scelta nel sistema, su qualunque marca. */
    fun appPosta(chiave: String): String = APP_POSTA.firstOrNull { it.first == chiave.ifEmpty { "predefinita" } }?.second ?: chiave

    /** Chi trascrive, in parole semplici (niente nomi tecnici). */
    val TRASCRITTORI = listOf(
        SceltaTrascrittore.GOOGLE to "Google sul telefono",
        SceltaTrascrittore.WHISPER to "Solo nel telefono, senza rete",
    )

    /** Il numero WhatsApp mostrato a schermo: mai in chiaro, solo le ultime 3 cifre. */
    fun numeroNascosto(n: String): String =
        if (n.length < 6) "non impostato" else "numero che finisce con " + n.takeLast(3)
}

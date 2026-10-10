package com.jarvis.telefono.voce

/**
 * Quando chiudere il dettato dell'interfaccia (DettatoNativo), fotogramma per fotogramma da 30 ms.
 * Pura: si prova sulla JVM.
 *
 * - dopo che l'utente ha parlato, [silenzioMs] di silenzio chiudono la frase;
 * - se non parla entro [attesaInizioMs], si chiude (e non c'è niente da trascrivere);
 * - in ogni caso si chiude a [maxMs].
 */
class FineDettato(
    private val silenzioMs: Long = 2_500L,
    private val attesaInizioMs: Long = 8_000L,
    private val maxMs: Long = 60_000L,
    private val fotogrammaMs: Long = Opzioni.FRAME_MS.toLong(),
) {
    var parlatoVisto = false
        private set
    private var trascorsoMs = 0L
    private var silenzioDiFilaMs = 0L

    /** Un fotogramma; vero quando il dettato va chiuso. */
    fun fotogramma(parlato: Boolean): Boolean {
        trascorsoMs += fotogrammaMs
        if (parlato) {
            parlatoVisto = true
            silenzioDiFilaMs = 0L
        } else {
            silenzioDiFilaMs += fotogrammaMs
        }
        return when {
            trascorsoMs >= maxMs -> true
            parlatoVisto -> silenzioDiFilaMs >= silenzioMs
            else -> trascorsoMs >= attesaInizioMs
        }
    }
}

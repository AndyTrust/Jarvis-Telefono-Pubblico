package com.jarvis.telefono.voce

import kotlin.math.sqrt

// Il cancello di energia: lo stadio che sta PRIMA del KWS, solo per la
// batteria (Rifondazione 1.0, «La batteria: le regole», punto 2). In una
// stanza silenziosa il modello della parola chiave e il filtro del rumore non
// girano: il fotogramma si butta. Sul computer non c'è (lì la corrente non conta).
//
// Come decide, per ogni fotogramma da 30 ms:
// - misura l'RMS;
// - lo confronta col pavimento del rumore: aperto se RMS > pavimento ×
//   cancelloRapporto (4 = 12 dB) oppure RMS > cancelloMinimo (0,01 assoluto);
// - resta aperto cancelloCodaMs (600) dopo l'ultimo fotogramma sopra soglia;
// - aggiorna il pavimento DOPO il confronto: scende subito se l'ambiente è
//   più silenzioso, segue piano (media mobile, ~3 s) quando l'RMS gli è
//   vicino, e sale pianissimo (~60 s) anche sopra soglia. Quest'ultima non
//   era nel piano: senza, un rumore di fondo che cresce di colpo (un
//   ventilatore acceso) terrebbe il cancello aperto per sempre.
//
// Il Motore lo consulta solo nello stato «parola». In «cattura» e «muto» non
// conta. Senza Android dentro: si prova sulla JVM (CancelloTest).
class Cancello(private val opzioni: Opzioni = Opzioni()) {

    companion object {
        // Sotto -80 dBFS il pavimento non scende: con zeri digitali (microfono
        // silenziato dal sistema) andrebbe a 0 e ogni soffio aprirebbe.
        const val PAVIMENTO_MINIMO = 1e-4f
        const val ALFA_DISCESA = 0.5f       // ~2 fotogrammi: scende subito
        const val ALFA_VICINO = 0.01f       // ~3 s: media mobile lenta
        const val ALFA_SOPRA = 0.0005f      // ~60 s: non resta aperto per sempre

        fun rms(pcm: FloatArray): Float {
            if (pcm.isEmpty()) return 0f
            var s = 0.0
            for (x in pcm) s += x.toDouble() * x
            return sqrt(s / pcm.size).toFloat()
        }
    }

    /** Il pavimento del rumore stimato (RMS, 0..1). Negativo finché non ha sentito niente. */
    var pavimento: Float = -1f
        private set

    /** L'RMS dell'ultimo fotogramma valutato. */
    var ultimoRms: Float = 0f
        private set

    /** Aperto adesso (dopo l'ultima valuta()). */
    var aperto: Boolean = false
        private set

    private var ultimoSuonoMs: Long = Long.MIN_VALUE / 2
    private var fotogrammi = 0L
    private var fotogrammiAperti = 0L

    /** Valuta un fotogramma (float -1..1) e dice se il cancello è aperto. */
    fun valuta(pcm: FloatArray, adessoMs: Long): Boolean {
        if (!opzioni.cancelloAttivo) {
            aperto = true
            conta(true)
            return true
        }
        val r = rms(pcm)
        ultimoRms = r
        if (pavimento < 0f) pavimento = maxOf(r, PAVIMENTO_MINIMO)
        val suono = r > pavimento * opzioni.cancelloRapporto || r > opzioni.cancelloMinimo
        if (suono) ultimoSuonoMs = adessoMs
        aperto = suono || adessoMs - ultimoSuonoMs < opzioni.cancelloCodaMs
        aggiornaPavimento(r)
        conta(aperto)
        return aperto
    }

    private fun aggiornaPavimento(r: Float) {
        val alfa = when {
            r < pavimento -> ALFA_DISCESA
            r <= pavimento * opzioni.cancelloRapporto -> ALFA_VICINO
            else -> ALFA_SOPRA
        }
        pavimento = maxOf(PAVIMENTO_MINIMO, pavimento + (r - pavimento) * alfa)
    }

    private fun conta(a: Boolean) {
        fotogrammi++
        if (a) fotogrammiAperti++
    }

    /** Percentuale di fotogrammi con il cancello aperto dall'ultimo azzeraStatistica() (per diag:). */
    fun percentualeAperto(): Double =
        if (fotogrammi == 0L) 0.0 else 100.0 * fotogrammiAperti / fotogrammi

    fun azzeraStatistica() {
        fotogrammi = 0
        fotogrammiAperti = 0
    }

    /** Chiude il cancello e dimentica la coda (il pavimento resta: è la stanza). */
    fun chiudi() {
        ultimoSuonoMs = Long.MIN_VALUE / 2
        aperto = false
    }
}

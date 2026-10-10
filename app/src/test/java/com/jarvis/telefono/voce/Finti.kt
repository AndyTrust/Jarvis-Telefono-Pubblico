package com.jarvis.telefono.voce

import kotlin.math.PI
import kotlin.math.sin

// I doppi finti delle prove della voce: niente Sherpa, niente microfono.

/** Scatta a comando: dà `scatta` alla prossima chiamata, poi torna muto. */
class RilevatoreFinto : RilevatoreParola {
    var scatta: String? = null
    var chiamate = 0
    var azzeramenti = 0
    val ricevuti = ArrayList<FloatArray>()

    override fun accetta(pcm: FloatArray): String? {
        chiamate++
        ricevuti.add(pcm)
        return scatta.also { scatta = null }
    }

    override fun azzera() {
        azzeramenti++
    }
}

/** Parlato quando l'RMS del pezzo supera la soglia. */
class VadFinto(private val soglia: Float = 0.05f) : Vad {
    var chiamate = 0
    override fun parlato(pcm: FloatArray): Boolean {
        chiamate++
        return Cancello.rms(pcm) > soglia
    }
}

/** Moltiplica per `fattore`: così si vede chi ha ricevuto l'audio pulito. */
class PulitoreFinto(private val fattore: Float = 0.5f) : Pulitore {
    var chiamate = 0
    override fun pulisci(pcm: FloatArray): List<FloatArray> {
        chiamate++
        return listOf(FloatArray(pcm.size) { pcm[it] * fattore })
    }

    override fun azzera() {}
}

class VerificatoreFinto(override val pronta: Boolean, private val valore: Float?) : Verificatore {
    var chiamate = 0
    var ricevuto: FloatArray? = null
    override fun somiglianza(crudo: FloatArray): Float? {
        chiamate++
        ricevuto = crudo
        return valore
    }
}

object SuoniFinti {
    fun silenzio() = ShortArray(Opzioni.FRAME_LEN)

    /** Una sinusoide a 300 Hz: ampiezza 0..1 del fondo scala. */
    fun tono(ampiezza: Double) = ShortArray(Opzioni.FRAME_LEN) {
        (ampiezza * 32767 * sin(2 * PI * 300 * it / Opzioni.RATE)).toInt().toShort()
    }

    fun voce() = tono(0.3)          // RMS ~0,21
    fun rumore() = tono(0.002)      // RMS ~0,0014: una stanza silenziosa
}

/** Un orologio finto a passi di 30 ms. */
class OrologioFinto(var ms: Long = 0L) {
    fun passo(): Long {
        ms += Opzioni.FRAME_MS
        return ms
    }
}

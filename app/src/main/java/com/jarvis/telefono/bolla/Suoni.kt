package com.jarvis.telefono.bolla

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * I tre segnali di Jarvis (1.2.2, Boss 2026-10-07: «non sento quando si attiva»).
 *
 * Prima c'era un ToneGenerator sul flusso delle notifiche al 30%: il telefono lo suonava a
 * guadagno 0,0178 (-35 dB, logcat del 07/10 16:16:20), cioè non si sentiva. Adesso le note
 * sono generate qui e suonano sullo stesso flusso della voce di Jarvis (USAGE_ASSISTANT, il
 * volume multimediale): se Boss sente Jarvis parlare, sente anche i segnali. Più una
 * vibrazione breve, per quando il volume è basso.
 *
 * - ATTIVO: due note che salgono, corte (sta dentro i 300 ms che il Motore butta dopo la parola);
 * - FATTO: tre note che salgono;
 * - ERRORE: due note basse che scendono.
 */
class Suoni(private val context: Context) {

    enum class Tipo { ATTIVO, FATTO, ERRORE }

    companion object {
        private const val TAG = "JarvisSuoni"
        const val RATE = 24_000

        /** Frequenze (Hz) e durata di ogni nota (ms). */
        fun note(tipo: Tipo): List<Pair<Double, Int>> = when (tipo) {
            Tipo.ATTIVO -> listOf(880.0 to 90, 1320.0 to 110)
            Tipo.FATTO -> listOf(784.0 to 90, 1047.0 to 90, 1568.0 to 160)
            Tipo.ERRORE -> listOf(440.0 to 160, 311.0 to 240)
        }

        /** Campioni PCM 16 bit mono a [RATE], con attacco e rilascio morbidi (niente clic). */
        fun campioni(tipo: Tipo, ampiezza: Double = 0.55): ShortArray {
            val pezzi = note(tipo).map { (f, ms) ->
                val n = RATE * ms / 1000
                val rampa = min(n / 4, RATE * 8 / 1000)
                ShortArray(n) { i ->
                    val inv = when {
                        i < rampa -> i.toDouble() / rampa
                        i > n - rampa -> (n - i).toDouble() / rampa
                        else -> 1.0
                    }
                    (sin(2 * PI * f * i / RATE) * ampiezza * inv * Short.MAX_VALUE).toInt().toShort()
                }
            }
            val out = ShortArray(pezzi.sumOf { it.size })
            var k = 0
            for (p in pezzi) { p.copyInto(out, k); k += p.size }
            return out
        }

        fun durataMs(tipo: Tipo): Int = note(tipo).sumOf { it.second }
    }

    private val cache = HashMap<Tipo, ShortArray>()

    /** Volume dei segnali 0..1 sopra il volume multimediale (config-boss.json «volume_segnali»). */
    @Volatile
    var volume: Float = 1f

    /** Suona e vibra. Torna subito; da qualunque thread. */
    fun suona(tipo: Tipo) {
        vibra(tipo)
        val pcm = synchronized(cache) { cache.getOrPut(tipo) { campioni(tipo) } }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setVolume(volume.coerceIn(0f, 1f))
            track.notificationMarkerPosition = pcm.size
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { runCatching { t.release() } }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
            val am = context.getSystemService(AudioManager::class.java)
            val vol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
            val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
            Log.i(TAG, "suono ${tipo.name.lowercase()}: ${durataMs(tipo)} ms, volume multimediale $vol/$max")
            // Paracadute: se il marcatore non arriva, la traccia si libera comunque.
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ runCatching { track.release() } }, durataMs(tipo) + 1_500L)
        }.onFailure { Log.w(TAG, "suono ${tipo.name.lowercase()} non suonato: $it") }
    }

    private fun vibra(tipo: Tipo) {
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            if (!v.hasVibrator()) return
            val effetto = when (tipo) {
                Tipo.ATTIVO -> VibrationEffect.createOneShot(40, 180)
                Tipo.FATTO -> VibrationEffect.createWaveform(longArrayOf(0, 35, 80, 35), intArrayOf(0, 160, 0, 160), -1)
                Tipo.ERRORE -> VibrationEffect.createOneShot(220, 220)
            }
            v.vibrate(effetto)
        }.onFailure { Log.w(TAG, "vibrazione non partita: $it") }
    }
}

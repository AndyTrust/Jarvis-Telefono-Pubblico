package com.jarvis.telefono.voce

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 0.3.3 (Boss 07/10 sera: «funziona malissimo»): il riconoscimento di Google SUL TELEFONO
 * (SpeechRecognizer on-device, pacchetto italiano offline) applicato all'audio GIÀ CATTURATO dal
 * nostro microfono, invece di Whisper small.
 *
 * Perché così e non con il microfono di Google: il KWS e Google non possono ascoltare insieme, e il
 * passaggio di microfono dopo la parola perde l'inizio della frase. Da Android 13 (API 33) il
 * riconoscitore accetta l'audio da un descrittore (EXTRA_AUDIO_SOURCE): gli passiamo la frase che il
 * Motore ha già registrato (pre-roll compreso), PCM 16 bit 16 kHz mono, da una pipe. Un solo
 * AudioRecord, nessun passaggio, niente di perso.
 *
 * Sincrona e bloccante (come Whisper): si chiama dal thread di lavoro, il riconoscitore vive sul
 * principale. null = Google non disponibile o fallito: chi chiama ripiega su Whisper.
 * Il testo non va mai nei log: solo tempi e lunghezze.
 */
class TrascrittoreGoogle(context: Context) {

    companion object {
        private const val TAG = "JarvisGoogle"
        private const val LINGUA = "it-IT"
        /** Oltre questo tempo (più la durata della frase) si rinuncia e si ripiega su Whisper. */
        private const val SCADENZA_BASE_MS = 6_000L

        /**
         * Quante volte più veloce del tempo reale si spinge l'audio nella pipe (0 = tutto subito).
         * Misurato sul S24 il 07/10: con 0 (tutto subito) il testo arriva lo stesso, in 0,5-1 s.
         */
        @Volatile
        var ritmo: Float = 0f

        /** Il riconoscimento sul dispositivo con audio da file c'è (Android 13+ e servizio on-device). */
        fun disponibile(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    }

    private val app = context.applicationContext
    private val principale = Handler(Looper.getMainLooper())

    /** Esito dell'ultima trascrizione, per il log e il banco: «ok», «vuoto», «errore N», «scaduto», «non disponibile». */
    @Volatile
    var ultimoEsito: String = ""
        private set

    /**
     * Trascrive [pcm] (float 16 kHz mono). Mai sul thread principale: aspetta il risultato.
     * null = non è andata (chi chiama ripiega); "" = Google non ha sentito parole.
     */
    fun trascrivi(pcm: FloatArray): String? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "TrascrittoreGoogle.trascrivi sul thread principale" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !disponibile(app)) {
            ultimoEsito = "non disponibile"
            return null
        }
        val inizio = SystemClock.elapsedRealtime()
        val fatto = CountDownLatch(1)
        val risultato = AtomicReference<String?>(null)
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) {
            ultimoEsito = "pipe"
            return null
        }
        val lettura = pipe[0]
        val scrittura = pipe[1]
        val riconoscitore = AtomicReference<SpeechRecognizer?>(null)

        principale.post {
            try {
                val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                riconoscitore.set(sr)
                sr.setRecognitionListener(object : RecognitionListener {
                    private var parziale = ""
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }?.let { parziale = it }
                    }
                    override fun onSegmentResults(segmentResults: Bundle) {
                        val t = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }.orEmpty()
                        Log.i(TAG, "segmento: ${t.length} caratteri")
                        if (t.isNotEmpty()) parziale = (parziale + " " + t).trim()
                    }
                    override fun onResults(results: Bundle?) {
                        // Con l'audio da pipe Soda manda DUE finali: il primo col testo, il secondo vuoto
                        // («empty final recognition results», misurato sul S24 il 07/10). Vince il primo non vuoto.
                        val lista = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        // Sul S24 (Soda, audio da pipe) il finale arriva VUOTO: il testo sta nei parziali.
                        if (fatto.count == 0L) return
                        val t = lista?.firstOrNull { it.isNotBlank() }.orEmpty().ifEmpty { parziale }
                        if (t.isEmpty() && risultato.get() == null) { ultimoEsito = "vuoto"; risultato.set("") }
                        if (t.isEmpty()) {
                            // Se dopo un finale vuoto non arriva niente, si chiude da sé.
                            principale.postDelayed({ fatto.countDown() }, 500L)
                            return
                        }
                        ultimoEsito = "ok"
                        risultato.set(t)
                        fatto.countDown()
                    }
                    override fun onError(error: Int) {
                        if (fatto.count == 0L) return
                        // 7 = nessuna corrispondenza: Google non ha sentito parole (non è un guasto).
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            ultimoEsito = "vuoto e$error" + if (parziale.isNotEmpty()) " (parziale)" else ""
                            risultato.set(parziale)
                        } else {
                            ultimoEsito = "errore $error"
                        }
                        fatto.countDown()
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onEndOfSegmentedSession() { fatto.countDown() }
                })
                val intento = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, LINGUA)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LINGUA)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, lettura)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Opzioni.RATE)
                }
                sr.startListening(intento)
            } catch (t: Throwable) {
                ultimoEsito = "avvio ${t.javaClass.simpleName}"
                fatto.countDown()
            }
        }

        // La pipe tiene ~64 KB: si scrive da un thread a parte, poi si chiude (fine dell'audio).
        val scrittore = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(scrittura).use { out ->
                    val byte = TrascrittoreGoogleWav.pcm16(pcm)
                    var i = 0
                    // Blocchi da 100 ms (3200 byte), a [ritmo] volte il tempo reale.
                    val blocco = 3_200
                    val pausaMs = if (ritmo > 0f) (100f / ritmo).toLong() else 0L
                    while (i < byte.size) {
                        val n = minOf(blocco, byte.size - i)
                        out.write(byte, i, n)
                        out.flush()
                        i += n
                        if (pausaMs > 0) Thread.sleep(pausaMs)
                    }
                    // Un filo di silenzio in coda: il VAD di Google chiude la frase da sé.
                    out.write(ByteArray(Opzioni.RATE / 2 * 2))
                }
            } catch (_: IOException) {
                // Il riconoscitore ha chiuso prima (errore o fine): niente da fare.
            }
        }, "jarvis-google-pipe")
        scrittore.start()

        val durataMs = pcm.size * 1000L / Opzioni.RATE
        val inTempo = fatto.await(SCADENZA_BASE_MS + durataMs, TimeUnit.MILLISECONDS)
        if (!inTempo) ultimoEsito = "scaduto"
        principale.post {
            riconoscitore.get()?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
            runCatching { lettura.close() }
        }
        scrittore.join(1_000L)
        Log.i(TAG, "google su audio: $ultimoEsito in ${SystemClock.elapsedRealtime() - inizio} ms (${durataMs} ms di audio, ${risultato.get()?.length ?: -1} caratteri)")
        return if (inTempo && ultimoEsito != "" && !ultimoEsito.startsWith("errore") && !ultimoEsito.startsWith("avvio")) risultato.get()?.trim() else null
    }
}

package com.jarvis.telefono.voce

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.jarvis.telefono.voce.SceltaGoogle.Azione
import com.jarvis.telefono.voce.SceltaGoogle.Via

/**
 * Il riconoscimento vocale di Google per il dettato della chat (1.1.8, Boss 2026-10-07: «il tasto parla
 * con Jarvis è molto lento»). Il testo arriva mentre si parla (risultati parziali) e il finale arriva
 * appena finisce la frase, invece di registrare tutto e poi far girare Whisper small sul telefono.
 *
 * Vie, in ordine ([SceltaGoogle.vie]): riconoscimento sul dispositivo (API 31+), Google col pacchetto
 * offline preferito (EXTRA_PREFER_OFFLINE), Google in rete. Quella che funziona si ricorda per il
 * processo. Se nessuna va, [Ascolto.nonDisponibile] e il chiamante ripiega su Whisper.
 *
 * Tutto sul thread principale (SpeechRecognizer lo pretende). Il testo non va mai nei log.
 */
class RiconoscitoreGoogle(context: Context) {

    interface Ascolto {
        fun parziale(testo: String)
        fun finale(testo: String)
        /** Chiusura senza testo: «nessun_parlato», «microfono_negato», «microfono_guasto», «google_fallito». */
        fun senzaTesto(motivo: String)
        /** Nessuna via di Google ha funzionato prima che l'utente parlasse: ripiegare su Whisper. */
        fun nonDisponibile()
    }

    companion object {
        private const val TAG = "JarvisDettato"
        private const val LINGUA = "it-IT"

        /** La via che ha funzionato l'ultima volta (per processo). */
        @Volatile
        private var viaBuona: Via? = null

        /** I servizi di riconoscimento di Google, in ordine di preferenza. */
        private val PACCHETTI_GOOGLE = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")

        fun disponibile(context: Context): Boolean =
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) ||
                dispositivo(context)

        fun dispositivo(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    }

    private val app = context.applicationContext
    private var sr: SpeechRecognizer? = null
    private var ascolto: Ascolto? = null
    private var vie: List<Via> = emptyList()
    private var indice = 0
    private var inizioMs = 0L
    private var ultimoParziale = ""
    private var parlatoIniziato = false
    private var chiusuraChiesta = false
    private var attivo = false
    private var avvioViaMs = 0L
    private var riprovatoSubito = false

    /** La via in uso adesso (per lo stato della pagina), o null. */
    var viaCorrente: Via? = null
        private set

    fun avvia(a: Ascolto) {
        annulla()
        ascolto = a
        vie = SceltaGoogle.vie(dispositivo(app), viaBuona)
        indice = 0
        inizioMs = SystemClock.elapsedRealtime()
        ultimoParziale = ""
        parlatoIniziato = false
        chiusuraChiesta = false
        riprovatoSubito = false
        attivo = true
        provaVia()
    }

    /** L'utente ha toccato di nuovo il microfono: si chiude e arriva il finale di quello che ha detto. */
    fun ferma() {
        if (!attivo) return
        chiusuraChiesta = true
        runCatching { sr?.stopListening() }
    }

    /** La pagina è andata via: niente callback. */
    fun annulla() {
        attivo = false
        ascolto = null
        distruggi()
    }

    private fun distruggi() {
        sr?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        sr = null
        viaCorrente = null
    }

    private fun ms() = SystemClock.elapsedRealtime() - inizioMs

    private fun provaVia() {
        distruggi()
        if (indice >= vie.size) {
            Log.i(TAG, "google: nessuna via disponibile dopo ${ms()} ms, ripiego su Whisper")
            fineConQualcosa { it.nonDisponibile() }
            return
        }
        val via = vie[indice]
        val nuovo = runCatching { crea(via) }.getOrElse {
            Log.w(TAG, "google: ${via.etichetta} non si crea (${it.javaClass.simpleName})")
            null
        }
        if (nuovo == null) { indice++; provaVia(); return }
        sr = nuovo
        viaCorrente = via
        parlatoIniziato = false
        nuovo.setRecognitionListener(Ascoltatore(nuovo, via))
        avvioViaMs = SystemClock.elapsedRealtime()
        try {
            nuovo.startListening(intento(via))
            Log.i(TAG, "google: ${via.etichetta} avviato a ${ms()} ms")
        } catch (t: Throwable) {
            Log.w(TAG, "google: ${via.etichetta} non parte (${t.javaClass.simpleName})")
            indice++
            provaVia()
        }
    }

    private fun crea(via: Via): SpeechRecognizer? = when (via) {
        Via.DISPOSITIVO ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else null
        Via.GOOGLE_OFFLINE, Via.GOOGLE -> {
            val google = servizioGoogle()
            if (google != null) SpeechRecognizer.createSpeechRecognizer(app, google)
            else if (SpeechRecognizer.isRecognitionAvailable(app)) SpeechRecognizer.createSpeechRecognizer(app)
            else null
        }
    }

    /** Il servizio di riconoscimento di Google se è installato (Samsung di serie usa il suo). */
    private fun servizioGoogle(): ComponentName? {
        val servizi = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrNull().orEmpty()
        for (p in PACCHETTI_GOOGLE) {
            val s = servizi.firstOrNull { it.serviceInfo?.packageName == p }?.serviceInfo ?: continue
            return ComponentName(s.packageName, s.name)
        }
        return null
    }

    private fun intento(via: Via): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LINGUA)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LINGUA)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        // Le frasi dettate hanno pause: senza questi valori Google chiude dopo ~1 s di silenzio.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        if (via != Via.GOOGLE) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun fineConQualcosa(f: (Ascolto) -> Unit) {
        val a = ascolto
        attivo = false
        ascolto = null
        distruggi()
        if (a != null) f(a)
    }

    private inner class Ascoltatore(private val mio: SpeechRecognizer, private val via: Via) : RecognitionListener {
        private fun vivo() = attivo && sr === mio

        override fun onReadyForSpeech(params: Bundle?) {
            if (vivo()) Log.i(TAG, "google: ${via.etichetta} pronto a ${ms()} ms")
        }

        override fun onBeginningOfSpeech() {
            if (!vivo()) return
            parlatoIniziato = true
            viaBuona = via
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (vivo()) Log.i(TAG, "google: fine del parlato a ${ms()} ms")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!vivo()) return
            val t = SceltaGoogle.primo(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            if (t.isEmpty() || t == ultimoParziale) return
            if (ultimoParziale.isEmpty()) Log.i(TAG, "google: primo testo a ${ms()} ms")
            ultimoParziale = t
            viaBuona = via
            ascolto?.parziale(t)
        }

        override fun onResults(results: Bundle?) {
            if (!vivo()) return
            val t = SceltaGoogle.primo(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                .ifEmpty { ultimoParziale }
            Log.i(TAG, "google: ${via.etichetta} finale a ${ms()} ms, ${t.length} caratteri")
            if (t.isNotEmpty()) viaBuona = via
            fineConQualcosa { if (t.isNotEmpty()) it.finale(t) else it.senzaTesto("nessun_parlato") }
        }

        override fun onError(error: Int) {
            if (!vivo()) return
            val azione = SceltaGoogle.classifica(error, ultimoParziale.isNotEmpty(), parlatoIniziato, chiusuraChiesta)
            Log.i(TAG, "google: ${via.etichetta} errore $error a ${ms()} ms → $azione")
            // Alcune versioni dell'app Google danno «nessuna corrispondenza» subito dopo l'avvio, prima che
            // l'utente apra bocca: una sola ripartenza sulla stessa via.
            val subito = SystemClock.elapsedRealtime() - avvioViaMs < 800
            if (azione == Azione.NESSUN_PARLATO && subito && !parlatoIniziato && !chiusuraChiesta && !riprovatoSubito) {
                riprovatoSubito = true
                provaVia()
                return
            }
            when (azione) {
                Azione.PROSSIMA_VIA -> {
                    if (viaBuona == via) viaBuona = null
                    indice++
                    provaVia()
                }
                Azione.CONSEGNA_PARZIALE -> { val t = ultimoParziale; fineConQualcosa { it.finale(t) } }
                Azione.NESSUN_PARLATO -> fineConQualcosa { it.senzaTesto("nessun_parlato") }
                Azione.MICROFONO_NEGATO -> fineConQualcosa { it.senzaTesto("microfono_negato") }
                Azione.MICROFONO_GUASTO -> fineConQualcosa { it.senzaTesto("microfono_guasto") }
                Azione.FALLITO -> fineConQualcosa { it.senzaTesto("google_fallito") }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

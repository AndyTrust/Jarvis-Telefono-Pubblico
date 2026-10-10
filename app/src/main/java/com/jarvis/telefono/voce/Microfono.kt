package com.jarvis.telefono.voce

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

/**
 * L'UNICO `AudioRecord` dell'app (Rifondazione 1.0, «La batteria», regola 1).
 *
 * 16 kHz, mono, PCM 16 bit, sorgente `VOICE_RECOGNITION`. Un thread dedicato
 * con priorità `THREAD_PRIORITY_URGENT_AUDIO` fa letture **bloccanti**
 * (`READ_BLOCKING`) a blocchi di [CAMPIONI_BLOCCO] campioni (300 ms = 10
 * fotogrammi da 480): fra un blocco e l'altro il thread dorme nel kernel. Mai
 * callback, mai polling.
 *
 * Perché letture a blocchi e non callback: sul computer un microfono aperto con
 * callback «si accendeva» senza errori e non riceveva mai niente mentre un
 * altro stream suonava (memoria del progetto, 18/09/2026). Se un giorno il
 * microfono sembra acceso ma muto, guardare come è aperto lo stream.
 *
 * **Va avviato solo quando il servizio in primo piano di tipo `microphone` è
 * già partito** (`startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)`
 * con `RECORD_AUDIO` già concesso). Su Android 14 un `AudioRecord` aperto da
 * un servizio che non è in primo piano col tipo microphone riceve solo
 * silenzio, e il tipo microphone dichiarato senza permesso fa cadere il
 * servizio.
 *
 * @param onBlocco riceve ogni blocco INTERO di [CAMPIONI_BLOCCO] campioni, sul
 *   thread del microfono. Chi lo riceve (il servizio, per il Motore) lo spezza
 *   in 10 fotogrammi da [Opzioni.FRAME_LEN]. Il blocco è una copia: si può
 *   tenere. Deve tornare in fretta (meno di 300 ms), altrimenti il buffer del
 *   sistema si riempie e si perde audio.
 * @param onErrore chiamata una volta sola, sul thread del microfono, quando il
 *   microfono non si apre o si rompe (`AudioRecord` non inizializzato, 3
 *   letture fallite di fila, permesso mancante). Dopo la chiamata il microfono
 *   è già fermo e rilasciato: per riprovare si chiama di nuovo [avvia].
 */
class Microfono(
    private val context: Context,
    private val onBlocco: (ShortArray) -> Unit,
    private val onErrore: (String) -> Unit,
) {
    companion object {
        private const val TAG = "JarvisMicrofono"
        const val RATE = Opzioni.RATE
        /** 300 ms a 16 kHz = 10 fotogrammi da 480. */
        const val CAMPIONI_BLOCCO = Opzioni.FRAME_LEN * 10
        private const val ERRORI_MAX = 3
        private const val ATTESA_FERMO_MS = 1500L
    }

    @Volatile
    private var inCorso = false

    @Volatile
    private var registratore: AudioRecord? = null

    private var thread: Thread? = null

    /** Blocchi consegnati dall'ultimo [avvia]: per la diagnosi («il microfono gira?»). */
    @Volatile
    var blocchiLetti: Long = 0L
        private set

    /** True mentre il thread legge dal microfono. */
    val attivo: Boolean
        get() = inCorso && thread?.isAlive == true

    /** Apre il microfono e comincia a leggere. Se è già attivo non fa niente. */
    @Synchronized
    fun avvia() {
        if (attivo) return
        inCorso = true
        blocchiLetti = 0L
        thread = Thread({ gira() }, "jarvis-microfono").apply { start() }
    }

    /**
     * Chiude il microfono: sblocca la lettura in corso, aspetta il thread (al
     * massimo 1,5 s) e rilascia l'`AudioRecord`. Si può chiamare da qualunque
     * thread tranne quello del microfono stesso (cioè non da dentro onBlocco).
     */
    @Synchronized
    fun ferma() {
        inCorso = false
        // stop() fa tornare subito la read bloccante.
        runCatching { registratore?.stop() }
        val t = thread
        if (t != null && t !== Thread.currentThread()) {
            runCatching { t.join(ATTESA_FERMO_MS) }
            if (t.isAlive) Log.w(TAG, "il thread del microfono non si è fermato in ${ATTESA_FERMO_MS} ms")
        }
        thread = null
    }

    @SuppressLint("MissingPermission") // il permesso lo controlla il servizio; qui si cattura la SecurityException
    private fun gira() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var r: AudioRecord? = null
        var errore: String? = null
        try {
            val minimo = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimo <= 0) {
                errore = "microfono: formato 16 kHz mono non supportato ($minimo)"
                return
            }
            // Almeno 2 volte il minimo e almeno 2 blocchi (in byte): una lettura
            // in ritardo non fa perdere audio.
            val buffer = maxOf(minimo * 2, CAMPIONI_BLOCCO * 2 * 2)
            r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer,
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                errore = "microfono non inizializzato (occupato da un'altra app?)"
                return
            }
            registratore = r
            if (!inCorso) return
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                errore = "microfono: la registrazione non parte (occupato da un'altra app?)"
                return
            }
            Log.i(TAG, "microfono aperto: $RATE Hz, buffer $buffer byte")
            var erroriDiFila = 0
            while (inCorso) {
                val blocco = ShortArray(CAMPIONI_BLOCCO)
                var letti = 0
                // Con READ_BLOCKING la read torna col blocco pieno; il ciclo
                // serve solo se stop() la interrompe a metà.
                while (letti < CAMPIONI_BLOCCO && inCorso) {
                    val n = r.read(blocco, letti, CAMPIONI_BLOCCO - letti, AudioRecord.READ_BLOCKING)
                    if (n > 0) {
                        letti += n
                        erroriDiFila = 0
                    } else {
                        if (!inCorso) break
                        erroriDiFila++
                        Log.w(TAG, "lettura fallita: $n ($erroriDiFila di fila)")
                        if (erroriDiFila >= ERRORI_MAX) {
                            errore = "microfono: $ERRORI_MAX letture fallite di fila (codice $n)"
                            return
                        }
                    }
                }
                if (!inCorso) break
                blocchiLetti++
                onBlocco(blocco)
            }
        } catch (e: SecurityException) {
            errore = "permesso microfono mancante"
        } catch (e: Throwable) {
            errore = "microfono: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            registratore = null
            r?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            val eraInCorso = inCorso
            inCorso = false
            if (errore != null && eraInCorso) {
                Log.w(TAG, errore)
                runCatching { onErrore(errore) }
            } else if (errore != null) {
                Log.i(TAG, "fermato durante l'apertura: $errore")
            } else {
                Log.i(TAG, "microfono chiuso dopo $blocchiLetti blocchi")
            }
        }
    }
}

package com.jarvis.telefono.voce

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig

// Le implementazioni vere delle interfacce del Motore, sopra l'AAR
// sherpa-onnx 1.13.8 (app/libs/). Firme verificate con javap sul classes.jar
// dell'AAR il 2026-09-26. Qui dentro solo il collegamento a Sherpa: la logica
// sta nel Motore e nel Riblocca, che si provano sulla JVM.
//
// I modelli si leggono dagli asset dell'APK (AssetManager); con assets = null
// i percorsi valgono come file sul disco (utile per i modelli scaricati).
// Ognuna tiene memoria nativa: chiudere con close() quando non serve più.

/**
 * Solo «JBOSS» (sei pronunce di «Hey Boss» / «Hey JBoss», tutte @JBOSS) con il KeywordSpotter (0.6.0: niente più «Jarvis»). Le parole sono
 * sequenze di token BPE di tokens.txt (vocabolario aperto: niente riaddestramento). La configurazione del KWS
 * vive qui (dalla seconda ondata non c'è altro posto): modello zipformer2 in
 * assets/kws-model/, soglia 0.1 in keywords.txt, la stessa già tarata sulla
 * voce dell'utente e provata dal vivo nella 0.7.x.
 */
class RilevatoreParolaSherpa(
    assets: AssetManager?,
    cartella: String = "kws-model",
) : RilevatoreParola, AutoCloseable {

    private val spotter: KeywordSpotter
    private var flusso: OnlineStream

    init {
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = Opzioni.RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$cartella/encoder.onnx",
                    decoder = "$cartella/decoder.onnx",
                    joiner = "$cartella/joiner.onnx",
                ),
                tokens = "$cartella/tokens.txt",
                modelType = "zipformer2",
            ),
            keywordsFile = "$cartella/keywords.txt",
        )
        spotter = KeywordSpotter(assetManager = assets, config = config)
        flusso = spotter.createStream()
    }

    override fun accetta(pcm: FloatArray): String? {
        flusso.acceptWaveform(pcm, Opzioni.RATE)
        // Il risultato si guarda a ogni decode, come faceva la 0.7.x (provato
        // dal vivo): dopo un altro decode la parola potrebbe sparire.
        var trovata: String? = null
        while (spotter.isReady(flusso)) {
            spotter.decode(flusso)
            val k = spotter.getResult(flusso).keyword
            if (trovata == null && k.isNotBlank()) trovata = k
        }
        // Normalizzata come `" ".join(r.upper().split())` sul computer.
        return trovata?.let { Motore.normalizza(it) }?.takeIf { it.isNotEmpty() }
    }

    override fun azzera() {
        spotter.reset(flusso)
    }

    override fun close() {
        flusso.release()
        spotter.release()
    }
}

/**
 * VAD Silero (assets/voce/silero_vad.onnx, soglia 0.5).
 *
 * Come lo fa: la primitiva più semplice della classe Vad è compute(finestra),
 * che dà la probabilità di parlato di UNA finestra. Il nativo vuole la
 * finestra esatta (stringa nel .so: «n: %d != window_size: %d»), e per
 * Silero a 16 kHz è 512 campioni. I fotogrammi da 480 si rimettono quindi in
 * finestre da 512 con Riblocca; parlato() risponde con la probabilità
 * dell'ultima finestra completa (ritardo massimo una finestra, 32 ms). Il
 * primo fotogramma, con meno di 512 campioni, dice «non parlato».
 *
 * Non si usa acceptWaveform + isSpeechDetected: quella strada accumula i
 * segmenti in una coda (da svuotare a mano) e ha l'isteresi di
 * minSpeechDuration/minSilenceDuration, mentre il Motore vuole un sì/no per
 * fotogramma come webrtcvad sul computer (i «3 fotogrammi di fila» li conta lui).
 */
class VadSilero(
    assets: AssetManager?,
    modello: String = "voce/silero_vad.onnx",
    private val soglia: Float = 0.5f,
) : Vad, AutoCloseable {

    companion object {
        const val FINESTRA = 512
    }

    private val vad = com.k2fsa.sherpa.onnx.Vad(
        assetManager = assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modello,
                threshold = soglia,
                windowSize = FINESTRA,
            ),
            sampleRate = Opzioni.RATE,
            numThreads = 1,
        ),
    )
    private val finestre = Riblocca(FINESTRA)
    private var ultimaProbabilita = 0f

    override fun parlato(pcm: FloatArray): Boolean {
        for (w in finestre.aggiungi(pcm)) {
            for (i in w.indices) w[i] = w[i].coerceIn(-1f, 1f)
            ultimaProbabilita = vad.compute(w)
        }
        return ultimaProbabilita >= soglia
    }

    override fun azzera() {
        vad.reset()
        finestre.azzera()
        ultimaProbabilita = 0f
    }

    override fun close() {
        vad.release()
    }
}

/**
 * Filtro del rumore GTCRN (assets/voce/gtcrn_simple.onnx), in streaming con
 * OnlineSpeechDenoiser, la stessa classe che usa il computer (Pulitore in
 * manilibere.py). L'uscita arriva a blocchi con un piccolo ritardo e si
 * rimette in fotogrammi da 480 con Riblocca, come `Pulitore.__call__`.
 */
class PulitoreGtcrn(
    assets: AssetManager?,
    modello: String = "voce/gtcrn_simple.onnx",
) : Pulitore, AutoCloseable {

    private val d = OnlineSpeechDenoiser(
        assetManager = assets,
        config = OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = modello),
                numThreads = 1,
            ),
        ),
    )
    private val fotogrammi = Riblocca(Opzioni.FRAME_LEN)

    override fun pulisci(pcm: FloatArray): List<FloatArray> {
        val out = d.run(pcm, Opzioni.RATE)
        return fotogrammi.aggiungi(out.samples)
    }

    override fun azzera() {
        d.reset()
        fotogrammi.azzera()
    }

    override fun close() {
        d.release()
    }
}

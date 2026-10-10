package com.jarvis.telefono.voce

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/** Da audio a testo. [pcm] è float32 mono a 16 kHz, valori in [-1, 1]. */
interface Trascrittore {
    fun trascrivi(pcm: FloatArray): String
}

private const val FREQUENZA = 16_000
/** Il fotogramma del VAD: 30 ms a 16 kHz, lo stesso del Motore. */
private const val FOTOGRAMMA = 480

/**
 * Spezza [pcm] in pezzi di al più [maxS] secondi, tagliando nei silenzi.
 *
 * Il VAD guarda l'audio a fotogrammi da 480 campioni (una sola passata, in
 * ordine, perché Silero ha memoria). Se tutto sta in [maxS] l'audio torna
 * intero. Altrimenti, dentro la finestra dei prossimi [maxS] secondi, si cerca
 * la corsa di fotogrammi muti più lunga (a parità vince la più tarda, così i
 * pezzi restano lunghi) e si taglia a metà di quella corsa. Senza silenzi si
 * taglia a [maxS] netti. La somma delle lunghezze è sempre quella di partenza.
 *
 * Funzione pura: nessun modello, nessun Android, si prova sulla JVM.
 */
fun spezza(pcm: FloatArray, vad: Vad, maxS: Float = 30f): List<FloatArray> {
    val max = (maxS * FREQUENZA).toInt()
    require(max >= FOTOGRAMMA) { "maxS troppo piccolo: $maxS" }
    if (pcm.size <= max) return listOf(pcm)

    // muto[i] = il fotogramma i (campioni i*480 ..< (i+1)*480) non ha parlato.
    // L'ultimo fotogramma incompleto non conta come silenzio.
    val interi = pcm.size / FOTOGRAMMA
    val muto = BooleanArray(interi) { i ->
        !vad.parlato(pcm.copyOfRange(i * FOTOGRAMMA, (i + 1) * FOTOGRAMMA))
    }

    val tagli = mutableListOf<Int>()
    var inizio = 0
    while (pcm.size - inizio > max) {
        val fine = inizio + max
        // Fotogrammi interamente dentro [inizio, fine).
        val primo = (inizio + FOTOGRAMMA - 1) / FOTOGRAMMA
        val ultimo = fine / FOTOGRAMMA - 1
        var migliore = -1
        var lunghezzaMigliore = 0
        var corsa = 0
        for (i in primo..ultimo) {
            if (i < interi && muto[i]) {
                corsa++
                if (corsa >= lunghezzaMigliore) {
                    lunghezzaMigliore = corsa
                    migliore = i - corsa + 1   // primo fotogramma della corsa
                }
            } else {
                corsa = 0
            }
        }
        var taglio = if (migliore >= 0) {
            (migliore * FOTOGRAMMA) + (lunghezzaMigliore * FOTOGRAMMA) / 2
        } else {
            fine
        }
        if (taglio <= inizio) taglio = fine   // mai un pezzo vuoto
        tagli += taglio
        inizio = taglio
    }

    val pezzi = ArrayList<FloatArray>(tagli.size + 1)
    var da = 0
    for (t in tagli) { pezzi += pcm.copyOfRange(da, t); da = t }
    pezzi += pcm.copyOfRange(da, pcm.size)
    return pezzi
}

/**
 * Whisper small int8 offline, sopra `OfflineRecognizer` di sherpa-onnx.
 *
 * - Sincrona e bloccante: il primo [trascrivi] carica il modello (≈ 375 MB,
 *   secondi), ogni frase costa CPU. Mai sul thread principale: il thread lo
 *   sceglie chi chiama.
 * - Il recognizer si crea una volta, alla prima frase, e si libera con [release].
 * - Se il modello non è ancora scaricato [trascrivi] lancia
 *   `IllegalStateException`: meglio dire che manca che restituire un testo finto.
 * - Whisper accetta al massimo 30 s: le frasi lunghe passano da [spezza] e i
 *   testi dei pezzi si uniscono con uno spazio.
 * - `tailPaddings` resta al valore predefinito dell'AAR 1.13.8 (1000), quello
 *   che usano gli esempi ufficiali. La lingua è fissa «it»: niente
 *   riconoscimento automatico, che su frasi brevi sbaglia lingua.
 */
class TrascrittoreWhisper(
    private val modelli: Modelli,
    private val vad: Vad,
    private val numThreads: Int = 4,
) : Trascrittore {

    private var recognizer: OfflineRecognizer? = null

    @Synchronized
    override fun trascrivi(pcm: FloatArray): String {
        if (pcm.isEmpty()) return ""
        val r = recognizer ?: crea().also { recognizer = it }
        return spezza(pcm, vad).mapNotNull { pezzo ->
            val stream = r.createStream()
            try {
                stream.acceptWaveform(pezzo, FREQUENZA)
                r.decode(stream)
                r.getResult(stream).text.trim().takeIf { it.isNotEmpty() }
            } finally {
                stream.release()
            }
        }.joinToString(" ")
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
    }

    private fun crea(): OfflineRecognizer {
        val m = Modello.WHISPER_SMALL
        check(modelli.presente(m)) { "Whisper non è scaricato: la trascrizione offline non è disponibile" }
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = modelli.percorso("small-encoder.int8.onnx").absolutePath,
                    decoder = modelli.percorso("small-decoder.int8.onnx").absolutePath,
                    language = "it",
                    task = "transcribe",
                ),
                tokens = modelli.percorso("small-tokens.txt").absolutePath,
                numThreads = numThreads,
                provider = "cpu",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        // Senza AssetManager sherpa-onnx legge i file dal percorso (newFromFile).
        return OfflineRecognizer(config = config)
    }
}

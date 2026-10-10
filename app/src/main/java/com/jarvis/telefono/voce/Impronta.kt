package com.jarvis.telefono.voce

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

// L'impronta vocale dell'utente sul telefono: porta fedele di
// backtalk/backtalk/manilibere.py (Impronta, solo_voce, leggi_auto,
// impara_frase, _somma_auto, ricalcola_da_archivio, AscoltatoreManiLibere.impara)
// e di il correttore del computer. Rifondazione 1.0, 2026-09-26.
//
// È un dato biometrico: tutto resta in `cartella` (filesDir), niente log,
// niente diag:, niente ponte. Qui dentro non c'è una sola chiamata a Log.

/** Trasforma una frase (16 kHz, float in [-1, 1]) in un vettore a norma 1. */
interface Embedder {
    fun embedding(pcm: FloatArray): FloatArray
}

private const val FOTOGRAMMA = 480 // 30 ms a 16 kHz

/**
 * Tiene solo i fotogrammi da 30 ms entro [db] dal più forte, più [margine]
 * fotogrammi attorno. Il modello pesa il silenzio quanto la voce: senza questo
 * taglio la coda di silenzio fa crollare la somiglianza della stessa voce.
 * Come sul computer, i campioni oltre l'ultimo fotogramma intero si perdono.
 */
fun soloVoce(audio: FloatArray, db: Float = 25f, margine: Int = 3): FloatArray {
    val nf = audio.size / FOTOGRAMMA
    if (nf == 0) return audio
    val rms = DoubleArray(nf)
    var massimo = 0.0
    for (i in 0 until nf) {
        var s = 0.0
        val o = i * FOTOGRAMMA
        for (j in 0 until FOTOGRAMMA) {
            val x = audio[o + j].toDouble()
            s += x * x
        }
        rms[i] = sqrt(s / FOTOGRAMMA) + 1e-9
        if (rms[i] > massimo) massimo = rms[i]
    }
    val soglia = massimo * 10.0.pow(-db.toDouble() / 20.0)
    val k = BooleanArray(nf) { rms[it] > soglia }
    val k2 = k.copyOf()
    for (d in 1..margine) {
        for (i in 0 until nf) {
            if (i - d >= 0 && k[i - d]) k2[i] = true
            if (i + d < nf && k[i + d]) k2[i] = true
        }
    }
    val tenuti = k2.count { it }
    val out = FloatArray(tenuti * FOTOGRAMMA)
    var p = 0
    for (i in 0 until nf) {
        if (k2[i]) {
            System.arraycopy(audio, i * FOTOGRAMMA, out, p, FOTOGRAMMA)
            p += FOTOGRAMMA
        }
    }
    return out
}

/**
 * L'impronta dell'utente: registrata apposta (`impronta.bin`, peso 5) più quella
 * imparata da sola (`impronta-auto.bin`, somma con decadimento 0,97, vale da
 * 3 frasi in su). La soglia (0,55) la applica il Motore, non questa classe.
 */
class Impronta(
    private val embedder: Embedder,
    private val cartella: File,
    private val maxFrasi: Int = MAX_FRASI,
    private val maxByte: Long = MAX_BYTE,
    private val orologio: () -> Long = System::currentTimeMillis,
) : Verificatore {

    companion object {
        const val RATE = 16000
        const val MIN_FRASI_AUTO = 3
        const val DECADIMENTO_AUTO = 0.97f
        const val PESO_REGISTRATA = 5f
        /** 1,5 s di voce dopo soloVoce: più corta, l'embedding è debole. */
        const val MIN_CAMPIONI_VOCE = RATE * 3 / 2
        const val MAX_FRASI = 500
        const val MAX_BYTE = 200L * 1024 * 1024
        const val FILE_AUTO = "impronta-auto.bin"
        const val FILE_REGISTRATA = "impronta.bin"
        const val CARTELLA_VOCE = "voce-utente"
        const val FILE_INDICE = "indice.jsonl"

        private const val MAGIA = 0x4A494D50 // "JIMP"
        private const val VERSIONE = 1

        /** (vettore, frasi) da un file d'impronta, o null se manca o è rotto. */
        fun leggiVettore(f: File): Pair<FloatArray, Int>? = try {
            DataInputStream(f.inputStream().buffered()).use { d ->
                if (d.readInt() != MAGIA || d.readInt() != VERSIONE) return null
                val frasi = d.readInt()
                val dim = d.readInt()
                if (dim <= 0 || dim > 65536 || frasi < 0) return null
                FloatArray(dim) { d.readFloat() } to frasi
            }
        } catch (e: Exception) {
            null
        }

        /** Scrittura atomica: file temporaneo accanto, poi rename. */
        fun scriviVettore(f: File, v: FloatArray, frasi: Int) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            FileOutputStream(tmp).use { fo ->
                val d = DataOutputStream(fo.buffered())
                d.writeInt(MAGIA)
                d.writeInt(VERSIONE)
                d.writeInt(frasi)
                d.writeInt(v.size)
                for (x in v) d.writeFloat(x)
                d.flush()
                fo.fd.sync()
            }
            Files.move(tmp.toPath(), f.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        fun daShort(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32768f }

        private fun norma(v: FloatArray): Float {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            return sqrt(s).toFloat()
        }

        internal fun normalizza(v: FloatArray): FloatArray {
            val n = norma(v) + 1e-9f
            return FloatArray(v.size) { v[it] / n }
        }

        private fun prodotto(a: FloatArray, b: FloatArray): Float? {
            if (a.size != b.size) return null // modello cambiato: impronta non confrontabile
            var s = 0.0
            for (i in a.indices) s += a[i].toDouble() * b[i]
            return s.toFloat()
        }
    }

    private val lock = Any()
    private val lockArchivio = Any()
    private val fileAuto = File(cartella, FILE_AUTO)
    private val fileRegistrata = File(cartella, FILE_REGISTRATA)
    // Le copie fatte prima della 1.1.3 stavano in una cartella «voce-<nome>»: si rinomina da sola.
    private val cartellaVoce = File(cartella, CARTELLA_VOCE).also { nuova ->
        val vecchia = cartella.listFiles { f -> f.isDirectory && f.name.startsWith("voce-") && f.name != CARTELLA_VOCE }?.firstOrNull()
        if (!nuova.exists() && vecchia != null) vecchia.renameTo(nuova)
    }
    private val indice = File(cartellaVoce, FILE_INDICE)

    @Volatile
    private var rif: FloatArray? = null

    /** «nessuna», «registrata», «imparata da N frasi», o le due unite con « + ». */
    @Volatile
    var origine: String = "nessuna"
        private set

    override val pronta: Boolean get() = rif != null

    init {
        ricarica()
    }

    /** Frasi già imparate nell'impronta automatica (anche sotto le 3). */
    fun frasiImparate(): Int = leggiVettore(fileAuto)?.second ?: 0

    /** Rilegge i file: registrata (peso 5) + automatica (da 3 frasi in su). */
    fun ricarica() {
        synchronized(lock) {
            var tot: FloatArray? = null
            val parti = mutableListOf<String>()
            leggiVettore(fileRegistrata)?.let { (v, _) ->
                val n = norma(v) + 1e-9f
                tot = FloatArray(v.size) { PESO_REGISTRATA * v[it] / n }
                parti += "registrata"
            }
            val a = leggiVettore(fileAuto)
            if (a != null && a.second >= MIN_FRASI_AUTO) {
                val t = tot
                if (t == null) {
                    tot = a.first.copyOf()
                } else if (t.size == a.first.size) {
                    for (i in t.indices) t[i] += a.first[i]
                }
                parti += "imparata da ${a.second} frasi"
            }
            rif = tot?.let { normalizza(it) }
            origine = parti.joinToString(" + ").ifEmpty { "nessuna" }
        }
    }

    /** Coseno fra la frase CRUDA (non filtrata) e l'impronta; null se non c'è. */
    override fun somiglianza(crudo: FloatArray): Float? {
        val r = rif ?: return null
        val voce = soloVoce(crudo)
        if (voce.isEmpty()) return null
        return prodotto(embedder.embedding(voce), r)
    }

    fun impara(pcm: ShortArray, testo: String? = null, provenienza: String = "tasto"): Int? =
        impara(daShort(pcm), testo, provenienza)

    /**
     * Una frase dell'utente (dal tasto, o una delle prime a mani libere) migliora
     * l'impronta e, se ha un testo, va nell'archivio voce-utente/. Ritorna le
     * frasi imparate, o null se la frase ha meno di 1,5 s di voce.
     */
    fun impara(pcm: FloatArray, testo: String? = null, provenienza: String = "tasto"): Int? {
        val voce = soloVoce(pcm)
        if (voce.size < MIN_CAMPIONI_VOCE) {
            if (!testo.isNullOrEmpty()) archivia(pcm, testo, provenienza, null, null)
            return null
        }
        val e = embedder.embedding(voce)
        val sim = rif?.let { prodotto(e, it) } // somiglianza con l'impronta di PRIMA
        val n = sommaAuto(e)
        ricarica()
        if (!testo.isNullOrEmpty()) {
            archivia(pcm, testo, provenienza, sim, voce.size.toFloat() / RATE)
        }
        return n
    }

    private fun sommaAuto(e: FloatArray): Int = synchronized(lock) {
        val a = leggiVettore(fileAuto)
        val (somma, prima) = if (a != null && a.first.size == e.size) a else FloatArray(e.size) to 0
        for (i in somma.indices) somma[i] = somma[i] * DECADIMENTO_AUTO + e[i]
        val n = prima + 1
        scriviVettore(fileAuto, somma, n)
        n
    }

    /**
     * Rifà l'impronta automatica da tutte le frasi dell'archivio, dalla più
     * vecchia alla più nuova, con lo stesso decadimento. Sul computer si fa quando
     * l'impronta automatica è persa ma l'archivio è pieno. È lenta (un
     * embedding per frase): va chiamata fuori dal thread del microfono.
     */
    fun ricalcolaDaArchivio(): Int {
        var somma: FloatArray? = null
        var n = 0
        for (r in frasi()) {
            val nome = r["wav"] as? String ?: continue
            val pcm = leggiWav(File(cartellaVoce, nome)) ?: continue
            val voce = soloVoce(daShort(pcm))
            if (voce.size < MIN_CAMPIONI_VOCE) continue
            val e = embedder.embedding(voce)
            val s = somma
            somma = if (s == null || s.size != e.size) e.copyOf() else {
                for (i in s.indices) s[i] = s[i] * DECADIMENTO_AUTO + e[i]
                s
            }
            n++
        }
        val s = somma
        if (n > 0 && s != null) {
            synchronized(lock) { scriviVettore(fileAuto, s, n) }
            ricarica()
        }
        return n
    }

    // ------------------------------------------------------------ archivio

    fun archivia(pcm: ShortArray, testo: String, provenienza: String,
                 somiglianza: Float?, voceS: Float?): String? =
        archivia(daShort(pcm), testo, provenienza, somiglianza, voceS)

    /**
     * Salva una frase accettata dell'utente: `voce-utente/AAAAMMGG-HHMMSS.wav`
     * (16 kHz mono 16 bit) e una riga in `indice.jsonl`. Tetto: [maxFrasi]
     * frasi o [maxByte], via le più vecchie (quelle della raccolta guidata per
     * ultime). Ritorna il nome della frase, o null se l'audio è vuoto.
     */
    fun archivia(pcm: FloatArray, testo: String, provenienza: String,
                 somiglianza: Float?, voceS: Float?): String? {
        if (pcm.isEmpty()) return null
        synchronized(lockArchivio) {
            cartellaVoce.mkdirs()
            val adesso = Date(orologio())
            val base = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(adesso)
            var nome = base
            var k = 2
            while (File(cartellaVoce, "$nome.wav").exists()) {
                nome = "$base-$k"
                k++
            }
            scriviWav(File(cartellaVoce, "$nome.wav"), pcm)
            val riga = linkedMapOf<String, Any?>(
                "id" to nome,
                "wav" to "$nome.wav",
                "ts" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(adesso),
                "testo" to testo,
                "durata_s" to arrotonda(pcm.size.toFloat() / RATE, 2),
                "voce_s" to voceS?.let { arrotonda(it, 2) },
                "somiglianza" to somiglianza?.let { arrotonda(it, 3) },
                "provenienza" to provenienza,
            )
            FileOutputStream(indice, true).use {
                it.write((RigaJson.scrivi(riga) + "\n").toByteArray(Charsets.UTF_8))
            }
            pota()
            return nome
        }
    }

    /** Le righe dell'indice, dalla più vecchia; le righe rotte si saltano. */
    fun frasi(): List<Map<String, Any?>> = try {
        indice.readLines(Charsets.UTF_8).mapNotNull { RigaJson.leggi(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun pota() {
        val righe = frasi().toMutableList()
        val peso = HashMap<String, Long>()
        var totale = 0L
        for (r in righe) {
            val w = r["wav"] as? String ?: continue
            val f = File(cartellaVoce, w)
            if (f.isFile) {
                peso[w] = f.length()
                totale += f.length()
            }
        }
        var tolte = 0
        while (righe.isNotEmpty() && (righe.size > maxFrasi || totale > maxByte)) {
            val i = righe.indexOfFirst { it["provenienza"] != "raccolta" }.let { if (it < 0) 0 else it }
            val r = righe.removeAt(i)
            val w = r["wav"] as? String
            if (w != null) {
                totale -= peso[w] ?: 0L
                File(cartellaVoce, w).delete()
            }
            tolte++
        }
        if (tolte > 0) {
            val tmp = File(cartellaVoce, "$FILE_INDICE.tmp")
            tmp.writeText(righe.joinToString("") { RigaJson.scrivi(it) + "\n" }, Charsets.UTF_8)
            Files.move(tmp.toPath(), indice.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun arrotonda(x: Float, cifre: Int): Double? {
        if (x.isNaN() || x.isInfinite()) return null
        val m = 10.0.pow(cifre)
        return Math.round(x.toDouble() * m) / m
    }
}

/** WAV 16 kHz mono 16 bit, intestazione di 44 byte scritta a mano. */
internal fun scriviWav(f: File, audio: FloatArray, rate: Int = Impronta.RATE) {
    val dati = audio.size * 2
    val b = ByteBuffer.allocate(44 + dati).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray(Charsets.US_ASCII))
    b.putInt(36 + dati)
    b.put("WAVE".toByteArray(Charsets.US_ASCII))
    b.put("fmt ".toByteArray(Charsets.US_ASCII))
    b.putInt(16)            // lunghezza del blocco fmt
    b.putShort(1)           // PCM
    b.putShort(1)           // mono
    b.putInt(rate)
    b.putInt(rate * 2)      // byte al secondo
    b.putShort(2)           // byte per campione
    b.putShort(16)          // bit per campione
    b.put("data".toByteArray(Charsets.US_ASCII))
    b.putInt(dati)
    for (x in audio) {
        val c = if (x.isNaN()) 0f else x.coerceIn(-1f, 1f)
        b.putShort((c * 32767f).toInt().toShort())
    }
    f.writeBytes(b.array())
}

/** I campioni di un WAV PCM 16 bit mono, o null se non lo è. */
internal fun leggiWav(f: File): ShortArray? = try {
    val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val tag = ByteArray(4)
    b.get(tag)
    if (String(tag, Charsets.US_ASCII) != "RIFF") null else {
        b.getInt()
        b.get(tag)
        if (String(tag, Charsets.US_ASCII) != "WAVE") null else {
            var out: ShortArray? = null
            while (b.remaining() >= 8 && out == null) {
                b.get(tag)
                val lung = b.getInt()
                if (String(tag, Charsets.US_ASCII) == "data") {
                    val n = minOf(lung, b.remaining()) / 2
                    out = ShortArray(n) { b.getShort() }
                } else {
                    b.position(b.position() + lung + (lung and 1))
                }
            }
            out
        }
    }
} catch (e: Exception) {
    null
}

/**
 * Una riga JSON piatta (stringhe, numeri, null, true/false), scritta e letta a
 * mano: org.json di Android sulla JVM delle prove è uno stub che non funziona.
 */
internal object RigaJson {
    fun scrivi(m: Map<String, Any?>): String = buildString {
        append('{')
        var primo = true
        for ((k, v) in m) {
            if (!primo) append(", ")
            primo = false
            stringa(k)
            append(": ")
            when (v) {
                null -> append("null")
                is String -> stringa(v)
                is Boolean -> append(v)
                is Double -> if (v.isNaN() || v.isInfinite()) append("null") else append(v)
                is Float -> if (v.isNaN() || v.isInfinite()) append("null") else append(v.toDouble())
                is Number -> append(v)
                else -> stringa(v.toString())
            }
        }
        append('}')
    }

    private fun StringBuilder.stringa(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }

    /** L'oggetto della riga, o null se la riga non è un oggetto piatto valido. */
    fun leggi(riga: String): Map<String, Any?>? = try {
        Lettore(riga.trim()).oggetto()
    } catch (e: Exception) {
        null
    }

    private class Lettore(val s: String) {
        var i = 0

        fun oggetto(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            spazi(); atteso('{'); spazi()
            if (s[i] == '}') { i++; return fine(m) }
            while (true) {
                spazi()
                val k = stringa()
                spazi(); atteso(':'); spazi()
                m[k] = valore()
                spazi()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return fine(m)
                    else -> error("atteso , o }")
                }
            }
        }

        private fun fine(m: Map<String, Any?>): Map<String, Any?> {
            spazi()
            require(i == s.length) { "testo dopo l'oggetto" }
            return m
        }

        private fun valore(): Any? = when {
            s[i] == '"' -> stringa()
            s.startsWith("null", i) -> { i += 4; null }
            s.startsWith("true", i) -> { i += 4; true }
            s.startsWith("false", i) -> { i += 5; false }
            else -> {
                val inizio = i
                while (i < s.length && s[i] in "+-0123456789.eE") i++
                s.substring(inizio, i).toDouble()
            }
        }

        private fun stringa(): String {
            atteso('"')
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"' -> b.append('"')
                        '\\' -> b.append('\\')
                        '/' -> b.append('/')
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000C')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("escape non valido: $e")
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun atteso(c: Char) {
            require(s[i] == c) { "atteso $c" }
            i++
        }

        private fun spazi() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}

/**
 * L'Embedder vero: ERes2Net base 3D-Speaker via sherpa-onnx
 * (`3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`, 39,6 MB).
 * Il modello non sta negli assets: lo scarica Modelli in filesDir/modelli/.
 * Da chiudere con [close] quando il servizio si spegne.
 */
class EmbedderSherpa(modello: File, numThreads: Int = 2) : Embedder, Closeable {

    private val estrattore: SpeakerEmbeddingExtractor

    init {
        require(modello.isFile) { "modello dell'impronta assente: ${modello.name}" }
        estrattore = SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(
                model = modello.absolutePath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
        )
    }

    @Synchronized
    override fun embedding(pcm: FloatArray): FloatArray {
        val st = estrattore.createStream()
        try {
            st.acceptWaveform(pcm, Impronta.RATE)
            st.inputFinished()
            return Impronta.normalizza(estrattore.compute(st))
        } finally {
            st.release()
        }
    }

    @Synchronized
    override fun close() {
        estrattore.release()
    }
}

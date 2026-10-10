package com.jarvis.telefono.voce

import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Un file che deve esistere sul telefono perché un modello funzioni.
 * [byte] è la dimensione attesa (null = non nota: basta che il file non sia vuoto).
 */
data class FileModello(val nome: String, val byte: Long?)

/**
 * Un modello da scaricare. [ARCHIVIO] = tar.bz2 da cui si estraggono solo i
 * [file] elencati (cercati per nome, ignorando la cartella dentro l'archivio);
 * [SINGOLO] = l'URL è il file stesso, e [file] ne contiene uno solo.
 */
data class Modello(
    val nome: String,
    val url: String,
    val tipo: Tipo,
    /** Byte da scaricare (l'archivio compresso, o il file singolo). */
    val byteDaScaricare: Long?,
    val file: List<FileModello>,
) {
    enum class Tipo { ARCHIVIO, SINGOLO }

    companion object {
        /**
         * Whisper small multilingue, esportato da k2-fsa. Verificato il 2026-09-26:
         * l'archivio è 639.387.718 byte e non esiste una versione solo int8
         * (`sherpa-onnx-whisper-small.int8.tar.bz2` dà 404). Dentro, nell'ordine:
         * small-decoder.int8.onnx, small-tokens.txt, small-encoder.onnx,
         * small-encoder.int8.onnx, test_wavs/, small-decoder.onnx.
         * Si tengono i tre sotto (≈ 375 MB) e ci si ferma appena ci sono.
         */
        val WHISPER_SMALL = Modello(
            nome = "whisper-small",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            tipo = Tipo.ARCHIVIO,
            byteDaScaricare = 639_387_718L,
            file = listOf(
                FileModello("small-encoder.int8.onnx", 112_442_483L),
                FileModello("small-decoder.int8.onnx", 262_226_114L),
                FileModello("small-tokens.txt", 816_730L),
            ),
        )

        /** ERes2Net base di 3D-Speaker per l'impronta. Sì, il tag è «recongition» (refuso di k2-fsa). */
        val ERES2NET = Modello(
            nome = "eres2net",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
            tipo = Tipo.SINGOLO,
            byteDaScaricare = 39_593_761L,
            file = listOf(FileModello("3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx", 39_593_761L)),
        )

        val CATALOGO = listOf(WHISPER_SMALL, ERES2NET)
    }
}

/** Com'è andato uno scaricamento. Le eccezioni non escono mai da [Modelli.scarica]. */
sealed class EsitoScarico {
    object Fatto : EsitoScarico()
    object Annullato : EsitoScarico()
    data class Errore(val motivo: String, val causa: Throwable? = null) : EsitoScarico()
}

/** Com'è andata l'importazione da una cartella ([Modelli.importa]). */
sealed class EsitoImport {
    /** Nella cartella di partenza non c'è nessun file del modello: niente da fare. */
    object Niente : EsitoImport()
    data class Fatto(val copiati: List<String>) : EsitoImport()
    data class Errore(val motivo: String) : EsitoImport()
}

/**
 * Scarica, estrae, verifica e cancella i modelli offline della voce, tutti in
 * [cartella] (un file per nome, nessuna sottocartella).
 *
 * Regole:
 * - La classe NON decide sulla rete. Non guarda se c'è Wi-Fi, se la rete è a
 *   consumo o se la batteria è bassa: espone solo [scarica]. La regola della
 *   specifica («solo su Wi-Fi», sezione «La trascrizione offline») la applica
 *   chi la chiama, cioè il servizio (o un WorkManager con rete non a consumo).
 * - Tutto è sincrono e bloccante: mai sul thread principale.
 * - Si scrive sempre su `<nome>.part` e si rinomina solo a file completo e della
 *   dimensione giusta: un file finale esiste solo se è buono.
 * - Il file singolo riprende da dove si era fermato con l'header `Range`.
 *   L'archivio no (un bz2 non si riprende a metà): si riscarica dall'inizio, ma
 *   passa in streaming dalla rete all'estrazione senza mai finire intero su
 *   disco, e i file già estratti e completi non si riscrivono.
 * - Per annullare: interrompere il thread che sta scaricando → [EsitoScarico.Annullato].
 * - 0.3.1: una rete che si ferma o un 5xx si riprova da solo ([tentativi] volte); prima di
 *   partire si guarda lo spazio libero; ogni errore ha un testo in italiano che dice cosa
 *   fare; ogni passo va nel [registro] (logcat JarvisModelli). Strada alternativa senza
 *   rete: [importa] da una cartella riempita con `adb push` (scripts/importa-modelli.sh).
 */
class Modelli(
    private val cartella: File,
    private val client: OkHttpClient,
    /** Righe per il logcat (JarvisModelli): inizio, tentativi, esito. Mai dati personali. */
    private val registro: (String) -> Unit = {},
    /** Quante volte si prova uno scarico che si interrompe per la rete. */
    private val tentativi: Int = 3,
    /** Pausa prima del secondo tentativo (poi il doppio, il triplo...). */
    private val pausaTraTentativiMs: Long = 5_000L,
) {

    /** Dove sta (o starà) il file finale [nome]. */
    fun percorso(nome: String): File = File(cartella, nome)

    /** Vero se tutti i file del modello ci sono e hanno la dimensione attesa. */
    fun presente(modello: Modello): Boolean = modello.file.all { buono(it) }

    /** Toglie i file finali e i `.part` del modello. */
    fun cancella(modello: Modello) {
        for (f in modello.file) {
            percorso(f.nome).delete()
            parte(f.nome).delete()
        }
    }

    /**
     * Scarica il modello. [avanzamento] riceve i byte scaricati e i totali
     * (-1 se il server non li dice), al più una volta per MB più l'ultima.
     */
    fun scarica(
        modello: Modello,
        avanzamento: (byteFatti: Long, byteTotali: Long) -> Unit = { _, _ -> },
    ): EsitoScarico {
        if (presente(modello)) return EsitoScarico.Fatto
        if (!cartella.isDirectory && !cartella.mkdirs()) {
            return EsitoScarico.Errore("non riesco a creare la cartella dei modelli (${cartella.path})")
        }
        spazioMancante(modello)?.let { registro("${modello.nome}: $it"); return EsitoScarico.Errore(it) }
        registro("${modello.nome}: inizio scarico da ${modello.url.substringBefore('?')}")
        var esito: EsitoScarico = EsitoScarico.Errore("${modello.nome}: nessun tentativo")
        for (tentativo in 1..tentativi) {
            esito = unTentativo(modello, avanzamento)
            val errore = esito as? EsitoScarico.Errore ?: break
            registro("${modello.nome}: tentativo $tentativo di $tentativi non riuscito: ${errore.motivo}")
            if (!daRiprovare(errore) || tentativo == tentativi) break
            try {
                Thread.sleep(pausaTraTentativiMs * tentativo)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                esito = EsitoScarico.Annullato
                break
            }
        }
        registro("${modello.nome}: ${if (esito == EsitoScarico.Fatto) "fatto" else "esito $esito"}")
        return esito
    }

    private fun unTentativo(modello: Modello, avanzamento: (Long, Long) -> Unit): EsitoScarico = try {
        when (modello.tipo) {
            Modello.Tipo.SINGOLO -> scaricaSingolo(modello, avanzamento)
            Modello.Tipo.ARCHIVIO -> scaricaArchivio(modello, avanzamento)
        }
    } catch (e: SocketTimeoutException) {
        // Va prima di InterruptedIOException, che è la sua classe madre: una rete ferma
        // non è un «annulla» di Boss (fino alla 0.3.0 lo diceva così).
        EsitoScarico.Errore("${modello.nome}: la rete si è fermata per più di un minuto", e)
    } catch (e: InterruptedIOException) {
        EsitoScarico.Annullato
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        EsitoScarico.Annullato
    } catch (e: UnknownHostException) {
        EsitoScarico.Errore("${modello.nome}: niente internet (github.com non risponde): controlla il Wi-Fi", e)
    } catch (e: IOException) {
        val testo = e.message.orEmpty()
        if (testo.contains("ENOSPC") || testo.contains("No space", ignoreCase = true)) {
            EsitoScarico.Errore("${modello.nome}: il telefono ha finito lo spazio: libera almeno 1 GB e riprova", e)
        } else {
            EsitoScarico.Errore("${modello.nome}: la connessione si è interrotta (${e.javaClass.simpleName})", e)
        }
    } catch (e: Exception) {
        EsitoScarico.Errore("${modello.nome}: errore inatteso (${e.javaClass.simpleName}: ${e.message})", e)
    }

    /** Le interruzioni di rete e gli errori del server (5xx) si riprovano; il resto no. */
    private fun daRiprovare(e: EsitoScarico.Errore): Boolean {
        val c = e.causa
        if (c != null) return c is IOException && !(e.motivo.contains("spazio"))
        return Regex("HTTP 5\\d\\d").containsMatchIn(e.motivo)
    }

    /** null se c'è posto per i file che mancano (più un margine), altrimenti il testo per Boss. */
    private fun spazioMancante(modello: Modello): String? {
        val serve = modello.file.filterNot { buono(it) }.sumOf { it.byte ?: 0L } + MARGINE_SPAZIO
        val libero = cartella.usableSpace
        if (libero <= 0L || libero >= serve) return null
        return "${modello.nome}: spazio insufficiente, servono ${mb(serve)} MB liberi e ce ne sono ${mb(libero)}: " +
            "libera spazio e riprova"
    }

    // --- file singolo, con ripresa --------------------------------------------------

    private fun scaricaSingolo(modello: Modello, avanzamento: (Long, Long) -> Unit): EsitoScarico {
        val atteso = modello.file.single()
        val part = parte(atteso.nome)
        var gia = if (part.exists()) part.length() else 0L

        if (atteso.byte != null && gia > atteso.byte) {
            part.delete(); gia = 0L
        }
        if (atteso.byte != null && gia == atteso.byte) return concludi(part, atteso)

        val richiesta = Request.Builder().url(modello.url).apply {
            if (gia > 0) header("Range", "bytes=$gia-")
        }.build()

        client.newCall(richiesta).execute().use { risposta ->
            val append = when (risposta.code) {
                206 -> true
                200 -> { gia = 0L; false }   // il server ignora Range: si ricomincia
                416 -> {                      // il .part non torna col server: via
                    part.delete()
                    return EsitoScarico.Errore("${modello.nome}: il server rifiuta la ripresa (416), riprova da zero")
                }
                else -> return EsitoScarico.Errore(httpNonRiuscito(modello, risposta.code))
            }
            val corpo = risposta.body ?: return EsitoScarico.Errore("${modello.nome}: risposta vuota")
            val lunghezza = corpo.contentLength()
            val totali = when {
                lunghezza >= 0 -> gia + lunghezza
                atteso.byte != null -> atteso.byte
                else -> -1L
            }
            FileOutputStream(part, append).use { out ->
                copia(corpo.byteStream(), out, gia, totali, avanzamento)
            }
        }
        return concludi(part, atteso)
    }

    // --- archivio tar.bz2, in streaming ---------------------------------------------

    private fun scaricaArchivio(modello: Modello, avanzamento: (Long, Long) -> Unit): EsitoScarico {
        val voluti = modello.file.associateBy { it.nome }
        val mancanti = modello.file.filterNot { buono(it) }.map { it.nome }.toMutableSet()

        val richiesta = Request.Builder().url(modello.url).build()
        client.newCall(richiesta).execute().use { risposta ->
            if (risposta.code != 200) return EsitoScarico.Errore(httpNonRiuscito(modello, risposta.code))
            val corpo = risposta.body ?: return EsitoScarico.Errore("${modello.nome}: risposta vuota")
            val totali = corpo.contentLength().takeIf { it >= 0 } ?: modello.byteDaScaricare ?: -1L

            val contati = Contatore(BufferedInputStream(corpo.byteStream(), BLOCCO), totali, avanzamento)
            TarArchiveInputStream(BZip2CompressorInputStream(contati)).use { tar ->
                while (mancanti.isNotEmpty()) {
                    val voce = tar.nextEntry ?: break
                    if (voce.isDirectory) continue
                    // Solo il nome, mai il percorso dell'archivio: niente «../» che esce dalla cartella.
                    val nome = voce.name.substringAfterLast('/')
                    if (nome !in mancanti) continue
                    val atteso = voluti.getValue(nome)
                    val part = parte(nome)
                    FileOutputStream(part, false).use { out -> tar.copyTo(out, BLOCCO) }
                    val esito = concludi(part, atteso)
                    if (esito != EsitoScarico.Fatto) return esito
                    mancanti.remove(nome)
                }
            }
            contati.fine()
        }
        // Chiudendo la risposta prima della fine dell'archivio la parte che non
        // serve (test_wavs, decoder fp32) non si scarica: meno dati, meno batteria.
        return if (mancanti.isEmpty()) EsitoScarico.Fatto
        else EsitoScarico.Errore("${modello.nome}: nell'archivio mancano ${mancanti.sorted()}")
    }

    // --- importazione da una cartella (ADB) ------------------------------------------

    /**
     * Copia i file del modello da [sorgente] (di solito
     * `getExternalFilesDir(null)/modelli/<nome>/`, dove arriva `adb push`) nella cartella
     * dei modelli. Ogni file passa da `.part`, si controlla la dimensione e solo allora
     * diventa finale; l'originale si cancella dopo la copia riuscita. Un file che è già
     * buono nella cartella dei modelli non si ricopia (e l'originale si toglie lo stesso).
     *
     * Esiti: [EsitoImport.Niente] se in [sorgente] non c'è nessun file del modello,
     * [EsitoImport.Fatto] con i file copiati, [EsitoImport.Errore] con un testo per Boss.
     */
    fun importa(modello: Modello, sorgente: File): EsitoImport {
        val trovati = modello.file.filter { File(sorgente, it.nome).isFile }
        if (trovati.isEmpty()) return EsitoImport.Niente
        return try {
            if (!cartella.isDirectory && !cartella.mkdirs()) {
                return EsitoImport.Errore("non riesco a creare la cartella dei modelli (${cartella.path})")
            }
            val mancanti = modello.file.filterNot { buono(it) || File(sorgente, it.nome).isFile }
            if (mancanti.isNotEmpty()) {
                return EsitoImport.Errore(
                    "nella cartella ${sorgente.path} mancano ${mancanti.joinToString { it.nome }}: " +
                        "rimettili con adb push e riprova"
                )
            }
            // Prima si controllano tutte le dimensioni, poi si copia: niente copie a metà.
            for (f in trovati) {
                val n = File(sorgente, f.nome).length()
                if (f.byte != null && n != f.byte) {
                    return EsitoImport.Errore(
                        "${f.nome} in ${sorgente.path} è di $n byte invece di ${f.byte}: " +
                            "il file è incompleto, rifai adb push"
                    )
                }
            }
            val serve = trovati.filterNot { buono(it) }.sumOf { File(sorgente, it.nome).length() }
            if (cartella.usableSpace in 0 until serve + MARGINE_SPAZIO) {
                return EsitoImport.Errore(
                    "spazio insufficiente: servono ${mb(serve + MARGINE_SPAZIO)} MB liberi, " +
                        "ce ne sono ${mb(cartella.usableSpace)}"
                )
            }
            val copiati = mutableListOf<String>()
            for (f in trovati) {
                val origine = File(sorgente, f.nome)
                if (!buono(f)) {
                    val part = parte(f.nome)
                    origine.inputStream().use { i -> FileOutputStream(part, false).use { o -> i.copyTo(o, BLOCCO) } }
                    when (val e = concludi(part, f)) {
                        EsitoScarico.Fatto -> copiati += f.nome
                        is EsitoScarico.Errore -> return EsitoImport.Errore("copia non riuscita: ${e.motivo}")
                        EsitoScarico.Annullato -> return EsitoImport.Errore("copia annullata")
                    }
                }
                origine.delete()
            }
            registro("${modello.nome}: importati ${copiati.size} file da ${sorgente.path}")
            if (sorgente.list()?.isEmpty() == true) sorgente.delete()
            EsitoImport.Fatto(copiati)
        } catch (e: Exception) {
            EsitoImport.Errore("copia non riuscita (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    // --- in comune -----------------------------------------------------------------

    private fun parte(nome: String) = File(cartella, "$nome.part")

    private fun httpNonRiuscito(modello: Modello, codice: Int) = when (codice) {
        404 -> "${modello.nome}: HTTP 404, il file non c'è più su GitHub: serve una versione nuova dell'app " +
            "(oppure importa i file da cartella)"
        in 500..599 -> "${modello.nome}: HTTP $codice, GitHub ha un problema: riprova fra qualche minuto"
        else -> "${modello.nome}: HTTP $codice dal server"
    }

    private fun buono(f: FileModello): Boolean {
        val file = percorso(f.nome)
        if (!file.isFile) return false
        return if (f.byte != null) file.length() == f.byte else file.length() > 0
    }

    /** Controlla la dimensione del `.part` e lo rinomina nel file finale. */
    private fun concludi(part: File, atteso: FileModello): EsitoScarico {
        val n = part.length()
        if (atteso.byte != null && n != atteso.byte) {
            part.delete()
            return EsitoScarico.Errore("${atteso.nome}: $n byte invece di ${atteso.byte}")
        }
        if (atteso.byte == null && n == 0L) {
            part.delete()
            return EsitoScarico.Errore("${atteso.nome}: file vuoto")
        }
        val finale = percorso(atteso.nome)
        finale.delete()
        if (!part.renameTo(finale)) return EsitoScarico.Errore("${atteso.nome}: rinomina fallita")
        return EsitoScarico.Fatto
    }

    private fun copia(
        ingresso: InputStream,
        out: FileOutputStream,
        partenza: Long,
        totali: Long,
        avanzamento: (Long, Long) -> Unit,
    ) {
        val buf = ByteArray(BLOCCO)
        var fatti = partenza
        var ultimo = partenza
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("annullato")
            val n = ingresso.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            fatti += n
            if (fatti - ultimo >= PASSO_AVANZAMENTO) { avanzamento(fatti, totali); ultimo = fatti }
        }
        avanzamento(fatti, totali)
    }

    /** Conta i byte compressi letti dalla rete, per la barra dell'archivio. */
    private class Contatore(
        ingresso: InputStream,
        private val totali: Long,
        private val avanzamento: (Long, Long) -> Unit,
    ) : FilterInputStream(ingresso) {
        private var fatti = 0L
        private var ultimo = 0L

        override fun read(): Int {
            controlla()
            val b = super.read()
            if (b >= 0) conta(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            controlla()
            val n = super.read(b, off, len)
            if (n > 0) conta(n.toLong())
            return n
        }

        fun fine() = avanzamento(fatti, totali)

        private fun controlla() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("annullato")
        }

        private fun conta(n: Long) {
            fatti += n
            if (fatti - ultimo >= PASSO_AVANZAMENTO) { avanzamento(fatti, totali); ultimo = fatti }
        }
    }

    private companion object {
        const val BLOCCO = 64 * 1024
        const val PASSO_AVANZAMENTO = 1024L * 1024L
        /** Spazio da lasciare libero oltre ai file: Android rallenta sotto qualche centinaio di MB. */
        const val MARGINE_SPAZIO = 200L * 1024L * 1024L

        fun mb(byte: Long) = byte / (1024L * 1024L)
    }
}

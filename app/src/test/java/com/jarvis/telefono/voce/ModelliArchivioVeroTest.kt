package com.jarvis.telefono.voce

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit

/**
 * Lo scarico di Whisper sull'archivio VERO (639 MB), servito da un server HTTP locale
 * che legge il file a blocchi: è la stessa strada del telefono (OkHttp → bz2 → tar),
 * con l'archivio di k2-fsa invece di uno finto. Gira solo se c'è l'archivio:
 *
 *   JARVIS_ARCHIVIO_WHISPER=~/modelli-whisper/sherpa-onnx-whisper-small.tar.bz2 \
 *     bash scripts/prove-jvm.sh --tests '*ModelliArchivioVeroTest'
 *
 * Con JARVIS_URL_VERO=1 scarica invece da GitHub (serve internet).
 */
class ModelliArchivioVeroTest {

    @get:Rule val tmp = TemporaryFolder()

    private var server: ServerSocket? = null

    @After fun chiudi() { server?.close() }

    private val archivio: File? = System.getenv("JARVIS_ARCHIVIO_WHISPER")?.let { File(it) }?.takeIf { it.isFile }

    @Before fun serve() {
        val a = archivio ?: return
        // Un server HTTP minimo su socket: legge il file a blocchi, senza tenerlo in memoria.
        val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server = ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        s.use { sock ->
                            val inp = sock.getInputStream().bufferedReader()
                            while (true) { val riga = inp.readLine() ?: break; if (riga.isEmpty()) break }
                            val out = sock.getOutputStream()
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                                "Content-Length: ${a.length()}\r\nConnection: close\r\n\r\n").toByteArray())
                            a.inputStream().use { it.copyTo(out, 256 * 1024) }
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Test
    fun `dall'archivio vero escono i tre file della dimensione giusta`() {
        val vero = System.getenv("JARVIS_URL_VERO") == "1"
        assumeTrue("serve JARVIS_ARCHIVIO_WHISPER o JARVIS_URL_VERO=1", archivio != null || vero)
        val url = if (vero) Modello.WHISPER_SMALL.url
        else "http://127.0.0.1:${server!!.localPort}/whisper.tar.bz2"
        val m = Modello.WHISPER_SMALL.copy(url = url)
        val cartella = tmp.newFolder("modelli")
        val inizio = System.nanoTime()

        val esito = Modelli(cartella, client()).scarica(m)

        val secondi = (System.nanoTime() - inizio) / 1e9
        println("archivio vero: $esito in ${"%.1f".format(secondi)} s")
        assertEquals(EsitoScarico.Fatto, esito)
        for (f in m.file) assertEquals(f.nome, f.byte, File(cartella, f.nome).length())
        assertTrue(Modelli(cartella, client()).presente(m))
    }
}

package com.jarvis.telefono.voce

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class ModelliTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cartella: File
    private lateinit var modelli: Modelli

    @Before fun prepara() {
        server = MockWebServer().also { it.start() }
        cartella = tmp.newFolder("modelli")
        modelli = Modelli(cartella, OkHttpClient(), pausaTraTentativiMs = 1L)
    }

    @After fun chiudi() = server.shutdown()

    private fun dati(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    private fun singolo(n: Int) = Modello(
        nome = "prova", url = server.url("/modello.onnx").toString(), tipo = Modello.Tipo.SINGOLO,
        byteDaScaricare = n.toLong(), file = listOf(FileModello("modello.onnx", n.toLong())),
    )

    @Test
    fun `il file singolo riprende dal part con Range e poi si rinomina`() {
        val tutto = dati(300_000)
        val gia = 120_000
        File(cartella, "modello.onnx.part").writeBytes(tutto.copyOfRange(0, gia))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $gia-${tutto.size - 1}/${tutto.size}")
                .setBody(Buffer().write(tutto.copyOfRange(gia, tutto.size)))
        )
        val avanzamenti = mutableListOf<Pair<Long, Long>>()

        val esito = modelli.scarica(singolo(tutto.size)) { f, t -> avanzamenti += f to t }

        assertEquals(EsitoScarico.Fatto, esito)
        assertEquals("bytes=$gia-", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Range"))
        assertArrayEquals(tutto, File(cartella, "modello.onnx").readBytes())
        assertFalse(File(cartella, "modello.onnx.part").exists())
        assertTrue(modelli.presente(singolo(tutto.size)))
        assertEquals(tutto.size.toLong() to tutto.size.toLong(), avanzamenti.last())
    }

    @Test
    fun `se il server ignora Range si ricomincia da zero senza sporcare il file`() {
        val tutto = dati(50_000)
        File(cartella, "modello.onnx.part").writeBytes(ByteArray(10_000) { 9 })
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(tutto)))

        assertEquals(EsitoScarico.Fatto, modelli.scarica(singolo(tutto.size)))
        assertArrayEquals(tutto, File(cartella, "modello.onnx").readBytes())
    }

    @Test
    fun `senza part non manda Range`() {
        val tutto = dati(1_000)
        server.enqueue(MockResponse().setBody(Buffer().write(tutto)))
        assertEquals(EsitoScarico.Fatto, modelli.scarica(singolo(tutto.size)))
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Range"))
    }

    @Test
    fun `presente e falso se la dimensione e sbagliata`() {
        val m = singolo(1_000)
        assertFalse(modelli.presente(m))
        File(cartella, "modello.onnx").writeBytes(dati(999))
        assertFalse(modelli.presente(m))
        File(cartella, "modello.onnx").writeBytes(dati(1_000))
        assertTrue(modelli.presente(m))
        assertEquals(File(cartella, "modello.onnx"), modelli.percorso("modello.onnx"))
    }

    @Test
    fun `un file scaricato della dimensione sbagliata non diventa finale`() {
        server.enqueue(MockResponse().setBody(Buffer().write(dati(900))))
        val esito = modelli.scarica(singolo(1_000))
        assertTrue(esito is EsitoScarico.Errore)
        assertFalse(File(cartella, "modello.onnx").exists())
        assertFalse(File(cartella, "modello.onnx.part").exists())
    }

    @Test
    fun `un errore di rete torna come esito e non come eccezione`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        assertTrue(modelli.scarica(singolo(10)) is EsitoScarico.Errore)
        assertEquals("un 5xx si riprova tre volte", 3, server.requestCount)

        server.shutdown()   // nessuno risponde più
        assertTrue(modelli.scarica(singolo(10)) is EsitoScarico.Errore)
    }

    @Test
    fun `dal tar bz2 estrae solo i file voluti e non lascia l'archivio su disco`() {
        val voluto = dati(70_000)
        val tokens = "a 0\nb 1\n".toByteArray()
        val scarto = dati(40_000)
        val archivio = tarBz2(
            "cartella-modello/" to null,
            "cartella-modello/voluto.int8.onnx" to voluto,
            "cartella-modello/scarto.onnx" to scarto,
            "cartella-modello/tokens.txt" to tokens,
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archivio)))
        val m = Modello(
            nome = "finto", url = server.url("/finto.tar.bz2").toString(), tipo = Modello.Tipo.ARCHIVIO,
            byteDaScaricare = archivio.size.toLong(),
            file = listOf(FileModello("voluto.int8.onnx", voluto.size.toLong()), FileModello("tokens.txt", null)),
        )
        val avanzamenti = mutableListOf<Long>()

        val esito = modelli.scarica(m) { f, _ -> avanzamenti += f }

        assertEquals(EsitoScarico.Fatto, esito)
        assertArrayEquals(voluto, File(cartella, "voluto.int8.onnx").readBytes())
        assertArrayEquals(tokens, File(cartella, "tokens.txt").readBytes())
        assertEquals(setOf("voluto.int8.onnx", "tokens.txt"), cartella.list()!!.toSet())
        assertTrue(modelli.presente(m))
        assertTrue(avanzamenti.isNotEmpty())

        modelli.cancella(m)
        assertFalse(modelli.presente(m))
        assertEquals(0, cartella.list()!!.size)
    }

    @Test
    fun `se nell'archivio manca un file lo dice`() {
        val archivio = tarBz2("x/altro.onnx" to dati(100))
        server.enqueue(MockResponse().setBody(Buffer().write(archivio)))
        val m = Modello(
            nome = "finto", url = server.url("/finto.tar.bz2").toString(), tipo = Modello.Tipo.ARCHIVIO,
            byteDaScaricare = null, file = listOf(FileModello("voluto.onnx", 10)),
        )
        val esito = modelli.scarica(m)
        assertTrue(esito is EsitoScarico.Errore)
        assertTrue((esito as EsitoScarico.Errore).motivo.contains("voluto.onnx"))
    }

    @Test
    fun `il catalogo ha i nomi e le dimensioni verificati`() {
        val w = Modello.WHISPER_SMALL
        assertEquals(639_387_718L, w.byteDaScaricare)
        assertEquals(
            listOf("small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt"),
            w.file.map { it.nome },
        )
        assertEquals(39_593_761L, Modello.ERES2NET.byteDaScaricare)
    }

    // --- 0.3.1: riprese, messaggi, importazione da cartella -------------------------

    @Test
    fun `una rete che si ferma e un errore che dice cosa e successo, non un annullato`() {
        val lento = Modelli(
            cartella,
            OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build(),
            tentativi = 1,
        )
        server.enqueue(MockResponse().setBody(Buffer().write(dati(1_000))).throttleBody(10, 1, TimeUnit.SECONDS))
        val esito = lento.scarica(singolo(1_000))
        assertTrue("era $esito", esito is EsitoScarico.Errore)
        assertTrue((esito as EsitoScarico.Errore).motivo.contains("la rete si è fermata"))
    }

    @Test
    fun `dopo un'interruzione il secondo tentativo riprende e finisce`() {
        val tutto = dati(200_000)
        val righe = mutableListOf<String>()
        val m = Modelli(cartella, OkHttpClient(), registro = { righe += it }, pausaTraTentativiMs = 1L)
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(Buffer().write(tutto)))
        assertEquals(EsitoScarico.Fatto, m.scarica(singolo(tutto.size)))
        assertArrayEquals(tutto, File(cartella, "modello.onnx").readBytes())
        assertTrue(righe.any { it.contains("tentativo 1 di 3") })
        assertTrue(righe.last().contains("fatto"))
    }

    @Test
    fun `un 404 non si riprova e dice di importare da cartella`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val esito = modelli.scarica(singolo(10)) as EsitoScarico.Errore
        assertEquals(1, server.requestCount)
        assertTrue(esito.motivo.contains("importa"))
    }

    private fun whisperFinto() = Modello(
        nome = "whisper-small", url = "http://nessuno.invalid/", tipo = Modello.Tipo.ARCHIVIO, byteDaScaricare = null,
        file = listOf(FileModello("enc.onnx", 3_000), FileModello("dec.onnx", 5_000), FileModello("tok.txt", 100)),
    )

    @Test
    fun `importa copia i tre file, controlla le dimensioni e toglie gli originali`() {
        val m = whisperFinto()
        val sorgente = tmp.newFolder("esterna", "modelli", "whisper-small")
        val contenuti = m.file.associate { it.nome to dati(it.byte!!.toInt()) }
        contenuti.forEach { (n, b) -> File(sorgente, n).writeBytes(b) }

        val esito = modelli.importa(m, sorgente)

        assertEquals(EsitoImport.Fatto(listOf("enc.onnx", "dec.onnx", "tok.txt")), esito)
        assertTrue(modelli.presente(m))
        contenuti.forEach { (n, b) -> assertArrayEquals(b, File(cartella, n).readBytes()) }
        assertFalse("la cartella di partenza si svuota e sparisce", sorgente.exists())
        assertTrue(cartella.list()!!.none { it.endsWith(".part") })
        assertEquals("una seconda volta non c'è niente", EsitoImport.Niente, modelli.importa(m, sorgente))
    }

    @Test
    fun `importa rifiuta un file troncato e non tocca niente`() {
        val m = whisperFinto()
        val sorgente = tmp.newFolder("esterna2")
        File(sorgente, "enc.onnx").writeBytes(dati(3_000))
        File(sorgente, "dec.onnx").writeBytes(dati(4_000))   // adb push interrotto
        File(sorgente, "tok.txt").writeBytes(dati(100))

        val esito = modelli.importa(m, sorgente)

        assertTrue(esito is EsitoImport.Errore)
        assertTrue((esito as EsitoImport.Errore).motivo.contains("dec.onnx"))
        assertFalse(modelli.presente(m))
        assertEquals(0, cartella.list()!!.size)
        assertTrue("gli originali restano per riprovare", File(sorgente, "enc.onnx").isFile)
    }

    @Test
    fun `importa dice quali file mancano`() {
        val m = whisperFinto()
        val sorgente = tmp.newFolder("esterna3")
        File(sorgente, "enc.onnx").writeBytes(dati(3_000))
        val esito = modelli.importa(m, sorgente) as EsitoImport.Errore
        assertTrue(esito.motivo.contains("dec.onnx") && esito.motivo.contains("tok.txt"))
    }

    @Test
    fun `importa completa un modello a meta senza ricopiare quello che c'e gia`() {
        val m = whisperFinto()
        File(cartella, "enc.onnx").writeBytes(dati(3_000))
        val sorgente = tmp.newFolder("esterna4")
        File(sorgente, "dec.onnx").writeBytes(dati(5_000))
        File(sorgente, "tok.txt").writeBytes(dati(100))
        assertEquals(EsitoImport.Fatto(listOf("dec.onnx", "tok.txt")), modelli.importa(m, sorgente))
        assertTrue(modelli.presente(m))
    }

    @Test
    fun `importa da una cartella vuota o assente non fa niente`() {
        assertEquals(EsitoImport.Niente, modelli.importa(whisperFinto(), File(tmp.root, "non-esiste")))
    }

    /** Un tar.bz2 in memoria: contenuto null = cartella. */
    private fun tarBz2(vararg voci: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((nome, contenuto) in voci) {
                val voce = TarArchiveEntry(nome)
                if (contenuto != null) voce.size = contenuto.size.toLong()
                tar.putArchiveEntry(voce)
                if (contenuto != null) tar.write(contenuto)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }
}

package com.jarvis.telefono.voce

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Embedder finto e deterministico: istogramma dell'ampiezza |x| in 16 caselle,
 * a norma 1. Due seni della stessa ampiezza danno lo stesso vettore, un'onda
 * quadra ne dà uno molto diverso.
 */
class EmbedderFinto : Embedder {
    var chiamate = 0
    override fun embedding(pcm: FloatArray): FloatArray {
        chiamate++
        val h = FloatArray(16)
        for (x in pcm) h[minOf(15, (abs(x) * 16).toInt())] += 1f
        var n = 0.0
        for (x in h) n += x.toDouble() * x
        val r = sqrt(n).toFloat() + 1e-9f
        return FloatArray(16) { h[it] / r }
    }
}

class ImprontaTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rate = Impronta.RATE

    private fun seno(secondi: Double, hz: Double = 220.0, ampiezza: Float = 0.5f) =
        FloatArray((secondi * rate).toInt()) { (ampiezza * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun quadra(secondi: Double, ampiezza: Float = 0.2f) =
        FloatArray((secondi * rate).toInt()) { if ((it / 40) % 2 == 0) ampiezza else -ampiezza }

    private fun silenzio(secondi: Double) = FloatArray((secondi * rate).toInt())

    private operator fun FloatArray.plus(altro: FloatArray): FloatArray {
        val o = FloatArray(size + altro.size)
        System.arraycopy(this, 0, o, 0, size)
        System.arraycopy(altro, 0, o, size, altro.size)
        return o
    }

    @Test
    fun `soloVoce toglie il silenzio in coda`() {
        val audio = seno(1.0) + silenzio(8.0)
        val v = soloVoce(audio)
        // 33 fotogrammi interi di voce, uno a metà, più 3 di margine: niente di più
        assertTrue("tenuti ${v.size} campioni", v.size in (33 * 480)..(38 * 480))
        assertEquals(0, v.size % 480)
    }

    @Test
    fun `soloVoce non tocca un segnale tutto pieno`() {
        val audio = seno(2.0)
        val v = soloVoce(audio)
        val interi = audio.size / 480 * 480
        assertEquals(interi, v.size)
        assertArrayEquals(audio.copyOf(interi), v, 0f)
    }

    @Test
    fun `soloVoce con meno di un fotogramma restituisce l'audio com'è`() {
        val audio = seno(0.01)
        assertArrayEquals(audio, soloVoce(audio), 0f)
    }

    @Test
    fun `con meno di un secondo e mezzo di voce impara scarta la frase`() {
        val imp = Impronta(EmbedderFinto(), tmp.root)
        assertNull(imp.impara(seno(1.0) + silenzio(5.0), null, "tasto"))
        assertFalse(File(tmp.root, Impronta.FILE_AUTO).exists())
        assertEquals(0, imp.frasiImparate())
    }

    @Test
    fun `dopo tre frasi l'impronta e' pronta e prima no`() {
        val imp = Impronta(EmbedderFinto(), tmp.root)
        assertFalse(imp.pronta)
        assertEquals("nessuna", imp.origine)
        assertNull(imp.somiglianza(seno(2.0)))
        assertEquals(1, imp.impara(seno(2.0), null, "tasto"))
        assertFalse(imp.pronta)
        assertEquals(2, imp.impara(seno(2.0, 300.0), null, "tasto"))
        assertFalse(imp.pronta)
        assertEquals(3, imp.impara(seno(2.0, 180.0), null, "mani libere"))
        assertTrue(imp.pronta)
        assertEquals("imparata da 3 frasi", imp.origine)
        // Un'altra istanza sulla stessa cartella la ritrova dal file.
        assertTrue(Impronta(EmbedderFinto(), tmp.root).pronta)
    }

    @Test
    fun `il decadimento 0,97 si applica alla somma salvata`() {
        val emb = EmbedderFinto()
        val imp = Impronta(emb, tmp.root)
        val a = seno(2.0)
        val b = quadra(2.0)
        imp.impara(a, null, "tasto")
        imp.impara(b, null, "tasto")
        val ea = emb.embedding(soloVoce(a))
        val eb = emb.embedding(soloVoce(b))
        val (somma, frasi) = Impronta.leggiVettore(File(tmp.root, Impronta.FILE_AUTO))!!
        assertEquals(2, frasi)
        val atteso = FloatArray(16) { ea[it] * 0.97f + eb[it] }
        assertArrayEquals(atteso, somma, 1e-6f)
    }

    @Test
    fun `la stessa voce somiglia piu' di una voce diversa`() {
        val imp = Impronta(EmbedderFinto(), tmp.root)
        repeat(3) { imp.impara(seno(2.0, 200.0 + 50 * it), null, "tasto") }
        val stessa = imp.somiglianza(seno(3.0, 330.0) + silenzio(4.0))!!
        val diversa = imp.somiglianza(quadra(3.0))!!
        assertTrue("stessa $stessa, diversa $diversa", stessa > diversa)
        assertTrue(stessa > 0.99f)
        assertTrue(diversa < 0.55f)
    }

    @Test
    fun `l'impronta registrata vale da sola e si somma a quella imparata`() {
        Impronta.scriviVettore(File(tmp.root, Impronta.FILE_REGISTRATA),
            EmbedderFinto().embedding(seno(2.0)), 1)
        val imp = Impronta(EmbedderFinto(), tmp.root)
        assertTrue(imp.pronta)
        assertEquals("registrata", imp.origine)
        repeat(3) { imp.impara(seno(2.0), null, "tasto") }
        assertEquals("registrata + imparata da 3 frasi", imp.origine)
    }

    @Test
    fun `la scrittura dell'impronta e' atomica e non lascia file temporanei`() {
        val imp = Impronta(EmbedderFinto(), tmp.root)
        repeat(4) { imp.impara(seno(2.0), "frase numero $it", "tasto") }
        val residui = tmp.root.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()
        assertTrue("residui: $residui", residui.isEmpty())
        assertEquals(4, Impronta.leggiVettore(File(tmp.root, Impronta.FILE_AUTO))!!.second)
    }

    @Test
    fun `un file d'impronta rotto vale come assente`() {
        File(tmp.root, Impronta.FILE_AUTO).writeText("non sono un'impronta")
        val imp = Impronta(EmbedderFinto(), tmp.root)
        assertFalse(imp.pronta)
        assertEquals(1, imp.impara(seno(2.0), null, "tasto"))
    }

    @Test
    fun `archivia scrive un wav valido e una riga JSON leggibile`() {
        var t = 1_790_000_000_000L
        val imp = Impronta(EmbedderFinto(), tmp.root, orologio = { t })
        val audio = seno(1.0)
        val testo = "Jarvis, apri \"iCassa\"\ne dimmi\\tutto"
        val nome = imp.archivia(audio, testo, "mani libere", 0.8123f, 0.9876f)!!
        assertTrue(nome.matches(Regex("\\d{8}-\\d{6}")))

        val wav = File(tmp.root, "voce-utente/$nome.wav").readBytes()
        assertEquals(44 + audio.size * 2, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + audio.size * 2, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(1, b.getShort(20).toInt())      // PCM
        assertEquals(1, b.getShort(22).toInt())      // mono
        assertEquals(16000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(audio.size * 2, b.getInt(40))
        assertEquals(audio.size, leggiWav(File(tmp.root, "voce-utente/$nome.wav"))!!.size)

        val righe = File(tmp.root, "voce-utente/indice.jsonl").readLines()
        assertEquals(1, righe.size)
        val r = RigaJson.leggi(righe[0])
        assertNotNull("riga non leggibile: ${righe[0]}", r)
        assertEquals(testo, r!!["testo"])
        assertEquals("mani libere", r["provenienza"])
        assertEquals(1.0, r["durata_s"])
        assertEquals(0.812, r["somiglianza"])
        assertEquals(0.99, r["voce_s"])
        assertEquals("$nome.wav", r["wav"])
        assertTrue((r["ts"] as String).matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))

        // Stesso secondo: il nome non si sovrascrive.
        val secondo = imp.archivia(audio, "altra", "tasto", null, null)!!
        assertEquals("$nome-2", secondo)
        assertNull(RigaJson.leggi(File(tmp.root, "voce-utente/indice.jsonl").readLines()[1])!!["somiglianza"])
    }

    @Test
    fun `oltre 500 frasi l'archivio toglie le piu' vecchie`() {
        var t = 1_790_000_000_000L
        val imp = Impronta(EmbedderFinto(), tmp.root, orologio = { t += 1000; t })
        val nomi = (1..501).map { imp.archivia(seno(0.01), "frase $it", "tasto", null, null)!! }
        val cartella = File(tmp.root, "voce-utente")
        val righe = imp.frasi()
        assertEquals(500, righe.size)
        assertEquals("frase 2", righe.first()["testo"])
        assertEquals("frase 501", righe.last()["testo"])
        assertFalse(File(cartella, "${nomi[0]}.wav").exists())
        assertTrue(File(cartella, "${nomi[1]}.wav").exists())
        assertEquals(500, cartella.listFiles { f -> f.name.endsWith(".wav") }!!.size)
        assertTrue(cartella.listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty())
    }

    @Test
    fun `il tetto dei byte toglie le piu' vecchie ma la raccolta per ultima`() {
        var t = 1_790_000_000_000L
        // ogni wav da 0,1 s pesa 44 + 3200 = 3244 byte: ne stanno 3 in 10.000
        val imp = Impronta(EmbedderFinto(), tmp.root, maxByte = 10_000, orologio = { t += 1000; t })
        imp.archivia(seno(0.1), "guidata", "raccolta", null, null)
        imp.archivia(seno(0.1), "uno", "tasto", null, null)
        imp.archivia(seno(0.1), "due", "tasto", null, null)
        imp.archivia(seno(0.1), "tre", "tasto", null, null)
        assertEquals(listOf("guidata", "due", "tre"), imp.frasi().map { it["testo"] })
    }

    @Test
    fun `impara con un testo archivia anche la frase`() {
        val imp = Impronta(EmbedderFinto(), tmp.root)
        imp.impara(seno(2.0), "prima frase", "tasto")
        imp.impara(seno(1.0), "troppo corta ma si archivia", "tasto")
        val r = imp.frasi()
        assertEquals(listOf("prima frase", "troppo corta ma si archivia"), r.map { it["testo"] })
        assertEquals(1.98, r[0]["voce_s"]) // 66 fotogrammi interi da 30 ms
        assertNull(r[0]["somiglianza"]) // l'impronta di prima non c'era
        assertNull(r[1]["voce_s"])
    }

    @Test
    fun `l'impronta si rifa' dall'archivio`() {
        val imp = Impronta(EmbedderFinto(), tmp.root)
        repeat(3) { imp.impara(seno(2.0), "frase $it", "tasto") }
        imp.archivia(seno(1.0), "corta", "tasto", null, null)
        File(tmp.root, Impronta.FILE_AUTO).delete()
        val nuova = Impronta(EmbedderFinto(), tmp.root)
        assertFalse(nuova.pronta)
        assertEquals(3, nuova.ricalcolaDaArchivio())
        assertTrue(nuova.pronta)
        assertEquals("imparata da 3 frasi", nuova.origine)
    }

    @Test
    fun `la riga JSON regge i caratteri di controllo e il testo non valido`() {
        val m = linkedMapOf<String, Any?>("a" to "x\u0001y", "b" to null, "c" to true, "d" to -1.5)
        assertEquals(m, RigaJson.leggi(RigaJson.scrivi(m)))
        assertNull(RigaJson.leggi("{\"a\": "))
        assertNull(RigaJson.leggi("non json"))
    }
}

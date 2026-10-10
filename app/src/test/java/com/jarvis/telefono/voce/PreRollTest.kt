package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 0.3.3: il Motore tiene il microfono crudo PRIMA della cattura (pre-roll) e TUTTO quello che viene
 * dopo, compresi i primi ignoraInizialiMs: l'inizio di una frase detta di fila non si perde più.
 * E lo strumento di misura scrive e rilegge WAV 16 kHz senza perdere niente.
 */
class PreRollTest {

    private val ril = RilevatoreFinto()
    private val vad = VadFinto()
    private val ora = OrologioFinto(1_000_000)

    @Test fun `la frase porta il pre-roll e i primi 300 ms`() {
        val m = Motore(Opzioni(cancelloAttivo = false, preRollMs = 600), ril, vad, null, null)
        // 1 s di stanza prima della parola: nel pre-roll restano gli ultimi 600 ms (20 fotogrammi).
        repeat(33) { m.feed(SuoniFinti.rumore(), ora.passo(), false) }
        ril.scatta = "jboss"
        assertEquals(Evento.Parola("JBOSS"), m.feed(SuoniFinti.voce(), ora.passo(), false))
        var ev: Evento? = null
        // 1,5 s di voce, poi silenzio fino alla chiusura.
        repeat(50) { ev = ev ?: m.feed(SuoniFinti.voce(), ora.passo(), false) }
        repeat(100) { ev = ev ?: m.feed(SuoniFinti.silenzio(), ora.passo(), false) }
        val f = ev as Evento.Frase
        assertEquals("pre-roll di 600 ms", 20 * Opzioni.FRAME_LEN, f.prima.size)
        assertTrue("tutto ha anche i 300 ms buttati", f.tutto.size >= f.crudo.size + 9 * Opzioni.FRAME_LEN)
    }

    @Test fun `la cattura dal tocco porta il pre-roll`() {
        val m = Motore(Opzioni(cancelloAttivo = false, preRollMs = 300), ril, vad, null, null)
        repeat(20) { m.feed(SuoniFinti.rumore(), ora.passo(), false) }
        m.forzaCattura(ora.passo())
        var ev: Evento? = null
        repeat(40) { ev = ev ?: m.feed(SuoniFinti.voce(), ora.passo(), false) }
        repeat(100) { ev = ev ?: m.feed(SuoniFinti.silenzio(), ora.passo(), false) }
        assertEquals(10 * Opzioni.FRAME_LEN, (ev as Evento.Frase).prima.size)
    }

    @Test fun `wav scritto e riletto`() {
        val pcm = FloatArray(16_000) { (kotlin.math.sin(it / 10.0) * 0.5).toFloat() }
        val f = File(Files.createTempDirectory("wav").toFile(), "x.wav")
        FrasiDebug.scriviWav(f, pcm)
        assertEquals(44 + 32_000, f.length().toInt())
        val letto = assertNotNullE(FrasiDebug.leggiWav(f))
        assertEquals(pcm.size, letto.size)
        assertTrue(pcm.indices.all { kotlin.math.abs(pcm[it] - letto[it]) < 1e-3 })
    }

    private fun <T> assertNotNullE(v: T?): T { assertNotNull(v); return v!! }
}

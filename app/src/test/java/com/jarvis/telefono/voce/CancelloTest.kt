package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Il cancello di energia prima del KWS: in una stanza silenziosa il modello
// della parola chiave non deve girare (batteria), con un suono sì.
class CancelloTest {

    private val ril = RilevatoreFinto()
    private val vad = VadFinto()
    private val ora = OrologioFinto(5_000_000)

    private fun f(s: ShortArray) = FloatArray(s.size) { s[it] / 32768f }

    @Test
    fun `in silenzio il rilevatore non viene chiamato`() {
        val pul = PulitoreFinto(1f)
        val m = Motore(Opzioni(), ril, vad, pul, null)
        repeat(1000) { m.feed(SuoniFinti.rumore(), ora.passo(), false) }   // 30 s
        assertEquals(0, ril.chiamate)
        assertEquals("nemmeno il filtro del rumore gira", 0, pul.chiamate)
        assertEquals(0.0, m.cancello.percentualeAperto(), 1e-9)
    }

    @Test
    fun `con un suono il rilevatore riceve il suono e il preroll`() {
        val m = Motore(Opzioni(), ril, vad, null, null)
        repeat(100) { m.feed(SuoniFinti.rumore(), ora.passo(), false) }
        assertEquals(0, ril.chiamate)
        m.feed(SuoniFinti.voce(), ora.passo(), false)
        // 300 ms di preroll = 10 fotogrammi, più quello del suono
        assertEquals(11, ril.chiamate)
        assertTrue(m.cancello.aperto)
    }

    @Test
    fun `la parola chiave passa dal cancello`() {
        val m = Motore(Opzioni(), ril, vad, null, null)
        repeat(100) { m.feed(SuoniFinti.rumore(), ora.passo(), false) }
        ril.scatta = "jboss"
        assertEquals(Evento.Parola("JBOSS"), m.feed(SuoniFinti.voce(), ora.passo(), false))
    }

    @Test
    fun `dopo l ultimo suono resta aperto 600 ms`() {
        val m = Motore(Opzioni(), ril, vad, null, null)
        repeat(100) { m.feed(SuoniFinti.rumore(), ora.passo(), false) }
        m.feed(SuoniFinti.voce(), ora.passo(), false)
        val ultimoSuono = ora.ms
        val prima = ril.chiamate
        // +30 ... +570: 19 fotogrammi ancora aperti
        while (ora.ms - ultimoSuono < 570) m.feed(SuoniFinti.rumore(), ora.passo(), false)
        assertEquals(prima + 19, ril.chiamate)
        m.feed(SuoniFinti.rumore(), ora.passo(), false)    // +600: chiuso
        assertEquals(prima + 19, ril.chiamate)
        assertFalse(m.cancello.aperto)
    }

    @Test
    fun `in cattura il cancello non conta`() {
        val m = Motore(Opzioni(), ril, vad, null, null)
        ril.scatta = "jboss"
        m.feed(SuoniFinti.voce(), ora.passo(), false)
        assertEquals(Motore.Stato.CATTURA, m.stato)
        repeat(100) { m.feed(SuoniFinti.rumore(), ora.passo(), false) }
        assertTrue("il VAD riceve anche il silenzio", vad.chiamate > 80)
    }

    @Test
    fun `apre sopra 12 dB dal pavimento`() {
        val c = Cancello()
        repeat(200) { c.valuta(f(SuoniFinti.tono(0.001)), ora.passo()) }
        assertFalse(c.aperto)
        // 3x il pavimento (9,5 dB): resta chiuso
        assertFalse(c.valuta(f(SuoniFinti.tono(0.003)), ora.passo()))
        // 5x il pavimento (14 dB): apre, anche se sotto 0,01 assoluto
        assertTrue(c.valuta(f(SuoniFinti.tono(0.005)), ora.passo()))
    }

    @Test
    fun `sopra 0,01 di RMS apre anche in una stanza rumorosa`() {
        val c = Cancello()
        // RMS ~0,0106, costante: il pavimento lo raggiunge
        repeat(2000) { c.valuta(f(SuoniFinti.tono(0.015)), ora.passo()) }
        assertTrue(c.aperto)
        assertTrue(c.pavimento > 0.009f)
    }

    @Test
    fun `il pavimento scende subito se la stanza si fa silenziosa`() {
        val c = Cancello()
        repeat(200) { c.valuta(f(SuoniFinti.tono(0.005)), ora.passo()) }
        val alto = c.pavimento
        repeat(10) { c.valuta(f(SuoniFinti.tono(0.0005)), ora.passo()) }
        assertTrue("da $alto a ${c.pavimento}", c.pavimento < alto / 5)
    }

    @Test
    fun `il pavimento non scende sotto il minimo con gli zeri digitali`() {
        val c = Cancello()
        repeat(500) { c.valuta(f(SuoniFinti.silenzio()), ora.passo()) }
        assertEquals(Cancello.PAVIMENTO_MINIMO, c.pavimento, 1e-9f)
        assertFalse(c.aperto)
    }

    @Test
    fun `un rumore di fondo che cresce non tiene aperto per sempre`() {
        val c = Cancello()
        repeat(100) { c.valuta(f(SuoniFinti.tono(0.0005)), ora.passo()) }
        // Il fondo sale di 20 dB ma resta sotto 0,01 assoluto
        repeat(20_000) { c.valuta(f(SuoniFinti.tono(0.005)), ora.passo()) }   // 10 minuti
        assertFalse(c.aperto)
    }

    @Test
    fun `spento e sempre aperto`() {
        val c = Cancello(Opzioni(cancelloAttivo = false))
        assertTrue(c.valuta(f(SuoniFinti.silenzio()), ora.passo()))
        assertEquals(100.0, c.percentualeAperto(), 1e-9)
    }

    @Test
    fun `la percentuale conta i fotogrammi aperti`() {
        val c = Cancello(Opzioni(cancelloCodaMs = 0))
        repeat(30) { c.valuta(f(SuoniFinti.tono(0.001)), ora.passo()) }
        c.azzeraStatistica()
        repeat(3) { c.valuta(f(SuoniFinti.tono(0.001)), ora.passo()) }
        c.valuta(f(SuoniFinti.voce()), ora.passo())
        assertEquals(25.0, c.percentualeAperto(), 1e-9)
    }
}

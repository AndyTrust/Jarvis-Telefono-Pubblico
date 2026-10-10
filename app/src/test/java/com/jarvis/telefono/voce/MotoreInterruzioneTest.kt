package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 09/10: «JBoss» detto sopra la voce di JBoss (popup aperto) la interrompe; la pausa azzera il motore.
class MotoreInterruzioneTest {

    private val ril = RilevatoreFinto()
    private val vad = VadFinto()
    private val ora = OrologioFinto(1_000_000)
    private fun motore() = Motore(Opzioni(), ril, vad, null, null)

    @Test
    fun `di base mentre JBoss parla il KWS non sente niente`() {
        val m = motore()
        ril.scatta = "jboss"
        assertNull(m.feed(SuoniFinti.voce(), ora.passo(), altoparlanti = true))
        assertEquals(Motore.Stato.MUTO, m.stato)
        assertEquals(0, ril.chiamate)
    }

    @Test
    fun `col popup aperto la parola sopra la voce interrompe`() {
        val m = motore()
        m.parolaDuranteVoce = true
        assertNull(m.feed(SuoniFinti.voce(), ora.passo(), altoparlanti = true))
        ril.scatta = "hey boss"
        val ev = m.feed(SuoniFinti.voce(), ora.passo(), altoparlanti = true)
        assertEquals(Evento.Parola("HEY BOSS"), ev)
        assertEquals("resta muto: la cattura la apre il servizio dopo aver zittito la voce", Motore.Stato.MUTO, m.stato)
        assertEquals(1, m.interruzioni)
        assertEquals("non è un'attivazione della parola", 0, m.attivazioni)
    }

    @Test
    fun `sopra la voce una parola fuori lista non interrompe`() {
        val m = motore()
        m.parolaDuranteVoce = true
        ril.scatta = "ok boss"
        assertNull(m.feed(SuoniFinti.voce(), ora.passo(), altoparlanti = true))
        assertEquals(0, m.interruzioni)
    }

    @Test
    fun `dopo l'interruzione la cattura forzata parte subito senza coda d'eco`() {
        val m = motore()
        m.parolaDuranteVoce = true
        ril.scatta = "jboss"
        m.feed(SuoniFinti.voce(), ora.passo(), altoparlanti = true)
        // Il servizio zittisce la voce e apre la cattura.
        m.forzaCattura(ora.passo())
        assertEquals(Motore.Stato.CATTURA, m.stato)
        // Il fotogramma dopo, con gli altoparlanti spenti, non è muto (la coda d'eco è dimenticata).
        assertNull(m.feed(SuoniFinti.voce(), ora.passo(), altoparlanti = false))
        assertEquals(Motore.Stato.CATTURA, m.stato)
    }

    @Test
    fun `utenteParla dice quando Boss sta dicendo la frase`() {
        val m = motore()
        m.forzaCattura(ora.passo())
        assertFalse(m.utenteParla)
        // I primi 300 ms si buttano, poi 3 fotogrammi di voce = parlato vero.
        repeat(20) { m.feed(SuoniFinti.voce(), ora.passo(), false) }
        assertTrue(m.utenteParla)
        // Silenzio fino alla chiusura della frase: dopo, non parla più.
        var ev: Evento? = null
        while (ev == null) ev = m.feed(SuoniFinti.silenzio(), ora.passo(), false)
        assertTrue(ev is Evento.Frase)
        assertFalse(m.utenteParla)
    }

    @Test
    fun `azzera butta la cattura in corso (pausa e spegnimento)`() {
        val m = motore()
        m.forzaCattura(ora.passo())
        repeat(20) { m.feed(SuoniFinti.voce(), ora.passo(), false) }
        assertTrue(m.utenteParla)
        m.azzera()
        assertEquals(Motore.Stato.PAROLA, m.stato)
        assertFalse(m.utenteParla)
        // Ripartendo, il silenzio non chiude nessuna frase vecchia.
        repeat(200) { assertNull(m.feed(SuoniFinti.silenzio(), ora.passo(), false)) }
    }
}

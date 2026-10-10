package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// L'ascolto continuo col popup aperto (09/10): accodamento alla domanda di prima, interruzione, silenzi.
class ConversazioneTest {

    private fun aperta() = Conversazione().also { it.apri() }

    @Test
    fun `la prima frase e una domanda nuova`() {
        val c = aperta()
        val d = c.nuovaFrase("apri WhatsApp", 1_000, confermaInAttesa = false)
        assertEquals("apri WhatsApp", d.testo)
        assertFalse(d.accodata)
        assertTrue(c.inAttesaRisposta)
        assertEquals("apri WhatsApp", c.ultimeParole)
    }

    @Test
    fun `mentre JBoss pensa le parole nuove si accodano e la risposta vecchia si scarta`() {
        val c = aperta()
        c.nuovaFrase("cerca un ristorante a Cagliari", 1_000, false)
        val d = c.nuovaFrase("con il parcheggio", 3_000, false)
        assertTrue(d.accodata)
        assertTrue(d.scartaRispostaPrecedente)
        assertEquals("cerca un ristorante a Cagliari, con il parcheggio", d.testo)
        assertEquals(d.testo, c.domanda)
        // Arriva la risposta alla prima domanda: non si dice.
        assertFalse(c.risposta(5_000))
        assertTrue(c.inAttesaRisposta)
        // Arriva quella alla domanda intera: si dice.
        assertTrue(c.risposta(6_000))
        assertFalse(c.inAttesaRisposta)
    }

    @Test
    fun `due aggiunte di fila scartano due risposte`() {
        val c = aperta()
        c.nuovaFrase("scrivi a Marco", 0, false)
        c.nuovaFrase("che arrivo tardi", 1_000, false)
        val d = c.nuovaFrase("di dieci minuti", 2_000, false)
        assertEquals("scrivi a Marco, che arrivo tardi, di dieci minuti", d.testo)
        assertFalse(c.risposta(3_000))
        assertFalse(c.risposta(3_100))
        assertTrue(c.risposta(3_200))
    }

    @Test
    fun `dopo la risposta un «e anche» si accoda alla domanda di prima`() {
        val c = aperta()
        c.nuovaFrase("che tempo fa a Roma", 0, false)
        assertTrue(c.risposta(2_000))
        val d = c.nuovaFrase("e anche a Milano", 5_000, false)
        assertTrue(d.accodata)
        assertFalse("la risposta era già arrivata: non c'è niente da scartare", d.scartaRispostaPrecedente)
        assertEquals("che tempo fa a Roma, e anche a Milano", d.testo)
        assertTrue(c.risposta(7_000))
    }

    @Test
    fun `dopo la risposta una frase senza parola d'aggiunta e una domanda nuova`() {
        val c = aperta()
        c.nuovaFrase("che ore sono", 0, false)
        c.risposta(1_000)
        val d = c.nuovaFrase("apri Spotify", 2_000, false)
        assertFalse(d.accodata)
        assertEquals("apri Spotify", d.testo)
    }

    @Test
    fun `fuori dalla finestra l'aggiunta diventa domanda nuova`() {
        val c = Conversazione(finestraAggiuntaMs = 30_000).also { it.apri() }
        c.nuovaFrase("che tempo fa a Roma", 0, false)
        c.risposta(1_000)
        val d = c.nuovaFrase("e a Milano", 40_000, false)
        assertFalse(d.accodata)
        assertEquals("e a Milano", d.testo)
    }

    @Test
    fun `interrompere JBoss apre la finestra per l'aggiunta`() {
        val c = aperta()
        c.nuovaFrase("leggimi le mail", 0, false)
        c.risposta(1_000) // JBoss comincia a leggere...
        c.interrotta(50_000) // ...Boss lo interrompe molto dopo
        val d = c.nuovaFrase("anche quelle di ieri", 52_000, false)
        assertTrue(d.accodata)
        assertEquals("leggimi le mail, anche quelle di ieri", d.testo)
    }

    @Test
    fun `interrompere e chiedere altro apre una domanda nuova`() {
        val c = aperta()
        c.nuovaFrase("leggimi le mail", 0, false)
        c.risposta(1_000)
        c.interrotta(2_000)
        val d = c.nuovaFrase("che ore sono", 3_000, false)
        assertFalse(d.accodata)
        assertEquals("che ore sono", d.testo)
    }

    @Test
    fun `la risposta a una conferma passa com'e e non tocca la domanda`() {
        val c = aperta()
        c.nuovaFrase("scrivi a Marco che arrivo", 0, false)
        val d = c.nuovaFrase("invia", 1_000, confermaInAttesa = true)
        assertEquals("invia", d.testo)
        assertFalse(d.accodata)
        assertEquals("scrivi a Marco che arrivo", c.domanda)
        // Anche senza bozza in attesa, «invia» non si incolla mai a una domanda.
        val e = c.nuovaFrase("invia", 2_000, confermaInAttesa = false)
        assertFalse(e.accodata)
        assertEquals("invia", e.testo)
    }

    @Test
    fun `col popup chiuso niente accodamento`() {
        val c = Conversazione()
        c.nuovaFrase("apri WhatsApp", 0, false)
        val d = c.nuovaFrase("e scrivi a Marco", 1_000, false)
        assertFalse(d.accodata)
    }

    @Test
    fun `il silenzio riascolta mentre JBoss pensa, a domanda chiusa chiude dopo due`() {
        val c = Conversazione(silenziMax = 2).also { it.apri() }
        c.nuovaFrase("cerca un volo", 0, false)
        assertTrue(c.silenzio(1_000))
        assertTrue(c.silenzio(2_000))
        assertTrue(c.silenzio(3_000))
        c.risposta(4_000)
        assertTrue("primo silenzio: riascolta", c.silenzio(5_000))
        assertFalse("secondo silenzio di fila: chiude", c.silenzio(6_000))
    }

    @Test
    fun `una risposta che non arriva piu non tiene aperto il popup`() {
        val c = Conversazione(silenziMax = 1, attesaRispostaMaxMs = 10_000).also { it.apri() }
        c.nuovaFrase("cerca un volo", 0, false)
        assertTrue(c.silenzio(5_000))
        assertFalse(c.silenzio(20_000))
    }

    @Test
    fun `una frase azzera i silenzi`() {
        val c = Conversazione(silenziMax = 2).also { it.apri() }
        assertTrue(c.silenzio(0))
        c.nuovaFrase("che ore sono", 1_000, false)
        c.risposta(2_000)
        assertTrue(c.silenzio(3_000))
    }

    @Test
    fun `chiudi dimentica tutto`() {
        val c = aperta()
        c.nuovaFrase("apri WhatsApp", 0, false)
        c.nuovaFrase("e scrivi a Marco", 500, false)
        c.chiudi()
        assertFalse(c.aperta)
        assertNull(c.domanda)
        assertEquals("", c.ultimeParole)
        assertFalse(c.silenzio(1_000))
        // Dopo la chiusura le risposte si dicono tutte (niente scarti pendenti).
        assertTrue(c.risposta(2_000))
    }

    @Test
    fun `parole d'aggiunta e frasi di chiusura`() {
        assertTrue(Conversazione.eAggiunta("E poi scrivi a Marco"))
        assertTrue(Conversazione.eAggiunta("anche domani"))
        assertTrue(Conversazione.eAggiunta("aggiungi il latte"))
        assertTrue(Conversazione.eAggiunta("ah, e chiama Luca"))
        assertFalse(Conversazione.eAggiunta("eccomi"))
        assertFalse(Conversazione.eAggiunta("apri Spotify"))
        assertTrue(Conversazione.chiudeIlPopup("Basta così."))
        assertTrue(Conversazione.chiudeIlPopup("chiudi"))
        assertFalse(Conversazione.chiudeIlPopup("chiudi WhatsApp"))
        assertTrue(Conversazione.eRispostaAConferma("Invia!"))
        assertFalse(Conversazione.eRispostaAConferma("invia la mail a Marco"))
    }
}

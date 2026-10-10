package com.jarvis.telefono.ui

import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.ui.ChiamataAgente.Passo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La barra degli agenti: testo guida di ognuno e chiamata a mani libere. */
class BarraAgenteTest {

    private val tutti = listOf(CatalogoAgenti.JARVIS, CatalogoAgenti.POSTINO, CatalogoAgenti.RICERCATORE,
        CatalogoAgenti.SOCIAL, CatalogoAgenti.MANI, CatalogoAgenti.SCRITTORE)

    @Test fun `ogni agente ha il suo testo guida con esempi, JBoss mai Jarvis`() {
        val visti = HashSet<String>()
        for (a in tutti) {
            val t = TestiBarra.suggerimento(a)
            assertTrue(t, t.startsWith("Scrivi a"))
            assertTrue(t, t.contains("«"))
            assertFalse(t, t.contains("Jarvis"))
            assertTrue("doppione: $t", visti.add(t))
        }
        assertEquals("Scrivi al Postino: «invia la 1», «cestina la 2»…", TestiBarra.suggerimento(CatalogoAgenti.POSTINO))
        assertTrue(TestiBarra.suggerimento(CatalogoAgenti.JARVIS).startsWith("Scrivi a JBoss"))
    }

    @Test fun `i motivi del dettato diventano frasi chiare`() {
        assertEquals(TestiBarra.MICROFONO, TestiBarra.motivoDettato("microfono_negato"))
        assertEquals(TestiBarra.VOCE_ASSENTE, TestiBarra.motivoDettato("whisper_assente"))
        assertEquals(TestiBarra.DETTATO_FALLITO, TestiBarra.motivoDettato("microfono_guasto"))
    }

    @Test fun `la chiamata ascolta, manda, aspetta la risposta e riascolta`() {
        val c = ChiamataAgente()
        assertEquals(Passo.Ascolta, c.avvia())
        assertEquals(Passo.Manda("cerca il meteo di domani"), c.dettato("cerca il meteo di domani"))
        assertEquals(ChiamataAgente.Stato.ATTESA_RISPOSTA, c.stato)
        // un dettato in ritardo mentre aspetta non manda niente
        assertEquals(Passo.Niente, c.dettato("altro"))
        assertEquals(Passo.Ascolta, c.rispostaArrivata())
        assertEquals(ChiamataAgente.Stato.ASCOLTO, c.stato)
    }

    @Test fun `basta chiude la chiamata e non manda niente`() {
        val c = ChiamataAgente(); c.avvia()
        assertEquals(Passo.Chiudi(""), c.dettato("Basta, grazie!"))
        assertFalse(c.aperta)
        val d = ChiamataAgente(); d.avvia()
        assertEquals(Passo.Chiudi(""), d.dettato("riattacca"))
    }

    @Test fun `due silenzi di fila chiudono, uno solo riascolta`() {
        val c = ChiamataAgente(); c.avvia()
        assertEquals(Passo.Ascolta, c.dettato(""))
        assertEquals(Passo.Chiudi(TestiBarra.SILENZIO), c.dettato("  "))
        val d = ChiamataAgente(); d.avvia()
        d.dettato("")
        assertEquals(Passo.Manda("apri le impostazioni"), d.dettato("apri le impostazioni"))
        d.rispostaArrivata()
        assertEquals(Passo.Ascolta, d.dettato(""))   // il conto dei silenzi riparte dopo una frase
    }

    @Test fun `microfono guasto chiude, secondo tocco chiude, a chiamata spenta niente`() {
        val c = ChiamataAgente(); c.avvia()
        assertEquals(Passo.Chiudi("guasto"), c.dettato("", guasto = true))
        assertEquals(Passo.Niente, c.ferma())
        val d = ChiamataAgente(); d.avvia()
        assertEquals(Passo.Chiudi(""), d.ferma())
        assertEquals(Passo.Niente, d.rispostaArrivata())
        assertEquals(Passo.Niente, ChiamataAgente().dettato("ciao"))
    }

    @Test fun `una frase della chat di un agente mandata alla VPS resta nel filo di quell'agente`() {
        assertEquals("chat-social", com.jarvis.telefono.vps.Instradamento.esitoFrase("chat-social"))
        assertEquals("alla VPS", com.jarvis.telefono.vps.Instradamento.esitoFrase("scritto"))
        val voci = listOf(
            com.jarvis.telefono.nucleo.Cronologia.Voce(1, 1L, com.jarvis.telefono.nucleo.Cronologia.BOSS, "prepara un post", "", "chat-social", 0L, ""),
        )
        val sc = com.jarvis.telefono.nucleo.FiltroCronologia.scambi(voci).single()
        assertFalse(com.jarvis.telefono.nucleo.FiltroCronologia.diAgente(sc, com.jarvis.telefono.nucleo.FiltroCronologia.FILO_JBOSS))
    }
}

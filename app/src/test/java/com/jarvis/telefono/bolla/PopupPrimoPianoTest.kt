package com.jarvis.telefono.bolla

import com.jarvis.telefono.bolla.RegolePopup.Azione
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boss 2026-10-09: il popup della voce solo con l'app chiusa, e sparisce subito al ritorno dell'app davanti. */
class PopupPrimoPianoTest {

    private fun cambio(app: Boolean, chat: Boolean = false, postino: Boolean = false) = ContatorePrimoPiano.Cambio(app, chat, postino)

    @Test
    fun `popup non mostrato con l'app in primo piano, mostrato con l'app chiusa`() {
        val p = ContatorePrimoPiano()
        assertTrue("app chiusa: il popup si può mostrare", RegolePopup.puoMostrare(p.app))
        p.avviata(eChatJBoss = false) // Home davanti
        assertFalse("Home davanti: niente popup", RegolePopup.puoMostrare(p.app))
        p.avviata(eChatJBoss = true); p.fermata(eChatJBoss = false) // Home → chat di JBoss
        assertFalse("chat di JBoss davanti: niente popup", RegolePopup.puoMostrare(p.app))
        p.avviata(eChatJBoss = false); p.fermata(eChatJBoss = true) // chat → Impostazioni
        assertFalse("Impostazioni davanti: niente popup", RegolePopup.puoMostrare(p.app))
        p.fermata(eChatJBoss = false) // l'app va in sottofondo
        assertTrue("app in sottofondo: il popup si può mostrare", RegolePopup.puoMostrare(p.app))
    }

    @Test
    fun `al ritorno in primo piano il popup sparisce`() {
        val p = ContatorePrimoPiano()
        val avvisi = mutableListOf<ContatorePrimoPiano.Cambio>()
        p.osserva { avvisi += it }
        p.avviata(eChatJBoss = false)
        assertEquals(cambio(app = true), avvisi.last())
        assertEquals(setOf(Azione.NASCONDI), RegolePopup.alCambio(avvisi.last(), inCorso = true, conversazioneAperta = false))
    }

    @Test
    fun `l'app va in sottofondo durante un lavoro, il popup torna, senza lavoro no`() {
        val dietro = cambio(app = false)
        assertEquals(setOf(Azione.RIMOSTRA), RegolePopup.alCambio(dietro, inCorso = true, conversazioneAperta = true))
        assertEquals(emptySet<Azione>(), RegolePopup.alCambio(dietro, inCorso = false, conversazioneAperta = false))
        assertEquals(setOf(Azione.CHIUDI_CONVERSAZIONE), RegolePopup.alCambio(dietro, inCorso = false, conversazioneAperta = true))
    }

    @Test
    fun `l'ascolto continuo dipende dall'app davanti, non dalla chat di JBoss`() {
        // Boss 09/10: «passa al Postino» apre la sua pagina e la voce resta accesa: nessuna schermata dell'app la chiude
        for (c in listOf(cambio(app = true, chat = true), cambio(app = true, postino = true), cambio(app = true))) {
            assertEquals(c.toString(), setOf(Azione.NASCONDI), RegolePopup.alCambio(c, inCorso = false, conversazioneAperta = true))
        }
        // solo con l'app dietro e niente in corso si chiude
        assertEquals(setOf(Azione.CHIUDI_CONVERSAZIONE), RegolePopup.alCambio(cambio(app = false), inCorso = false, conversazioneAperta = true))
    }

    @Test
    fun `dalla chat di JBoss alla pagina del Postino l'ascolto resta acceso`() {
        // la sequenza vera del ciclo di vita: la pagina parte (onStart) prima che la chat si fermi (onStop)
        val p = ContatorePrimoPiano()
        p.avviata(eChatJBoss = true)
        val avvisi = mutableListOf<ContatorePrimoPiano.Cambio>()
        p.osserva { avvisi += it }
        p.avviata(eChatJBoss = false, ePostino = true)
        p.fermata(eChatJBoss = true)
        assertEquals(cambio(app = true, postino = true), p.adesso)
        assertTrue(avvisi.isNotEmpty())
        for (c in avvisi) {
            assertFalse("niente chiusura dell'ascolto: $c", Azione.CHIUDI_CONVERSAZIONE in RegolePopup.alCambio(c, false, conversazioneAperta = true))
            assertTrue("dopo la risposta si riascolta: $c", RegolePopup.riascoltaDopoRisposta(c))
            assertTrue("dopo un silenzio si riascolta: $c", RegolePopup.riascoltaDopoSilenzio(c))
            assertFalse("niente popup dentro l'app: $c", RegolePopup.puoMostrare(c.app))
        }
        // la parola detta nella pagina del Postino non la copre con la chat di JBoss
        assertFalse(RegolePopup.apriChatJBoss(p.adesso))
        // Riprendi (dopo una pausa) con la pagina davanti e la conversazione del Postino: torna ad ascoltare da sola
        assertTrue(RegolePopup.ascoltaDaSola(p.adesso, conPostino = true))
        assertFalse("pagina aperta da sola, senza passaggio: decide la parola", RegolePopup.ascoltaDaSola(p.adesso, conPostino = false))
        // torna a JBoss: la chat riparte, la pagina si chiude, l'app resta davanti
        p.avviata(eChatJBoss = true)
        p.fermata(eChatJBoss = false, ePostino = true)
        assertEquals(cambio(app = true, chat = true), p.adesso)
        // l'app va dietro: solo adesso, senza un'operazione in corso, l'ascolto si chiude
        p.fermata(eChatJBoss = true)
        assertEquals(setOf(Azione.CHIUDI_CONVERSAZIONE), RegolePopup.alCambio(p.adesso, inCorso = false, conversazioneAperta = true))
    }

    @Test
    fun `girare lo schermo non fa lampeggiare il popup`() {
        val p = ContatorePrimoPiano()
        p.avviata(eChatJBoss = true)
        val avvisi = mutableListOf<ContatorePrimoPiano.Cambio>()
        p.osserva { avvisi += it }
        p.fermata(eChatJBoss = true, cambioConfigurazione = true)
        p.avviata(eChatJBoss = true)
        assertTrue(avvisi.all { it.app })
    }

    @Test
    fun `nessun avviso se non cambia niente, e il contatore non va sotto zero`() {
        val p = ContatorePrimoPiano()
        val avvisi = mutableListOf<ContatorePrimoPiano.Cambio>()
        val smetti = p.osserva { avvisi += it }
        p.avviata(false); p.avviata(false) // seconda schermata: già davanti
        assertEquals(1, avvisi.size)
        p.fermata(false); p.fermata(false); p.fermata(false)
        assertFalse(p.app)
        assertEquals(2, avvisi.size)
        smetti()
        p.avviata(false)
        assertEquals(2, avvisi.size)
    }

    @Test
    fun `la voce dentro l'app va nella chat di JBoss`() {
        assertTrue("Home davanti: si apre la chat", RegolePopup.apriChatJBoss(cambio(app = true, chat = false)))
        assertFalse("chat già davanti", RegolePopup.apriChatJBoss(cambio(app = true, chat = true)))
        assertFalse("pagina del Postino davanti: si resta lì", RegolePopup.apriChatJBoss(cambio(app = true, postino = true)))
        assertFalse("app chiusa: popup, non chat", RegolePopup.apriChatJBoss(cambio(app = false)))
        assertTrue("dentro l'app l'ascolto continuo non ha bisogno del popup", RegolePopup.conversazionePossibile(true, finestra = false))
        assertFalse(RegolePopup.conversazionePossibile(false, finestra = false))
        assertTrue(RegolePopup.conversazionePossibile(false, finestra = true))
    }

    @Test
    fun `finita l'operazione fuori dall'app il popup si chiude, dentro l'app si riascolta`() {
        assertTrue(RegolePopup.riascoltaDopoRisposta(cambio(app = true, chat = true)))
        assertTrue(RegolePopup.riascoltaDopoRisposta(cambio(app = true, postino = true)))
        assertFalse(RegolePopup.riascoltaDopoRisposta(cambio(app = false)))
        // Silenzio: dentro l'app l'ascolto continuo resta; fuori dall'app il popup non resta ad aspettare.
        assertTrue(RegolePopup.riascoltaDopoSilenzio(cambio(app = true, chat = true)))
        assertTrue(RegolePopup.riascoltaDopoSilenzio(cambio(app = true, postino = true)))
        assertFalse(RegolePopup.riascoltaDopoSilenzio(cambio(app = false)))
    }
}

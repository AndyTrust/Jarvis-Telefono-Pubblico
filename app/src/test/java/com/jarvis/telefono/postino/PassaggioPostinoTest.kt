package com.jarvis.telefono.postino

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il passaggio della conversazione fra JBoss e il Postino (Boss, 2026-10-09: «passa al Postino», «torna a JBoss»;
 * prima la VPS rispondeva «non posso passarti la conversazione»).
 */
class PassaggioPostinoTest {

    @Test
    fun `le frasi del passaggio al Postino`() {
        for (f in listOf("passa al Postino", "Passami il postino.", "Postino", "postino!", "JBoss, passa al Postino",
            "fammi parlare con il postino", "voglio parlare col Postino", "vai dal postino", "ok passa al postino per favore")) {
            assertEquals(f, PassaggioPostino.Verso.AL_POSTINO, PassaggioPostino.capisci(f))
        }
    }

    @Test
    fun `le frasi del ritorno a JBoss, anche come le trascrive la voce`() {
        for (f in listOf("torna a JBoss", "JBoss", "Torna da J Boss", "passami jboss", "ritorna a jay boss", "esci dal postino",
            "basta con il postino", "torniamo a JBoss")) {
            assertEquals(f, PassaggioPostino.Verso.A_JBOSS, PassaggioPostino.capisci(f))
        }
    }

    @Test
    fun `le altre frasi non sono un passaggio`() {
        for (f in listOf("leggi le mail", "chiedi al postino di leggere le mail di jarvis", "cancella questa", "che ore sono",
            "manda una mail al postino: ciao", "apri WhatsApp", "aggiorna la posta", "")) {
            assertNull(f, PassaggioPostino.capisci(f))
        }
    }

    @Test
    fun `passa al Postino e torna a JBoss, con la riga da scrivere nel filo`() {
        var adesso = 0L
        val p = PassaggioPostino { adesso }
        assertFalse(p.conPostino)
        // «JBoss» detto mentre JBoss ha già la conversazione: niente passaggio, la frase segue il giro normale
        assertNull(p.passa(PassaggioPostino.Verso.A_JBOSS))
        val al = p.passa(PassaggioPostino.Verso.AL_POSTINO)!!
        assertTrue(al.conPostino && al.cambiato)
        assertTrue(al.testo, al.testo.startsWith("Ora parli con il Postino"))
        assertTrue(p.conPostino)
        // ripetuto: lo dice, senza cambiare
        val ancora = p.passa(PassaggioPostino.Verso.AL_POSTINO)!!
        assertFalse(ancora.cambiato)
        assertEquals(PassaggioPostino.GIA_POSTINO, ancora.testo)
        val torna = p.passa(PassaggioPostino.Verso.A_JBOSS)!!
        assertFalse(torna.conPostino)
        assertEquals("Ora parli con JBoss.", torna.testo)
        assertFalse(p.conPostino)
    }

    @Test
    fun `senza frasi per troppo tempo la conversazione torna a JBoss da sola`() {
        var adesso = 0L
        val p = PassaggioPostino { adesso }
        p.passa(PassaggioPostino.Verso.AL_POSTINO)
        adesso += PassaggioPostino.FINESTRA_MS - 1
        p.usato()
        adesso += PassaggioPostino.FINESTRA_MS - 1
        assertTrue("ogni frase allunga la finestra", p.conPostino)
        adesso += 2
        assertFalse(p.conPostino)
    }

    @Test
    fun `dopo il passaggio la frase libera va al Postino, che la capisce`() {
        // la catena della chat di JBoss con il Postino attivo: passaggio, poi PostaCondivisa.perPostino
        val p = PassaggioPostino()
        assertEquals(PassaggioPostino.Verso.AL_POSTINO, PassaggioPostino.capisci("passa al Postino"))
        p.passa(PassaggioPostino.Verso.AL_POSTINO)
        assertTrue(p.conPostino)
        // senza canale lo dice onestamente, ma la frase è stata capita come posta (non «non capisco»)
        val r = PostaCondivisa().perPostino("leggi le mail", PostaCondivisa.Da.JBOSS)!!
        assertEquals(PostaCondivisa.NON_COLLEGATO, r.dire)
        assertNull(PostaCondivisa().perPostino("che tempo fa", PostaCondivisa.Da.JBOSS))
    }

    @Test
    fun `passa al Postino apre la sua pagina, torna a JBoss la chiude`() {
        // Boss 09/10: «"passa al Postino" deve aprire anche la pagina del Postino»
        val p = PassaggioPostino()
        val al = p.passa(PassaggioPostino.Verso.AL_POSTINO)!!
        assertTrue("la pagina si apre", al.apriPagina)
        assertFalse(al.chiudiPagina)
        // ripetuto (la pagina magari è dietro): torna davanti, senza un secondo passaggio
        val ancora = p.passa(PassaggioPostino.Verso.AL_POSTINO)!!
        assertTrue(ancora.apriPagina && !ancora.cambiato)
        val torna = p.passa(PassaggioPostino.Verso.A_JBOSS)!!
        assertTrue("la pagina si chiude e si torna alla chat di JBoss", torna.chiudiPagina)
        assertFalse(torna.apriPagina)
        assertEquals(PassaggioPostino.A_JBOSS, torna.testo)
    }

    @Test
    fun `con la pagina del Postino davanti la finestra non scade, poi i 15 minuti contano da quando la lascia`() {
        var adesso = 0L
        var davanti = true
        val p = PassaggioPostino(paginaDavanti = { davanti }) { adesso }
        p.passa(PassaggioPostino.Verso.AL_POSTINO)
        adesso += 3 * PassaggioPostino.FINESTRA_MS
        assertTrue("Boss sta guardando la posta: il Postino ha ancora la conversazione", p.conPostino)
        davanti = false
        p.usato() // la pagina lasciata (onStop)
        adesso += PassaggioPostino.FINESTRA_MS - 1
        assertTrue(p.conPostino)
        assertEquals(2L, p.scadeFra())
        adesso += 2
        assertFalse("il ritorno automatico dopo 15 minuti resta", p.conPostino)
        assertNull(p.scadeFra())
    }
}

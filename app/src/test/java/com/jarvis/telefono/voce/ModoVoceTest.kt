package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// La voce senza mani (09/10): acceso, pausa, spento. Popup e notifica leggono queste regole.
class ModoVoceTest {

    @Test
    fun `solo da acceso si ascolta`() {
        assertTrue(ModoVoce.ACCESO.ascolta)
        assertFalse(ModoVoce.PAUSA.ascolta)
        assertFalse(ModoVoce.SPENTO.ascolta)
    }

    @Test
    fun `il modo salvato si rilegge, e un valore ignoto vale acceso`() {
        for (m in ModoVoce.entries) assertEquals(m, ModoVoce.da(m.chiave))
        assertEquals(ModoVoce.PAUSA, ModoVoce.da(" Pausa "))
        assertEquals(ModoVoce.ACCESO, ModoVoce.da(null))
        assertEquals(ModoVoce.ACCESO, ModoVoce.da("boh"))
    }

    @Test
    fun `pausa riprendi e spegni dalla notifica`() {
        assertEquals(ModoVoce.PAUSA, RegoleModo.dopo(ModoVoce.ACCESO, AzioneVoce.PAUSA))
        assertEquals(ModoVoce.ACCESO, RegoleModo.dopo(ModoVoce.PAUSA, AzioneVoce.RIPRENDI))
        assertEquals(ModoVoce.SPENTO, RegoleModo.dopo(ModoVoce.PAUSA, AzioneVoce.SPEGNI))
        assertEquals(ModoVoce.ACCESO, RegoleModo.dopo(ModoVoce.SPENTO, AzioneVoce.RIPRENDI))
        // «Pausa» da spento non riaccende niente.
        assertEquals(ModoVoce.SPENTO, RegoleModo.dopo(ModoVoce.SPENTO, AzioneVoce.PAUSA))
    }

    @Test
    fun `l'interruttore del popup accende e spegne`() {
        assertEquals(ModoVoce.SPENTO, RegoleModo.dopo(ModoVoce.ACCESO, AzioneVoce.INTERRUTTORE))
        assertEquals(ModoVoce.ACCESO, RegoleModo.dopo(ModoVoce.SPENTO, AzioneVoce.INTERRUTTORE))
        assertEquals(ModoVoce.ACCESO, RegoleModo.dopo(ModoVoce.PAUSA, AzioneVoce.INTERRUTTORE))
    }

    @Test
    fun `ascolta ed esci non cambiano il modo`() {
        for (m in ModoVoce.entries) {
            assertEquals(m, RegoleModo.dopo(m, AzioneVoce.ASCOLTA))
            assertEquals(m, RegoleModo.dopo(m, AzioneVoce.ESCI))
        }
    }

    @Test
    fun `i tasti della notifica seguono il modo`() {
        assertEquals(listOf(AzioneVoce.PAUSA, AzioneVoce.SPEGNI, AzioneVoce.ASCOLTA), RegoleModo.tastiNotifica(ModoVoce.ACCESO))
        assertEquals(listOf(AzioneVoce.RIPRENDI, AzioneVoce.SPEGNI), RegoleModo.tastiNotifica(ModoVoce.PAUSA))
        assertEquals(listOf(AzioneVoce.RIPRENDI, AzioneVoce.ESCI), RegoleModo.tastiNotifica(ModoVoce.SPENTO))
        // Android ne mostra al massimo tre; «Ascolta» non c'è mai in pausa o spento.
        for (m in ModoVoce.entries) {
            assertTrue(RegoleModo.tastiNotifica(m).size <= 3)
            if (!m.ascolta) assertFalse(AzioneVoce.ASCOLTA in RegoleModo.tastiNotifica(m))
        }
    }

    @Test
    fun `i tasti del popup seguono il modo`() {
        assertEquals(AzioneVoce.PAUSA to AzioneVoce.SPEGNI, RegoleModo.tastiPopup(ModoVoce.ACCESO))
        assertEquals(AzioneVoce.RIPRENDI to AzioneVoce.SPEGNI, RegoleModo.tastiPopup(ModoVoce.PAUSA))
        assertEquals(AzioneVoce.RIPRENDI, RegoleModo.tastiPopup(ModoVoce.SPENTO).first)
        assertNull(RegoleModo.tastiPopup(ModoVoce.SPENTO).second)
    }

    @Test
    fun `il microfono si apre solo da acceso, con la parola attiva e senza dettato`() {
        assertTrue(RegoleModo.microfonoConsentito(ModoVoce.ACCESO, parolaAttiva = true, inPausaDettato = false))
        assertFalse(RegoleModo.microfonoConsentito(ModoVoce.PAUSA, parolaAttiva = true, inPausaDettato = false))
        assertFalse(RegoleModo.microfonoConsentito(ModoVoce.SPENTO, parolaAttiva = true, inPausaDettato = false))
        assertFalse(RegoleModo.microfonoConsentito(ModoVoce.ACCESO, parolaAttiva = false, inPausaDettato = false))
        assertFalse(RegoleModo.microfonoConsentito(ModoVoce.ACCESO, parolaAttiva = true, inPausaDettato = true))
    }

    // 09/10, difetto visto sul telefono: in pausa/spento il microfono raccoglieva ancora parole e JBoss ripartiva.
    // Causa: i blocchi in volo e le frasi già catturate (in trascrizione) non guardavano il modo. CancelloAscolto lo fa.
    @Test
    fun `in pausa e spento nessun blocco del microfono entra nel Motore`() {
        val c = CancelloAscolto(ModoVoce.ACCESO)
        assertTrue(c.bloccoAmmesso())
        c.imposta(ModoVoce.PAUSA)
        assertFalse(c.bloccoAmmesso())
        c.imposta(ModoVoce.SPENTO)
        assertFalse(c.bloccoAmmesso())
        c.imposta(ModoVoce.ACCESO)
        assertTrue(c.bloccoAmmesso())
    }

    @Test
    fun `una frase catturata prima della pausa non arriva al nucleo, neanche se si riprende subito`() {
        val c = CancelloAscolto(ModoVoce.ACCESO)
        val timbro = c.timbro()            // il Motore chiude la frase
        assertTrue(c.fraseValida(timbro))
        c.imposta(ModoVoce.PAUSA)          // Boss tocca Pausa mentre Whisper trascrive
        assertFalse(c.fraseValida(timbro)) // dopo la trascrizione: buttata
        c.imposta(ModoVoce.ACCESO)         // e anche dopo «Riprendi» resta vecchia
        assertFalse(c.fraseValida(timbro))
        assertTrue(c.fraseValida(c.timbro())) // le frasi nuove passano
    }

    @Test
    fun `spento e uguale a pausa per le frasi in volo, e rimettere lo stesso modo non invalida niente`() {
        val c = CancelloAscolto(ModoVoce.ACCESO)
        val t = c.timbro()
        c.imposta(ModoVoce.ACCESO)
        assertTrue(c.fraseValida(t))
        c.imposta(ModoVoce.SPENTO)
        assertFalse(c.fraseValida(t))
        assertFalse(c.fraseValida(c.timbro())) // da spento nessuna frase vale
    }
}

package com.jarvis.telefono.bolla

import com.jarvis.telefono.mani.CancelloInvio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 1.2.2: i testi della bolla di stato, i segnali e la verifica dopo l'invio. */
class BollaTest {

    private fun args(vararg kv: Pair<String, String>): (String) -> String = { k -> kv.toMap()[k].orEmpty() }

    @Test
    fun `lo strumento della VPS diventa il nome dell'azione`() {
        assertEquals("Apro WhatsApp…", TestiBolla.perStrumento("componi", args("tipo" to "whatsapp", "numero" to "3331234567"))!!.titolo)
        assertEquals("a 3331234567", TestiBolla.perStrumento("componi", args("tipo" to "whatsapp", "numero" to "3331234567"))!!.dettaglio)
        assertEquals("Apro la mail…", TestiBolla.perStrumento("componi", args("tipo" to "mail", "a" to "x@y.it"))!!.titolo)
        assertEquals("Cerco su Google…", TestiBolla.perStrumento("cerca_google", args("domanda" to "meteo Olbia"))!!.titolo)
        assertEquals("Chiedo a Gemini…", TestiBolla.perStrumento("gemini_chiedi", args("domanda" to "ciao"))!!.titolo)
        assertEquals("Apro WhatsApp…", TestiBolla.perStrumento("apri_app", args("nome" to "whatsapp"))!!.titolo)
        assertEquals("Apro Revolut…", TestiBolla.perStrumento("apri_app", args("nome" to "revolut"))!!.titolo)
        assertEquals("Guardo lo schermo…", TestiBolla.perStrumento("read_screen", args())!!.titolo)
        assertEquals("Sto lavorando…", TestiBolla.perStrumento("qualcosa_di_nuovo", args())!!.titolo)
        assertNull("le attese non cambiano la bolla", TestiBolla.perStrumento("attendi", args()))
    }

    @Test
    fun `annulla dice che niente e partito e che JBoss resta in ascolto`() {
        // 1.2.4: «Annulla» fa quello che faceva «Ferma»: stessa bolla rossa, stesso messaggio.
        val r = TestiBolla.invio("annulla")
        assertEquals("Annullato", r.titolo)
        assertEquals("niente inviato · JBoss resta in ascolto", r.dettaglio)
        assertEquals(TestiBolla.Tono.ERRORE, r.tono)
    }

    @Test
    fun `invia_bozza aspetta il si di Boss`() {
        val r = TestiBolla.perStrumento("invia_bozza", args("app" to "WhatsApp", "destinatario" to "Boss"))!!
        assertEquals("Aspetto il tuo «invia»", r.titolo)
        assertEquals(TestiBolla.Tono.ATTESA, r.tono)
        assertTrue(r.dettaglio.contains("WhatsApp"))
    }

    @Test
    fun `la risposta finale e Fatto, la domanda resta aperta, l'errore e rosso`() {
        val f = TestiBolla.perRisposta("Fatto, Boss. Il messaggio è partito alle 16:17.", errore = false)
        assertEquals("Fatto", f.titolo)
        assertEquals(TestiBolla.Tono.FATTO, f.tono)
        assertEquals("Fatto, Boss.", f.dettaglio)
        val d = TestiBolla.perRisposta("Ho trovato due Marco. Quale dei due?", errore = false)
        assertEquals(TestiBolla.Tono.ATTESA, d.tono)
        assertEquals("Quale dei due?", d.dettaglio)
        val e = TestiBolla.perRisposta("Ho avuto un problema a ragionarci: timeout", errore = true)
        assertEquals(TestiBolla.Tono.ERRORE, e.tono)
        assertEquals("timeout", e.dettaglio)
    }

    @Test
    fun `il dettaglio lungo si accorcia`() {
        val lungo = "a".repeat(300)
        val r = TestiBolla.hoSentito(lungo)
        assertTrue(r.dettaglio.length <= TestiBolla.MAX_DETTAGLIO + 2)
        assertTrue(r.dettaglio.endsWith("…»"))
        assertEquals("«manda un WhatsApp»", TestiBolla.hoSentito("  manda   un WhatsApp ").dettaglio)
    }

    @Test
    fun `i tre segnali sono diversi, udibili e corti`() {
        val att = Suoni.campioni(Suoni.Tipo.ATTIVO)
        val fat = Suoni.campioni(Suoni.Tipo.FATTO)
        val err = Suoni.campioni(Suoni.Tipo.ERRORE)
        // Il segnale d'attivazione sta dentro i 300 ms che il Motore butta dopo la parola.
        assertTrue(Suoni.durataMs(Suoni.Tipo.ATTIVO) < 300)
        assertEquals(Suoni.RATE * Suoni.durataMs(Suoni.Tipo.ATTIVO) / 1000, att.size)
        assertNotEquals(att.size, fat.size)
        assertNotEquals(fat.size, err.size)
        // Udibile: picco oltre metà scala (il vecchio ToneGenerator suonava a -35 dB).
        assertTrue((att.maxOf { abs(it.toInt()) }) > Short.MAX_VALUE / 2)
        // Niente clic: inizio e fine a zero.
        assertTrue(abs(att.first().toInt()) < 200 && abs(err.last().toInt()) < 2000)
        // L'errore è più basso (note sotto i 500 Hz), l'attivazione più alta.
        assertTrue(Suoni.note(Suoni.Tipo.ERRORE).all { it.first < 500 })
        assertTrue(Suoni.note(Suoni.Tipo.ATTIVO).all { it.first > 800 })
    }

    @Test
    fun `Whisper scrive In via e vale come invia, la domanda di Jarvis no`() {
        assertEquals(CancelloInvio.Esito.INVIA, CancelloInvio.interpreta("In via."))
        assertEquals(CancelloInvio.Esito.INVIA, CancelloInvio.interpreta("in viala"))
        assertEquals(CancelloInvio.Esito.INVIA, CancelloInvio.interpreta("invia"))
        assertEquals(CancelloInvio.Esito.ALTRO, CancelloInvio.interpreta("Aspetto la tua risposta."))
        assertEquals(CancelloInvio.Esito.ALTRO, CancelloInvio.interpreta("vado in via Roma"))
    }

    @Test
    fun `dopo Invia conta se la bozza e sparita dal campo`() {
        val bozza = "Messaggio di prova da Jarvis"
        // Il caso del 07/10: il campo vuoto di WhatsApp mostra il suggerimento «Messaggio».
        assertEquals(
            "la bozza non è più nel campo: partita",
            CancelloInvio.verificaInvio(false, listOf(bozza), listOf("Messaggio"), bozza),
        )
        assertEquals("l'app ha chiuso la bozza", CancelloInvio.verificaInvio(true, listOf(bozza), listOf(bozza), bozza))
        assertEquals("il campo del testo si è svuotato", CancelloInvio.verificaInvio(false, listOf(bozza), listOf(""), bozza))
        assertTrue(CancelloInvio.verificaInvio(false, listOf(bozza), listOf(bozza), bozza).startsWith("toccato Invia, ma"))
    }
}

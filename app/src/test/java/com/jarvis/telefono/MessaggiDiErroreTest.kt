package com.jarvis.telefono

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cosa si dice a Claude quando un'azione sul telefono non va a segno.
 *
 * Non è cosmesi: Claude legge questo testo e ci costruisce sopra la frase che
 * l'utente sente. «Azione non riuscita» non gli basta per capire cosa dire, e
 * soprattutto non gli fa capire se deve riprovare o fermarsi.
 */
class MessaggiDiErroreTest {

    @Test
    fun `app non trovata dice quale app`() {
        val m = PhoneActionExecutor.failureMessage("open_app", app = "Ristomanager")
        assertTrue("manca il nome dell'app: $m", m.contains("Ristomanager"))
    }

    @Test
    fun `tasto sconosciuto dice quali sono quelli buoni`() {
        val m = PhoneActionExecutor.failureMessage("key", tasto = "MENU")
        assertTrue("manca il tasto chiesto: $m", m.contains("MENU"))
        assertTrue("non dice quali valgono: $m", m.contains("HOME") && m.contains("BACK") && m.contains("RECENTS"))
    }

    @Test
    fun `scrivere senza un campo selezionato lo dice chiaro`() {
        val m = PhoneActionExecutor.failureMessage("type_text")
        assertTrue("non spiega perché: $m", m.contains("campo di testo"))
    }

    @Test
    fun `il tocco a vuoto non promette cose che non sa`() {
        val m = PhoneActionExecutor.failureMessage("tap")
        assertTrue("non dice che è il tocco a non essere andato: $m", m.contains("tocco"))
    }

    @Test
    fun `un'azione che non conosciamo la nomina invece di tacere`() {
        val m = PhoneActionExecutor.failureMessage("balla_la_tarantella")
        assertEquals("Azione non riuscita sul telefono: balla_la_tarantella", m)
    }

    @Test
    fun `nessun messaggio e vuoto o generico`() {
        for (azione in listOf("open_app", "tap", "type_text", "key", "qualcosaltro")) {
            val m = PhoneActionExecutor.failureMessage(azione)
            assertTrue("messaggio vuoto per $azione", m.isNotBlank())
            assertTrue("messaggio troppo corto per $azione: $m", m.length > 20)
        }
    }
}

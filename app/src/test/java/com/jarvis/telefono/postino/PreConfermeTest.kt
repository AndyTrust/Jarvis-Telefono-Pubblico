package com.jarvis.telefono.postino

import com.jarvis.telefono.postino.PreConferme.Esito
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Una sola conferma nel Postino: il tocco su Invia (o Elimina) fatto sul telefono vale come il sì della VPS per QUEL
 * lavoro, solo se il testo e il destinatario sono quelli visti. Altrimenti il riquadro resta (DA_CONFERMARE).
 * La regola «nessun invio senza il sì dal telefono» resta: senza tocco non c'è permesso.
 */
class PreConfermeTest {

    private val ora = 1_000_000L
    private val anteprima = "Ricevuto, grazie.\n\n\n\nIl 07/10/2026 alle 19:57, \"Marco Rossi\" <marco.rossi@example.com> ha scritto:\n> Buongiorno"
    private val dest = "\"Marco Rossi\" <marco.rossi@example.com>"
    private val motivo = "Invio la risposta al numero 1 a \"Marco Rossi\" <marco.rossi@example.com>?"

    @Before fun pulisci() = PreConferme.azzera()

    @Test fun `senza tocco sul telefono la conferma resta da fare`() {
        assertEquals(Esito.DA_CONFERMARE, PreConferme.verifica("L1", "A1", "invio", dest, anteprima, motivo, ora))
    }

    @Test fun `tocco su Invia con lo stesso testo vale come il si, una volta sola`() {
        PreConferme.registra("L1", "invio", "Ricevuto, grazie.", "marco.rossi@example.com", 1, ora)
        assertEquals(Esito.CONFERMATA_ORA, PreConferme.verifica("L1", "A1", "invio", dest, anteprima, motivo, ora + 5_000))
        // la stessa conferma vista da un altro punto dell'app: niente secondo «invia», niente riquadro
        assertEquals(Esito.GIA_CONFERMATA, PreConferme.verifica("L1", "A1", "invio", dest, anteprima, motivo, ora + 6_000))
        // un secondo invio nello stesso lavoro chiede il sì: il permesso era per uno
        assertEquals(Esito.DA_CONFERMARE, PreConferme.verifica("L1", "A2", "invio", dest, anteprima, motivo, ora + 7_000))
    }

    @Test fun `testo diverso da quello visto - si chiede`() {
        PreConferme.registra("L1", "invio", "Ricevuto, grazie mille.", null, 1, ora)
        assertEquals(Esito.DA_CONFERMARE, PreConferme.verifica("L1", "A1", "invio", dest, anteprima, motivo, ora))
    }

    @Test fun `destinatario diverso - si chiede`() {
        PreConferme.registra("L1", "invio", "Ricevuto, grazie.", "altro@example.com", 1, ora)
        assertEquals(Esito.DA_CONFERMARE, PreConferme.verifica("L1", "A1", "invio", dest, anteprima, motivo, ora))
    }

    @Test fun `numero di mail diverso - si chiede`() {
        PreConferme.registra("L1", "invio", "Ricevuto, grazie.", null, 7, ora)
        assertEquals(Esito.DA_CONFERMARE, PreConferme.verifica("L1", "A1", "invio", dest, anteprima, motivo, ora))
    }

    @Test fun `un altro lavoro o un altro tipo - si chiede`() {
        PreConferme.registra("L1", "invio", "Ricevuto, grazie.", null, 1, ora)
        assertEquals(Esito.DA_CONFERMARE, PreConferme.verifica("L2", "A1", "invio", dest, anteprima, motivo, ora))
        assertEquals(Esito.DA_CONFERMARE, PreConferme.verifica("L1", "A1", "cancellazione", "", "", "", ora))
    }

    @Test fun `permesso scaduto - si chiede`() {
        PreConferme.registra("L1", "invio", "Ricevuto, grazie.", null, 1, ora)
        assertEquals(Esito.DA_CONFERMARE, PreConferme.verifica("L1", "A1", "invio", dest, anteprima, motivo, ora + PreConferme.VALIDITA_MS + 1))
    }

    @Test fun `invio senza testo visto non passa mai da solo`() {
        PreConferme.registra("L1", "invio", null, null, null, ora)
        assertEquals(Esito.DA_CONFERMARE, PreConferme.verifica("L1", "A1", "invio", dest, anteprima, motivo, ora))
    }

    @Test fun `cancellazione confermata sul telefono copre il blocco della VPS`() {
        PreConferme.registra("L9", "cancellazione", ora = ora)
        assertEquals(Esito.CONFERMATA_ORA, PreConferme.verifica("L9", "B1", "cancellazione", "", "20 mail nel Cestino", "", ora + 1_000))
    }

    @Test fun `i testi delle tre azioni parlano di JBoss, mai di Jarvis`() {
        val testi = listOf(PostinoTesti.PARLA) + listOf("PAUSA", "IN_AVVIO", "GUASTO", "SPENTO", "VOCE_SPENTA").map { PostinoTesti.voceNonPronta(it) }
        for (t in testi) { assertTrue(t, t.contains("JBoss")); assertFalse(t, t.contains("Jarvis")) }
    }
}

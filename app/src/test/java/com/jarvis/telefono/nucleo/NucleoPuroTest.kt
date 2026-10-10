package com.jarvis.telefono.nucleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Configurazione personale e frasi dopo gli errori delle mani: le parti pure del nucleo. */
class NucleoPuroTest {

    @Test fun `di fabbrica i valori sono neutri`() {
        val c = ConfigPersonale()
        assertEquals("", c.accountMail)
        assertEquals("", c.chatSeStesso)
        assertEquals(-1f, c.sogliaEffettiva)
        assertEquals(1.5, c.fineFraseS, 0.0)
    }

    @Test fun `il file di Boss si legge`() {
        val c = ConfigPersonale.daJson(
            """{"filtro_impronta": false, "fine_frase_s": 1.5, "ascolto_sempre_acceso": true, "volume_segnali": 0.8,
               "app_mail": "gmail", "account_mail": "nome@example.com", "chat_se_stesso": "NOME (Tu)", "lingua": "it"}""",
            "/data/x/config-boss.json",
        )
        assertEquals("gmail", c.appMail)
        assertEquals("nome@example.com", c.accountMail)
        assertEquals("NOME (Tu)", c.chatSeStesso)
        assertEquals(0.8f, c.volumeSegnali!!, 0.001f)
        assertEquals(-1f, c.sogliaEffettiva)
        assertTrue(c.ascoltoSempreAcceso)
    }

    @Test fun `file rotto vale fabbrica e valori fuori scala si tagliano`() {
        assertTrue(ConfigPersonale.daJson("non json", "x").origine.startsWith("fabbrica"))
        assertEquals(7.0, ConfigPersonale.daJson("""{"fine_frase_s": 30}""", "x").fineFraseS, 0.0)
        assertEquals("", ConfigPersonale.daJson("""{"account_mail": "senza chiocciola"}""", "x").accountMail)
        assertNull(ConfigPersonale.daJson("{}", "x").volumeSegnali)
        // whatsapp_me: numero di esempio neutro; in qualunque forma diventa «39…», senza «+».
        assertEquals("393330000001", ConfigPersonale.daJson("""{"whatsapp_me": "393330000001"}""", "x").whatsappMe)
        assertEquals("393330000001", ConfigPersonale.daJson("""{"whatsapp_me": "+39 333 000 0001"}""", "x").whatsappMe)
        assertEquals("393330000001", ConfigPersonale.daJson("""{"whatsapp_me": "333 0000001"}""", "x").whatsappMe)
        assertEquals("", ConfigPersonale.daJson("""{"whatsapp_me": "boh"}""", "x").whatsappMe)
        assertEquals("", ConfigPersonale.daJson("{}", "x").whatsappMe)
    }

    @Test fun `filtro impronta acceso usa la soglia`() =
        assertEquals(0.4f, ConfigPersonale.daJson("""{"filtro_impronta": true, "soglia_impronta": 0.4}""", "x").sogliaEffettiva, 0.001f)

    @Test fun `errori delle mani in frasi per Boss`() {
        assertEquals("Annullato: non ho mandato niente.", TestiRisposta.dopoErrore("invia_bozza", "Boss ha annullato: niente è stato inviato. Non fare altre azioni"))
        assertEquals("", TestiRisposta.dopoErrore("invia_bozza", "Boss non ha confermato entro 2 minuti: niente è stato inviato."))
        assertEquals("Non ho inviato niente.", TestiRisposta.dopoErrore("invia_bozza", "Boss NON ha confermato l'invio. Ha detto: «boh»."))
        assertEquals("Non trovo l'app Pippo sul telefono.", TestiRisposta.dopoErrore("apri_app", "Non ho trovato sul telefono un'app che corrisponde a \"Pippo\".", "Pippo"))
        assertTrue(TestiRisposta.dopoErrore("read_screen", "Il servizio di Accessibilità di Jarvis non è attivo: vai").contains("accessibilità"))
        assertEquals("Non ci sono riuscito: Tipo sconosciuto.", TestiRisposta.dopoErrore("componi", "Tipo sconosciuto. Altro testo"))
    }

    @Test fun `prima frase senza lo schermo`() {
        assertEquals("Non trovo un campo.", TestiRisposta.primaFrase("Non trovo un campo. Schermo:\n[1] ok"))
        assertFalse(TestiRisposta.primaFrase("x".repeat(500)).length > 160)
    }
}

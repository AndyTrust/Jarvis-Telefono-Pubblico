package com.jarvis.telefono.configura

import com.jarvis.telefono.nucleo.ConfigPersonale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.1: Impostazioni → Voce. I valori si scrivono nella stessa configurazione che legge la voce. */
class VoceImpostazioniTest {

    @Test
    fun `fine frase da 1,0 a 3,0 s a passi di 0,1`() {
        assertEquals(1.6, RegoleVoce.fineFrase(1.5, +1), 1e-9)
        assertEquals(1.4, RegoleVoce.fineFrase(1.5, -1), 1e-9)
        assertEquals(3.0, RegoleVoce.fineFrase(2.95, +1), 1e-9)
        assertEquals(1.0, RegoleVoce.fineFrase(1.0, -1), 1e-9)
        assertEquals(1.0, RegoleVoce.fineFrase(0.6, 0), 1e-9) // un valore vecchio sotto il minimo si porta a 1,0
        var v = 1.0
        repeat(20) { v = RegoleVoce.fineFrase(v, +1) }
        assertEquals(3.0, v, 1e-9)
    }

    @Test
    fun `volume in percento a passi di 10`() {
        assertEquals(100, RegoleVoce.volumePercento(null))
        assertEquals(70, RegoleVoce.volumePercento(0.7f))
        assertEquals(0.7, RegoleVoce.volumeDaPercento(70), 1e-9)
        assertEquals(1.0, RegoleVoce.volumeDaPercento(130), 1e-9)
    }

    @Test
    fun `quello che la pagina salva la voce lo rilegge`() {
        val testo = ConfigPersonale.unisci(
            """{"app_mail":"gmail","lingua":"it"}""",
            mapOf("fine_frase_s" to 2.2, "filtro_impronta" to true, "volume_segnali" to 0.4, "ascolto_sempre_acceso" to false, "app_mail" to "samsung"),
        )
        val c = ConfigPersonale.daJson(testo, "prova")
        assertEquals(2.2, c.fineFraseS, 1e-9)
        assertTrue(c.filtroImpronta)
        assertEquals(0.35f, c.sogliaEffettiva, 1e-6f)
        assertEquals(0.4f, c.volumeSegnali!!, 1e-6f)
        assertFalse(c.ascoltoSempreAcceso)
        assertEquals("samsung", c.appMail)
        assertEquals("it", c.lingua)
    }

    @Test
    fun `filtro spento = soglia -1, null toglie la chiave`() {
        val testo = ConfigPersonale.unisci("""{"filtro_impronta":true,"volume_segnali":0.5}""", mapOf("filtro_impronta" to false, "volume_segnali" to null))
        val c = ConfigPersonale.daJson(testo, "prova")
        assertEquals(-1f, c.sogliaEffettiva, 0f)
        assertEquals(null, c.volumeSegnali)
    }

    @Test
    fun `niente segreti e il numero WhatsApp non si scrive in chiaro`() {
        val testo = ConfigPersonale.unisci("""{"whatsapp_me":"393331234567"}""", mapOf("token" to "x", "password_mail" to "y", "whatsapp_me" to "393339999999"))
        val o = JSONObject(testo)
        assertFalse(o.has("token")); assertFalse(o.has("password_mail"))
        assertEquals("393331234567", o.getString("whatsapp_me")) // non sovrascritto da qui
        val tolto = JSONObject(ConfigPersonale.unisci(testo, mapOf("whatsapp_me" to null)))
        assertFalse(tolto.has("whatsapp_me"))
    }

    @Test
    fun `il numero si vede solo nelle ultime tre cifre e i nomi sono semplici`() {
        assertEquals("numero che finisce con 567", RegoleVoce.numeroNascosto("393331234567"))
        assertEquals("non impostato", RegoleVoce.numeroNascosto(""))
        assertEquals("Quella del telefono", RegoleVoce.appPosta(""))
        assertEquals("Samsung Email", RegoleVoce.appPosta("samsung"))
        assertEquals("Gmail", RegoleVoce.appPosta("gmail"))
        assertTrue(RegoleVoce.TRASCRITTORI.none { it.second.contains("Whisper", ignoreCase = true) })
    }
}

package com.jarvis.telefono.configura

import com.jarvis.telefono.configura.IndiceImpostazioni.Gruppo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.1: Impostazioni a 7 gruppi con la ricerca in alto (layout approvato da Boss il 08/10). */
class IndiceImpostazioniTest {

    private fun primo(q: String) = IndiceImpostazioni.cerca(q).first()

    @Test
    fun `i 7 gruppi del layout approvato, nell'ordine`() {
        assertEquals(
            listOf("Voce", "Postino e mail", "Collegamento Jarvis", "Account e accessi", "Sicurezza", "Aspetto", "Informazioni"),
            Gruppo.values().map { it.titolo },
        )
    }

    @Test
    fun `ogni gruppo ha almeno una voce e ogni voce sta in un posto solo`() {
        for (g in Gruppo.values()) assertTrue(g.titolo, IndiceImpostazioni.VOCI.any { it.gruppo == g })
        val titoli = IndiceImpostazioni.VOCI.map { it.titolo.lowercase() }
        assertEquals(titoli.size, titoli.toSet().size)
    }

    @Test
    fun `la ricerca porta al gruppo giusto`() {
        assertEquals(Gruppo.VOCE, primo("fine frase").gruppo)
        assertEquals(Gruppo.VOCE, primo("impronta").gruppo)
        assertEquals(Gruppo.VOCE, primo("microfono").gruppo)
        assertEquals(Gruppo.COLLEGAMENTO, primo("qr").gruppo)
        assertEquals(Gruppo.COLLEGAMENTO, primo("vps").gruppo)
        assertEquals(Gruppo.ACCOUNT, primo("password siti").gruppo)
        assertEquals(Gruppo.ASPETTO, primo("scuro").gruppo)
        assertEquals(Gruppo.INFO, primo("licenze").gruppo)
        assertEquals(Gruppo.POSTA, primo("pec").gruppo)
        assertEquals(Gruppo.SICUREZZA, primo("esporta").gruppo)
    }

    @Test
    fun `senza maiuscole, accenti e con piu parole`() {
        assertEquals("Permessi", primo("ACCESSIBILITA").titolo)
        assertEquals("Fine frase", primo("Fine   Frase").titolo)
        assertTrue(IndiceImpostazioni.cerca("x").isEmpty())
        assertTrue(IndiceImpostazioni.cerca("zebra marziana").isEmpty())
    }

    @Test
    fun `le chiavi dei gruppi si rileggono`() {
        for (g in Gruppo.values()) assertEquals(g, Gruppo.da(g.chiave))
        assertEquals(null, Gruppo.da("permessi"))
    }
}

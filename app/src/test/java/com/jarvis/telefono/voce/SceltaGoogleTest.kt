package com.jarvis.telefono.voce

import com.jarvis.telefono.voce.SceltaGoogle.Azione
import com.jarvis.telefono.voce.SceltaGoogle.Via
import org.junit.Assert.assertEquals
import org.junit.Test

class SceltaGoogleTest {

    @Test
    fun `senza testo un errore di rete o di lingua passa alla via successiva`() {
        for (c in listOf(1, 2, 4, 5, 8, 10, 11, 12, 13)) {
            assertEquals("codice $c", Azione.PROSSIMA_VIA, SceltaGoogle.classifica(c, testoVisto = false, parlatoIniziato = false, chiusuraChiesta = false))
        }
    }

    @Test
    fun `con del testo provvisorio si consegna quello`() {
        assertEquals(Azione.CONSEGNA_PARZIALE, SceltaGoogle.classifica(2, testoVisto = true, parlatoIniziato = true, chiusuraChiesta = false))
        assertEquals(Azione.CONSEGNA_PARZIALE, SceltaGoogle.classifica(7, testoVisto = true, parlatoIniziato = true, chiusuraChiesta = true))
    }

    @Test
    fun `silenzio e nessuna corrispondenza sono niente parlato`() {
        assertEquals(Azione.NESSUN_PARLATO, SceltaGoogle.classifica(6, false, false, false))
        assertEquals(Azione.NESSUN_PARLATO, SceltaGoogle.classifica(7, false, false, false))
    }

    @Test
    fun `permessi e audio`() {
        assertEquals(Azione.MICROFONO_NEGATO, SceltaGoogle.classifica(9, false, false, false))
        assertEquals(Azione.MICROFONO_GUASTO, SceltaGoogle.classifica(3, false, false, false))
    }

    @Test
    fun `chiusura chiesta dall'utente senza testo non cambia via`() {
        assertEquals(Azione.NESSUN_PARLATO, SceltaGoogle.classifica(5, false, false, chiusuraChiesta = true))
    }

    @Test
    fun `se l'utente ha già parlato non si cambia via`() {
        assertEquals(Azione.FALLITO, SceltaGoogle.classifica(2, false, parlatoIniziato = true, chiusuraChiesta = false))
    }

    @Test
    fun `ordine delle vie e ripartenza da quella buona`() {
        assertEquals(listOf(Via.DISPOSITIVO, Via.GOOGLE_OFFLINE, Via.GOOGLE), SceltaGoogle.vie(true, null))
        assertEquals(listOf(Via.GOOGLE_OFFLINE, Via.GOOGLE), SceltaGoogle.vie(false, null))
        assertEquals(listOf(Via.GOOGLE), SceltaGoogle.vie(true, Via.GOOGLE))
        assertEquals(listOf(Via.GOOGLE_OFFLINE, Via.GOOGLE), SceltaGoogle.vie(false, Via.DISPOSITIVO))
    }

    @Test
    fun `prima ipotesi ripulita`() {
        assertEquals("ciao Jarvis", SceltaGoogle.primo(listOf("  ciao   Jarvis ", "altro")))
        assertEquals("", SceltaGoogle.primo(null))
        assertEquals("", SceltaGoogle.primo(emptyList()))
    }
}

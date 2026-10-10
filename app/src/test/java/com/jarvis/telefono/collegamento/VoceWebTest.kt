package com.jarvis.telefono.collegamento

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoceWebTest {
    @Test fun `un testo corto resta un pezzo`() {
        assertEquals(listOf("Ciao l'utente."), VoceWeb.pezzi("Ciao l'utente."))
    }

    @Test fun `un testo vuoto non dà pezzi`() {
        assertTrue(VoceWeb.pezzi("   ").isEmpty())
    }

    @Test fun `un testo lungo si taglia a fine frase sotto il limite`() {
        val frase = "Questa è una frase di prova abbastanza lunga. "
        val testo = frase.repeat(200)
        val p = VoceWeb.pezzi(testo, max = 1000)
        assertTrue(p.size > 1)
        assertTrue(p.all { it.length <= 1000 })
        assertTrue(p.dropLast(1).all { it.trimEnd().endsWith(".") })
        assertEquals(testo.trim().replace(" ", ""), p.joinToString("").replace(" ", ""))
    }

    @Test fun `senza spazi taglia comunque`() {
        val p = VoceWeb.pezzi("a".repeat(2500), max = 1000)
        assertEquals(3, p.size)
        assertEquals(2500, p.sumOf { it.length })
    }

    @Test fun `l'id di un pezzo si scrive e si rilegge`() {
        val x = VoceWeb.Id.leggi(VoceWeb.Id.scrivi("u7", 1, 3, 2999))!!
        assertEquals("u7", x.id); assertEquals(1, x.k); assertEquals(3, x.n); assertEquals(2999, x.offset)
        assertNull(VoceWeb.Id.leggi("rotto"))
        assertNull(VoceWeb.Id.leggi(null))
    }

    @Test fun `l'id passato alla pagina non porta codice`() {
        assertEquals("'u7'", VoceWeb.Id.js("u7"))
        assertEquals("'xalert1'", VoceWeb.Id.js("x');alert(1);//"))
    }
}

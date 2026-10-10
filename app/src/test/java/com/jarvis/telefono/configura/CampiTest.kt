package com.jarvis.telefono.configura

import com.jarvis.telefono.cassaforte.Sicurezza
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Controlli dei campi, messaggi in italiano, registro degli accessi senza valori (0.4.0). */
class CampiTest {
    @Test fun `indirizzo`() {
        assertNull(Campi.indirizzo("boss@esempio.it"))
        assertTrue(Campi.indirizzo("")!!.contains("Scrivi"))
        assertTrue(Campi.indirizzo("boss esempio.it")!!.contains("spazi"))
        assertTrue(Campi.indirizzo("boss@esempio")!!.contains("nome@dominio"))
    }

    @Test fun `password e password per app`() {
        assertNull(Campi.password("x", perApp = false))
        assertTrue(Campi.password("", perApp = true)!!.contains("password per app"))
        assertEquals("abcdefghijklmnop", Campi.pulisciPasswordPerApp("abcd efgh ijkl mnop"))
        assertEquals("Pw con spazio", Campi.pulisciPasswordPerApp("Pw con spazio"))
    }

    @Test fun `server e porta`() {
        assertEquals(993, Campi.server("imap.gmail.com", "993", Sicurezza.SSL).getOrThrow().porta)
        assertTrue(Campi.server("", "993", Sicurezza.SSL).exceptionOrNull()!!.message!!.contains("server"))
        assertTrue(Campi.server("imap.x.it", "abc", Sicurezza.SSL).exceptionOrNull()!!.message!!.contains("numero"))
        assertTrue(Campi.porta("70000")!!.contains("65535"))
        assertTrue(Campi.host("imap")!!.contains("non sembra"))
    }

    @Test fun `frase dell'esportazione`() {
        assertTrue(Campi.frase("corta".toCharArray(), "corta".toCharArray())!!.contains("8"))
        assertTrue(Campi.frase("una frase lunga".toCharArray(), "un'altra frase".toCharArray())!!.contains("uguali"))
        assertNull(Campi.frase("una frase lunga".toCharArray(), "una frase lunga".toCharArray()))
    }

    @Test fun `id della casella unico e pulito`() {
        assertEquals("mail-mario-rossi", Campi.idCasella("Mario.Rossi@esempio.it", emptySet()))
        assertEquals("mail-boss-2", Campi.idCasella("boss@a.it", setOf("mail-boss")))
        assertTrue(Campi.idCasella("boss@a.it", emptySet()).matches(Regex("[a-z0-9][a-z0-9-]{1,40}")))
    }

    @Test fun `indirizzo corto per l'elenco`() {
        assertEquals("a@b.it", Campi.corto("a@b.it"))
        assertTrue(Campi.corto("nomemoltolungodavvero@gmail.com").let { it.endsWith("…@gmail.com") && it.length <= 22 })
    }

    @Test fun `registro degli accessi - ultime righe, persistente, mai valori`() {
        val f = File.createTempFile("registro", ".json")
        var t = 1_000L
        val r = RegistroAccessi(f) { t }
        r.segna("mostrato", "boss@esempio.it"); t += 10
        r.segna("esportata", "cassaforte")
        assertEquals(listOf("esportata", "mostrato"), r.ultime().map { it.azione })
        val di = RegistroAccessi(f) { t }
        assertEquals(2, di.ultime().size)
        repeat(RegistroAccessi.MASSIMO + 5) { di.segna("x", "y") }
        assertEquals(RegistroAccessi.MASSIMO, di.ultime(1000).size)
        assertTrue(RegistroAccessi.testo(r.ultime().first()).contains("esportata: cassaforte"))
        f.delete()
    }
}

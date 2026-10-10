package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le parti pure della Voce: la spezzatura del testo e l'intestazione WAV. */
class VoceTest {

    @Test
    fun `un testo corto resta un pezzo solo`() {
        assertEquals(listOf("Ciao l'utente. Tutto a posto."), TestoVoce.spezza("  Ciao l'utente.\n Tutto a posto.  "))
    }

    @Test
    fun `un testo vuoto non da pezzi`() {
        assertTrue(TestoVoce.spezza("   \n ").isEmpty())
    }

    @Test
    fun `un testo lungo si spezza sui punti in pezzi di al massimo 300`() {
        val frase = "Questa è una frase di prova che dice qualcosa di sensato sul lavoro di oggi."
        val testo = (1..12).joinToString(" ") { frase } + " Fine; ultima parte."
        val pezzi = TestoVoce.spezza(testo)
        assertTrue(pezzi.size > 1)
        pezzi.forEach { assertTrue("pezzo di ${it.length}", it.length <= 300) }
        // Ogni pezzo tranne l'ultimo finisce su un punto o un punto e virgola.
        pezzi.dropLast(1).forEach { assertTrue(it, it.endsWith(".") || it.endsWith(";")) }
        // Niente si perde e niente si aggiunge.
        assertEquals(testo, pezzi.joinToString(" "))
    }

    @Test
    fun `il punto e virgola vale come il punto`() {
        val a = "a".repeat(200) + ";"
        val b = "b".repeat(200) + "."
        assertEquals(listOf(a, b), TestoVoce.spezza("$a $b"))
    }

    @Test
    fun `una frase piu lunga di 300 si taglia sulle virgole e poi sugli spazi`() {
        val parte = "parola ".repeat(30).trim() // 209 caratteri
        val testo = "$parte, $parte, $parte."
        val pezzi = TestoVoce.spezza(testo)
        pezzi.forEach { assertTrue("pezzo di ${it.length}", it.length <= 300) }
        assertEquals(testo, pezzi.joinToString(" "))
        val lunghissima = "x".repeat(700)
        assertEquals(listOf(300, 300, 100), TestoVoce.spezza(lunghissima).map { it.length })
    }

    @Test
    fun `json del testo con virgolette e a capo`() {
        assertEquals("\"di \\\"ciao\\\"\\n\\\\ok\"", TestoVoce.jsonStringa("di \"ciao\"\n\\ok"))
    }
}

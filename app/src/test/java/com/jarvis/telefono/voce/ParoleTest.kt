package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * La parola di attivazione (0.6.0): solo «Hey Boss» e «Hey JBoss» (sei pronunce, tutte @JBOSS).
 * Il KeywordSpotter rifiuta una riga con un token che non esiste: qui si controlla prima.
 */
class ParoleTest {

    private val cartella = listOf(File("src/main/assets/kws-model"), File("app/src/main/assets/kws-model")).first { it.isDirectory }
    private val token = File(cartella, "tokens.txt").readLines().map { it.substringBefore(' ') }.toSet()
    private val righe = File(cartella, "keywords.txt").readLines().filter { it.isNotBlank() }

    @Test
    fun `ogni token delle parole esiste nel modello`() {
        for (r in righe) {
            val pezzi = r.split(Regex("\\s+")).filter { !it.startsWith(":") && !it.startsWith("#") && !it.startsWith("@") }
            val mancano = pezzi.filter { it !in token }
            assertTrue("riga «$r»: token assenti $mancano", mancano.isEmpty())
        }
    }

    @Test
    fun `solo Hey Boss e Hey JBoss, niente Jarvis`() {
        // 0.6.0 (Boss 08/10): «devo avere attivo solo Hey Boss e Hey JBoss; vanno tolti Hey Jarvis, Jarvis ecc.»
        assertEquals(6, righe.size)
        assertTrue("ogni riga esce come JBOSS", righe.all { it.endsWith("@JBOSS") })
        assertTrue("nessuna pronuncia di Jarvis", righe.none { "JA R VI S" in it || "A R VI S" in it || "VI S" in it })
        assertTrue(righe.any { it.startsWith("▁HE Y ▁BO S S ") })
        assertTrue(righe.any { it.startsWith("▁HE Y ▁JA Y ▁BO S S ") })
        assertTrue(righe.all { Regex(":\\d+(\\.\\d+)? #0\\.\\d+").containsMatchIn(it) })
        // «Hey Boss» non più permissivo di #0.2: sotto, «Ok boss lo faccio io» scatta (banco del 07/10).
        val heyBoss = righe.first { it.startsWith("▁HE Y ▁BO S S ") }
        assertTrue(Regex("#(0\\.\\d+)").find(heyBoss)!!.groupValues[1].toDouble() >= 0.2)
    }

    @Test
    fun `il motore accetta solo JBoss`() {
        val p = Opzioni().parole.map { Motore.normalizza(it) }.toSet()
        assertEquals(setOf("JBOSS", "HEY JBOSS", "HEY BOSS"), p)
        assertTrue(p.none { "JARVIS" in it })
    }
}

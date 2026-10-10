package com.jarvis.telefono.ui

import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.nucleo.Cronologia
import com.jarvis.telefono.nucleo.FiltroCronologia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boss 2026-10-09: il filo di JBoss ha lo storico (fino a 60 scambi, niente filtro di sessione, i più vecchi escono);
 * le chat degli altri agenti restano «solo la sessione aperta».
 */
class StoricoJBossTest {

    private var n = 0L
    private fun boss(t: String, esito: String = "voce") = Cronologia.Voce(++n, n * 1000, Cronologia.BOSS, t, esito = esito)
    private fun jarvis(t: String, agente: String = "") = Cronologia.Voce(++n, n * 1000, Cronologia.JARVIS, t, "regole", "ok", 100, agente)

    /** [k] scambi di JBoss: domanda e risposta. */
    private fun scambi(k: Int, da: Int = 0) = (da until da + k).flatMap { listOf(boss("domanda $it"), jarvis("risposta $it")) }

    @Test
    fun `il filo di JBoss mostra lo storico di prima della sessione`() {
        val prima = scambi(5)
        val sessione = n * 1000 + 1 // la chat si apre dopo questi 5 scambi
        val dopo = scambi(2, da = 5)
        val f = FiltroCronologia.perFilo(prima + dopo, FiltroCronologia.JARVIS, "", sessione, storico = true)
        assertEquals(14, f.size)
        assertEquals("domanda 0", f.first().testo)
        assertEquals("risposta 6", f.last().testo)
    }

    @Test
    fun `il filo di JBoss si ferma a 60 scambi e i più vecchi escono`() {
        val tutte = scambi(75)
        val f = FiltroCronologia.perFilo(tutte, FiltroCronologia.JARVIS, "", Long.MAX_VALUE, storico = true)
        assertEquals(FiltroCronologia.STORICO_JBOSS, FiltroCronologia.scambi(f).size)
        assertEquals(120, f.size)
        assertEquals("domanda 15", f.first().testo)
        assertEquals("risposta 74", f.last().testo)
        // Uno nuovo entra, il più vecchio esce.
        val conNuovo = tutte + listOf(boss("domanda 75"), jarvis("risposta 75"))
        val g = FiltroCronologia.perFilo(conNuovo, FiltroCronologia.JARVIS, "", Long.MAX_VALUE, storico = true)
        assertEquals("domanda 16", g.first().testo)
        assertEquals("risposta 75", g.last().testo)
        assertEquals(g.first().quando, FiltroCronologia.inizioStorico(g))
    }

    @Test
    fun `lo storico di JBoss lascia fuori gli scambi del Postino`() {
        val tutte = scambi(2) + listOf(boss("leggi la posta"), jarvis("3 mail nuove", CatalogoAgenti.POSTINO))
        val f = FiltroCronologia.perFilo(tutte, FiltroCronologia.JARVIS, "", Long.MAX_VALUE, storico = true)
        assertTrue(f.none { it.agenteEsecutore == CatalogoAgenti.POSTINO })
        assertEquals(4, f.size)
    }

    @Test
    fun `le chat degli altri agenti restano con il filtro di sessione`() {
        val prima = listOf(boss("apri spotify", esito = "chat-mani"), jarvis("Ho aperto Spotify.", CatalogoAgenti.MANI))
        val sessione = n * 1000 + 1
        val dopo = listOf(boss("apri maps", esito = "chat-mani"), jarvis("Ho aperto Maps.", CatalogoAgenti.MANI))
        val f = FiltroCronologia.perFilo(prima + dopo, CatalogoAgenti.MANI, "", sessione, storico = false)
        assertEquals(listOf("apri maps", "Ho aperto Maps."), f.map { it.testo })
        // Senza niente dopo l'apertura il filo è vuoto (prima: «solo la sessione aperta»).
        assertEquals(emptyList<Cronologia.Voce>(), FiltroCronologia.perFilo(prima, CatalogoAgenti.MANI, "", sessione, storico = false))
    }

    @Test
    fun `filo vuoto, le schede delle deleghe partono da zero`() {
        assertEquals(0L, FiltroCronologia.inizioStorico(emptyList()))
    }
}

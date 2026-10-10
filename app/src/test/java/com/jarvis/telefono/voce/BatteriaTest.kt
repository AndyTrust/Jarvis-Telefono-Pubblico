package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il conto della batteria con un orologio finto: niente telefono. */
class BatteriaTest {
    private val ora = ContoBatteria.ORA_MS
    private val t0 = 1_000_000L

    @Test
    fun `nessuna stima prima di un ora piena`() {
        val c = ContoBatteria()
        c.inizia(t0, 100f, inCarica = false)
        assertFalse(c.dovuta(t0 + ora - 1))
        assertNull(c.registra(t0 + ora - 1, 99.5f, inCarica = false))
        assertNull(c.stima)
        assertTrue(c.dovuta(t0 + ora))
    }

    @Test
    fun `da 100 a 98,6 in un ora fa 1,4 per cento all ora`() {
        val c = ContoBatteria()
        c.inizia(t0, 100f, inCarica = false)
        val s = c.registra(t0 + ora, 98.6f, inCarica = false)
        assertEquals(1.4f, s!!, 0.001f)
        assertEquals(1.4f, c.stima!!, 0.001f)
        assertEquals("batteria 1,4 %/ora", riga(s))
    }

    @Test
    fun `un intervallo in carica si scarta e il conto riparte`() {
        val c = ContoBatteria()
        c.inizia(t0, 80f, inCarica = false)
        assertNull(c.registra(t0 + ora, 85f, inCarica = true))
        assertNull(c.stima)
        // L'ora dopo parte in carica: si scarta anche lei.
        assertNull(c.registra(t0 + 2 * ora, 84f, inCarica = false))
        // Staccato dalla spina a tutti e due i capi: conta.
        val s = c.registra(t0 + 3 * ora, 83f, inCarica = false)
        assertEquals(1.0f, s!!, 0.001f)
    }

    @Test
    fun `una batteria che sale senza carica segnata si scarta`() {
        val c = ContoBatteria()
        c.inizia(t0, 50f, inCarica = false)
        assertNull(c.registra(t0 + ora, 52f, inCarica = false))
    }

    @Test
    fun `chiamata in ritardo divide per il tempo vero`() {
        val c = ContoBatteria()
        c.inizia(t0, 100f, inCarica = false)
        val s = c.registra(t0 + 2 * ora, 97f, inCarica = false)
        assertEquals(1.5f, s!!, 0.001f)
    }

    @Test
    fun `ferma azzera il conto ma tiene la stima`() {
        val c = ContoBatteria()
        c.inizia(t0, 100f, inCarica = false)
        c.registra(t0 + ora, 99f, inCarica = false)
        c.ferma()
        assertNull(c.prossimaMs)
        assertFalse(c.dovuta(t0 + 10 * ora))
        assertEquals(1.0f, c.stima!!, 0.001f)
    }
}

package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Dal blocco del microfono (4800 campioni) ai fotogrammi del Motore (480).
class SpezzatoreTest {

    @Test
    fun `un blocco da 4800 da 10 fotogrammi da 480 in ordine`() {
        val blocco = ShortArray(4800) { it.toShort() }
        val f = Spezzatore.inFotogrammi(blocco)
        assertEquals(10, f.size)
        assertTrue(f.all { it.size == 480 })
        assertEquals(0.toShort(), f[0][0])
        assertEquals(480.toShort(), f[1][0])
        assertEquals(4799.toShort(), f[9][479])
    }

    @Test
    fun `un campione in piu si scarta`() {
        assertEquals(10, Spezzatore.inFotogrammi(ShortArray(4801)).size)
    }

    @Test
    fun `meno di un fotogramma non da niente`() {
        assertEquals(0, Spezzatore.inFotogrammi(ShortArray(479)).size)
    }

    @Test
    fun `l orologio avanza di 30 ms a fotogramma`() {
        assertEquals(1_000L, Spezzatore.istante(1_000L, 0))
        assertEquals(1_270L, Spezzatore.istante(1_000L, 9))
    }
}

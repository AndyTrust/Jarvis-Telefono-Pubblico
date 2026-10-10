package com.jarvis.telefono.nucleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.1 (KO 7): ricerca nella cronologia, un lavoro per pausa e nessun risultato vecchio sopra il nuovo. */
class RicercaRitardataTest {

    /** Un orologio finto con la coda dei lavori pianificati, come un Handler. */
    private class Orologio {
        var ora = 0L
        val coda = ArrayList<Pair<Long, Runnable>>()
        fun pianifica(ms: Long, r: Runnable) { coda += (ora + ms) to r }
        fun annulla(r: Runnable) { coda.removeAll { it.second === r } }
        fun avanza(ms: Long) {
            ora += ms
            val pronti = coda.filter { it.first <= ora }.sortedBy { it.first }
            coda.removeAll(pronti.toSet())
            pronti.forEach { it.second.run() }
        }
    }

    @Test
    fun `dieci lettere di fila fanno partire un solo lavoro, 300 ms dopo l'ultima`() {
        val o = Orologio()
        val r = Rinvio(Rinvio.RICERCA_MS, o::pianifica, o::annulla)
        var partiti = 0
        var ultimoTesto = ""
        val parola = "fatturaxyz"
        for (i in parola.indices) {
            val t = parola.substring(0, i + 1)
            r.chiedi { partiti++; ultimoTesto = t }
            o.avanza(80) // si scrive veloce: 80 ms fra le lettere
        }
        assertEquals(0, partiti)
        assertTrue(r.haInAttesa)
        o.avanza(299 - 80)
        assertEquals(0, partiti)
        o.avanza(1)
        assertEquals(1, partiti)
        assertEquals(parola, ultimoTesto)
        assertFalse(r.haInAttesa)
    }

    @Test
    fun `due pause, due lavori`() {
        val o = Orologio()
        val r = Rinvio(300, o::pianifica, o::annulla)
        var partiti = 0
        r.chiedi { partiti++ }; o.avanza(300)
        r.chiedi { partiti++ }; o.avanza(300)
        assertEquals(2, partiti)
    }

    @Test
    fun `cancella toglie il lavoro in attesa`() {
        val o = Orologio()
        val r = Rinvio(300, o::pianifica, o::annulla)
        var partiti = 0
        r.chiedi { partiti++ }
        r.cancella()
        o.avanza(1000)
        assertEquals(0, partiti)
        assertTrue(o.coda.isEmpty())
    }

    @Test
    fun `un risultato vecchio che arriva dopo il nuovo non si disegna`() {
        val t = Turni()
        val vecchio = t.nuovo()
        val nuovo = t.nuovo()
        val disegnati = ArrayList<String>()
        // arriva prima il nuovo, poi il vecchio (thread lento): solo il nuovo passa
        if (t.valido(nuovo)) disegnati += "nuovo"
        if (t.valido(vecchio)) disegnati += "vecchio"
        assertEquals(listOf("nuovo"), disegnati)
    }
}

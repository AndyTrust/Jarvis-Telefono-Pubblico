package com.jarvis.telefono.voce

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Lo stato condiviso: sulla JVM il «thread principale» è un esecutore sincrono. */
class StatoTest {

    @Before
    fun prepara() {
        StatoJarvis.azzera()
        StatoJarvis.consegna = { it() }
    }

    @After
    fun pulisci() {
        StatoJarvis.azzera()
    }

    @Test
    fun `chi osserva riceve subito lo stato corrente`() {
        StatoJarvis.aggiorna { it.copy(ascolto = Ascolto.ASCOLTO) }
        val visti = ArrayList<Stato>()
        StatoJarvis.osserva { visti.add(it) }
        assertEquals(1, visti.size)
        assertEquals(Ascolto.ASCOLTO, visti[0].ascolto)
    }

    @Test
    fun `poi riceve i cambiamenti in ordine`() {
        val visti = ArrayList<Ascolto>()
        StatoJarvis.osserva { visti.add(it.ascolto) }
        StatoJarvis.aggiorna { it.copy(ascolto = Ascolto.CATTURA) }
        StatoJarvis.aggiorna { it.copy(ascolto = Ascolto.PENSO) }
        StatoJarvis.aggiorna { it.copy(ascolto = Ascolto.PARLO) }
        assertEquals(listOf(Ascolto.SPENTO, Ascolto.CATTURA, Ascolto.PENSO, Ascolto.PARLO), visti)
        assertEquals(Ascolto.PARLO, StatoJarvis.corrente.ascolto)
    }

    @Test
    fun `uno stato uguale non si riconsegna`() {
        var consegne = 0
        StatoJarvis.osserva { consegne++ }
        StatoJarvis.aggiorna { it.copy(fineParlatoS = 1.5) } // era già 1.5
        StatoJarvis.aggiorna { it }
        assertEquals(1, consegne)
    }

    @Test
    fun `la disiscrizione ferma le consegne`() {
        var consegne = 0
        val smetti = StatoJarvis.osserva { consegne++ }
        StatoJarvis.aggiorna { it.copy(ultimoJarvis = "uno") }
        smetti()
        StatoJarvis.aggiorna { it.copy(ultimoJarvis = "due") }
        StatoJarvis.aggiorna { it.copy(ultimoUtente = "ciao") }
        assertEquals(2, consegne)
    }

    @Test
    fun `una consegna gia in coda non arriva dopo la disiscrizione`() {
        // Il thread principale finto accumula le consegne e le esegue dopo.
        val coda = ArrayList<() -> Unit>()
        StatoJarvis.consegna = { coda.add(it) }
        var consegne = 0
        val smetti = StatoJarvis.osserva { consegne++ }
        StatoJarvis.aggiorna { it.copy(glossarioTermini = 7) }
        smetti()
        coda.forEach { it() }
        assertEquals(0, consegne)
    }

    @Test
    fun `aggiornamenti da piu thread non si perdono`() {
        val n = 8
        val volte = 500
        val via = CountDownLatch(1)
        val fatti = CountDownLatch(n)
        repeat(n) {
            Thread {
                via.await()
                repeat(volte) { StatoJarvis.aggiorna { s -> s.copy(glossarioTermini = s.glossarioTermini + 1) } }
                fatti.countDown()
            }.start()
        }
        via.countDown()
        fatti.await(10, TimeUnit.SECONDS)
        assertEquals(n * volte, StatoJarvis.corrente.glossarioTermini)
    }
}

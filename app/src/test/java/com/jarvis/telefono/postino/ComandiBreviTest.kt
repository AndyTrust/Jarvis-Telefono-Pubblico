package com.jarvis.telefono.postino

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Le scorciatoie del Postino (0.5.0): «successiva», «archivia», «seleziona tutte le fatture»… */
class ComandiBreviTest {

    @Test fun sullaMailAperta() {
        assertEquals(ComandiBrevi.Breve.Successiva, ComandiBrevi.capisci("Successiva", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Successiva, ComandiBrevi.capisci("prossima.", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Precedente, ComandiBrevi.capisci("indietro", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Archivia, ComandiBrevi.capisci("archivia", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Rispondi, ComandiBrevi.capisci("Rispondi", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Invia, ComandiBrevi.capisci("inviala", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Chiudi, ComandiBrevi.capisci("torna alla lista", mailAperta = true))
    }

    /** 0.6.6: Elimina, Spam, Fatto, Dopo anche a voce sulla mail aperta (Elimina e Spam poi chiedono la conferma). */
    @Test fun leAzioniNuoveAVoce() {
        assertEquals(ComandiBrevi.Breve.Cestina, ComandiBrevi.capisci("Elimina", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Cestina, ComandiBrevi.capisci("eliminala", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Spam, ComandiBrevi.capisci("spam", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Spam, ComandiBrevi.capisci("è spam", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Fatto, ComandiBrevi.capisci("Fatto.", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Dopo, ComandiBrevi.capisci("più tardi", mailAperta = true))
        assertEquals(ComandiBrevi.Breve.Dopo, ComandiBrevi.capisci("dopo", mailAperta = true))
        assertNull(ComandiBrevi.capisci("togli", mailAperta = true))
        assertNull(ComandiBrevi.capisci("spam", mailAperta = false))
    }

    @Test fun lefrasiVannoAJBoss() {
        assertNull(ComandiBrevi.capisci("rispondi che arrivo lunedì", mailAperta = true))
        assertNull(ComandiBrevi.capisci("chiedi a Marco il preventivo", mailAperta = true))
        assertNull(ComandiBrevi.capisci("rispondi 4: ok", mailAperta = true))
        assertNull(ComandiBrevi.capisci("archivia", mailAperta = false))   // sulla lista «archivia» vuole i numeri
    }

    @Test fun selezioneDaVoce() {
        val f = ComandiBrevi.capisci("seleziona tutte le fatture", mailAperta = false) as ComandiBrevi.Breve.Seleziona
        assertEquals(setOf(7, 3), f.livelli)
        assertEquals(setOf(5, 6), (ComandiBrevi.capisci("Seleziona tutte le promozioni", false) as ComandiBrevi.Breve.Seleziona).livelli)
        assertEquals(emptySet<Int>(), (ComandiBrevi.capisci("seleziona tutte", false) as ComandiBrevi.Breve.Seleziona).livelli)
        assertEquals(ComandiBrevi.Breve.Deseleziona, ComandiBrevi.capisci("deseleziona", false))
        val p = StatoPostino.Proposta("leggi", null, "")
        fun v(n: Int, l: Int) = StatoPostino.Voce(n, "jarvis", "x", "x@y.it", "o", "", "c", l, "media", p, "", 0, null)
        assertEquals(listOf(2, 4), ComandiBrevi.numeri(f, listOf(v(1, 5), v(2, 7), v(3, 1), v(4, 3))))
    }
}

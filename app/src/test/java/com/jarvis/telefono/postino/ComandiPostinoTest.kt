package com.jarvis.telefono.postino

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Gli stessi esempi di strumenti/prova_postino_numeri.py (ESEMPI_PARSER / ERRORI_PARSER): i due parser non si separano. */
class ComandiPostinoTest {

    private data class Atteso(val verbo: String, val numeri: List<Int>, val selettore: String?, val cartella: String?)

    private val esempi = listOf(
        "rispondi 3 e 7" to listOf(Atteso("rispondi", listOf(3, 7), null, null)),
        "cestina da 10 a 15" to listOf(Atteso("cestina", (10..15).toList(), null, null)),
        "cestina dal 10 al 12" to listOf(Atteso("cestina", listOf(10, 11, 12), null, null)),
        "Cestina 3, 5-9 e 12" to listOf(Atteso("cestina", listOf(3, 5, 6, 7, 8, 9, 12), null, null)),
        "invia tutte le bozze" to listOf(Atteso("invia", emptyList(), "bozze", null)),
        "rispondi 4: Grazie, confermo lunedì." to listOf(Atteso("rispondi", listOf(4), null, null)),
        "archivia 5" to listOf(Atteso("archivia", listOf(5), null, null)),
        "sposta 6 in fatture fornitori" to listOf(Atteso("sposta", listOf(6), null, "Fatture-Fornitori")),
        "sposta 6 nella cartella veicolo" to listOf(Atteso("sposta", listOf(6), null, "Veicolo")),
        "cestina tre e sette" to listOf(Atteso("cestina", listOf(3, 7), null, null)),
        "cestina dal tre al sette" to listOf(Atteso("cestina", (3..7).toList(), null, null)),
        "cestina ventitré" to listOf(Atteso("cestina", listOf(23), null, null)),
        "cestina tutte le promozioni" to listOf(Atteso("cestina", emptyList(), "cat:5,6", null)),
        "segna come lette 1-3" to listOf(Atteso("letta", listOf(1, 2, 3), null, null)),
        "cestina 3; archivia 4" to listOf(Atteso("cestina", listOf(3), null, null), Atteso("archivia", listOf(4), null, null)),
        "cestina 3 poi rispondi 4: ok" to listOf(Atteso("cestina", listOf(3), null, null), Atteso("rispondi", listOf(4), null, null)),
        "controlla la posta" to listOf(Atteso("resoconto", emptyList(), null, null)),
        "pagina 2" to listOf(Atteso("pagina", listOf(2), null, null)),
        "apri 12" to listOf(Atteso("apri", listOf(12), null, null)),
        "rispondi e invia 9: va bene" to listOf(Atteso("rispondi", listOf(9), null, null)),
        "cestina 3–5" to listOf(Atteso("cestina", listOf(3, 4, 5), null, null)),
        "inoltra 5 a mario.rossi@example.com: guarda tu" to listOf(Atteso("inoltra", listOf(5), null, null)),
        "rispondi 4 a x80@example.it: primo; secondo poi terzo" to listOf(Atteso("rispondi", listOf(4), null, null)),
        "istruisci 3: rispondi che arrivo lunedì; poi chiedi il totale" to listOf(Atteso("istruisci", listOf(3), null, null)),
        "cestina 2; rispondi 4: ok; va bene" to listOf(Atteso("cestina", listOf(2), null, null), Atteso("rispondi", listOf(4), null, null)),
        "inoltra e invia 6 a a@b.it: ecco" to listOf(Atteso("inoltra", listOf(6), null, null)),
    )
    private val errori = listOf(
        "", "fai qualcosa", "cestina", "cestina 0", "cestina 9-3", "sposta 3", "sposta 3 in boh",
        "rispondi tutte", "cestina 1-900", "cestina 3: testo", "apri 3 e 4", "archivia le bozze",
        "istruisci 3", "inoltra 3: testo", "istruisci 3 e 4: x",
    )

    @Test fun esempiComeLaVps() {
        for ((testo, attesi) in esempi) {
            val r = ComandiPostino.capisci(testo)
            assertEquals(testo, attesi, r.map { Atteso(it.verbo, it.numeri, it.selettore, it.cartella) })
        }
    }

    @Test fun testoDopoIDuePuntiEInvia() {
        assertEquals("Grazie, confermo lunedì.", ComandiPostino.capisci("rispondi 4: Grazie, confermo lunedì.")[0].testo)
        assertTrue(ComandiPostino.capisci("rispondi e invia 9: va bene")[0].invia)
    }

    @Test fun erroriComeLaVps() {
        for (t in errori) {
            try {
                ComandiPostino.capisci(t)
                fail("doveva fermarsi: «$t»")
            } catch (e: ComandiPostino.Errore) {
                assertTrue(t, !e.message.isNullOrBlank())
            }
        }
    }

    @Test fun parolePerNumeri() {
        assertEquals(21, ComandiPostino.parolaNumero("ventuno"))
        assertEquals(38, ComandiPostino.parolaNumero("trentotto"))
        assertEquals(23, ComandiPostino.parolaNumero("ventitré"))
        assertEquals(null, ComandiPostino.parolaNumero("una"))
    }

    @Test fun elencoCortoEAnteprima() {
        assertEquals("3, 5-7, 9, 10", ComandiPostino.elencoCorto(listOf(9, 3, 5, 6, 7, 10)))
        assertEquals("Farò: cestina 10-15 (6 mail)", ComandiPostino.anteprima(ComandiPostino.capisci("cestina da 10 a 15")))
        assertEquals("Farò: rispondi 3, 7 (2 mail) con una bozza scritta dal Postino",
            ComandiPostino.anteprima(ComandiPostino.capisci("rispondi 3 e 7")))
        assertEquals("Farò: invia tutte le bozze", ComandiPostino.anteprima(ComandiPostino.capisci("invia tutte le bozze")))
        assertEquals("Farò: sposta 6 in Veicolo", ComandiPostino.anteprima(ComandiPostino.capisci("sposta 6 in veicolo")))
        assertEquals("cestina 3, 5-7", ComandiPostino.daSelezione("cestina", setOf(7, 3, 5, 6)))
    }

    // 0.5.0 (08/10/2026): testo intero dopo i due punti, destinatario, casella, cartelle vere

    @Test fun testoInteroEDestinatario() {
        val r = ComandiPostino.capisci("rispondi 4 a x80@example.it: primo; secondo poi terzo\nquarto")
        assertEquals(1, r.size)
        assertEquals(listOf(4), r[0].numeri)
        assertEquals("x80@example.it", r[0].destinatario)
        assertEquals("primo; secondo poi terzo\nquarto", r[0].testo)
        val i = ComandiPostino.capisci("inoltra e invia 6 a a@b.it: ecco")[0]
        assertEquals("inoltra", i.verbo)
        assertTrue(i.invia)
        assertEquals("a@b.it", i.destinatario)
        assertEquals("Farò: inoltra e invia 6 a a@b.it", ComandiPostino.anteprima(listOf(i)))
    }

    @Test fun postaDiUnaCasella() {
        assertEquals("jarvis", ComandiPostino.capisci("controlla la posta di jarvis")[0].casella)
        assertEquals(null, ComandiPostino.capisci("controlla la posta")[0].casella)
        assertEquals("Farò: controlla la posta di jarvis", ComandiPostino.anteprima(ComandiPostino.capisci("controlla la posta di jarvis")))
    }

    @Test fun cartelleVereDelResoconto() {
        try {
            ComandiPostino.capisci("sposta 3 in richieste voli")
            fail("senza resoconto la cartella non c'è")
        } catch (e: ComandiPostino.Errore) { }
        ComandiPostino.cartelleVere = listOf("Richieste-Voli", "Partner")
        try {
            assertEquals("Richieste-Voli", ComandiPostino.capisci("sposta 3 in richieste voli")[0].cartella)
            assertEquals("Partner", ComandiPostino.capisci("archivia 3 in partner")[0].cartella)
        } finally {
            ComandiPostino.cartelleVere = emptyList()
        }
    }
}

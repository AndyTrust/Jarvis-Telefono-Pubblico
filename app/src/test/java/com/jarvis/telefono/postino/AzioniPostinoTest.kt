package com.jarvis.telefono.postino

import com.jarvis.telefono.postino.AzioniPostino.Azione
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 0.6.6: swipe e azioni rapide del Postino (layout approvato da Boss il 2026-10-08). */
class AzioniPostinoTest {

    @Test fun swipeSinistraEliminaDestraArchivia() {
        assertEquals(Azione.ELIMINA, AzioniPostino.perSwipe(AzioniPostino.Swipe.SINISTRA))
        assertEquals(Azione.ARCHIVIA, AzioniPostino.perSwipe(AzioniPostino.Swipe.DESTRA))
    }

    @Test fun eliminaESpamSempreConConferma() {
        assertTrue(AzioniPostino.chiedeConferma(Azione.ELIMINA))
        assertTrue(AzioniPostino.chiedeConferma(Azione.SPAM))
        // archivia subito, senza conferma ma annullabile; le altre non sono irreversibili
        for (a in listOf(Azione.ARCHIVIA, Azione.RISPONDI, Azione.FATTO, Azione.DOPO)) assertFalse(a.name, AzioniPostino.chiedeConferma(a))
        assertTrue(AzioniPostino.annullabile(Azione.ARCHIVIA))
        assertFalse(AzioniPostino.annullabile(Azione.ELIMINA))
        // la domanda vale anche per una mail sola
        assertEquals("Elimino la mail 4? Va nel Cestino (recuperabile da lì).", AzioniPostino.domanda(Azione.ELIMINA, listOf(4)))
        assertTrue(AzioniPostino.domanda(Azione.SPAM, listOf(3, 4, 5)).startsWith("Segno 3 mail (3-5) come spam"))
    }

    @Test fun comandiPerOgniAzione() {
        assertEquals(listOf("cestina 4"), AzioniPostino.comandi(Azione.ELIMINA, listOf(4)))
        assertEquals(listOf("spam 3-5"), AzioniPostino.comandi(Azione.SPAM, listOf(5, 3, 4)))
        assertEquals(listOf("tieni 7"), AzioniPostino.comandi(Azione.DOPO, listOf(7)))
        assertEquals(listOf("sposta 2 in Rumore"), AzioniPostino.comandi(Azione.ARCHIVIA, listOf(2), "Rumore"))
        // Fatto: prima letta (verificata in arrivo), poi l'archivio
        assertEquals(listOf("letta 2", "sposta 2 in GitHub"), AzioniPostino.comandi(Azione.FATTO, listOf(2), "GitHub"))
        // senza cartella non si inventa niente; Rispondi non manda niente da solo
        assertTrue(AzioniPostino.comandi(Azione.ARCHIVIA, listOf(2), null).isEmpty())
        assertTrue(AzioniPostino.comandi(Azione.FATTO, listOf(2), "").isEmpty())
        assertTrue(AzioniPostino.comandi(Azione.RISPONDI, listOf(2)).isEmpty())
        assertTrue(AzioniPostino.comandi(Azione.ELIMINA, emptyList()).isEmpty())
        assertTrue(AzioniPostino.vuoleCartella(Azione.FATTO) && AzioniPostino.vuoleCartella(Azione.ARCHIVIA))
    }

    @Test fun iComandiSonoCapitiDalParser() {
        val attese = mapOf(
            "cestina 4" to "cestina", "spam 3-5" to "spam", "tieni 7" to "tieni", "letta 2" to "letta",
            "sposta 2 in Rumore" to "sposta", "sposta 3, 7 in Fatture-Fornitori; sposta 5 in GitHub" to "sposta",
        )
        for ((c, v) in attese) {
            val r = ComandiPostino.capisci(c)
            assertTrue(c, r.all { it.verbo == v })
        }
        assertEquals("Rumore", ComandiPostino.capisci("sposta 2 in Rumore")[0].cartella)
    }

    @Test fun togliNonCestinaPiu() {
        try {
            val r = ComandiPostino.capisci("togli 3")
            fail("«togli» non deve diventare un comando: $r")
        } catch (e: ComandiPostino.Errore) {
            assertTrue(e.message!!.startsWith("non capisco"))
        }
    }

    @Test fun cartellaSoloSeVera() {
        val vere = listOf("Rumore", "GitHub", "Richieste-Voli")
        assertEquals("GitHub", AzioniPostino.cartellaArchivio("GitHub", "Rumore", null, vere))
        assertEquals("Rumore", AzioniPostino.cartellaArchivio(null, "Rumore", "GitHub", vere))
        assertEquals("Richieste-Voli", AzioniPostino.cartellaArchivio("Inventata", null, "Richieste-Voli", vere))
        assertNull(AzioniPostino.cartellaArchivio(null, null, "Fatture-Fornitori", vere))
        assertNull(AzioniPostino.cartellaArchivio("", null, null, vere))
    }

    @Test fun piuArchiviUnComandoSolo() {
        assertNull(AzioniPostino.comandoArchivi(emptyMap()))
        assertEquals("sposta 3, 7 in Rumore; sposta 5 in GitHub",
            AzioniPostino.comandoArchivi(linkedMapOf(7 to "Rumore", 5 to "GitHub", 3 to "Rumore")))
    }

    @Test fun codaAnnullabile() {
        var adesso = 1_000L
        val c = AzioniPostino.CodaAnnullabile(ora = { adesso }, attesa = 5_000)
        assertNull(c.prossimaFra())
        c.metti(3, "Rumore")
        adesso += 2_000
        c.metti(7, "GitHub")
        assertEquals(setOf(3, 7), c.numeri())
        assertEquals(3_000L, c.prossimaFra())
        // prima dello scadere non parte niente
        assertTrue(c.scadute().isEmpty())
        // Annulla: la mail resta dov'è, non parte
        assertTrue(c.annulla(7))
        assertFalse(c.annulla(7))
        adesso += 3_000
        assertEquals(mapOf(3 to "Rumore"), c.scadute())
        assertFalse(c.contiene(3))
        assertFalse(c.annulla(3))                 // già partita: troppo tardi
        c.metti(9, "Rumore")
        assertEquals(mapOf(9 to "Rumore"), c.scadute(tutte = true))   // uscendo dalla schermata parte tutto
        assertTrue(c.numeri().isEmpty())
    }

    @Test fun riassuntoInDueRighe() {
        assertEquals("", AzioniPostino.riassuntoBreve(null))
        assertEquals("Fattura di ottobre.", AzioniPostino.riassuntoBreve("  Fattura   di\nottobre. "))
        val lungo = "Il fornitore manda la fattura 77 di ottobre. Scade il 30 ottobre. " +
            "Chiede conferma del ricevimento entro venerdì. Allega il PDF e il file XML della fattura elettronica."
        val b = AzioniPostino.riassuntoBreve(lungo)
        assertTrue(b, b.length <= 160)
        assertTrue(b, b.startsWith("Il fornitore manda la fattura 77 di ottobre. Scade il 30 ottobre."))
        val senzaPunti = "parola ".repeat(60)
        val c = AzioniPostino.riassuntoBreve(senzaPunti)
        assertTrue(c, c.length <= 160 && c.endsWith("…"))
    }

    // ------------------------------------------------ Fatto: «letta», poi «sposta» solo dopo la verifica

    private fun sessioneConResoconto(): SessionePostino {
        val m = javaClass.getResource("/postino/giro-vps.jsonl")!!.readText().lines().filter { it.isNotBlank() }.map { JSONObject(it) }
        val s = SessionePostino()
        val primo = m.first().optString("id")
        s.ultimi[primo] = 0; s.inCorso.add(primo)
        m.filter { it.optString("id") == primo }.forEach { s.ricevi(it) }
        return s
    }

    private fun statoLetta(id: String, rid: String, n: Int, esito: String) = JSONObject()
        .put("type", "job_event").put("id", id).put("n", 1).put("kind", "postino")
        .put("dati", JSONObject().put("tipo", "stato").put("report_id", rid).put("numero", n)
            .put("verbo", "letta").put("esito", esito).put("stato", "segnata come letta"))

    private fun fine(id: String, esito: String = "ok") = JSONObject().put("type", "job_done").put("id", id).put("n", 2).put("esito", esito)

    @Test fun fattoArchiviaSoloDopoLettaVerificata() {
        val s = sessioneConResoconto()
        val rid = s.stato.reportId!!
        val comandi = AzioniPostino.comandi(Azione.FATTO, listOf(5), "Rumore")
        val (id) = s.prepara(comandi[0], nuovoId = { "fatto-1" })
        s.accoda(id, listOf(5), "letta", comandi.drop(1))
        assertTrue(s.prendiPronti().isEmpty())
        s.ricevi(statoLetta(id, rid, 5, "ok"))
        assertTrue("non prima della fine del lavoro", s.prendiPronti().isEmpty())
        s.ricevi(fine(id))
        assertEquals(listOf(listOf("sposta 5 in Rumore")), s.prendiPronti())
        assertTrue(s.prendiPronti().isEmpty())     // una volta sola
    }

    @Test fun fattoSiFermaSeLettaNonVerificata() {
        val s = sessioneConResoconto()
        val rid = s.stato.reportId!!
        val (id) = s.prepara("letta 6", nuovoId = { "fatto-2" })
        s.accoda(id, listOf(6), "letta", listOf("sposta 6 in Rumore"))
        s.ricevi(statoLetta(id, rid, 6, "errore"))
        s.ricevi(fine(id))                         // il lavoro è «ok» ma l'azione no: l'archivio non parte
        assertTrue(s.prendiPronti().isEmpty())

        val (id2) = s.prepara("letta 7", nuovoId = { "fatto-3" })
        s.accoda(id2, listOf(7), "letta", listOf("sposta 7 in Rumore"))
        s.ricevi(JSONObject().put("type", "job_errore").put("id", id2).put("motivo", "VPS giù"))
        assertTrue(s.prendiPronti().isEmpty())
    }
}

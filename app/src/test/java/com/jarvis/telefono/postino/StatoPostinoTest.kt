package com.jarvis.telefono.postino

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Lo stato della chat costruito dai messaggi VERI del ponte: giro-vps.jsonl è registrato da
 * jarvis-agent/server/postinoNumeri.test.js (lavori.js + postino_numeri.py su 200 mail finte):
 * resoconto, bozza, invio confermato, seconda bozza, invio annullato.
 */
class StatoPostinoTest {

    private fun messaggi(): List<JSONObject> =
        javaClass.getResource("/postino/giro-vps.jsonl")!!.readText().lines().filter { it.isNotBlank() }.map { JSONObject(it) }

    private fun sessioneConLavori(m: List<JSONObject>) = SessionePostino().apply {
        m.map { it.optString("id") }.distinct().forEach { ultimi[it] = 0; inCorso.add(it) }
    }

    @Test fun resocontoDa200Mail() {
        val m = messaggi()
        val s = sessioneConLavori(m)
        val primo = m.takeWhile { !(it.optString("type") == "job_done") }
        primo.forEach { s.ricevi(it) }
        assertEquals(200, s.stato.totale)
        assertEquals(200, s.stato.voci().size)
        assertEquals((1..200).toList(), s.stato.voci().map { it.numero })
        assertEquals(200, s.stato.conta(StatoPostino.Filtro.DA_FARE))
        assertTrue(s.stato.rigaRiassunto(), s.stato.rigaRiassunto().startsWith("200 mail"))
        val v = s.stato.voci().first { it.proposta.azione == "archivia" }
        assertNotNull(v.proposta.cartella)
        assertTrue(s.stato.voci().all { it.casella.isNotEmpty() && it.oggetto.isNotEmpty() && it.categoria.isNotEmpty() })
    }

    @Test fun confermaConAnteprimaPoiStatoVerificato() {
        val m = messaggi()
        val s = sessioneConLavori(m)
        var vistaConferma: StatoPostino.Conferma? = null
        var numeroInviato = 0
        for (x in m) {
            s.ricevi(x)
            if (x.optString("kind") == "conferma" && vistaConferma == null) vistaConferma = s.stato.conferma
            if (x.optString("kind") == "postino" && x.getJSONObject("dati").optString("stato") == "inviata") {
                numeroInviato = x.getJSONObject("dati").getInt("numero")
                val st = s.stato.voce(numeroInviato)!!.stato!!
                assertEquals("ok", st.esito)
                assertTrue(st.prova!!.startsWith("trovata in"))
                assertTrue(StatoPostino.rigaStato(st).startsWith("✓ inviata · trovata in"))
            }
        }
        val c = vistaConferma!!
        assertEquals("invio", c.azione)
        assertTrue(c.anteprima.contains("Ricevuto, grazie."))
        assertNotNull(c.numero)
        assertTrue(numeroInviato > 0)
        // dopo l'ultimo lavoro (invio annullato) lo stato di quel numero è «annullato» e torna fra i da fare
        val ultimo = s.stato.voce(numeroInviato)!!.stato!!
        assertEquals("annullato", ultimo.esito)
        assertNull("la conferma chiusa non resta a video", s.stato.conferma)
        assertTrue(s.stato.filtra(StatoPostino.Filtro.DA_FARE).any { it.numero == numeroInviato })
        assertTrue(s.inCorso.isEmpty())
    }

    @Test fun eventiRipetutiOAltruiSiScartano() {
        val m = messaggi()
        val s = sessioneConLavori(m)
        m.forEach { s.ricevi(it) }
        val prima = s.stato.voci()
        m.forEach { assertFalse(s.ricevi(it)) }      // ripresa: già visti
        assertEquals(prima, s.stato.voci())
        val altrui = JSONObject(m.first { it.optString("kind") == "postino" }.toString()).put("id", "un-altro-lavoro-1").put("n", 999)
        assertFalse(s.ricevi(altrui))
    }

    @Test fun mandaSoloConIlResoconto() {
        val s = SessionePostino()
        try {
            s.prepara("cestina 3")
            fail("senza resoconto non si manda un'azione")
        } catch (e: ComandiPostino.Errore) {
            assertTrue(e.message!!.contains("controlla la posta"))
        }
        val (id, testo, op) = s.prepara("controlla la posta") { "postino-prova-xyz1" }
        assertEquals("postino-prova-xyz1", id)
        assertEquals("controlla la posta", testo)
        assertEquals("numeri", op.getString("modo"))
        assertFalse(op.has("report_id"))
        assertEquals(listOf(true, false), s.bolle.map { it.daBoss })
        assertEquals(listOf("postino-prova-xyz1" to 0), s.daSeguire())
    }

    @Test fun filtriERighe() {
        val m = messaggi()
        val s = sessioneConLavori(m)
        m.forEach { s.ricevi(it) }
        val tot = StatoPostino.Filtro.values().filter { it != StatoPostino.Filtro.TUTTE }.sumOf { s.stato.conta(it) }
        assertEquals(200, tot)
        assertEquals("→ archivia in Fatture-Fornitori", StatoPostino.rigaProposta(StatoPostino.Proposta("archivia", "Fatture-Fornitori", "")))
        assertEquals("! errore: non la trovo", StatoPostino.rigaStato(StatoPostino.StatoVoce("errore", "errore", "invia", null, "non la trovo", null)))
    }

    // 0.5.0: scheda della mail aperta, bozza di JBoss, cartelle vere (eventi come li manda postino_numeri.py)

    private fun ev(id: String, n: Int, dati: String) =
        JSONObject("""{"type":"job_event","id":"$id","n":$n,"kind":"postino","testo":"","dati":$dati}""")

    @Test fun schedaBozzaECartelle() {
        val s = SessionePostino()
        listOf("l1", "l2", "l3").forEach { s.ultimi[it] = 0; s.inCorso.add(it) }
        s.ricevi(ev("l1", 1, """{"tipo":"totali","report_id":"r20261008081001","quando":"x","totale":2,"pages":1,"pagine":1,
            "caselle":[{"casella":"jarvis","nuove":2}],"cartelle":{"jarvis":["Fatture-Fornitori","Rumore","Instinct/negozio"]}}"""))
        s.ricevi(ev("l1", 2, """{"tipo":"voci","report_id":"r20261008081001","pagina":1,"pagine":1,"voci":[
            {"numero":2,"casella":"jarvis","da":"Fornitore Prova","indirizzo":"noreply@fornitore-prova.example","oggetto":"Fattura n. 77",
             "categoria":"Fatture fornitori","livello":7,"urgenza":"media","proposta":{"azione":"archivia","cartella":"Fatture-Fornitori","perche":"fattura"},
             "anteprima":"","allegati":1,"stato":null},
            {"numero":18,"casella":"jarvis","da":"Mario","indirizzo":"gmail-utente@example.com","oggetto":"Quando arrivi?",
             "categoria":"Da leggere","livello":2,"urgenza":"media","proposta":{"azione":"rispondi","cartella":null,"perche":""},
             "anteprima":"","allegati":0,"stato":null}]}"""))
        assertEquals(listOf("Fatture-Fornitori", "Rumore", "Instinct/negozio"), s.stato.cartellePer(2))
        assertTrue("Instinct/negozio" in ComandiPostino.cartelleVere)
        s.ricevi(ev("l2", 1, """{"tipo":"scheda","report_id":"r20261008081001","numero":2,"casella":"jarvis","da":"Fornitore Prova",
            "rispondi_a":"noreply@fornitore-prova.example","oggetto":"Fattura n. 77","testo":"In allegato la fattura.",
            "allegati":[{"n":1,"nome":"fattura-77.pdf","tipo":"application/pdf","kb":1,"estratto":"Totale da pagare 1.234,64 euro","nota":null,"letto":true}],
            "riassunto":"Fattura 77: 1.234,64 euro entro il 31/10.","riassunto_da":"modello","riassunto_in_arrivo":false,
            "destinazione":{"cartella":"Fatture-Fornitori","perche":"fattura di un fornitore","chiara":true,"scelte":["Fatture-Fornitori","Rumore"]},
            "bozza":null,"secondi":11.9}"""))
        val sc = s.stato.schede[2]!!
        assertEquals("fattura-77.pdf", sc.allegati[0].nome)
        assertTrue(sc.riassunto.contains("1.234,64") && sc.riassuntoDalModello)
        assertEquals("Fatture-Fornitori", sc.cartella)
        // JBoss chiede il destinatario: niente bozza salvata, la domanda arriva in chat
        s.ricevi(ev("l3", 1, """{"tipo":"bozza","report_id":"r20261008081001","numero":18,"tipo_bozza":"inoltro","a":"",
            "scelte":[{"nome":"Marco Alfa","indirizzo":"marco.a@example.com"},{"nome":"Marco Beta","indirizzo":"marco.b@example.com"}],
            "testo":"Mi mandi il preventivo?","domanda":"A chi la mando?","salvata":false}"""))
        val b = s.stato.bozze[18]!!
        assertFalse(b.salvata)
        assertEquals(2, b.scelte.size)
        assertEquals("A chi la mando?", s.stato.ultimaFrase)
        // poi la bozza salvata e verificata (evento stato del rispondi), poi l'invio verificato
        s.ricevi(ev("l3", 2, """{"tipo":"stato","report_id":"r20261008081001","azione_id":"a","numero":18,"verbo":"rispondi","esito":"ok",
            "stato":"bozza creata","prova":"bozza trovata in [Gmail]/Bozze (uid 542), a gmail-utente@example.com",
            "testo_bozza":"Arrivo lunedì.","destinatario":"gmail-utente@example.com"}"""))
        assertTrue(s.stato.bozze[18]!!.salvata)
        assertEquals("gmail-utente@example.com", s.stato.bozze[18]!!.a)
        assertEquals("Arrivo lunedì.", s.stato.bozze[18]!!.testo)
        s.ricevi(ev("l3", 3, """{"tipo":"stato","report_id":"r20261008081001","azione_id":"b","numero":18,"verbo":"invia","esito":"ok",
            "stato":"inviata","prova":"trovata in [Gmail]/Posta inviata (uid 9); bozza tolta dalle Bozze","destinatario":"gmail-utente@example.com"}"""))
        assertTrue(s.stato.bozze[18]!!.inviata)
        assertTrue(s.stato.voce(18)!!.stato!!.fatto)
        ComandiPostino.cartelleVere = emptyList()
    }
}

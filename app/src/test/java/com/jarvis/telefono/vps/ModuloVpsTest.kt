package com.jarvis.telefono.vps

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Modulo VPS (0.3.0, 2026-10-07): protocollo, instradamento, ripresa, conferme, riconnessione. Niente rete vera. */
class ModuloVpsTest {

    // ─── protocollo ────────────────────────────────────────────────────────────
    @Test fun authHaIlRuoloLavori() {
        val o = JSONObject(ProtocolloVps.auth("segreto"))
        assertEquals("auth", o.getString("type"))
        assertEquals("lavori", o.getString("ruolo"))
        assertEquals(1, o.getInt("versione"))
    }

    @Test fun confermaSoloInviaOAnnulla() {
        assertEquals("invia", JSONObject(ProtocolloVps.conferma("a", "b", "invia")).getString("scelta"))
        assertEquals("annulla", JSONObject(ProtocolloVps.conferma("a", "b", "sì certo")).getString("scelta"))
        assertEquals("annulla", JSONObject(ProtocolloVps.conferma("a", "b", "annulla")).getString("scelta"))
    }

    @Test fun jobStartConOpzioni() {
        val o = JSONObject(ProtocolloVps.jobStart("tel-abc12345", "ricercatore", "cerca bandi", "opus", 90, "Bandi"))
        assertEquals("job_start", o.getString("type"))
        assertEquals("opus", o.getJSONObject("opzioni").getString("modello"))
        assertEquals(60, o.getJSONObject("opzioni").getInt("max_min"))
        assertEquals("Bandi", o.getJSONObject("opzioni").getString("titolo"))
    }

    @Test fun idValidoPerLaVps() {
        val id = ProtocolloVps.nuovoId(1791400000000L, 123456789L)
        assertTrue(id, Regex("^[A-Za-z0-9_-]{8,64}$").matches(id))
    }

    @Test fun leggeEventiFineListaEConferma() {
        val e = ProtocolloVps.leggi("""{"type":"job_event","id":"x","n":7,"kind":"conferma","ts":5,"testo":"Posso?","dati":{"azione_id":"a1","azione":"invio","destinatario":"a@example.com","anteprima":"posta.py","motivo":"invio di posta","scade_ts":99}}""")
        val ev = (e as MessaggioVps.Evento).evento
        assertEquals(7, ev.n)
        val c = ev.conferma()!!
        assertEquals("a1", c.azioneId)
        assertEquals("La VPS vuole inviare", c.titolo())
        assertEquals(99L, c.scadeTs)
        val f = ProtocolloVps.leggi("""{"type":"job_done","id":"x","n":8,"esito":"ok","riassunto":"Fatto","ts":6}""") as MessaggioVps.Fine
        assertEquals("ok", f.esito)
        val l = ProtocolloVps.leggi("""{"type":"job_lista","lavori":[{"id":"x","agente":"postino","titolo":"t","testo":"t","stato":"attesa","esito":null,"creato":1,"finito":null,"ultimo_evento":3,"ultimo":"ls","conferma":{"azione_id":"q","azione":"scrittura","scade_ts":3}}],"in_corso":1,"max_paralleli":2}""") as MessaggioVps.Lista
        assertEquals("q", l.lavori[0].conferma!!.azioneId)
        assertNull(l.lavori[0].esito)
        assertNull(ProtocolloVps.leggi("non json"))
        val chiusa = EventoLavoro("x", 9, "stato", 1, "no", """{"azione_id":"a1","scelta":"annulla"}""").confermaChiusa()
        assertEquals("a1" to "annulla", chiusa)
    }

    // ─── instradamento ─────────────────────────────────────────────────────────
    @Test fun paroleEspliciteVinconoSempre() {
        val v = Instradamento.decidi("sulla VPS, controlla lo spazio disco", moduloAcceso = true, rete = true)
        assertTrue(v is Instradamento.Esito.Vps)
        assertEquals("controlla lo spazio disco", (v as Instradamento.Esito.Vps).frase)
        val q = Instradamento.decidi("qui cerca bandi per la formazione", moduloAcceso = true, rete = true)
        assertEquals(Instradamento.Esito.Locale("cerca bandi per la formazione"), q)
    }

    @Test fun predefinitiPerAgente() {
        assertTrue(Instradamento.decidi("scrivi un post sul volo", agente = "social", moduloAcceso = true, rete = true) is Instradamento.Esito.Vps)
        assertTrue(Instradamento.decidi("fai una ricerca sui bandi", agente = "mani", moduloAcceso = true, rete = true) is Instradamento.Esito.Locale)
        assertTrue(Instradamento.decidi("una bozza breve", agente = "scrittore", moduloAcceso = true, rete = true) is Instradamento.Esito.Locale)
        // automatico: breve resta qui, lungo va alla VPS
        assertTrue(Instradamento.decidi("c'è qualcosa di urgente?", agente = "postino", moduloAcceso = true, rete = true) is Instradamento.Esito.Locale)
        assertTrue(Instradamento.decidi("smista l'arretrato", agente = "postino", moduloAcceso = true, rete = true) is Instradamento.Esito.Vps)
        assertTrue(Instradamento.decidi("leggi le 240 mail di ieri", agente = "postino", moduloAcceso = true, rete = true) is Instradamento.Esito.Vps)
        assertTrue(Instradamento.decidi("leggi le 5 mail di ieri", agente = "postino", moduloAcceso = true, rete = true) is Instradamento.Esito.Locale)
        assertTrue(Instradamento.decidi("cerca le fonti sulla normativa", agente = "ricercatore", moduloAcceso = true, rete = true) is Instradamento.Esito.Vps)
        // la scelta di Boss batte il predefinito
        assertTrue(Instradamento.decidi("scrivi un post", agente = "social", preferenza = Instradamento.Dove.QUI, moduloAcceso = true, rete = true) is Instradamento.Esito.Locale)
    }

    @Test fun gliInviiRestanoQui() {
        assertTrue(Instradamento.decidi("manda un WhatsApp a Marco: report pronto", moduloAcceso = true, rete = true) is Instradamento.Esito.Locale)
        assertTrue(Instradamento.decidi("chiama Marco", moduloAcceso = true, rete = true) is Instradamento.Esito.Locale)
        assertTrue(Instradamento.decidi("apri Spotify", moduloAcceso = true, rete = true) is Instradamento.Esito.Locale)
    }

    @Test fun moduloSpentoOSenzaReteDiceLaVerita() {
        val r = Instradamento.decidi("sulla VPS cerca bandi", moduloAcceso = false, rete = true)
        assertTrue(r is Instradamento.Esito.Rifiuto)
        assertTrue((r as Instradamento.Esito.Rifiuto).messaggio.contains("spento"))
        val c = Instradamento.decidi("sulla VPS cerca bandi", moduloAcceso = true, rete = false)
        assertTrue(c is Instradamento.Esito.InCoda)
        assertTrue((c as Instradamento.Esito.InCoda).messaggio.contains("Non è ancora partito"))
        assertTrue(Instradamento.promuovi(25_000, 3))
        assertTrue(Instradamento.promuovi(1_000, 21))
        assertFalse(Instradamento.promuovi(5_000, 3))
        assertEquals("postino", Instradamento.agentePer("smista le mail"))
        assertEquals("ricercatore", Instradamento.agentePer("ricerca sui bandi"))
    }

    // ─── nucleo: ripresa, conferme, coda senza rete ────────────────────────────
    private class FintoCanale(var collegato: Boolean = true) : NucleoVps.Canale {
        val mandati = CopyOnWriteArrayList<JSONObject>()
        var richiesteCollegamento = 0
        override fun manda(testo: String): Boolean { if (!collegato) return false; mandati += JSONObject(testo); return true }
        override fun collega() { richiesteCollegamento++ }
        fun tipi() = mandati.map { it.getString("type") }
    }

    private fun ev(id: String, n: Int, kind: String, testo: String = "", dati: String = "{}") =
        MessaggioVps.Evento(EventoLavoro(id, n, kind, 1000L + n, testo, dati))

    @Test fun lavoroPostinoMandatoDaFuoriSiSegueComeGliAltri() {
        // 0.3.0: la chat Postino manda il suo job_start (modo numeri); il nucleo lo registra e ne segue eventi e fine.
        val a = ArchivioInMemoria()
        val nu = NucleoVps(a, ora = { 10L })
        nu.canale = FintoCanale()
        val finiti = ArrayList<LavoroLocale>()
        nu.ascolta(object : NucleoVps.Ascoltatore { override fun finito(l: LavoroLocale) { finiti += l } })
        assertTrue(nu.lavoroGiaMandato("postino-1-2345", "postino", "controlla la posta", "Postino"))
        assertEquals(LavoroLocale.INVIATO, a.lavoro("postino-1-2345")!!.stato)
        nu.ricevi(ev("postino-1-2345", 1, "testo", "Leggo le caselle"))
        assertEquals("Leggo le caselle", a.lavoro("postino-1-2345")!!.ultimo)
        nu.ricevi(MessaggioVps.Fine("postino-1-2345", 2, "ok", "9 mail", 20L))
        assertEquals("postino", finiti.single().agente)
        // una seconda registrazione dello stesso id non lo riapre
        assertTrue(nu.lavoroGiaMandato("postino-1-2345", "postino", "x", "Postino"))
        assertEquals(LavoroLocale.FINITO, a.lavoro("postino-1-2345")!!.stato)
    }

    @Test fun lavoroCompletoConConfermaERipresa() {
        val a = ArchivioInMemoria()
        val nu = NucleoVps(a, ora = { 10L }, caso = { 42L })
        val c = FintoCanale()
        nu.canale = c
        val conferme = ArrayList<ConfermaVps>()
        val finiti = ArrayList<LavoroLocale>()
        nu.ascolta(object : NucleoVps.Ascoltatore {
            override fun conferma(c: ConfermaVps) { conferme += c }
            override fun finito(l: LavoroLocale) { finiti += l }
        })
        val id = nu.nuovoLavoro("ricercatore", "cerca bandi")
        assertEquals("job_start", c.tipi().last())
        assertEquals(LavoroLocale.INVIATO, a.lavoro(id)!!.stato)
        nu.ricevi(ev(id, 1, "stato", "avviato", """{"stato":"avviato"}"""))
        nu.ricevi(ev(id, 2, "comando", "curl -s https://example.com"))
        assertEquals("curl -s https://example.com", a.lavoro(id)!!.ultimo)
        nu.ricevi(ev(id, 3, "conferma", "Posso?", """{"azione_id":"a1","azione":"invio","scade_ts":99999}"""))
        assertEquals(1, conferme.size)
        assertEquals(LavoroLocale.ATTESA, a.lavoro(id)!!.stato)
        // doppione: ignorato
        nu.ricevi(ev(id, 3, "conferma", "Posso?", """{"azione_id":"a1","azione":"invio","scade_ts":99999}"""))
        assertEquals(1, conferme.size)
        // il telefono cade proprio adesso: la scelta resta in attesa e parte al collegamento
        c.collegato = false
        nu.scollegato()
        assertFalse(nu.scegli(id, "a1", "invia"))
        assertEquals("invia", a.lavoro(id)!!.confermaMandata)
        assertTrue(c.richiesteCollegamento > 0)
        c.collegato = true
        c.mandati.clear()
        nu.collegatoOra()
        // al collegamento: elenco, ripresa del lavoro aperto dall'evento 3, poi la scelta rimasta in attesa
        assertEquals(listOf("job_lista", "job_segui", "conferma"), c.tipi())
        assertEquals(3, c.mandati[1].getInt("ultimo_evento"))
        assertEquals("invia", c.mandati[2].getString("scelta"))
        // job_lista dice che sulla VPS ci sono 6 eventi: già seguito, nessun doppione
        c.mandati.clear()
        nu.ricevi(ProtocolloVps.leggi("""{"type":"job_lista","lavori":[{"id":"$id","agente":"ricercatore","titolo":"t","testo":"t","stato":"lavoro","creato":1,"finito":null,"ultimo_evento":6,"ultimo":""}]}""")!!)
        assertTrue(c.tipi().isEmpty())
        nu.ricevi(ev(id, 4, "stato", "Boss ha detto sì", """{"stato":"lavoro","azione_id":"a1","scelta":"invia"}"""))
        assertNull(a.lavoro(id)!!.conferma)
        nu.ricevi(ev(id, 5, "risultato", "Trovati 3 bandi.\nDettagli"))
        nu.ricevi(MessaggioVps.Fine(id, 6, "ok", "Trovati 3 bandi.", 2000))
        nu.ricevi(MessaggioVps.Fine(id, 6, "ok", "Trovati 3 bandi.", 2000)) // doppione dopo una ripresa
        assertEquals(1, finiti.size)
        val l = a.lavoro(id)!!
        assertEquals(LavoroLocale.FINITO, l.stato)
        assertEquals("ok", l.esito)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), a.eventi(id).map { it.n })
        assertFalse(nu.haLavoroAperto())
    }

    @Test fun lavoroSenzaReteParteAlCollegamento() {
        val a = ArchivioInMemoria()
        val nu = NucleoVps(a)
        val c = FintoCanale(collegato = false)
        nu.canale = c
        val id = nu.nuovoLavoro("postino", "smista l'arretrato")
        assertEquals(LavoroLocale.DA_MANDARE, a.lavoro(id)!!.stato)
        assertTrue(nu.haLavoroAperto())
        c.collegato = true
        nu.collegatoOra()
        assertEquals(listOf("job_lista", "job_start"), c.tipi())
        assertEquals(id, c.mandati[1].getString("id"))
        assertEquals(LavoroLocale.INVIATO, a.lavoro(id)!!.stato)
        // la VPS lo rifiuta: si chiude con il motivo vero, mai un finto «fatto»
        nu.ricevi(MessaggioVps.Errore(id, "troppi lavori in coda (10)"))
        assertEquals("errore", a.lavoro(id)!!.esito)
        assertTrue(a.lavoro(id)!!.riassunto!!.contains("troppi lavori"))
    }

    @Test fun annullaPrimaDiPartireNonDisturbaLaVps() {
        val a = ArchivioInMemoria()
        val nu = NucleoVps(a)
        val c = FintoCanale(collegato = false)
        nu.canale = c
        val id = nu.nuovoLavoro("generico", "x")
        assertTrue(nu.annulla(id))
        assertEquals("annullato", a.lavoro(id)!!.esito)
        c.collegato = true
        nu.collegatoOra()
        assertEquals(listOf("job_lista"), c.tipi())
    }

    // ─── client vero (OkHttp) contro un finto ponte locale ─────────────────────
    private val server = MockWebServer()

    @After fun chiudi() { runCatching { server.shutdown() } }

    /** Un finto ponte: accetta l'auth con ruolo lavori, risponde a job_start con due eventi e la fine. */
    private inner class FintoPonte(val chiudiDopoPrimoEvento: Boolean) : WebSocketListener() {
        val ricevuti = CopyOnWriteArrayList<JSONObject>()
        var connessioni = 0
        override fun onOpen(webSocket: WebSocket, response: Response) { connessioni++ }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = JSONObject(text)
            ricevuti += m
            when (m.getString("type")) {
                "auth" -> if (m.optString("token") == "giusto" && m.optString("ruolo") == "lavori")
                    webSocket.send("""{"type":"connected","ruolo":"lavori","versione":1}""") else webSocket.close(4001, "token non valido")
                "job_start" -> {
                    val id = m.getString("id")
                    webSocket.send("""{"type":"job_event","id":"$id","n":1,"kind":"stato","ts":1,"testo":"avviato","dati":{"stato":"avviato"}}""")
                    if (chiudiDopoPrimoEvento) webSocket.close(1001, "giù") else fine(webSocket, id)
                }
                "job_segui" -> {
                    val id = m.getString("id")
                    if (m.getInt("ultimo_evento") < 2) webSocket.send("""{"type":"job_event","id":"$id","n":2,"kind":"comando","ts":2,"testo":"ls","dati":{}}""")
                    fine(webSocket, id)
                }
            }
        }
        fun fine(ws: WebSocket, id: String) = ws.send("""{"type":"job_done","id":"$id","n":3,"esito":"ok","riassunto":"Fatto","ts":3}""")
    }

    private fun client(nu: NucleoVps, token: String, aperto: () -> Boolean = { true }) = ClientVps(
        url = { server.url("/phone").toString().replace("http", "ws") },
        token = { token },
        nucleo = nu,
        deveStareAperto = aperto,
        haRete = { true },
        riconnessione = Riconnessione(longArrayOf(100, 200, 400)),
        log = {},
    )

    @Test fun clientVeroLavoroERipresaDopoLaCaduta() {
        val ponte = FintoPonte(chiudiDopoPrimoEvento = true)
        server.enqueue(MockResponse().withWebSocketUpgrade(ponte))
        val ponte2 = FintoPonte(chiudiDopoPrimoEvento = false)
        server.enqueue(MockResponse().withWebSocketUpgrade(ponte2))
        server.start()
        val a = ArchivioInMemoria()
        val nu = NucleoVps(a)
        val finito = CountDownLatch(1)
        nu.ascolta(object : NucleoVps.Ascoltatore { override fun finito(l: LavoroLocale) { finito.countDown() } })
        val cl = client(nu, "giusto")
        nu.canale = cl
        val id = nu.nuovoLavoro("generico", "prova") // non collegato: chiede il collegamento, parte all'auth
        assertTrue("il lavoro deve finire dopo la riconnessione", finito.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2, 3), a.eventi(id).map { it.n })
        assertEquals("ok", a.lavoro(id)!!.esito)
        // secondo collegamento: job_lista e job_segui dall'evento 1 (il job_start è già partito sul primo)
        val tipi2 = ponte2.ricevuti.map { it.getString("type") }
        assertEquals("auth", tipi2.first())
        assertTrue(tipi2.toString(), tipi2.contains("job_segui"))
        assertEquals(1, ponte2.ricevuti.first { it.getString("type") == "job_segui" }.getInt("ultimo_evento"))
        assertTrue(cl.byteMandati > 0 && cl.byteRicevuti > 0)
        cl.spegni()
    }

    @Test fun tokenRifiutatoFermaITentativi() {
        val ponte = FintoPonte(false)
        server.enqueue(MockResponse().withWebSocketUpgrade(ponte))
        server.enqueue(MockResponse().withWebSocketUpgrade(ponte))
        server.start()
        val nu = NucleoVps(ArchivioInMemoria())
        val cl = client(nu, "sbagliato")
        nu.canale = cl
        cl.collega()
        val limite = System.currentTimeMillis() + 5_000
        while (!cl.tokenRifiutato && System.currentTimeMillis() < limite) Thread.sleep(20)
        assertTrue(cl.tokenRifiutato)
        Thread.sleep(600) // oltre due attese di riconnessione: nessun nuovo tentativo
        assertEquals(1, server.requestCount)
        cl.spegni()
    }

    @Test fun attesaCrescenteENessunCicloStretto() {
        val r = Riconnessione()
        val a = (1..8).map { r.prossima() }
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 120_000L, 120_000L), a)
        r.azzera()
        assertEquals(2_000L, r.prossima())
    }

    @Test fun unLavoroGiaFinitoScopertoDaJobListaNonSiAnnunciaDiNuovo() {
        // 09/10 (Boss: «il resoconto arriva doppio»): job_lista porta un resoconto del Postino già finito (partito
        // dall'app vecchia, da una prova o prima di ripulire il registro). Prima la ripresa (job_segui da 0) lo faceva
        // «finire» di nuovo: notifica e «[VPS · Postino] Finito: …» nel filo, una seconda volta.
        val a = ArchivioInMemoria()
        val nu = NucleoVps(a, ora = { 10L })
        val c = FintoCanale()
        nu.canale = c
        val finiti = ArrayList<LavoroLocale>()
        nu.ascolta(object : NucleoVps.Ascoltatore { override fun finito(l: LavoroLocale) { finiti += l } })
        val id = "postino-1791547599409-7800"
        nu.ricevi(ProtocolloVps.leggi("""{"type":"job_lista","lavori":[{"id":"$id","agente":"postino","titolo":"Postino","testo":"controlla la posta","stato":"finito","esito":"ok","riassunto":"Ho letto 5 caselle","creato":1,"finito":2,"ultimo_evento":14,"ultimo":""}]}""")!!)
        assertEquals("si segue per il dettaglio", "job_segui", c.tipi().last())
        nu.ricevi(ev(id, 13, "testo", "Ho letto 5 caselle"))
        nu.ricevi(MessaggioVps.Fine(id, 14, "ok", "Ho letto 5 caselle", 2))
        assertTrue("già finito quando l'abbiamo scoperto: niente secondo annuncio", finiti.isEmpty())
        assertEquals(LavoroLocale.FINITO, a.lavoro(id)!!.stato)
        assertTrue(a.lavoro(id)!!.dellaPostaUnica)
        // un lavoro scoperto ancora in corso invece si annuncia quando finisce (una volta)
        nu.ricevi(ProtocolloVps.leggi("""{"type":"job_lista","lavori":[{"id":"tel-abc12345","agente":"ricercatore","titolo":"t","testo":"t","stato":"lavoro","creato":1,"finito":null,"ultimo_evento":2,"ultimo":""}]}""")!!)
        nu.ricevi(MessaggioVps.Fine("tel-abc12345", 3, "ok", "Trovati 3 bandi.", 5))
        nu.ricevi(MessaggioVps.Fine("tel-abc12345", 3, "ok", "Trovati 3 bandi.", 5))
        assertEquals(listOf("tel-abc12345"), finiti.map { it.id })
    }

    @Test fun iLavoriDellaPostaUnicaSiRiconosconoAncheSenzaOrigine() {
        fun l(id: String, agente: String = "postino", origine: String = "") =
            LavoroLocale(id = id, agente = agente, titolo = "Postino", testo = "controlla la posta", stato = LavoroLocale.FINITO, origine = origine)
        assertTrue(l("postino-1-2345").dellaPostaUnica)
        assertTrue(l("x", origine = "chat-postino").dellaPostaUnica)
        assertFalse("il Postino come delega generica della VPS resta una delega", l("tel-abc12345").dellaPostaUnica)
        assertFalse(l("postino-1-2345", agente = "ricercatore").dellaPostaUnica)
    }
}

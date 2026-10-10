package com.jarvis.telefono.nucleo

import com.jarvis.telefono.vps.CanaleMani
import com.jarvis.telefono.vps.MappaStrumenti
import com.jarvis.telefono.vps.ProtocolloMani
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JBoss 0.3.4: il cervello della VPS (ruolo «mani») e la catena regole → VPS. Un finto ponte jarvis-agent
 * (MockWebServer con /health e /phone), niente rete vera, niente telefono.
 */
class CervelloVpsTest {

    // ─── finto ponte ───────────────────────────────────────────────────────────
    private val server = MockWebServer()
    private val ricevuti = LinkedBlockingQueue<JSONObject>()
    private val socketPonte = CopyOnWriteArrayList<WebSocket>()
    private val upgrade = AtomicInteger(0)
    @Volatile private var altroTelefono = false
    @Volatile private var copione: (WebSocket, JSONObject) -> Unit = { _, _ -> }
    private val TOKEN = "token-di-prova-lungo-0123456789"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/health" -> MockResponse().setBody("""{"ok":true,"phoneConnected":$altroTelefono}""")
                "/phone" -> {
                    upgrade.incrementAndGet()
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { socketPonte += webSocket }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val o = JSONObject(text)
                            if (o.optString("type") == "auth") {
                                if (o.optString("token") != TOKEN) webSocket.close(4001, "token non valido")
                                else webSocket.send("""{"type":"connected"}""")
                            }
                            ricevuti.put(o)
                            copione(webSocket, o)
                        }
                    })
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun chiudi() {
        canali.forEach { it.chiudi() }
        socketPonte.forEach { runCatching { it.close(1001, null) } }
        Thread.sleep(100)
        runCatching { server.shutdown() }
    }

    private fun url() = server.url("/phone").toString().replace("http://", "ws://")

    private val canali = CopyOnWriteArrayList<CanaleMani>()
    private fun canale(token: String = TOKEN) = CanaleMani(url = { url() }, token = { token }, riposoMs = 60_000).also { canali += it }

    private val eseguiti = CopyOnWriteArrayList<JSONObject>()
    private val maniFinte: suspend (JSONObject) -> JSONObject? = { c ->
        eseguiti += c
        when (c.optString("action")) {
            "invia_bozza" -> JSONObject().put("error", "Boss ha annullato: niente è stato inviato.")
            else -> JSONObject().put("result", JSONObject().put("aperto", true))
        }
    }

    private fun vps(c: CanaleMani, disp: CervelloVps.Disponibilita = CervelloVps.Disponibilita.PRONTO, primoSegnoMs: Long = 2_000) =
        CervelloVps(c, { disp }, maniFinte, primoSegnoMs = primoSegnoMs, silenzioMs = 2_000)

    private val ctx = Contesto(contatti = null, ora = 10, minuti = 5, giorno = 7, mese = 10, anno = 2026, giornoSettimana = 3)

    private fun prossimo(tipo: String): JSONObject {
        while (true) {
            val o = ricevuti.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("il ponte non ha ricevuto $tipo")
            if (o.optString("type") == tipo) return o
        }
    }

    // ─── mappa degli strumenti ─────────────────────────────────────────────────
    @Test fun i27StrumentiDellaVpsArrivanoAlleMani() {
        // I nomi di jarvis-agent/server/phoneTools.js (2026-10-07).
        val vps = listOf(
            "componi", "invia_bozza", "cerca_google", "gemini_chiedi", "apri_app", "elenca_app", "cerca_in_app", "cerca_contatto",
            "read_screen", "tocca", "pressione_lunga", "tap", "scrivi", "type_text", "scorri", "swipe", "attendi", "invio_tastiera",
            "key", "open_app", "screenshot", "compila_accesso", "registro_azioni", "emergenza", "stato_tecnico", "modo_tecnico",
            "request_send_confirmation",
        )
        assertEquals(27, vps.size)
        assertEquals(vps.toSet(), MappaStrumenti.DALLA_VPS.keys)
        for (n in vps) assertTrue(n, MappaStrumenti.supportata(MappaStrumenti.DALLA_VPS.getValue(n)))
        assertEquals("registro", MappaStrumenti.DALLA_VPS["registro_azioni"])
        assertFalse(MappaStrumenti.supportata("formatta_telefono"))
        assertEquals(135_000L, MappaStrumenti.attesaMs(JSONObject().put("action", "invia_bozza")))
        assertEquals(105_000L, MappaStrumenti.attesaMs(JSONObject().put("action", "gemini_chiedi").put("attesa_s", 75)))
    }

    // ─── protocollo ────────────────────────────────────────────────────────────
    @Test fun protocolloMani() {
        val a = JSONObject(ProtocolloMani.auth("segreto"))
        assertEquals("auth", a.getString("type")); assertEquals("mani", a.getString("ruolo"))
        assertEquals("ciao", JSONObject(ProtocolloMani.userMessage("ciao")).getString("text"))
        val r = JSONObject(ProtocolloMani.toolResult("x1", JSONObject().put("result", "ok")))
        assertEquals("x1", r.getString("id")); assertEquals("ok", r.getString("result")); assertFalse(r.has("error"))
        assertTrue(JSONObject(ProtocolloMani.toolResult("x2", null)).has("error"))
        assertEquals("https://jarvis-agent.esempio.it/health", ProtocolloMani.indirizzoSalute("wss://jarvis-agent.esempio.it/phone?x=1"))
        assertEquals("http://127.0.0.1:8790/health", ProtocolloMani.indirizzoSalute("ws://127.0.0.1:8790/phone"))
        val tc = ProtocolloMani.leggi("""{"type":"tool_call","id":"u1","command":{"action":"componi","tipo":"whatsapp"}}""")
        assertTrue(tc is ProtocolloMani.Messaggio.ChiamataStrumento)
        assertEquals("componi", (tc as ProtocolloMani.Messaggio.ChiamataStrumento).azione)
        assertTrue(ProtocolloMani.leggi("""{"type":"connected"}""") is ProtocolloMani.Messaggio.Collegato)
    }

    @Test fun complessita() {
        assertTrue(Complessita.complessa("cerca su Google quanto costa un biglietto per Londra e dimmi il primo risultato"))
        assertTrue(Complessita.complessa("trovami un ristorante di pesce vicino a me e aprimi le indicazioni"))
        assertTrue(Complessita.complessa("apri la fotocamera e fai un autoscatto"))
        assertFalse(Complessita.complessa("apri WhatsApp"))
        assertFalse(Complessita.complessa("scrivi a Marco che arrivo tardi"))
        assertFalse(Complessita.complessa("che ore sono"))
        assertTrue(Complessita.stop("basta"))
        assertTrue(Complessita.stop("JBoss, lascia perdere"))
        assertFalse(Complessita.stop("apri basta pasta"))
    }

    // ─── la catena ─────────────────────────────────────────────────────────────
    @Test fun regolePrimaLaVpsNonSiTocca() = runBlocking {
        val cat = CervelloCatena(CervelloRegole(), vps(canale()))
        val p = cat.capisci("apri WhatsApp", ctx)
        assertTrue(p.capito); assertEquals("regole", p.cervello); assertFalse(p.giaEseguito)
        assertEquals("apri_app", p.azioni.first().action)
        assertEquals(0, server.requestCount)
    }

    @Test fun moduloSpentoNonHoCapitoOnesto() = runBlocking {
        val cat = CervelloCatena(CervelloRegole(), vps(canale(), CervelloVps.Disponibilita.SPENTO))
        val p = cat.capisci("cosa c'è in calendario domani", ctx)
        assertFalse(p.capito)
        assertTrue(p.dire, p.dire.startsWith("Ho sentito"))
        assertEquals(0, server.requestCount)
        // Senza rete lo dice.
        val p2 = CervelloCatena(CervelloRegole(), vps(canale(), CervelloVps.Disponibilita.SENZA_RETE)).capisci("cosa c'è in calendario domani", ctx)
        assertFalse(p2.capito); assertTrue(p2.dire, p2.dire.endsWith("Senza rete non posso chiederlo alla VPS."))
        assertEquals(0, server.requestCount)
    }

    @Test fun fraseNonCapitaVaAllaVpsCheUsaLeMani() = runBlocking {
        copione = { ws, o ->
            when (o.optString("type")) {
                "user_message" -> ws.send("""{"type":"tool_call","id":"srv-1","command":{"action":"componi","tipo":"mappe","dove":"ristorante di pesce","naviga":false}}""")
                "tool_result" -> ws.send("""{"type":"assistant_message","text":"Ti ho aperto Maps sui ristoranti di pesce qui vicino."}""")
            }
        }
        val cat = CervelloCatena(CervelloRegole(), vps(canale()))
        val p = cat.capisci("cosa c'è in calendario domani", ctx)
        val auth = prossimo("auth")
        assertEquals("mani", auth.getString("ruolo")); assertEquals(TOKEN, auth.getString("token"))
        assertEquals("cosa c'è in calendario domani", prossimo("user_message").getString("text"))
        val tr = prossimo("tool_result")
        assertEquals("srv-1", tr.getString("id")); assertTrue(tr.getJSONObject("result").getBoolean("aperto"))
        assertTrue(p.capito); assertTrue(p.giaEseguito); assertFalse(p.errore)
        assertEquals("vps", p.cervello)
        assertEquals("Ti ho aperto Maps sui ristoranti di pesce qui vicino.", p.dire)
        assertEquals(listOf("componi"), p.eseguite.map { it.action })
        assertEquals("mappe", eseguiti.single().optString("tipo"))
        assertTrue(p.primaAzioneMs >= 0)
    }

    @Test fun fraseComplessaCapitaDalleRegoleVaLoStessoAllaVps() = runBlocking {
        copione = { ws, o -> if (o.optString("type") == "user_message") ws.send("""{"type":"assistant_message","text":"Il primo risultato dice 89 euro."}""") }
        val p = CervelloCatena(CervelloRegole(), vps(canale()))
            .capisci("cerca su Google quanto costa un biglietto per Londra e dimmi il primo risultato", ctx)
        assertEquals("vps", p.cervello); assertEquals("Il primo risultato dice 89 euro.", p.dire)
    }

    @Test fun vpsMutaEntroIlTempoTornanoLeRegoleONonHoCapito() = runBlocking {
        copione = { _, _ -> } // non risponde mai
        val c = canale()
        val inizio = System.currentTimeMillis()
        val p = CervelloCatena(CervelloRegole(), vps(c, primoSegnoMs = 400)).capisci("cosa c'è in calendario domani", ctx)
        assertTrue(System.currentTimeMillis() - inizio < 5_000)
        assertFalse(p.capito); assertTrue(p.dire, p.dire.contains("non ha risposto"))
        // Complessa ma capita dalle regole: la VPS tace → il piano delle regole (niente era stato fatto).
        val p2 = CervelloCatena(CervelloRegole(), vps(c, primoSegnoMs = 400)).capisci("cerca su Google meteo Cagliari e dimmi se piove", ctx)
        assertTrue(p2.capito); assertEquals("regole", p2.cervello); assertFalse(p2.giaEseguito)
        assertTrue(eseguiti.isEmpty())
    }

    @Test fun unSoloTelefonoSeCeGiaUnAltroNonMiCollego() = runBlocking {
        altroTelefono = true
        val p = CervelloCatena(CervelloRegole(), vps(canale())).capisci("cosa c'è in calendario domani", ctx)
        assertFalse(p.capito); assertTrue(p.dire, p.dire.contains("già collegato un altro telefono"))
        assertEquals(0, upgrade.get())
    }

    @Test fun sostituitoDaUnAltroTelefonoAMetaLoDice() = runBlocking {
        copione = { ws, o -> if (o.optString("type") == "user_message") ws.close(4000, "sostituito da una nuova connessione") }
        val e = vps(canale()).chiedi("cosa c'è in calendario domani")
        assertTrue(e is CanaleMani.Esito.Fallito)
        assertEquals(CanaleMani.Tipo.OCCUPATO, (e as CanaleMani.Esito.Fallito).tipo)
    }

    @Test fun tokenRifiutato() = runBlocking {
        val e = vps(canale("token-sbagliato-0000000000000")).chiedi("ciao")
        assertEquals(CanaleMani.Tipo.TOKEN, (e as CanaleMani.Esito.Fallito).tipo)
    }

    @Test fun strumentoSconosciutoErroreChiaroMaiFatto() = runBlocking {
        copione = { ws, o ->
            when (o.optString("type")) {
                "user_message" -> ws.send("""{"type":"tool_call","id":"srv-9","command":{"action":"formatta_telefono"}}""")
                "tool_result" -> ws.send("""{"type":"assistant_message","text":"Non posso farlo."}""")
            }
        }
        val p = CervelloCatena(CervelloRegole(), vps(canale())).capisci("formatta il telefono adesso", ctx)
        val tr = prossimo("tool_result")
        assertTrue(tr.getString("error"), tr.getString("error").contains("non esiste su JBoss"))
        assertTrue(eseguiti.isEmpty())
        assertTrue(p.eseguite.isEmpty()); assertEquals("Non posso farlo.", p.dire)
    }

    @Test fun confermaDInvioDecideIlTelefono() = runBlocking {
        copione = { ws, o ->
            when (o.optString("type")) {
                "user_message" -> ws.send("""{"type":"tool_call","id":"b1","command":{"action":"invia_bozza","app":"WhatsApp","destinatario":"Marco","bozza":"arrivo tardi"}}""")
                "tool_result" -> ws.send("""{"type":"assistant_message","text":"Ok, non l'ho mandato."}""")
            }
        }
        val p = CervelloCatena(CervelloRegole(), vps(canale())).capisci("di' a Marco in qualche modo che arrivo tardi", ctx)
        val tr = prossimo("tool_result")
        assertEquals("b1", tr.getString("id")); assertTrue(tr.getString("error").contains("annullato"))
        assertEquals("invia_bozza", eseguiti.single().optString("action"))
        assertEquals("Ok, non l'ho mandato.", p.dire)
    }

    @Test fun bossCambiaIdeaLaRispostaVecchiaSiScarta() = runBlocking {
        val ws0 = java.util.concurrent.atomic.AtomicReference<WebSocket>()
        copione = { ws, o -> if (o.optString("type") == "user_message") ws0.set(ws) }
        val c = canale()
        val v = vps(c, primoSegnoMs = 5_000)
        val cat = CervelloCatena(CervelloRegole(), v)
        val vecchia = async { cat.capisci("cosa c'è in calendario domani", ctx) }
        while (ws0.get() == null) delay(20)
        val stop = cat.capisci("basta", ctx)
        assertEquals("Fermato: la richiesta alla VPS non va avanti.", stop.dire)
        val p = vecchia.await()
        assertTrue(p.annullato); assertEquals("", p.dire)
        // La VPS risponde alla frase vecchia DOPO: si scarta, e uno strumento della frase vecchia si rifiuta.
        copione = { ws, o -> if (o.optString("type") == "user_message") ws.send("""{"type":"assistant_message","text":"risposta nuova"}""") }
        ws0.get().send("""{"type":"tool_call","id":"vecchio","command":{"action":"apri_app","nome":"maps"}}""")
        assertTrue(prossimo("tool_result").getString("error").contains("annullato"))
        ws0.get().send("""{"type":"assistant_message","text":"risposta vecchia"}""")
        delay(200)
        val nuova = cat.capisci("cosa c'è in calendario dopodomani", ctx)
        assertEquals("risposta nuova", nuova.dire)
        assertTrue(eseguiti.isEmpty())
        assertEquals(1, upgrade.get()) // stesso collegamento, aperto una volta
    }

    @Test fun strumentoFuoriDaUnaFraseSiRifiuta() = runBlocking {
        copione = { ws, o -> if (o.optString("type") == "user_message") ws.send("""{"type":"assistant_message","text":"ok"}""") }
        val c = canale()
        vps(c).chiedi("ciao")
        socketPonte.first().send("""{"type":"tool_call","id":"sito-1","command":{"action":"read_screen"}}""")
        val tr = prossimo("tool_result")
        assertEquals("sito-1", tr.getString("id")); assertNotNull(tr.optString("error"))
        assertTrue(eseguiti.isEmpty())
        assertTrue(c.collegato)
        c.chiudi()
        assertFalse(c.collegato)
    }

    @Test fun leFrasiDiBossCheLeRegoleNonFannoVannoAllaVps() {
        val conMarco = ctx.copy(contatti = listOf(com.jarvis.telefono.mani.CercaContatti.Contatto("Contatto Esempio", listOf("+390000000001"), emptyList())))
        val r = CervelloRegole()
        fun allaVps(f: String): Boolean { val p = r.interpreta(f, conMarco); return !p.capito || Complessita.complessa(f) || Complessita.pianoDebole(p) }
        for (f in listOf(
            "trovami un ristorante di pesce vicino a me e aprimi le indicazioni",
            "cerca su Google quanto costa un biglietto per Londra e dimmi il primo risultato",
            "cosa c'è in calendario domani", "apri la fotocamera e fai un autoscatto", "che tempo fa domani a Cagliari",
            "apri YouTube e cerca un video su come fare la pizza", "quanto manca a Natale", "metti una sveglia alle sette domani mattina",
            "leggimi l'ultima notifica", "mandami su WhatsApp la lista della spesa: latte, pane e uova",
            "apri le impostazioni del bluetooth e accendilo",
        )) assertTrue(f, allaVps(f))
        // Le frasi semplici restano alle regole (nessuna rete).
        for (f in listOf("apri WhatsApp", "scrivi a Marco che arrivo tardi", "che ore sono", "cerca su Google pizzerie a Cagliari", "torna indietro"))
            assertFalse(f, allaVps(f))
    }

    // ─── 0.4.2: segni di vita, risposte tardive, nota personale ──────────────────
    @Test fun inLavoroTieneVivaLaFraseOltreIlPrimoSegno() = runBlocking {
        // Il rapporto della posta: la VPS lavora 1,2 s con i suoi strumenti, il primo segno vale 400 ms.
        copione = { ws, o ->
            if (o.optString("type") == "user_message") Thread {
                repeat(4) { ws.send("""{"type":"in_lavoro","testo":"comando sulla VPS","secondi":$it}"""); Thread.sleep(300) }
                ws.send("""{"type":"assistant_message","text":"Hai 7 mail, una urgente."}""")
            }.start()
        }
        val lavori = CopyOnWriteArrayList<String>()
        val c = canale()
        val e = c.conversa("fammi il rapporto della posta", primoSegnoMs = 400, silenzioMs = 600, suLavoro = { lavori += it }, esegui = maniFinte)
        assertTrue(e.toString(), e is CanaleMani.Esito.Risposta)
        assertEquals("Hai 7 mail, una urgente.", (e as CanaleMani.Esito.Risposta).testo)
        assertTrue(lavori.isNotEmpty())
        assertEquals("comando sulla VPS", lavori.first())
    }

    @Test fun rispostaDiUnaFraseScadutaArrivaComeTardivaNonSiButta() = runBlocking {
        val tardive = LinkedBlockingQueue<String>()
        copione = { ws, o ->
            if (o.optString("type") == "user_message") Thread { Thread.sleep(900); ws.send("""{"type":"assistant_message","text":"Ecco il rapporto."}""") }.start()
        }
        val c = CanaleMani(url = { url() }, token = { TOKEN }, riposoMs = 60_000, suRispostaTardiva = { t, _ -> tardive.put(t) }).also { canali += it }
        val e = c.conversa("rapporto della posta", primoSegnoMs = 300, silenzioMs = 300, esegui = maniFinte)
        assertTrue(e is CanaleMani.Esito.Fallito)
        assertEquals("Ecco il rapporto.", tardive.poll(5, TimeUnit.SECONDS))
    }

    @Test fun rispostaTardivaDellaVpsNonDiventaLaRispostaDellaFraseNuova() = runBlocking {
        val tardive = LinkedBlockingQueue<String>()
        copione = { ws, o ->
            when (o.optString("type")) {
                "auth" -> ws.send("""{"type":"assistant_message","text":"Risposta di prima.","tardiva":true}""")
                "user_message" -> Thread { Thread.sleep(200); ws.send("""{"type":"assistant_message","text":"Risposta nuova."}""") }.start()
            }
        }
        val c = CanaleMani(url = { url() }, token = { TOKEN }, riposoMs = 60_000, suRispostaTardiva = { t, _ -> tardive.put(t) }).also { canali += it }
        val e = c.conversa("che ore sono a Tokyo", primoSegnoMs = 3_000, silenzioMs = 3_000, esegui = maniFinte)
        assertEquals("Risposta nuova.", (e as CanaleMani.Esito.Risposta).testo)
        assertEquals("Risposta di prima.", tardive.poll(5, TimeUnit.SECONDS))
    }

    @Test fun protocolloInLavoroETardiva() {
        val m = ProtocolloMani.leggi("""{"type":"in_lavoro","testo":"cerco sul web","secondi":20}""")
        assertEquals(ProtocolloMani.Messaggio.InLavoro("cerco sul web", 20), m)
        val r = ProtocolloMani.leggi("""{"type":"assistant_message","text":"x","tardiva":true}""") as ProtocolloMani.Messaggio.Risposta
        assertTrue(r.tardiva)
        assertFalse((ProtocolloMani.leggi("""{"type":"assistant_message","text":"x"}""") as ProtocolloMani.Messaggio.Risposta).tardiva)
    }

    @Test fun notaPersonaleSoloQuandoBossParlaDiSe() {
        val nota = "Boss stesso su WhatsApp è il numero +390000000001"
        assertTrue(CervelloVps.conNota("mandami su WhatsApp la lista della spesa", nota).contains("[Nota del telefono: $nota]"))
        assertTrue(CervelloVps.conNota("scrivi una mail a me con il meteo", nota).contains("Nota del telefono"))
        assertEquals("apri Spotify", CervelloVps.conNota("apri Spotify", nota))
        assertEquals("mandami il meteo", CervelloVps.conNota("mandami il meteo", ""))
    }

    // ─── 0.6.0: un solo punto decide chi risponde (Arbitro) ────────────────────
    @Test fun laRispostaDelSitoNonDiventaQuellaDiJBoss() = runBlocking {
        copione = { ws, o ->
            if (o.optString("type") == "user_message") {
                ws.send("""{"type":"assistant_message","text":"Risposta alla chat del sito","origine":"sito"}""")
                ws.send("""{"type":"assistant_message","text":"Risposta vecchia","origine":"telefono","rid":"jb-vecchio-1"}""")
                ws.send(JSONObject().put("type", "assistant_message").put("text", "Fatto per JBoss").put("origine", "telefono").put("rid", o.optString("rid")).toString())
            }
        }
        val p = CervelloCatena(CervelloRegole(), vps(canale())).capisci("cosa c'è in calendario domani", ctx)
        val auth = prossimo("auth")
        assertEquals("jboss", auth.getString("app"))
        val um = prossimo("user_message")
        assertTrue(um.getString("rid"), um.getString("rid").startsWith("jb-"))
        assertEquals("Fatto per JBoss", p.dire)
    }

    @Test fun avvisoFuoriTurnoVaNellaCodaNonInVoce() = runBlocking {
        val avvisi = CopyOnWriteArrayList<String>()
        val c = CanaleMani(url = { url() }, token = { TOKEN }, riposoMs = 60_000, suNotifica = { avvisi += it }).also { canali += it }
        copione = { ws, o ->
            if (o.optString("type") == "user_message") {
                ws.send("""{"type":"assistant_message","text":"Il report del Postino è pronto","origine":"sottofondo"}""")
                ws.send(JSONObject().put("type", "assistant_message").put("text", "Sono le dieci").put("origine", "telefono").put("rid", o.optString("rid")).toString())
            }
        }
        val p = CervelloCatena(CervelloRegole(), vps(c)).capisci("cosa c'è in calendario domani", ctx)
        assertEquals("Sono le dieci", p.dire)
        val fine = System.currentTimeMillis() + 2_000
        while (avvisi.isEmpty() && System.currentTimeMillis() < fine) Thread.sleep(20)
        assertEquals(listOf("Il report del Postino è pronto"), avvisi.toList())
    }
}

package com.jarvis.telefono.configura

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

/** L'abbinamento con codice monouso lato app (0.4.0): lettura del QR e dell'incolla, scambio sul WebSocket finto. */
class AbbinamentoTest {
    private val url = "wss://jarvis-agent.esempio.it/phone"
    private val server = MockWebServer()

    @After fun giu() = runCatching { server.shutdown() }.let { }

    @Test fun `QR del Mac - indirizzo e codice`() {
        val d = Abbinamento.leggi("jboss-vps:1?u=wss%3A%2F%2Fjarvis-agent.esempio.it%2Fphone&c=SVR767UE").getOrThrow()
        assertEquals(url, d.url)
        assertEquals("SVR767UE", d.codice)
        assertEquals("SVR7-67UE", d.codiceLeggibile)
        assertEquals("jarvis-agent.esempio.it", d.host)
    }

    @Test fun `incolla - testo libero dal terminale, codice col trattino e minuscolo`() {
        val d = Abbinamento.leggi("indirizzo: $url\n   codice:    svr7-67ue").getOrThrow()
        assertEquals(url, d.url); assertEquals("SVR767UE", d.codice)
    }

    @Test fun `incolla - due campi`() {
        val d = Abbinamento.leggi(url, "ab cd 23 45").getOrThrow()
        assertEquals("ABCD2345", d.codice)
    }

    @Test fun `errori in italiano`() {
        assertTrue(Abbinamento.leggi("http://x.it/phone ABCD2345").exceptionOrNull()!!.message!!.contains("wss://"))
        assertTrue(Abbinamento.leggi(url, "ABC").exceptionOrNull()!!.message!!.contains("8 caratteri"))
        assertTrue(Abbinamento.leggi(url, "ABCD0O1I").exceptionOrNull()!!.message!!.contains("0, O, 1, I"))
        assertTrue(Abbinamento.leggi("jboss-vps:2?u=x&c=y").exceptionOrNull()!!.message!!.contains("aggiorna"))
        assertTrue(Abbinamento.leggi("").exceptionOrNull()!!.message!!.contains("wss://"))
    }

    @Test fun `ws in chiaro solo verso il telefono stesso`() {
        assertNull(Abbinamento.controllaUrl("ws://127.0.0.1:8794/phone"))
        assertTrue(Abbinamento.controllaUrl("ws://jarvis.esempio.it/phone") != null)
    }

    @Test fun `il messaggio porta solo il codice`() {
        val o = JSONObject(Abbinamento.messaggio(DatiAbbinamento(url, "ABCD2345")))
        assertEquals("abbina", o.getString("type")); assertEquals("ABCD2345", o.getString("codice"))
        assertEquals(2, o.length())
    }

    @Test fun `risposte della VPS`() {
        assertEquals(EsitoAbbinamento.Fatto("t".repeat(32)), Abbinamento.risposta("""{"type":"abbinato","token":"${"t".repeat(32)}"}"""))
        assertTrue(Abbinamento.risposta("""{"type":"abbinato","token":"corto"}""") is EsitoAbbinamento.NonFatto)
        assertEquals(EsitoAbbinamento.NonFatto("Codice sbagliato"), Abbinamento.risposta("""{"type":"abbina_errore","motivo":"Codice sbagliato"}"""))
        assertNull(Abbinamento.risposta("""{"type":"connected"}"""))
        assertTrue(Abbinamento.perChiusura(4001).contains("aggiornato"))
    }

    private fun vps(rispondi: (WebSocket, String) -> Unit): String {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = rispondi(webSocket, text)
            override fun onOpen(webSocket: WebSocket, response: Response) {}
        }))
        server.start()
        return server.url("/phone").toString().replace("http://", "ws://")
    }

    @Test fun `scambio vero - codice giusto, arriva il token`() {
        val tok = "T".repeat(40)
        var ricevuto = ""
        val u = vps { ws, t -> ricevuto = t; ws.send("""{"type":"abbinato","token":"$tok","versione":1}"""); ws.close(1000, "abbinato") }
        val e = ClienteAbbinamento(attesaMs = 5000).scambia(DatiAbbinamento(u, "ABCD2345"))
        assertEquals(EsitoAbbinamento.Fatto(tok), e)
        assertEquals("ABCD2345", JSONObject(ricevuto).getString("codice"))
    }

    @Test fun `scambio vero - codice sbagliato, il motivo della VPS`() {
        val u = vps { ws, _ -> ws.send("""{"type":"abbina_errore","motivo":"Codice sbagliato o scaduto: generane uno nuovo sul Mac."}"""); ws.close(4003, "no") }
        val e = ClienteAbbinamento(attesaMs = 5000).scambia(DatiAbbinamento(u, "ABCD2345"))
        assertTrue(e is EsitoAbbinamento.NonFatto && e.messaggio.contains("scaduto"))
    }

    @Test fun `ponte vecchio - chiude con 4001 senza rispondere`() {
        val u = vps { ws, _ -> ws.close(4001, "token non valido") }
        val e = ClienteAbbinamento(attesaMs = 5000).scambia(DatiAbbinamento(u, "ABCD2345"))
        assertTrue(e is EsitoAbbinamento.NonFatto && (e as EsitoAbbinamento.NonFatto).messaggio.contains("aggiornato"))
    }

    @Test fun `VPS spenta - messaggio di rete, nessun token`() {
        val e = ClienteAbbinamento(attesaMs = 5000).scambia(DatiAbbinamento("ws://127.0.0.1:1/phone", "ABCD2345"))
        assertTrue(e is EsitoAbbinamento.NonFatto)
        assertFalse((e as EsitoAbbinamento.NonFatto).messaggio.contains("ABCD2345"))
    }
}

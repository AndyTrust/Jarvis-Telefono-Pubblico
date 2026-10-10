package com.jarvis.telefono.sveglia

import com.jarvis.telefono.vps.CanaleMani
import com.jarvis.telefono.vps.ProtocolloMani
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * JBoss 0.6.1: la sveglia FCM della VPS. Regole pure (scadenza, token, finestra di 3 minuti) e il canale delle mani
 * con un finto ponte (MockWebServer): token mandato al collegamento, strumenti della VPS eseguiti solo nella finestra.
 */
class SvegliaTest {

    // ─── regole ─────────────────────────────────────────────────────────────────
    @Test fun soloIlTipoSvegliaSveglia() {
        assertEquals(RegoleSveglia.Decisione.SVEGLIA, RegoleSveglia.decidi(mapOf("tipo" to "sveglia", "ts" to "1000"), 2_000))
        assertEquals(RegoleSveglia.Decisione.SVEGLIA, RegoleSveglia.decidi(mapOf("tipo" to "sveglia"), 2_000))
        assertEquals(RegoleSveglia.Decisione.NON_E_SVEGLIA, RegoleSveglia.decidi(mapOf("tipo" to "promo"), 2_000))
        assertEquals(RegoleSveglia.Decisione.NON_E_SVEGLIA, RegoleSveglia.decidi(emptyMap(), 2_000))
    }

    @Test fun unaSvegliaVecchiaDiPiuDiDueMinutiSiIgnora() {
        val ts = 1_000_000L
        assertEquals(RegoleSveglia.Decisione.SVEGLIA, RegoleSveglia.decidi(mapOf("tipo" to "sveglia", "ts" to "$ts"), ts + 120_000))
        assertEquals(RegoleSveglia.Decisione.SCADUTA, RegoleSveglia.decidi(mapOf("tipo" to "sveglia", "ts" to "$ts"), ts + 120_001))
        // orologio della VPS avanti: non si scarta
        assertEquals(RegoleSveglia.Decisione.SVEGLIA, RegoleSveglia.decidi(mapOf("tipo" to "sveglia", "ts" to "${ts + 60_000}"), ts))
    }

    @Test fun tokenFcmValido() {
        assertTrue(RegoleSveglia.tokenValido("f".repeat(40) + ":APA91b-" + "x".repeat(100)))
        assertFalse(RegoleSveglia.tokenValido(null))
        assertFalse(RegoleSveglia.tokenValido("corto"))
        assertFalse(RegoleSveglia.tokenValido("a".repeat(40) + " b"))
        assertFalse(RegoleSveglia.tokenValido("a".repeat(40) + "\""))
    }

    @Test fun laFinestraDuraTreMinutiESiAllungaSoloSeAperta() {
        assertEquals(180_000L, RegoleSveglia.FINESTRA_MS)
        val f = RegoleSveglia.Finestra()
        assertFalse(f.aperta(0))
        f.apri(1_000)
        assertTrue(f.aperta(1_000 + 179_999))
        assertFalse(f.aperta(1_000 + 180_000))
        f.allunga(200_000) // già chiusa: resta chiusa
        assertFalse(f.aperta(200_001))
        f.apri(300_000); f.allunga(400_000)
        assertTrue(f.aperta(400_000 + 179_999))
        f.chiudi(); assertFalse(f.aperta(400_001))
    }

    @Test fun protocolloDelToken() {
        val o = JSONObject(ProtocolloMani.tokenFcm("tok-" + "x".repeat(40)))
        assertEquals("fcm_token", o.getString("type"))
        assertEquals("jboss", o.getString("app"))
        assertTrue(o.getString("token").startsWith("tok-"))
    }

    @Test fun ilServizioFcmStaNelManifestNonEsportato() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val m = File(main, "AndroidManifest.xml").readText()
        val blocco = Regex("<service[^>]*?\\.sveglia\\.SvegliaFcm\"[^>]*>.*?</service>", RegexOption.DOT_MATCHES_ALL).find(m)?.value
        assertNotNull("SvegliaFcm manca nel Manifest", blocco)
        assertTrue(blocco!!.contains("android:exported=\"false\""))
        assertTrue(blocco.contains("com.google.firebase.MESSAGING_EVENT"))
        // nessun wakelock lungo né ciclo nel codice della sveglia
        val codice = File(main, "java/com/jarvis/telefono/sveglia").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(codice.contains("newWakeLock"))
        assertFalse(codice.contains("while (true)"))
    }

    // ─── canale con il finto ponte ──────────────────────────────────────────────
    private val server = MockWebServer()
    private val ricevuti = LinkedBlockingQueue<JSONObject>()
    private val socketPonte = CopyOnWriteArrayList<WebSocket>()
    @Volatile private var copione: (WebSocket, JSONObject) -> Unit = { _, _ -> }
    private val TOKEN = "token-di-prova-lungo-0123456789"
    private val FCM = "fcm-" + "a".repeat(60)

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/health" -> MockResponse().setBody("""{"ok":true,"phoneConnected":false}""")
                "/phone" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { socketPonte += webSocket }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val o = JSONObject(text)
                        if (o.optString("type") == "auth") {
                            webSocket.send("""{"type":"connected"}""")
                            // come jarvis-agent: lo strumento parte appena il telefono è autenticato
                            copione(webSocket, o)
                        }
                        ricevuti.put(o)
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    private val canali = CopyOnWriteArrayList<CanaleMani>()
    private val orologio = AtomicLong(1_000_000)
    private val eseguiti = CopyOnWriteArrayList<JSONObject>()

    private fun canale(libero: Boolean = true, fcm: String? = FCM) = CanaleMani(
        url = { server.url("/phone").toString().replace("http://", "ws://") },
        token = { TOKEN },
        riposoMs = 180_000,
        tokenFcm = { fcm },
        strumentoLibero = if (!libero) null else { c -> eseguiti += c; JSONObject().put("result", JSONObject().put("aperto", true)) },
        adesso = { orologio.get() },
    ).also { canali += it }

    private fun prossimo(tipo: String, secondi: Long = 5): JSONObject? {
        val fine = System.currentTimeMillis() + secondi * 1000
        while (System.currentTimeMillis() < fine) {
            val o = ricevuti.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (o.optString("type") == tipo) return o
        }
        return null
    }

    @After fun chiudi() {
        canali.forEach { it.chiudi() }
        socketPonte.forEach { runCatching { it.close(1001, null) } }
        Thread.sleep(100)
        runCatching { server.shutdown() }
    }

    @Test fun laSvegliaCollegaEMandaIlTokenFcm() = runBlocking {
        val c = canale()
        assertNull(c.apriPerSveglia())
        assertTrue(c.collegato)
        assertTrue(c.svegliato)
        val t = prossimo("fcm_token")
        assertNotNull("il token FCM non è arrivato alla VPS", t)
        assertEquals(FCM, t!!.getString("token"))
    }

    @Test fun senzaTokenFcmNonMandaNiente() = runBlocking {
        val c = canale(fcm = null)
        assertNull(c.apriPerSveglia())
        assertNull(prossimo("fcm_token", 1))
    }

    @Test fun loStrumentoChiestoSubitoDopoLaSvegliaSiEsegue() = runBlocking {
        copione = { ws, _ -> ws.send("""{"type":"tool_call","id":"s1","command":{"action":"read_screen"}}""") }
        val c = canale()
        assertNull(c.apriPerSveglia())
        val tr = prossimo("tool_result")
        assertNotNull(tr)
        assertEquals("s1", tr!!.getString("id"))
        assertFalse(tr.toString(), tr.has("error"))
        assertEquals("read_screen", eseguiti.single().optString("action"))
    }

    @Test fun dopoTreMinutiGliStrumentiFuoriFraseSiRifiutanoDiNuovo() = runBlocking {
        val c = canale()
        assertNull(c.apriPerSveglia())
        orologio.addAndGet(180_001)
        assertFalse(c.svegliato)
        socketPonte.last().send("""{"type":"tool_call","id":"s2","command":{"action":"read_screen"}}""")
        val tr = prossimo("tool_result")
        assertNotNull(tr)
        assertTrue(tr!!.getString("error").contains("annullato"))
        assertTrue(eseguiti.isEmpty())
    }

    @Test fun senzaSvegliaUnoStrumentoFuoriFraseSiRifiutaComePrima() = runBlocking {
        // canale aperto da una frase normale: la finestra della sveglia è chiusa
        copione = { _, _ -> }
        val c = canale()
        assertNull(c.apriPerSveglia())
        c.chiudi()
        assertFalse(c.svegliato)
        val c2 = canale(libero = false)
        assertNull(c2.apriPerSveglia())
        socketPonte.last().send("""{"type":"tool_call","id":"s3","command":{"action":"read_screen"}}""")
        val tr = prossimo("tool_result")
        assertTrue(tr!!.getString("error").contains("annullato"))
        assertTrue(eseguiti.isEmpty())
    }

    @Test fun dueSveglieInsiemeUnSoloCollegamento() = runBlocking {
        val c = canale()
        val a = async(Dispatchers.IO) { c.apriPerSveglia() }
        val b = async(Dispatchers.IO) { c.apriPerSveglia() }
        assertNull(a.await()); assertNull(b.await())
        Thread.sleep(200)
        assertEquals(1, socketPonte.size)
    }
}

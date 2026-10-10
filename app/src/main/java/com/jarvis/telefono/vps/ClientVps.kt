package com.jarvis.telefono.vps

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Attese crescenti fra un tentativo di collegamento e l'altro (2 s, 5 s, 15 s, 30 s, 60 s, poi ogni
 * 2 minuti). Mai un ciclo stretto: la 0.7.x dell'app vecchia ne aveva uno infinito che scaricava la batteria.
 */
class Riconnessione(private val attese: LongArray = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000, 120_000)) {
    private var tentativi = 0
    fun prossima(): Long = attese[minOf(tentativi, attese.size - 1)].also { tentativi++ }
    fun azzera() { tentativi = 0 }
    val fatti: Int get() = tentativi
}

/**
 * Il WebSocket verso il ponte della VPS, solo per il modulo VPS (ruolo «lavori»).
 *
 * Si collega solo se [deveStareAperto] (modulo acceso e qualcosa da fare: lavori aperti o una schermata
 * dei lavori aperta) e [haRete]. Se cade, riprova con [Riconnessione] finché serve; un token rifiutato
 * (4001) ferma i tentativi finché non cambia la configurazione. Il token non va mai nei log.
 * Ping ogni 30 s mentre è aperto (tiene vivo il collegamento attraverso i proxy e la rete mobile).
 */
class ClientVps(
    private val url: () -> String,
    private val token: () -> String,
    private val nucleo: NucleoVps,
    private val deveStareAperto: () -> Boolean,
    private val haRete: () -> Boolean,
    private val riconnessione: Riconnessione = Riconnessione(),
    pingSecondi: Long = 30,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /** 0.3.0: ogni messaggio della VPS così com'è (la chat Postino a numeri lo legge da qui). */
    private val suMessaggio: (String) -> Unit = {},
) : NucleoVps.Canale {

    private val http = OkHttpClient.Builder()
        .pingInterval(pingSecondi, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val orologio = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "jarvis-vps").apply { isDaemon = true } }

    @Volatile private var socket: WebSocket? = null
    @Volatile var autenticato = false
        private set
    @Volatile var tokenRifiutato = false
        private set
    @Volatile var ultimoErrore: String = ""
        private set
    private var prossimoTentativo: ScheduledFuture<*>? = null

    /** Byte mandati e ricevuti dal modulo (misura indicativa del traffico). */
    @Volatile var byteMandati = 0L
        private set
    @Volatile var byteRicevuti = 0L
        private set

    override fun manda(testo: String): Boolean {
        val s = socket ?: return false
        if (!autenticato) return false
        val ok = s.send(testo)
        if (ok) byteMandati += testo.length
        return ok
    }

    override fun collega() {
        orologio.execute { apri() }
    }

    /** La rete è tornata o la configurazione è cambiata: si riparte da capo, subito. */
    fun riprovaSubito() {
        orologio.execute {
            tokenRifiutato = false
            riconnessione.azzera()
            prossimoTentativo?.cancel(false)
            apri()
        }
    }

    /** Chiude se non serve più (nessun lavoro aperto, nessuna schermata aperta). */
    fun chiudiSeInutile() {
        orologio.execute {
            if (!deveStareAperto()) {
                prossimoTentativo?.cancel(false)
                socket?.let { log("chiudo: niente da seguire"); it.close(1000, "a riposo") }
                socket = null
                if (autenticato) { autenticato = false; nucleo.scollegato() }
            }
        }
    }

    fun spegni() {
        orologio.execute {
            prossimoTentativo?.cancel(false)
            socket?.close(1000, "modulo spento")
            socket = null
            if (autenticato) { autenticato = false; nucleo.scollegato() }
        }
    }

    val aperto: Boolean get() = socket != null

    private fun apri() {
        if (socket != null || tokenRifiutato) return
        if (!deveStareAperto()) return
        if (!haRete()) { log("niente rete: aspetto che torni"); return }
        val u = url()
        val t = token()
        if (u.isBlank() || t.isBlank()) { ultimoErrore = "manca indirizzo o token del modulo VPS"; log(ultimoErrore); return }
        val req = runCatching { Request.Builder().url(u).build() }.getOrElse { ultimoErrore = "indirizzo non valido"; log(ultimoErrore); return }
        autenticato = false
        log("collego (tentativo ${riconnessione.fatti + 1})")
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== socket) return
                webSocket.send(ProtocolloVps.auth(t))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) return
                byteRicevuti += text.length
                val m = ProtocolloVps.leggi(text) ?: return
                if (m is MessaggioVps.Collegato) {
                    autenticato = true
                    ultimoErrore = ""
                    riconnessione.azzera()
                    log("collegato (protocollo ${m.versione})")
                    nucleo.collegatoOra()
                    return
                }
                nucleo.ricevi(m)
                runCatching { suMessaggio(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                orologio.execute { perso(webSocket, "chiuso ($code)", code) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                orologio.execute { perso(webSocket, "errore di rete (${t.javaClass.simpleName})", response?.code ?: 0) }
            }
        })
    }

    private fun perso(ws: WebSocket, motivo: String, codice: Int) {
        if (ws !== socket) return
        socket = null
        val eraAutenticato = autenticato
        autenticato = false
        ultimoErrore = motivo
        log("collegamento perso: $motivo")
        if (eraAutenticato) nucleo.scollegato()
        if (codice == 4001) {
            tokenRifiutato = true
            ultimoErrore = "token rifiutato dalla VPS"
            log(ultimoErrore)
            return
        }
        if (!deveStareAperto()) return
        val attesa = riconnessione.prossima()
        log("riprovo fra ${attesa / 1000} s")
        prossimoTentativo = orologio.schedule({ apri() }, attesa, TimeUnit.MILLISECONDS)
    }

    companion object {
        const val TAG = "JarvisVps"
    }
}

package com.jarvis.telefono.configura

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Indirizzo del ponte e codice monouso letti dal QR (o incollati). Nessun token: quello lo dà la VPS. */
data class DatiAbbinamento(val url: String, val codice: String) {
    /** «ABCD-2345», come lo stampa il Mac. */
    val codiceLeggibile: String get() = codice.take(4) + "-" + codice.drop(4)
    val host: String get() = url.removePrefix("wss://").removePrefix("ws://").substringBefore('/')
}

sealed class EsitoAbbinamento {
    data class Fatto(val token: String) : EsitoAbbinamento()
    /** [messaggio] in italiano, per lo schermo. Mai il token o il codice dentro. */
    data class NonFatto(val messaggio: String) : EsitoAbbinamento()
}

/**
 * L'abbinamento con codice monouso (JBoss 0.4.0, 2026-10-07). Il Mac (scripts/genera-qr-vps.py) chiede un codice alla
 * VPS e mostra un QR «jboss-vps:1?u=<wss://…/phone>&c=<8 caratteri>». L'app legge indirizzo e codice ([leggi]),
 * apre il WebSocket del ponte e manda {type:"abbina", codice} invece di «auth»: la VPS risponde con il token del
 * ponte e chiude ([ClienteAbbinamento]). Il token va dritto nella Cassaforte. Lato VPS: jarvis-agent/server/abbina.js.
 * Funzioni pure qui sopra, provate in AbbinamentoTest.
 */
object Abbinamento {
    const val SCHEMA = "jboss-vps:1"
    const val ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun normalizzaCodice(s: String): String = s.uppercase().replace(Regex("[\\s-]"), "")

    /** null = va bene; altrimenti il motivo in parole semplici. */
    fun controllaCodice(c: String): String? = when {
        c.isEmpty() -> "Scrivi il codice che vedi sotto il QR sul Mac."
        c.length != 8 -> "Il codice ha 8 caratteri (lettere e numeri): ne hai scritti ${c.length}."
        c.any { it !in ALFABETO } -> "Il codice contiene un carattere che non può esserci (niente 0, O, 1, I): ricontrolla."
        else -> null
    }

    /** L'app parla con la VPS solo cifrato; in chiaro solo verso il telefono stesso (prove). */
    fun controllaUrl(u: String): String? = when {
        u.isBlank() -> "Manca l'indirizzo della VPS (comincia con wss://)."
        Regex("^ws://(127\\.0\\.0\\.1|localhost)(:\\d+)?/").containsMatchIn(u) -> null
        !u.startsWith("wss://") -> "L'indirizzo deve cominciare con wss:// (collegamento cifrato)."
        !Regex("^wss://[A-Za-z0-9.-]+(:\\d+)?/\\S*$").matches(u) -> "L'indirizzo non sembra giusto: wss://nome-del-server/phone"
        else -> null
    }

    /**
     * Dal testo del QR o da quello incollato: «jboss-vps:1?u=…&c=…», oppure un testo libero con dentro un indirizzo
     * wss://… e un codice di 8 caratteri (anche «ABCD-2345»). [codiceAParte] = il secondo campo della finestra «Incolla».
     */
    fun leggi(testo: String, codiceAParte: String = ""): Result<DatiAbbinamento> {
        val t = testo.trim()
        var url = ""
        var codice = ""
        if (t.startsWith(SCHEMA)) {
            val q = t.substringAfter('?', "")
            for (p in q.split('&')) {
                val k = p.substringBefore('=')
                val v = runCatching { URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                if (k == "u") url = v
                if (k == "c") codice = v
            }
        } else if (t.startsWith("jboss-vps:")) {
            return Result.failure(IllegalArgumentException("Questo QR è di una versione più nuova: aggiorna JBoss."))
        } else {
            url = Regex("wss?://\\S+").find(t)?.value?.trimEnd('.', ',', ';').orEmpty()
            val resto = t.replace(url, " ")
            codice = Regex("(?i)\\b([A-Z0-9]{4})[-\\s]?([A-Z0-9]{4})\\b").findAll(resto)
                .map { it.groupValues[1] + it.groupValues[2] }.lastOrNull().orEmpty()
        }
        if (codiceAParte.isNotBlank()) codice = codiceAParte
        codice = normalizzaCodice(codice)
        controllaUrl(url)?.let { return Result.failure(IllegalArgumentException(it)) }
        controllaCodice(codice)?.let { return Result.failure(IllegalArgumentException(it)) }
        return Result.success(DatiAbbinamento(url, codice))
    }

    fun messaggio(d: DatiAbbinamento): String = JSONObject().put("type", "abbina").put("codice", d.codice).toString()

    /** La risposta della VPS. null = un messaggio che non riguarda l'abbinamento. */
    fun risposta(testo: String): EsitoAbbinamento? {
        val o = runCatching { JSONObject(testo) }.getOrNull() ?: return null
        return when (o.optString("type")) {
            "abbinato" -> {
                val tok = o.optString("token")
                if (tok.length >= 16) EsitoAbbinamento.Fatto(tok) else EsitoAbbinamento.NonFatto("La VPS ha risposto senza un token valido.")
            }
            "abbina_errore" -> EsitoAbbinamento.NonFatto(o.optString("motivo").ifBlank { "Codice rifiutato dalla VPS." })
            else -> null
        }
    }

    /** Chiusura senza risposta: dal codice WebSocket a una frase. */
    fun perChiusura(codice: Int): String = when (codice) {
        4001 -> "La VPS non conosce ancora l'abbinamento con codice: va aggiornato il ponte (jarvis-agent)."
        4003 -> "Codice rifiutato dalla VPS."
        else -> "La VPS ha chiuso il collegamento senza rispondere."
    }
}

/** Lo scambio vero sul WebSocket. Bloccante: si chiama da un thread di lavoro, mai dal principale. */
class ClienteAbbinamento(
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build(),
    private val attesaMs: Long = 20_000,
) {
    fun scambia(d: DatiAbbinamento): EsitoAbbinamento {
        Abbinamento.controllaUrl(d.url)?.let { return EsitoAbbinamento.NonFatto(it) }
        val req = runCatching { Request.Builder().url(d.url).build() }.getOrElse { return EsitoAbbinamento.NonFatto("Indirizzo non valido.") }
        val fine = CountDownLatch(1)
        val esito = AtomicReference<EsitoAbbinamento?>(null)
        fun chiudi(e: EsitoAbbinamento) { if (esito.compareAndSet(null, e)) fine.countDown() }
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(Abbinamento.messaggio(d)) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                Abbinamento.risposta(text)?.let { chiudi(it); webSocket.close(1000, null) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                chiudi(EsitoAbbinamento.NonFatto(Abbinamento.perChiusura(code)))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { chiudi(EsitoAbbinamento.NonFatto(Abbinamento.perChiusura(code))) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                chiudi(EsitoAbbinamento.NonFatto("Non raggiungo la VPS: controlla la rete e l'indirizzo (${d.host})."))
            }
        })
        if (!fine.await(attesaMs, TimeUnit.MILLISECONDS)) {
            ws.cancel()
            return EsitoAbbinamento.NonFatto("La VPS non ha risposto in ${attesaMs / 1000} secondi.")
        }
        return esito.get() ?: EsitoAbbinamento.NonFatto("Abbinamento non riuscito.")
    }
}

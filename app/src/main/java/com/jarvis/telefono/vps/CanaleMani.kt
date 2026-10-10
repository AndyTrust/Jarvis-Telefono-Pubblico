package com.jarvis.telefono.vps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Il canale «mani» verso il cervello della VPS (JBoss 0.3.4, 2026-10-07). Kotlin + OkHttp, niente Android:
 * si prova sulla JVM con MockWebServer (CanaleManiTest).
 *
 * Batteria: si apre SOLO quando una frase va alla VPS ([conversa]); resta aperto [riposoMs] (3 minuti) per la
 * frase dopo, con un ping ogni 25 s; poi si chiude da solo. Se cade a riposo non si riapre da solo: si riapre
 * alla frase dopo. Nessun ciclo di riconnessione, nessun lavoro a schermo spento.
 *
 * Un solo telefono sul ruolo mani: prima di collegarsi legge /health; se la VPS ha già un telefono collegato
 * (l'app vecchia com.jarvis.app) non si collega e lo dice. Se un altro telefono prende il posto (chiusura 4000)
 * lo dice alla frase dopo, senza riprovare in automatico (niente braccio di ferro fra le due app).
 *
 * Una conversazione alla volta (la VPS ne fa una alla volta, in fila): `user_message` → `tool_call`* →
 * `assistant_message`. Una conversazione abbandonata (annullata, scaduta) lascia un «debito»: la sua risposta e i
 * suoi strumenti, se arrivano dopo, si scartano (gli strumenti con un errore chiaro alla VPS).
 * Il token non va mai nei log.
 *
 * 0.4.2 (2026-10-08, Boss: «manca il collegamento con Claude», «il rapporto della posta non torna»): la VPS manda
 * `in_lavoro` mentre Claude usa i suoi strumenti (Bash, agenti, web): ogni segno tiene viva la frase (prima dopo 15 s
 * senza strumenti del telefono la frase si chiudeva e la risposta arrivata dopo si buttava). Una frase SCADUTA non è
 * più un debito: la sua risposta, se arriva, va a [suRispostaTardiva] (bolla, voce, cronologia) e il collegamento
 * resta aperto fino a [attesaTardivaMs] per riceverla. Anche le risposte `tardiva` (tenute da parte dalla VPS mentre
 * il telefono era scollegato) vanno lì. Solo le frasi ANNULLATE da Boss restano debiti da scartare.
 *
 * 0.6.1 (2026-10-08, sveglia FCM): la VPS può svegliare il telefono ([apriPerSveglia], da sveglia/SvegliaFcm.kt). Il
 * canale si apre per [riposoMs] (3 minuti) e in quella finestra gli strumenti chiesti dalla VPS fuori da una frase di
 * Boss si eseguono con [strumentoLibero]; fuori dalla finestra si rifiutano come prima. A ogni collegamento il telefono
 * manda il suo token FCM ([tokenFcm], `{type:"fcm_token"}`): è così che la VPS sa chi svegliare.
 */
class CanaleMani(
    private val url: () -> String,
    private val token: () -> String,
    private val riposoMs: Long = 180_000L,
    pingSecondi: Long = 25,
    private val saluteTimeoutMs: Long = 4_000L,
    private val log: (String) -> Unit = {},
    /** Boss ha scritto nel sito mentre una bozza aspetta: la frase va al cancello d'invio del telefono. */
    private val suRispostaConferma: (String) -> Unit = {},
    /** 0.4.2: la risposta di una frase già chiusa sul telefono (scaduta, o tenuta da parte dalla VPS). */
    private val suRispostaTardiva: (testo: String, errore: Boolean) -> Unit = { _, _ -> },
    /** 0.4.2: quanto resta aperto il collegamento dopo una frase scaduta, per riceverne la risposta. */
    private val attesaTardivaMs: Long = 600_000L,
    /** 0.5.0: pausa prima del secondo tentativo di collegamento (0 = nessun secondo tentativo). */
    private val ripresaMs: Long = 1_500L,
    /** 0.6.0: un avviso di Jarvis fuori turno (origine «sottofondo»): va nella coda unica delle notifiche, senza voce. */
    private val suNotifica: (testo: String) -> Unit = {},
    /** 0.6.1: il token FCM del telefono, mandato alla VPS a ogni collegamento (null = Firebase spento). */
    private val tokenFcm: () -> String? = { null },
    /** 0.6.1: esegue uno strumento chiesto dalla VPS fuori da una frase, solo nella finestra della sveglia. */
    private val strumentoLibero: (suspend (JSONObject) -> JSONObject?)? = null,
    /** 0.6.1: orologio della finestra (le prove lo cambiano). */
    private val adesso: () -> Long = { System.currentTimeMillis() },
) {
    sealed class Esito {
        /** La VPS ha risposto. [strumenti]: le azioni eseguite dalle mani, in ordine. */
        data class Risposta(
            val testo: String,
            val errore: Boolean,
            val strumenti: List<JSONObject>,
            val esitiStrumenti: List<Boolean>,
            val primoStrumentoMs: Long,
            val rispostaMs: Long,
            val collegamentoMs: Long,
        ) : Esito()

        /** Non è arrivata una risposta. [strumenti] dice se qualcosa è già stato fatto sul telefono. */
        data class Fallito(val motivo: String, val tipo: Tipo, val strumenti: List<JSONObject>) : Esito()

        /** Boss ha cambiato idea: niente da dire. */
        data class Annullato(val strumenti: List<JSONObject>) : Esito()
    }

    enum class Tipo { CONFIGURAZIONE, OCCUPATO, TOKEN, RETE, SCADUTO, PERSO }

    private val http = OkHttpClient.Builder()
        .pingInterval(pingSecondi, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val httpSalute = http.newBuilder().pingInterval(0, TimeUnit.SECONDS)
        .callTimeout(saluteTimeoutMs, TimeUnit.MILLISECONDS).build()
    private val orologio = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "jboss-mani").apply { isDaemon = true } }

    private val turno = Mutex()
    private val lock = Any()
    /** 0.6.1: un collegamento alla volta (la frase e la sveglia possono chiederlo insieme). */
    private val collegamento = Mutex()
    /** 0.6.1: la finestra aperta dalla sveglia della VPS. */
    private val finestra = com.jarvis.telefono.sveglia.RegoleSveglia.Finestra(riposoMs)
    private val lavoroLibero = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    // Stato del collegamento (sotto [lock]).
    private var socket: WebSocket? = null
    private var autenticato = false
    private var attesaCollegamento: CompletableDeferred<String?>? = null
    private var chiusuraRiposo: ScheduledFuture<*>? = null

    /** Messaggi della conversazione in corso (null = nessuna). */
    private var inCorso: Channel<Evento>? = null
    private var debiti = 0
    /** 0.4.2: frasi scadute sul telefono la cui risposta può ancora arrivare. */
    private var tardive = 0
    /** 0.6.0: il rid della frase in corso e quelli delle frasi annullate (ultimi 20, restano anche a collegamento chiuso). */
    private var ridInCorso: String? = null
    private val annullati = LinkedHashSet<String>()
    private var contatoreRid = 0

    @Volatile var ultimoErrore: String = ""
        private set
    @Volatile var sostituito = false
        private set
    @Volatile var tokenRifiutato = false
        private set

    private sealed class Evento {
        data class Msg(val m: ProtocolloMani.Messaggio) : Evento()
        data class Perso(val motivo: String) : Evento()
        object Stop : Evento()
    }

    val collegato: Boolean get() = synchronized(lock) { socket != null && autenticato }
    val occupato: Boolean get() = synchronized(lock) { inCorso != null }
    /** 0.6.1: la finestra della sveglia è aperta (per lo stato e le prove). */
    val svegliato: Boolean get() = finestra.aperta(adesso())

    /**
     * 0.6.1: la VPS ha svegliato il telefono. Apre il canale (un secondo tentativo se la rete non è ancora pronta) e lo
     * tiene aperto [riposoMs], poi riposo. null = collegato; altrimenti il motivo. Non aspetta una frase in corso.
     */
    suspend fun apriPerSveglia(): Esito.Fallito? {
        // La finestra si apre PRIMA del collegamento: la VPS manda lo strumento appena il telefono si autentica.
        finestra.apri(adesso())
        var errore = withContext(Dispatchers.IO) { collegamento.withLock { collega() } }
        if (errore != null && errore.tipo == Tipo.RETE && ripresaMs > 0) {
            log("sveglia: collegamento non riuscito al primo tentativo, riprovo fra ${ripresaMs} ms")
            kotlinx.coroutines.delay(ripresaMs)
            errore = withContext(Dispatchers.IO) { collegamento.withLock { collega() } }
        }
        if (errore != null) {
            finestra.chiudi()
            log("sveglia: non collegato (${errore.tipo})")
            return errore
        }
        synchronized(lock) { if (inCorso == null) programmaRiposo() }
        log("sveglia: mani collegate per ${riposoMs / 1000} s")
        return null
    }

    /** La conversazione in corso si ferma (Boss ha detto «basta» o un'altra cosa). */
    fun annulla(): Boolean {
        val c = synchronized(lock) { inCorso } ?: return false
        c.trySend(Evento.Stop)
        return true
    }

    /** Chiude subito (modulo spento). */
    fun chiudi() {
        synchronized(lock) {
            chiusuraRiposo?.cancel(false)
            socket?.close(1000, "a riposo")
            socket = null
            autenticato = false
            debiti = 0
            tardive = 0
        }
        finestra.chiudi()
    }

    /**
     * Una frase al cervello della VPS. [esegui] fa l'azione sul telefono e restituisce il payload delle mani
     * (`{result}` o `{error}`, null = scaduta); [suStrumento] avvisa la bolla prima di ogni azione.
     * [primoSegnoMs]: entro quanto deve arrivare il primo segno di vita dopo la frase (strumento o risposta);
     * [silenzioMs]: fra un segno e l'altro (il tempo delle mani non conta); [totaleMs]: tetto di tutto.
     */
    suspend fun conversa(
        frase: String,
        primoSegnoMs: Long = 15_000L,
        silenzioMs: Long = 60_000L,
        totaleMs: Long = 600_000L,
        suStrumento: (JSONObject) -> Unit = {},
        /** 0.4.2: la VPS lavora con i suoi strumenti («comando sulla VPS», «cerco sul web»). */
        suLavoro: (String) -> Unit = {},
        esegui: suspend (JSONObject) -> JSONObject?,
    ): Esito = turno.withLock {
        val inizio = System.nanoTime()
        fun ms() = (System.nanoTime() - inizio) / 1_000_000
        val fatti = mutableListOf<JSONObject>()
        val esiti = mutableListOf<Boolean>()

        var errore = withContext(Dispatchers.IO) { collegamento.withLock { collega() } }
        // 0.5.0 (Boss 08/10: «la prima frase dopo ore deve arrivare»): al risveglio la rete può non essere ancora
        // pronta (Wi-Fi che riaggancia, DNS): un secondo tentativo dopo una breve pausa, solo per errori di rete.
        if (errore != null && errore.tipo == Tipo.RETE && ripresaMs > 0) {
            log("collegamento non riuscito al primo tentativo: riprovo fra ${ripresaMs} ms")
            kotlinx.coroutines.delay(ripresaMs)
            errore = withContext(Dispatchers.IO) { collegamento.withLock { collega() } }
        }
        if (errore != null) return@withLock errore
        val collegamentoMs = ms()

        val canale = Channel<Evento>(Channel.UNLIMITED)
        val mandata = synchronized(lock) {
            chiusuraRiposo?.cancel(false)
            inCorso = canale
            contatoreRid++
            ridInCorso = com.jarvis.telefono.collegamento.Arbitro.nuovoRid(contatoreRid)
            socket?.send(ProtocolloMani.userMessage(frase, ridInCorso)) == true
        }
        if (!mandata) {
            synchronized(lock) { inCorso = null; ridInCorso = null }
            return@withLock Esito.Fallito("Non riesco a mandare la frase alla VPS: il collegamento è caduto.", Tipo.PERSO, fatti)
        }
        log("frase alla VPS (${frase.length} caratteri), collegamento ${collegamentoMs} ms")

        var primoStrumento = -1L
        var risposta: Esito? = null
        try {
            var attesa = primoSegnoMs
            while (risposta == null) {
                val restante = totaleMs - ms()
                if (restante <= 0) break
                val ev = withTimeoutOrNull(minOf(attesa, restante)) { canale.receive() } ?: break
                when (ev) {
                    is Evento.Stop -> { risposta = Esito.Annullato(fatti); break }
                    is Evento.Perso -> {
                        risposta = Esito.Fallito(ev.motivo, if (sostituito) Tipo.OCCUPATO else Tipo.PERSO, fatti)
                        break
                    }
                    is Evento.Msg -> when (val m = ev.m) {
                        is ProtocolloMani.Messaggio.Risposta ->
                            risposta = Esito.Risposta(m.testo, m.errore, fatti, esiti, primoStrumento, ms(), collegamentoMs)
                        is ProtocolloMani.Messaggio.InLavoro -> {
                            // La VPS è viva e sta lavorando: si aspetta ancora (il tetto resta totaleMs).
                            if (!m.testo.isNullOrBlank()) runCatching { suLavoro(m.testo) }
                            attesa = silenzioMs
                        }
                        is ProtocolloMani.Messaggio.ChiamataStrumento -> {
                            if (primoStrumento < 0) primoStrumento = ms()
                            val payload = if (!MappaStrumenti.supportata(m.azione)) {
                                JSONObject().put("error", MappaStrumenti.nonSupportata(m.azione))
                            } else {
                                runCatching { suStrumento(m.comando) }
                                fatti += m.comando
                                runCatching { esegui(m.comando) }.getOrElse {
                                    if (it is kotlinx.coroutines.CancellationException) throw it
                                    JSONObject().put("error", "Errore sul telefono: ${it.javaClass.simpleName}")
                                }
                            }
                            if (MappaStrumenti.supportata(m.azione)) esiti += (payload != null && !payload.has("error"))
                            manda(ProtocolloMani.toolResult(m.id, payload))
                            log("strumento ${m.azione}: ${if (payload == null) "scaduto" else if (payload.has("error")) "errore" else "ok"}")
                            attesa = silenzioMs
                        }
                        else -> {}
                    }
                }
            }
        } finally {
            synchronized(lock) {
                inCorso = null
                if (risposta is Esito.Annullato) ridInCorso?.let { annullati += it; while (annullati.size > 20) annullati.remove(annullati.first()) }
                ridInCorso = null
                // La VPS risponde comunque, prima o poi: quella risposta (e i suoi strumenti) non sono per la frase dopo.
                // 0.4.2: annullata da Boss = debito da scartare; scaduta = risposta tardiva da far vedere.
                if (risposta is Esito.Annullato) debiti++
                else if (risposta == null) tardive++
                programmaRiposo()
            }
        }
        risposta ?: Esito.Fallito(
            if (primoStrumento < 0) "La VPS non ha risposto entro ${primoSegnoMs / 1000} secondi." else "La VPS si è fermata a metà: nessuna risposta in tempo.",
            Tipo.SCADUTO, fatti,
        )
    }

    private fun manda(testo: String): Boolean = synchronized(lock) { socket?.send(testo) == true }

    /** Assicura il collegamento autenticato. null = pronto; altrimenti il fallimento da restituire. */
    private suspend fun collega(): Esito.Fallito? {
        val u = url().trim()
        val t = token().trim()
        if (!(u.startsWith("wss://") || u.startsWith("ws://")) || t.isEmpty()) {
            return Esito.Fallito("Manca l'indirizzo o il token della VPS nella configurazione.", Tipo.CONFIGURAZIONE, emptyList())
        }
        val attesa: CompletableDeferred<String?>
        synchronized(lock) {
            if (socket != null && autenticato) return null
            if (tokenRifiutato) return Esito.Fallito("La VPS ha rifiutato il token: rifai la configurazione della VPS.", Tipo.TOKEN, emptyList())
        }
        // Un solo telefono: se la VPS ne ha già uno (e non siamo noi), non si ruba il posto.
        if (altroTelefonoCollegato(u)) {
            ultimoErrore = "un altro telefono è collegato"
            return Esito.Fallito(
                "Al cervello della VPS è già collegato un altro telefono, forse l'app Jarvis vecchia: spegnila e riprova.",
                Tipo.OCCUPATO, emptyList(),
            )
        }
        synchronized(lock) {
            if (socket != null && autenticato) return null
            sostituito = false
            attesa = CompletableDeferred()
            attesaCollegamento = attesa
            val req = runCatching { Request.Builder().url(u).build() }.getOrNull()
                ?: return Esito.Fallito("L'indirizzo della VPS non è valido.", Tipo.CONFIGURAZIONE, emptyList())
            log("collego le mani alla VPS")
            socket = http.newWebSocket(req, Ascoltatore(t))
        }
        val esito = withTimeoutOrNull(10_000L) { attesa.await() }
        synchronized(lock) { if (attesaCollegamento === attesa) attesaCollegamento = null }
        return when {
            esito == null && collegato -> null
            esito == null -> {
                chiudi()
                Esito.Fallito("La VPS non risponde (collegamento oltre 10 secondi).", Tipo.RETE, emptyList())
            }
            esito == "token" -> Esito.Fallito("La VPS ha rifiutato il token: rifai la configurazione della VPS.", Tipo.TOKEN, emptyList())
            esito == "occupato" -> Esito.Fallito("Al cervello della VPS è già collegato un altro telefono: spegnilo e riprova.", Tipo.OCCUPATO, emptyList())
            else -> Esito.Fallito("Non riesco a collegarmi alla VPS ($esito).", Tipo.RETE, emptyList())
        }
    }

    /** /health dice phoneConnected:true mentre noi non siamo collegati? Se /health non risponde si prova lo stesso. */
    private fun altroTelefonoCollegato(u: String): Boolean {
        val salute = ProtocolloMani.indirizzoSalute(u) ?: return false
        return runCatching {
            httpSalute.newCall(Request.Builder().url(salute).build()).execute().use { r ->
                if (!r.isSuccessful) return false
                JSONObject(r.body?.string().orEmpty()).optBoolean("phoneConnected", false)
            }
        }.getOrDefault(false)
    }

    private fun programmaRiposo() {
        chiusuraRiposo?.cancel(false)
        // 0.4.2: con una risposta tardiva attesa si resta aperti di più (la VPS può lavorare fino a 10 minuti).
        val dopo = if (tardive > 0) maxOf(riposoMs, attesaTardivaMs) else riposoMs
        chiusuraRiposo = orologio.schedule({
            synchronized(lock) {
                if (inCorso == null && socket != null) {
                    log("mani a riposo: chiudo il collegamento")
                    finestra.chiudi()
                    socket?.close(1000, "a riposo")
                    socket = null
                    autenticato = false
                    debiti = 0
                    tardive = 0
                }
            }
        }, dopo, TimeUnit.MILLISECONDS)
    }

    private inner class Ascoltatore(private val tokenDaMandare: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (synchronized(lock) { webSocket !== socket }) return
            webSocket.send(ProtocolloMani.auth(tokenDaMandare))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = ProtocolloMani.leggi(text) ?: return
            var daRifiutare: String? = null
            var conferma: String? = null
            var tardiva: ProtocolloMani.Messaggio.Risposta? = null
            var notifica: String? = null
            var libero: ProtocolloMani.Messaggio.ChiamataStrumento? = null
            var mandaToken = false
            synchronized(lock) {
                if (webSocket !== socket) return
                when (m) {
                    is ProtocolloMani.Messaggio.Collegato -> {
                        autenticato = true
                        ultimoErrore = ""
                        attesaCollegamento?.complete(null)
                        log("mani collegate alla VPS")
                        mandaToken = true
                    }
                    is ProtocolloMani.Messaggio.RispostaConferma -> conferma = m.testo
                    is ProtocolloMani.Messaggio.Risposta -> {
                        // 0.6.0: un solo punto decide chi risponde (collegamento/Arbitro.kt).
                        when (com.jarvis.telefono.collegamento.Arbitro.risposta(m.origine, m.rid, m.tardiva, if (inCorso != null) (ridInCorso ?: "") else null, annullati, debiti, tardive)) {
                            com.jarvis.telefono.collegamento.Arbitro.Chi.IGNORA -> { log("risposta della chat del sito: la mostra la webapp, JBoss tace"); return }
                            com.jarvis.telefono.collegamento.Arbitro.Chi.NOTIFICA -> notifica = m.testo
                            com.jarvis.telefono.collegamento.Arbitro.Chi.SCARTA -> {
                                if (m.rid != null) annullati.remove(m.rid)
                                if (debiti > 0) debiti--
                                log("risposta di una frase annullata: scartata"); return
                            }
                            com.jarvis.telefono.collegamento.Arbitro.Chi.FRASE_IN_CORSO -> inCorso?.trySend(Evento.Msg(m))
                            com.jarvis.telefono.collegamento.Arbitro.Chi.TARDIVA -> {
                                if (!m.tardiva && tardive > 0) tardive--
                                tardiva = m
                                if (!m.tardiva) programmaRiposo()
                                Unit
                            }
                        }
                    }
                    is ProtocolloMani.Messaggio.InLavoro -> inCorso?.trySend(Evento.Msg(m))
                    is ProtocolloMani.Messaggio.ChiamataStrumento -> {
                        val c = inCorso
                        when {
                            debiti > 0 -> daRifiutare = m.id
                            c != null -> c.trySend(Evento.Msg(m))
                            // 0.6.1: la VPS ha svegliato il telefono per questo strumento.
                            strumentoLibero != null && finestra.aperta(adesso()) -> libero = m
                            else -> daRifiutare = m.id
                        }
                    }
                    else -> {}
                }
            }
            if (mandaToken) {
                // 0.6.1: il token FCM a ogni collegamento (la VPS lo salva solo se cambia). Mai nei log.
                val t = runCatching { tokenFcm() }.getOrNull()
                if (com.jarvis.telefono.sveglia.RegoleSveglia.tokenValido(t)) {
                    webSocket.send(ProtocolloMani.tokenFcm(t!!))
                    log("token della sveglia mandato alla VPS")
                }
                return
            }
            libero?.let { eseguiLibero(webSocket, it) }
            daRifiutare?.let {
                log("strumento fuori da una frase di JBoss: rifiutato")
                webSocket.send(ProtocolloMani.toolErrore(it, "Boss ha annullato o cambiato richiesta su JBoss: non fare altre azioni sul telefono per questa richiesta."))
            }
            conferma?.let { runCatching { suRispostaConferma(it) } }
            tardiva?.let { log("risposta tardiva della VPS: la faccio vedere"); runCatching { suRispostaTardiva(it.testo, it.errore) } }
            notifica?.let { log("avviso di Jarvis fuori turno: alla coda delle notifiche"); runCatching { suNotifica(it) } }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            perso(webSocket, code, "chiuso dalla VPS ($code)")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = perso(webSocket, code, "chiuso ($code)")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            perso(webSocket, response?.code ?: 0, "errore di rete (${t.javaClass.simpleName})")
    }

    /** 0.6.1: uno strumento chiesto dalla VPS nella finestra della sveglia, fuori da una frase di Boss. */
    private fun eseguiLibero(ws: WebSocket, m: ProtocolloMani.Messaggio.ChiamataStrumento) {
        val esegui = strumentoLibero ?: return
        finestra.allunga(adesso())
        synchronized(lock) { if (inCorso == null && webSocket(ws)) programmaRiposo() }
        lavoroLibero.launch {
            val payload = if (!MappaStrumenti.supportata(m.azione)) {
                JSONObject().put("error", MappaStrumenti.nonSupportata(m.azione))
            } else {
                runCatching { esegui(m.comando) }.getOrElse { JSONObject().put("error", "Errore sul telefono: ${it.javaClass.simpleName}") }
            }
            ws.send(ProtocolloMani.toolResult(m.id, payload))
            log("sveglia: strumento ${m.azione}: ${if (payload == null) "scaduto" else if (payload.has("error")) "errore" else "ok"}")
        }
    }

    private fun webSocket(ws: WebSocket): Boolean = ws === socket

    private fun perso(ws: WebSocket, codice: Int, motivo: String) {
        synchronized(lock) {
            if (ws !== socket) return
            finestra.chiudi()
            socket = null
            autenticato = false
            debiti = 0
            tardive = 0
            chiusuraRiposo?.cancel(false)
            ultimoErrore = motivo
            val chiave = when (codice) {
                ProtocolloMani.CHIUSO_TOKEN -> { tokenRifiutato = true; "token" }
                ProtocolloMani.CHIUSO_SOSTITUITO -> { sostituito = true; "occupato" }
                else -> motivo
            }
            log("mani scollegate: $motivo")
            attesaCollegamento?.complete(chiave)
            val testo = when (codice) {
                ProtocolloMani.CHIUSO_SOSTITUITO -> "Un altro telefono si è collegato al cervello della VPS al posto mio: mi sono fermato."
                else -> "Il collegamento con la VPS è caduto a metà: controlla sul telefono cosa è stato fatto."
            }
            inCorso?.trySend(Evento.Perso(testo))
        }
    }

    /** La configurazione è cambiata: si riparte (token nuovo, indirizzo nuovo). */
    fun configurazioneCambiata() {
        tokenRifiutato = false
        sostituito = false
        chiudi()
    }
}

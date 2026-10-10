package com.jarvis.telefono.nucleo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.jarvis.telefono.AppTelefono
import com.jarvis.telefono.JarvisService
import com.jarvis.telefono.PhoneActionExecutor
import com.jarvis.telefono.ServizioComando
import com.jarvis.telefono.agenti.Competenze
import com.jarvis.telefono.bolla.TestiBolla
import com.jarvis.telefono.mani.CercaContatti
import com.jarvis.telefono.mani.RichiestaPermessoActivity
import com.jarvis.telefono.voce.StatoJarvis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Il giro di Jarvis Telefono, tutto sul telefono (passo A, 2026-10-07):
 *
 *   frase (voce dopo Whisper, dettato, campo di testo, banco ADB)
 *     → risposta a una bozza in attesa? (CancelloInvio: «invia» / «annulla») → fine
 *     → [Cervello.capisci] → [Piano]
 *     → ogni [Azione] a PhoneActionExecutor.handle(JSONObject) (le mani dell'app 1.2.x, invariate)
 *     → frase finale: bolla, segnale, voce di sistema, cronologia.
 *
 * Nessuna rete. Prende il posto di `sendUserText` → ponte VPS → `tool_call` dell'app 1.2.x:
 * PhoneActionExecutor risponde a [ricevi] come prima rispondeva al WebSocket.
 * Tutto sul thread principale, tranne rubrica e database (IO).
 */
object Nucleo {
    private const val TAG = "JarvisNucleo"
    /** invia_bozza aspetta Boss fino a 2 minuti: un po' di margine. */
    private const val ATTESA_BOZZA_MS = 135_000L
    private const val ATTESA_AZIONE_MS = 30_000L
    private const val RUBRICA_VALIDA_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inAttesa = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val contatore = AtomicLong(0)

    /** Le regole; da [prepara] (0.3.4) la catena regole → cervello della VPS (CervelloCatena). */
    @Volatile
    var cervello: Cervello = CervelloRegole()

    @Volatile
    private var collegato = false
    @Volatile
    private var appCtx: Context? = null
    private var rubrica: List<CercaContatti.Contatto>? = null
    private var rubricaLettaMs = 0L

    /** Ultima misura «frase → azione», per il banco ADB e la schermata. */
    @Volatile
    var ultimaMisura: String = ""
        private set

    /** Le mani rispondono qui (era il WebSocket del ponte). */
    fun prepara(context: Context) {
        if (collegato) return
        collegato = true
        val app = context.applicationContext
        appCtx = app
        PhoneActionExecutor.attach(app) { ricevi(it) }
        // 0.3.4: le frasi che le regole non capiscono (o «complesse») vanno al cervello della VPS, se il modulo è acceso.
        if (cervello is CervelloRegole) {
            cervello = CervelloCatena(
                CervelloRegole(),
                CervelloVps(
                    com.jarvis.telefono.vps.ManiVps.canale(app),
                    disponibilita = { com.jarvis.telefono.vps.ManiVps.disponibilita(app) },
                    esegui = { comando -> eseguiComando(comando) },
                    osservatore = OsservatoreBolla,
                    notaPersonale = { ConfigPersonale.di(app).notaPerVps() },
                ),
            )
        }
    }

    /** 0.3.4: la bolla mentre la VPS lavora («JBoss (VPS) → Mani: Apro WhatsApp…») e i tempi nel log. */
    private object OsservatoreBolla : CervelloVps.Osservatore {
        override fun inizio(frase: String) {
            scope.launch { JarvisService.instance?.statoBolla(TestiBolla.allaVps(frase)) }
        }

        override fun strumento(azione: Azione) {
            val agente = Competenze.perAzione(azione)
            scope.launch {
                if (agente != null) StatoJarvis.aggiorna { it.copy(agenteAlLavoro = agente) }
                TestiBolla.perStrumento(azione.action) { k -> azione.campi[k]?.toString().orEmpty() }?.let { r ->
                    Comandi.registro.ultimoAperto()?.let { Comandi.registro.lavoro(it, r.titolo) }
                    if (agente != null) StatoJarvis.aggiorna { it.copy(lavoroInCorso = r.titolo) }
                    JarvisService.instance?.statoBolla(r.copy(agente = agente, daVps = true))
                }
            }
        }

        override fun lavoro(testo: String) {
            scope.launch {
                Comandi.registro.ultimoAperto()?.let { Comandi.registro.lavoro(it, testo) }
                StatoJarvis.aggiorna { it.copy(lavoroInCorso = testo) }
                JarvisService.instance?.statoBolla(TestiBolla.vpsAlLavoro(testo))
            }
        }

        override fun misura(riga: String) {
            Log.i(TAG, "misura: $riga")
        }
    }

    /**
     * 0.4.2: la risposta della VPS a una frase già chiusa (scaduta sul telefono, o tenuta da parte mentre il telefono
     * era scollegato): bolla, voce e cronologia come una risposta normale.
     */
    fun rispostaTardiva(context: Context, testo: String, errore: Boolean) {
        val app = context.applicationContext
        val t = testo.trim()
        if (t.isEmpty()) return
        Log.i(TAG, "risposta tardiva della VPS (${t.length} caratteri)")
        scope.launch {
            StatoJarvis.aggiorna { it.copy(ultimoJarvis = t, agenteAlLavoro = null, lavoroInCorso = null) }
            withContext(Dispatchers.IO) { runCatching { Cronologia.di(app).aggiungi(Cronologia.JARVIS, t, CervelloVps.NOME, if (errore) "errore" else "ok (tardiva)", 0L, "") } }
            JarvisService.instance?.rispondi(t, errore, null, false, vps = true)
        }
    }

    fun ricevi(payload: String) {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
        if (o.optString("type") != "tool_result") return
        inAttesa.remove(o.optString("id"))?.complete(o)
    }

    /**
     * Una frase di Boss. [origine]: «voce», «scritto», «dettato», «prova». Sul thread principale.
     */
    fun elabora(context: Context, testo: String, origine: String) {
        val app = context.applicationContext
        prepara(app)
        val t = testo.trim()
        if (t.isEmpty()) return
        val cron = Cronologia.di(app)
        StatoJarvis.aggiorna { it.copy(ultimoUtente = t) }

        // 09/10 (Boss: ogni cancellazione è irreversibile): con una cancellazione del Postino in attesa «annulla» la
        // chiude, «sì»/«elimina» a voce NO: serve il tocco su Elimina nel filo.
        Comandi.registro.confermaInAttesa()?.takeIf { it.tipo == RegistroComandi.TipoConferma.POSTA }?.let { k ->
            when (com.jarvis.telefono.mani.CancelloInvio.interpreta(t)) {
                com.jarvis.telefono.mani.CancelloInvio.Esito.ANNULLA -> {
                    scope.launch(Dispatchers.IO) { cron.aggiungi(Cronologia.BOSS, t, esito = "risposta alla conferma") }
                    com.jarvis.telefono.postino.Postino.rispondiConferma(app, k.chiave, false)
                    return
                }
                com.jarvis.telefono.mani.CancelloInvio.Esito.INVIA -> {
                    scope.launch(Dispatchers.IO) { cron.aggiungi(Cronologia.BOSS, t, esito = "risposta alla conferma") }
                    JarvisService.instance?.rispondi("Per cancellare tocca ${k.etichettaSi} nel riquadro: a voce non cancello niente.", false, com.jarvis.telefono.postino.Postino.AGENTE)
                    return
                }
                else -> {}
            }
        }

        // «invia» / «annulla» con una bozza in attesa: la frase è la risposta e resta qui.
        if (PhoneActionExecutor.rispostaDiBoss(t)) {
            Log.i(TAG, "frase: risposta alla bozza in attesa ($origine)")
            scope.launch(Dispatchers.IO) { cron.aggiungi(Cronologia.BOSS, t, esito = "risposta alla bozza") }
            return
        }
        PhoneActionExecutor.nuovaRichiesta()

        // 0.7.0: ogni frase è un comando con la sua riga in chat; il servizio lo tiene vivo con l'app in secondo piano.
        val comando = Comandi.registro.inizia(t, origine)
        ServizioComando.avvia(app)
        // Regole di Boss del 08/10: un acquisto si prepara tutto, ma paga solo il tocco di Boss su Paga dopo il riepilogo.
        if (RegoleConferma.perFrase(t) == RegoleConferma.Livello.ACQUISTO) {
            Log.i(TAG, "frase: acquisto ($origine), pagamento solo dopo il riepilogo e il tocco di Boss")
            Comandi.registro.lavoro(comando, "Acquisto: preparo tutto, pago solo dopo il tuo tocco su Paga")
        }

        scope.launch {
          try {
            // 09/10 (Boss: «passa al Postino» / «torna a JBoss»): il passaggio della conversazione, visibile nel filo.
            // Prima la frase andava al cervello della VPS, che rispondeva «non posso passarti la conversazione».
            if (com.jarvis.telefono.postino.Postino.passa(app, t, origine, comando)) return@launch
            // Con il Postino che ha la conversazione, ogni frase va a lui (frasi libere e comandi a numeri), non a JBoss.
            if (com.jarvis.telefono.postino.Postino.passaggio.conPostino) {
                val inizio = SystemClock.elapsedRealtime()
                val r = com.jarvis.telefono.postino.Postino.perPostino(app, t, comando)
                withContext(Dispatchers.IO) { cron.aggiungi(Cronologia.BOSS, t, esito = origine) }
                if (r.idLavoro != null || r.chiaveConferma != null) {
                    Comandi.registro.lavoro(comando, r.dire)
                    withContext(Dispatchers.IO) { runCatching { cron.aggiungi(Cronologia.JARVIS, r.dire, com.jarvis.telefono.postino.Postino.AGENTE, "in corso", 0L, com.jarvis.telefono.postino.Postino.AGENTE) } }
                    JarvisService.instance?.rispondi(r.dire, false, com.jarvis.telefono.postino.Postino.AGENTE)
                } else {
                    chiudi(app, comando, r.dire, r.errore, if (r.errore) "errore" else "ok", com.jarvis.telefono.postino.Postino.AGENTE,
                        inizio, -1, "posta", com.jarvis.telefono.postino.Postino.AGENTE)
                }
                return@launch
            }
            // 0.6.0 (Collegamento Jarvis): webapp, memoria condivisa, contesti seguiti, report. Comandi del telefono.
            com.jarvis.telefono.collegamento.ComandiCollegamento.riconosci(t)?.let { cmd ->
                withContext(Dispatchers.IO) { cron.aggiungi(Cronologia.BOSS, t, esito = origine) }
                val (dire, errore) = withContext(Dispatchers.IO) { com.jarvis.telefono.collegamento.CollegamentoJarvis.esegui(app, cmd) }
                Log.i(TAG, "collegamento: ${cmd.javaClass.simpleName} ${if (errore) "errore" else "ok"}")
                withContext(Dispatchers.IO) { runCatching { cron.aggiungi(Cronologia.JARVIS, dire, "collegamento", if (errore) "errore" else "ok", 0L, "") } }
                Comandi.registro.chiudi(comando, if (errore) "errore" else "ok", errore, dire)
                JarvisService.instance?.rispondi(dire, errore)
                return@launch
            }
            // 09/10 (Boss: «JBoss deve collegarsi subito al Postino»): la posta («leggi le mail», «avanti», «cancella
            // questa», «aggiorna») va al canale unico del Postino, lo stesso della sua pagina. Prima finiva al cervello
            // della VPS (un'altra socket, un'altra lettura della posta) o a un lavoro generico che la pagina non vedeva.
            com.jarvis.telefono.postino.Postino.perJBoss(app, t, comando)?.let { r ->
                val inizio = SystemClock.elapsedRealtime()
                withContext(Dispatchers.IO) { cron.aggiungi(Cronologia.BOSS, t, esito = origine) }
                if (r.idLavoro != null || r.chiaveConferma != null) {
                    // la risposta vera arriva a fine lavoro (o dopo il tocco sul box): il comando resta aperto
                    Comandi.registro.lavoro(comando, r.dire)
                    withContext(Dispatchers.IO) { runCatching { cron.aggiungi(Cronologia.JARVIS, r.dire, com.jarvis.telefono.postino.Postino.AGENTE, "in corso", 0L, com.jarvis.telefono.postino.Postino.AGENTE) } }
                    JarvisService.instance?.rispondi(r.dire, false, com.jarvis.telefono.postino.Postino.AGENTE)
                } else {
                    chiudi(app, comando, r.dire, r.errore, if (r.errore) "errore" else "ok", com.jarvis.telefono.postino.Postino.AGENTE,
                        inizio, -1, "posta", com.jarvis.telefono.postino.Postino.AGENTE)
                }
                return@launch
            }
            // 0.3.0 (modulo VPS): «sulla VPS …», gli agenti «sulla VPS» e i compiti lunghi vanno alla VPS
            // (vps/Instradamento.kt); «qui …» resta qui. null = l'ha preso il modulo (mandato, in coda o rifiutato).
            val t = withContext(Dispatchers.IO) { com.jarvis.telefono.vps.ModuloVps.instrada(app, t, origine = origine) } ?: run {
                // Presa dal modulo VPS: si esce da «Penso» e si dice dove è andata (o perché non è partita).
                val (dire, errore) = com.jarvis.telefono.vps.ModuloVps.ultimaRisposta
                Comandi.registro.chiudi(comando, if (errore) "errore" else "alla vps", errore, dire)
                JarvisService.instance?.rispondi(dire, errore)
                return@launch
            }
            val inizio = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) { cron.aggiungi(Cronologia.BOSS, t, esito = origine) }
            val ctx = withContext(Dispatchers.IO) { contesto(app) }
            val c = cervello
            val piano = runCatching { c.capisci(t, ctx) }.getOrElse {
                Log.w(TAG, "cervello ${c.nome}: ${it.javaClass.simpleName}")
                Piano.nonCapito()
            }
            if (piano.serveRubrica) chiediRubrica(app)
            val nomeCervello = piano.cervello.ifEmpty { c.nome }
            // 0.3.4: il cervello della VPS ha già fatto le azioni (tool_call una alla volta): qui si chiude e basta.
            if (piano.giaEseguito) {
                if (piano.capito && !piano.annullato) JarvisService.instance?.capito()
                val agente = Competenze.perPiano(Piano(piano.eseguite, "")).orEmpty()
                val esito = when {
                    piano.annullato -> "annullato"
                    piano.errore -> "errore"
                    else -> "ok"
                }
                chiudi(app, comando, piano.dire, piano.errore, esito, nomeCervello, inizio, piano.primaAzioneMs,
                    piano.eseguite.joinToString("+") { it.action }, agente)
                return@launch
            }
            if (!piano.capito) {
                Log.i(TAG, "non capito (${t.length} caratteri, $origine)")
                // 0.3.3: la frase non capita resta sul telefono, con la data, per migliorare le regole.
                withContext(Dispatchers.IO) { runCatching { FrasiNonCapite.di(app).aggiungi(t, origine) } }
                // Dalla voce: il microfono si riapre da solo UNA volta (JarvisService.rispondi tiene il conto).
                chiudi(app, comando, piano.dire, errore = true, esito = "non capito", cervello = nomeCervello, inizio = inizio, primaMs = -1, cosa = "", riascolta = origine == "voce")
                return@launch
            }
            JarvisService.instance?.capito()
            var dire = piano.dire
            var errore = false
            var esito = "ok"
            var primaMs = -1L
            // 0.2.0: Jarvis passa il lavoro all'agente competente (Competenze: la mappa unica).
            // Vuoto = lo fa Jarvis stesso. Si vede in bolla («Jarvis → Postino») e in cronologia.
            val agente = Competenze.perPiano(piano).orEmpty()
            if (agente.isNotEmpty()) StatoJarvis.aggiorna { it.copy(agenteAlLavoro = agente) }
            for (a in piano.azioni) {
                if (a.action != "invia_bozza") {
                    TestiBolla.perStrumento(a.action) { k -> a.campi[k]?.toString().orEmpty() }?.let { r ->
                        Comandi.registro.lavoro(comando, r.titolo)
                        if (agente.isNotEmpty()) StatoJarvis.aggiorna { it.copy(lavoroInCorso = r.titolo) }
                        JarvisService.instance?.statoBolla(r.copy(agente = agente.ifEmpty { null }))
                    }
                }
                val r = esegui(a)
                if (primaMs < 0) primaMs = SystemClock.elapsedRealtime() - inizio
                val err = when {
                    r == null -> "nessuna risposta dalle mani in tempo"
                    r.has("error") -> r.optString("error")
                    else -> null
                }
                if (err != null) {
                    dire = TestiRisposta.dopoErrore(a.action, err, (a["nome"] ?: a["app"])?.toString().orEmpty())
                    errore = !err.lowercase().contains("annullato")
                    esito = if (errore) "errore" else "annullato"
                    break
                }
                val res = r!!.opt("result")
                if (res is JSONObject && res.has("aperto") && !res.optBoolean("aperto")) {
                    dire = "L'ho chiesto, ma l'app non è venuta in primo piano."
                    errore = true; esito = "errore"
                    break
                }
            }
            chiudi(app, comando, dire, errore, esito, nomeCervello, inizio, primaMs, piano.azioni.joinToString("+") { it.action }, agente)
          } finally {
            // Un'eccezione o un'uscita non prevista non lasciano la riga «in corso» per sempre.
            if (Comandi.registro.comando(comando)?.aperto == true) Comandi.registro.chiudi(comando, "errore", true, "Interrotto prima della fine.")
          }
        }
    }

    private fun chiudi(app: Context, comando: Long, dire: String, errore: Boolean, esito: String, cervello: String, inizio: Long, primaMs: Long, cosa: String, agente: String = "", riascolta: Boolean = false) {
        val totale = SystemClock.elapsedRealtime() - inizio
        ultimaMisura = "cervello=$cervello azioni=${cosa.ifEmpty { "-" }} prima_azione_ms=$primaMs totale_ms=$totale esito=$esito"
        // Nel log solo i nomi delle azioni e i tempi: mai il testo detto né i nomi dei contatti.
        Log.i(TAG, "misura: $ultimaMisura")
        Comandi.registro.chiudi(comando, esito, errore, dire)
        StatoJarvis.aggiorna { it.copy(ultimoJarvis = dire.ifEmpty { it.ultimoJarvis }, agenteAlLavoro = null, lavoroInCorso = null) }
        if (dire.isNotEmpty()) {
            scope.launch(Dispatchers.IO) { Cronologia.di(app).aggiungi(Cronologia.JARVIS, dire, cervello, esito, totale, agente) }
        }
        JarvisService.instance?.rispondi(dire, errore, agente.ifEmpty { null }, riascolta, vps = cervello == CervelloVps.NOME)
    }

    /** Un'azione alle mani, e la sua risposta (null = scaduta). */
    private suspend fun esegui(a: Azione): JSONObject? =
        eseguiComando(a.json(), if (a.action == "invia_bozza") ATTESA_BOZZA_MS else ATTESA_AZIONE_MS)

    /**
     * Un comando alle mani (anche quelli della VPS, 0.3.4) con un id locale: PhoneActionExecutor risponde a [ricevi].
     * Il payload torna così com'è (`result` o `error`); null = scaduto.
     */
    /** 0.6.1: uno strumento chiesto dalla VPS dopo la sveglia FCM (vps/CanaleMani.kt, finestra di 3 minuti). */
    suspend fun eseguiPerSveglia(comando: JSONObject): JSONObject? {
        Log.i(TAG, "strumento dalla sveglia della VPS: ${comando.optString("action")}")
        return eseguiComando(comando)
    }

    private suspend fun eseguiComando(comando: JSONObject, attesaMs: Long = com.jarvis.telefono.vps.MappaStrumenti.attesaMs(comando)): JSONObject? {
        // 0.4.2: schermo spento → si accende; telefono bloccato con PIN → errore chiaro, niente attesa a vuoto.
        appCtx?.let { ctx ->
            if (Schermo.serve(comando.optString("action"))) {
                if (Schermo.accendi(ctx)) kotlinx.coroutines.delay(800L)
                if (Schermo.bloccato(ctx)) return JSONObject().put("error", Schermo.MESSAGGIO_BLOCCATO)
            }
        }
        val id = "loc-" + contatore.incrementAndGet()
        val d = CompletableDeferred<JSONObject>()
        inAttesa[id] = d
        return try {
            withContext(Dispatchers.Main) {
                PhoneActionExecutor.handle(JSONObject().put("type", "tool_call").put("id", id).put("command", comando))
            }
            withTimeoutOrNull(attesaMs) { d.await() }
        } finally {
            inAttesa.remove(id)
        }
    }

    /** Sul thread IO. La rubrica si rilegge al massimo una volta al minuto (è la parte lenta). */
    private fun contesto(app: Context): Contesto {
        val adesso = SystemClock.elapsedRealtime()
        val contatti = if (!AppTelefono.rubricaPermessa(app)) null else {
            synchronized(this) {
                if (rubrica == null || adesso - rubricaLettaMs > RUBRICA_VALIDA_MS) {
                    rubrica = runCatching { AppTelefono.contatti(app) }.getOrDefault(emptyList())
                    rubricaLettaMs = adesso
                }
                rubrica
            }
        }
        val cal = Calendar.getInstance()
        return Contesto(
            contatti, ConfigPersonale.di(app), cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
            giorno = cal.get(Calendar.DAY_OF_MONTH), mese = cal.get(Calendar.MONTH) + 1, anno = cal.get(Calendar.YEAR),
            // Calendar: domenica = 1 … sabato = 7; qui lunedì = 1 … domenica = 7.
            giornoSettimana = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1,
        )
    }

    private fun chiediRubrica(app: Context) {
        runCatching {
            app.startActivity(
                Intent(app, RichiestaPermessoActivity::class.java)
                    .putExtra(RichiestaPermessoActivity.EXTRA_PERMESSO, Manifest.permission.READ_CONTACTS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

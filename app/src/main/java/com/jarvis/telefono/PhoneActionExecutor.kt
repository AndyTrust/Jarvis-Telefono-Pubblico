package com.jarvis.telefono

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.jarvis.telefono.bolla.Suoni
import com.jarvis.telefono.bolla.TestiBolla
import com.jarvis.telefono.mani.Bersaglio
import com.jarvis.telefono.mani.CancelloInvio
import com.jarvis.telefono.mani.CatalogoApp
import com.jarvis.telefono.mani.CercaContatti
import com.jarvis.telefono.mani.ComponiIntent
import com.jarvis.telefono.mani.InterruttoreTile
import com.jarvis.telefono.mani.NodoInfo
import com.jarvis.telefono.mani.ParoleInvio
import com.jarvis.telefono.mani.PianoIntent
import com.jarvis.telefono.mani.ProfiliApp
import com.jarvis.telefono.mani.RegistroAzioni
import com.jarvis.telefono.mani.RichiestaPermessoActivity
import com.jarvis.telefono.nucleo.Comandi
import com.jarvis.telefono.nucleo.RegistroComandi
import com.jarvis.telefono.nucleo.RegoleConferma
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

// Riceve i comandi che arrivano da jarvis-agent (tool_call) e li smista alle
// mani vere (JarvisAccessibilityService). Tutto sul thread principale.
//
// 1.2.0 (Boss 2026-10-07): le app vere con la bozza pronta (componi), ricerca
// Google, Gemini, tutte le app per nome, elementi numerati, e la conferma
// d'invio IMPOSTA DAL CODICE: un tocco su un pulsante «Invia» senza il sì di
// Boss viene rifiutato qui, qualunque cosa abbia deciso il modello.
object PhoneActionExecutor {
    const val CONFIRM_CHANNEL_ID = "jarvis_confirm"
    const val ACTION_CONFIRM = "com.jarvis.telefono.action.CONFIRM"
    const val ACTION_CANCEL = "com.jarvis.telefono.action.CANCEL"
    const val EXTRA_CALL_ID = "call_id"
    const val TAG_PROVA = "JarvisProva"

    /**
     * 0.5.0: app che si aprono dentro un'altra (KO del 08/10: «apri Gemini» porta in primo piano l'app Google,
     * che ospita Gemini su alcuni telefoni, e l'attesa del pacchetto com.google.android.apps.bard finiva in errore).
     */
    val PRIMO_PIANO_ANCHE: Map<String, List<String>> = mapOf(
        ComponiIntent.GEMINI to listOf(ComponiIntent.GOOGLE_APP),
    )

    // 1.2.2 (Boss 07/10): 2 minuti, poi si dice a voce e nella bolla che non è partito niente e
    // Jarvis è subito pronto per la richiesta dopo. Prima erano 5 minuti di attesa muta.
    private val confirmTimeoutMs = TimeUnit.MINUTES.toMillis(2)

    private var appContext: Context? = null
    private var sender: ((String) -> Unit)? = null
    private val cancello = CancelloInvio()
    private var registro: RegistroAzioni? = null
    private var confirmNotificationId = 1000
    private var notificaSospesa: Int? = null
    private var scadenzaSospesa: Runnable? = null

    // Pigro apposta: creato subito, rendeva impossibile caricare questo oggetto
    // fuori da un telefono, e quindi provarlo.
    private val principale by lazy { Handler(Looper.getMainLooper()) }

    private const val MSG_SERVIZIO_SPENTO =
        "Il servizio di Accessibilità di JBoss non è attivo: vai in Impostazioni > Accessibilità e accendilo."
    // 1.2.2 (Boss 07/10: «si deve bloccare quell'operazione, ma Jarvis deve essere sempre
    // funzionante»): l'annullamento toglie la bozza e rifiuta le azioni di QUESTA richiesta; la
    // richiesta successiva di Boss riparte da sola. Niente più mani ferme per sempre.
    // 1.2.4 (Boss 07/10: «Ferma non serve, rischiamo solo di creare confusione»): il pulsante
    // «Ferma» non c'è più; «Annulla» (tocco, notifica o a voce) fa quello che faceva «Ferma».
    private const val MSG_ANNULLATA =
        "Boss ha annullato: niente è stato inviato. Non fare altre azioni sul telefono " +
            "per questa richiesta e non riproporre la bozza: rispondi in una frase breve che hai annullato."
    private const val ANNULLATA_MS = 120_000L
    private val SEMPRE_PERMESSE = setOf("stato_tecnico", "modo_tecnico", "registro", "emergenza")

    private const val MSG_MANI_FERME =
        "Interruttore di emergenza acceso: le mani di JBoss sono ferme. Solo Boss le riattiva dal riquadro «Mani di JBoss» nella tendina del telefono."

    fun attach(context: Context, sendFn: (String) -> Unit) {
        appContext = context.applicationContext
        sender = sendFn
        registro = RegistroAzioni(File(context.filesDir, "registro-azioni.log"))
        createConfirmChannel(context)
        // 1.2.2: le «mani ferme» per sempre non esistono più; chi era rimasto fermo riparte.
        if (Prefs.isManiFerme(context)) Prefs.setManiFerme(context, false)
    }

    /** Per il banco di prova ADB quando il servizio di Jarvis non è acceso. */
    fun attachSeServe(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        registro = RegistroAzioni(File(context.filesDir, "registro-azioni.log"))
        createConfirmChannel(context)
    }

    fun detach() {
        sender = null
    }

    /** C'è una bozza che aspetta Boss? Il ponte resta aperto finché c'è. */
    fun inSospeso(): Boolean = cancello.sospesa() != null

    // ================================================================ smistamento

    fun handle(message: JSONObject) {
        val id = message.optString("id")
        val command = message.optJSONObject("command") ?: return
        val action = command.optString("action")
        val context = appContext
        val service = JarvisAccessibilityService.instance

        if (cancello.richiestaBloccata() && action !in SEMPRE_PERMESSE) {
            segna(service?.pacchettoAttivo(), action, "", "rifiutata: richiesta annullata da Boss")
            reply(id, error = MSG_ANNULLATA)
            return
        }

        when (action) {
            "request_send_confirmation" -> { askForConfirmation(id, command); return }
            "modo_tecnico", "stato_tecnico" -> { rispondiSulModoTecnico(id, action, command); return }
            "registro" -> { reply(id, result = registro?.ultime(command.optInt("righe", 30))?.joinToString("\n") ?: ""); return }
            "emergenza" -> {
                // 1.2.2: ferma l'operazione in corso (bozza annullata, azioni di questa richiesta
                // rifiutate); la prossima richiesta di Boss riparte da sola.
                if (command.optBoolean("ferma", true)) fermaOperazione("VPS")
                reply(id, result = "operazione fermata: niente inviato; la prossima richiesta di Boss riparte normalmente")
                return
            }
        }
        if (context == null) { reply(id, error = "Il servizio di JBoss non è avviato su questo telefono."); return }
        if (Prefs.isManiFerme(context)) { reply(id, error = MSG_MANI_FERME); return }

        // Azioni che non chiedono l'accessibilità.
        when (action) {
            "cerca_contatto" -> { cercaContatto(id, command, context); return }
            "elenca_app" -> { elencaApp(id, command, context); return }
            "componi" -> { componi(id, command, context); return }
            "cerca_google" -> {
                apriPiano(id, context, runCatching {
                    ComponiIntent.cercaGoogle(command.optString("domanda"), command.optBoolean("browser"))
                }, attesaExtraMs = 1800, attendiPagina = true)
                return
            }
        }

        if (service == null) { reply(id, error = MSG_SERVIZIO_SPENTO); return }

        when (action) {
            "tap" -> tapCoordinate(id, service, command)
            "tocca", "tocca_elemento" -> tocca(id, service, command, lungo = false)
            "pressione_lunga" -> tocca(id, service, command, lungo = true)
            "type_text", "scrivi" -> scrivi(id, service, command)
            "key" -> esito(id, action, service.pressKey(command.optString("name")), failureMessage("key", tasto = command.optString("name")))
            "open_app", "apri_app" -> apriApp(id, service, command)
            "read_screen", "leggi_schermo" -> reply(id, result = service.leggiSchermo())
            "scorri" -> {
                val n = command.optInt("n", 0).takeIf { it > 0 }?.let { service.elemento(it) }
                esito(id, action, service.scorri(command.optString("direzione", "giu").lowercase(), n), "Lo scorrimento non è riuscito.")
            }
            "swipe" -> esito(
                id, action,
                service.gesto(
                    command.optDouble("x1").toFloat(), command.optDouble("y1").toFloat(),
                    command.optDouble("x2").toFloat(), command.optDouble("y2").toFloat(),
                    command.optLong("ms", 300),
                ),
                "Lo strisciamento non è partito.",
            )
            "attendi" -> attendi(id, service, command)
            "invio_tastiera" -> invioTastiera(id, service)
            "invia_bozza" -> inviaBozza(id, service, command)
            "cerca_in_app" -> cercaInApp(id, service, command, context)
            "gemini_chiedi" -> geminiChiedi(id, service, command, context)
            "screenshot" -> service.screenshot(command.optInt("larghezza", 720)) { b64, errore ->
                if (b64 != null) {
                    segna(service.pacchettoAttivo(), "screenshot", "", "ok")
                    reply(id, result = JSONObject().put("immagine_jpeg_base64", b64))
                } else reply(id, error = errore ?: "Screenshot non riuscito")
            }
            "compila_accesso" -> {
                val errore = service.compilaAccesso(command.optString("utente").ifEmpty { null }, command.optString("password"))
                segna(service.pacchettoAttivo(), "compila_accesso", "[credenziali nascoste]", errore ?: "ok")
                if (errore == null) reply(id, result = "credenziali scritte nei campi (non ripeterle)") else reply(id, error = errore)
            }
            else -> reply(id, error = "Azione sconosciuta: $action")
        }
    }

    private fun esito(id: String, action: String, ok: Boolean, seNo: String) {
        segna(JarvisAccessibilityService.instance?.pacchettoAttivo(), action, "", if (ok) "ok" else "no")
        if (ok) reply(id, result = true) else reply(id, error = seNo)
    }

    private fun segna(app: String?, azione: String, dettaglio: String, esito: String) {
        registro?.scrivi(app, azione, dettaglio, esito)
    }

    // ================================================================ il cancello d'invio

    /** null = si può toccare; altrimenti il motivo del rifiuto. */
    private fun cancelloRifiuta(service: JarvisAccessibilityService, catena: List<NodoInfo>): String? {
        // 0.7.0: acquisti e ordini non sono attivi: un pulsante che paga o ordina non si preme mai, nemmeno dopo un sì.
        if (catena.any { RegoleConferma.pulsanteDiAcquisto(it) }) return RegoleConferma.MSG_USA_INVIA_BOZZA
        if (service.pacchettoAttivo() in ParoleInvio.SENZA_CONFERMA) return null
        if (catena.none { ParoleInvio.eInvio(it) }) return null
        if (cancello.consumaPermesso()) return null
        return "BLOCCATO DAL TELEFONO: quello è il pulsante d'invio e Boss non ha confermato. " +
            "Usa invia_bozza (mostra la bozza a Boss e invia solo dopo il suo «invia»)."
    }

    private fun tapCoordinate(id: String, service: JarvisAccessibilityService, command: JSONObject) {
        val x = command.optDouble("x").toFloat()
        val y = command.optDouble("y").toFloat()
        val nodo = service.nodoAlPunto(x.toInt(), y.toInt())
        if (nodo != null) {
            cancelloRifiuta(service, service.catenaBersaglio(nodo))?.let {
                segna(service.pacchettoAttivo(), "tap", "($x,$y) invio", "bloccato")
                reply(id, error = it)
                return
            }
        }
        esito(id, "tap", service.tap(x, y), failureMessage("tap"))
    }

    /** Il nodo indicato da n, da una chiave (testo/descrizione/«:id/x») o da x,y. */
    private fun bersaglio(service: JarvisAccessibilityService, command: JSONObject): AccessibilityNodeInfo? {
        val n = command.optInt("n", 0)
        if (n > 0) return service.elemento(n)
        val chiave = command.optString("testo").ifEmpty { command.optString("chiave") }
        if (chiave.isNotEmpty()) {
            val nodi = service.nodiVisibili()
            val i = Bersaglio.trova(listOf(chiave), nodi.map { service.info(it) })
            return nodi.getOrNull(i)
        }
        if (command.has("x") && command.has("y")) return service.nodoAlPunto(command.optInt("x"), command.optInt("y"))
        return null
    }

    private fun tocca(id: String, service: JarvisAccessibilityService, command: JSONObject, lungo: Boolean) {
        val azione = if (lungo) "pressione_lunga" else "tocca"
        val nodo = bersaglio(service, command)
        if (nodo == null) {
            if (lungo && command.has("x")) {
                val x = command.optDouble("x").toFloat(); val y = command.optDouble("y").toFloat()
                esito(id, azione, service.gesto(x, y, x, y, 900), "La pressione lunga non è partita.")
                return
            }
            reply(id, error = "Elemento non trovato (n=${command.optInt("n", 0)}, testo=\"${command.optString("testo")}\"). Rileggi lo schermo: i numeri cambiano a ogni lettura.")
            return
        }
        if (!lungo) {
            cancelloRifiuta(service, service.catenaBersaglio(nodo))?.let {
                segna(service.pacchettoAttivo(), azione, "invio", "bloccato")
                reply(id, error = it)
                return
            }
        }
        val etichetta = service.etichetta(nodo).ifEmpty { nodo.viewIdResourceName ?: "" }.take(40)
        val ok = service.clicca(nodo, lungo)
        segna(service.pacchettoAttivo(), azione, etichetta, if (ok) "ok" else "no")
        if (ok) reply(id, result = "toccato: $etichetta") else reply(id, error = "Il tocco su «$etichetta» non è andato a segno.")
    }

    private fun scrivi(id: String, service: JarvisAccessibilityService, command: JSONObject) {
        val testo = command.optString("text").ifEmpty { command.optString("testo") }
        val sensibile = command.optBoolean("sensibile")
        val nodo = if (command.has("n") || command.has("chiave") || command.has("campo")) {
            if (command.has("campo")) {
                val nodi = service.nodiVisibili()
                nodi.getOrNull(Bersaglio.trova(listOf(command.optString("campo")), nodi.map { service.info(it) }))
            } else bersaglio(service, command)
        } else null
        val ok = service.scrivi(nodo, testo, command.optBoolean("aggiungi"))
        segna(service.pacchettoAttivo(), "scrivi", if (sensibile) "[testo nascosto]" else "${testo.length} caratteri", if (ok) "ok" else "no")
        if (ok) reply(id, result = true) else reply(id, error = failureMessage("type_text"))
    }

    private fun invioTastiera(id: String, service: JarvisAccessibilityService) {
        // Nelle app di messaggi «Invio» della tastiera può spedire: vale la regola d'invio.
        val pkg = service.pacchettoAttivo()
        val messaggi = ProfiliApp.di(pkg)?.invio?.isNotEmpty() == true && pkg !in ParoleInvio.SENZA_CONFERMA
        if (messaggi && !cancello.consumaPermesso()) {
            reply(id, error = "BLOCCATO DAL TELEFONO: in questa app l'invio della tastiera può spedire il messaggio. Usa invia_bozza.")
            return
        }
        esito(id, "invio_tastiera", service.invioTastiera(), "La tastiera non ha accettato l'invio (serve Android 11 e un campo selezionato).")
    }

    // ================================================================ attese

    /** Ripete [prova] ogni [passoMs] finché torna true o scade [maxMs]; poi [fine] con l'esito. */
    private fun ripeti(maxMs: Long, passoMs: Long = 250, prova: () -> Boolean, fine: (Boolean) -> Unit) {
        val inizio = SystemClock.elapsedRealtime()
        val giro = object : Runnable {
            override fun run() {
                val ok = runCatching(prova).getOrDefault(false)
                if (ok) { fine(true); return }
                if (SystemClock.elapsedRealtime() - inizio >= maxMs) { fine(false); return }
                principale.postDelayed(this, passoMs)
            }
        }
        principale.post(giro)
    }

    private fun attendi(id: String, service: JarvisAccessibilityService, command: JSONObject) {
        val ms = command.optLong("ms", 2000).coerceIn(100, 15_000)
        val testo = command.optString("testo")
        if (testo.isEmpty()) {
            principale.postDelayed({ reply(id, result = true) }, ms)
            return
        }
        ripeti(ms, 300, prova = {
            service.nodiVisibili().any { n -> Bersaglio.punteggio(testo, service.info(n)) > 0 }
        }) { trovato ->
            if (trovato) reply(id, result = "comparso: $testo") else reply(id, error = "Dopo ${ms / 1000} s «$testo» non è comparso.")
        }
    }

    /** Dopo un intent: aspetta che l'app sia davanti e restituisce lo schermo. */
    private fun dopoApertura(id: String, attese: List<String>, descrizione: String, attesaExtraMs: Long = 700, attendiPagina: Boolean = false) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            reply(id, result = JSONObject().put("aperto", true).put("descrizione", descrizione)
                .put("schermo", "(accessibilità spenta: non posso leggere lo schermo)"))
            return
        }
        val prima = service.pacchettoAttivo()
        ripeti(7000, 250, prova = {
            val p = service.pacchettoAttivo()
            if (attese.isNotEmpty()) p in attese else p != null && p != prima
        }) { arrivato ->
            // 0.5.0 (KO del 08/10: «cerca … e dimmelo», la VPS leggeva Google ancora «Elaborazione in corso…»):
            // per le pagine che si riempiono dopo, si aspetta che il testo «in corso» sparisca (al massimo 6 s).
            if (arrivato && attendiPagina) {
                principale.postDelayed({
                    ripeti(6000, 500, prova = { !ParoleInvio.paginaInCaricamento(service.nodiVisibili().mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }) }) { _ ->
                        val p = service.pacchettoAttivo()
                        segna(p, "apri", descrizione, "ok")
                        reply(id, result = JSONObject().put("aperto", true).put("app_in_primo_piano", p ?: "?")
                            .put("descrizione", descrizione).put("schermo", service.leggiSchermo()))
                    }
                }, attesaExtraMs)
                return@ripeti
            }
            principale.postDelayed({
                val p = service.pacchettoAttivo()
                segna(p, "apri", descrizione, if (arrivato) "ok" else "app non in primo piano")
                reply(id, result = JSONObject()
                    .put("aperto", arrivato)
                    .put("app_in_primo_piano", p ?: "?")
                    .put("descrizione", descrizione)
                    .put("schermo", service.leggiSchermo()))
            }, attesaExtraMs)
        }
    }

    // ================================================================ app e intent

    private fun apriPiano(id: String, context: Context, piano: Result<PianoIntent>, attesaExtraMs: Long = 700, attendiPagina: Boolean = false) {
        val p = piano.getOrElse { reply(id, error = it.message ?: "Richiesta non valida"); return }
        val usato = runCatching { AppTelefono.esegui(context, p) }.getOrElse {
            segna(null, "componi", p.descrizione, "errore: ${it.message}")
            reply(id, error = it.message ?: "Apertura non riuscita")
            return
        }
        // 0.5.0: sveglia e timer con SKIP_UI non portano l'Orologio davanti: fatto appena l'Orologio accetta l'intent.
        if (p.azione == ComponiIntent.SET_ALARM || p.azione == ComponiIntent.SET_TIMER) {
            segna(usato.ifEmpty { null }, "componi", p.descrizione, "ok")
            reply(id, result = JSONObject().put("fatto", true).put("descrizione", p.descrizione))
            return
        }
        dopoApertura(id, if (usato.isNotEmpty()) listOf(usato) else emptyList(), p.descrizione, attesaExtraMs, attendiPagina)
    }

    private fun apriApp(id: String, service: JarvisAccessibilityService, command: JSONObject) {
        val nome = command.optString("app").ifEmpty { command.optString("nome") }.ifEmpty { command.optString("pacchetto") }
        val ctx = appContext ?: return
        val app = AppTelefono.apri(ctx, nome)
        if (app == null) {
            segna(null, "apri_app", nome, "non trovata")
            reply(id, error = failureMessage("open_app", app = nome))
            return
        }
        // Il vecchio open_app rispondeva subito true: chi lo usa ancora non aspetta lo schermo.
        if (command.optString("action") == "open_app" && !command.optBoolean("leggi")) {
            segna(app.pacchetto, "apri_app", app.nome, "ok")
            reply(id, result = true)
            return
        }
        dopoApertura(id, listOf(app.pacchetto) + (PRIMO_PIANO_ANCHE[app.pacchetto] ?: emptyList()), "app ${app.nome}")
    }

    private fun elencaApp(id: String, command: JSONObject, context: Context) {
        if (command.optBoolean("chiedi_permesso") && !AppTelefono.usoPermesso(context)) AppTelefono.apriPermessoUso(context)
        val uso = AppTelefono.usoRecente(context)
        val filtro = command.optString("filtro")
        val tutte = AppTelefono.installate(context)
        val scelte = if (filtro.isNotBlank()) CatalogoApp.cerca(filtro, tutte, uso) else CatalogoApp.ordinaPerUso(tutte, uso)
        val max = command.optInt("max", 60).coerceIn(1, 400)
        val arr = JSONArray()
        scelte.take(max).forEach { arr.put(JSONObject().put("nome", it.nome).put("pacchetto", it.pacchetto).apply {
            uso[it.pacchetto]?.takeIf { u -> u > 0 }?.let { u -> put("ultimo_uso_ore_fa", (System.currentTimeMillis() - u) / 3_600_000L) }
            if (ProfiliApp.di(it.pacchetto) != null) put("profilo", true)
        }) }
        reply(id, result = JSONObject()
            .put("totale", tutte.size)
            .put("ordinate_per_uso", uso.isNotEmpty())
            .put("nota", if (uso.isEmpty()) "Senza «Accesso all'uso» l'ordine è alfabetico: elenca_app con chiedi_permesso=true apre la pagina per concederlo." else "")
            .put("app", arr))
    }

    private fun cercaContatto(id: String, command: JSONObject, context: Context) {
        if (!AppTelefono.rubricaPermessa(context)) {
            chiediPermesso(context, android.Manifest.permission.READ_CONTACTS)
            reply(id, error = "Manca il permesso della rubrica: l'ho chiesto sullo schermo del telefono. Boss tocca «Consenti», poi riprova.")
            return
        }
        val trovati = CercaContatti.cerca(command.optString("nome"), AppTelefono.contatti(context)).take(5)
        val arr = JSONArray()
        trovati.forEach { c -> arr.put(JSONObject().put("nome", c.nome).put("numeri", JSONArray(c.numeri)).put("mail", JSONArray(c.mail))) }
        reply(id, result = JSONObject().put("contatti", arr).put("trovati", trovati.size))
    }

    private fun chiediPermesso(context: Context, permesso: String) {
        runCatching {
            context.startActivity(
                Intent(context, RichiestaPermessoActivity::class.java)
                    .putExtra(RichiestaPermessoActivity.EXTRA_PERMESSO, permesso)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Il nome detto da Boss → il numero o la mail dalla rubrica. Null se non c'è o è ambiguo (in [errore]). */
    private fun dallaRubrica(context: Context, nome: String, mail: Boolean, errore: (String) -> Unit): String? {
        if (!AppTelefono.rubricaPermessa(context)) {
            chiediPermesso(context, android.Manifest.permission.READ_CONTACTS)
            errore("Manca il permesso della rubrica: l'ho chiesto sullo schermo. Boss tocca «Consenti», poi riprova (o dammi il numero).")
            return null
        }
        val trovati = CercaContatti.cerca(nome, AppTelefono.contatti(context))
            .filter { if (mail) it.mail.isNotEmpty() else it.numeri.isNotEmpty() }
        if (trovati.isEmpty()) { errore("Nessun contatto «$nome» in rubrica con ${if (mail) "una mail" else "un numero"}."); return null }
        val primo = trovati.first()
        val voci = if (mail) primo.mail else primo.numeri
        val esatto = CercaContatti.punteggio(nome, primo.nome) >= 85
        if ((trovati.size > 1 && !esatto) || voci.size > 1) {
            errore("Più corrispondenze per «$nome»: " + trovati.take(4).joinToString("; ") {
                it.nome + " " + (if (mail) it.mail else it.numeri).joinToString(", ")
            } + ". Chiedi a Boss quale, poi ripeti con il numero o la mail.")
            return null
        }
        return voci.first()
    }

    private fun componi(id: String, command: JSONObject, context: Context) {
        val tipo = command.optString("tipo").lowercase()
        val testo = command.optString("testo")
        val nome = command.optString("nome")
        var errore: String? = null
        fun numero(): String? = command.optString("numero").ifEmpty { null }
            ?: nome.takeIf { it.isNotEmpty() }?.let { dallaRubrica(context, it, mail = false) { e -> errore = e } }

        val piano: Result<PianoIntent> = runCatching {
            when (tipo) {
                "whatsapp" -> ComponiIntent.whatsapp(numero() ?: throw IllegalArgumentException(errore ?: "Serve numero o nome"), testo, command.optBoolean("business"))
                "sms" -> ComponiIntent.sms(numero() ?: throw IllegalArgumentException(errore ?: "Serve numero o nome"), testo)
                "chiama", "telefono" -> ComponiIntent.chiama(numero() ?: throw IllegalArgumentException(errore ?: "Serve numero o nome"))
                "mail", "email" -> {
                    val a = command.optJSONArray("a")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                        ?: command.optString("a").split(',', ';').filter { it.isNotBlank() }
                    val destinatari = a.ifEmpty {
                        listOfNotNull(nome.takeIf { it.isNotEmpty() }?.let { dallaRubrica(context, it, mail = true) { e -> errore = e } })
                    }
                    if (destinatari.isEmpty()) throw IllegalArgumentException(errore ?: "Serve l'indirizzo («a») o il nome in rubrica")
                    val cc = command.optString("cc").split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                    ComponiIntent.mail(
                        destinatari, command.optString("oggetto"), command.optString("corpo").ifEmpty { testo }, cc,
                        AppTelefono.pacchettoPosta(context, command.optString("app").ifEmpty { null }),
                    )
                }
                "telegram" -> ComponiIntent.telegram(command.optString("utente").ifEmpty { null }, testo)
                "link", "url" -> ComponiIntent.link(command.optString("url"))
                "mappe", "maps", "naviga" -> ComponiIntent.mappe(command.optString("dove"), tipo == "naviga" || command.optBoolean("naviga"))
                "calendario", "evento" -> ComponiIntent.calendario(
                    command.optString("titolo"), command.optString("inizio"), command.optString("fine").ifEmpty { null },
                    command.optString("luogo"), command.optString("note"),
                )
                "google", "cerca" -> ComponiIntent.cercaGoogle(command.optString("domanda").ifEmpty { testo })
                "youtube" -> ComponiIntent.youtube(command.optString("domanda").ifEmpty { testo })
                "sveglia" -> ComponiIntent.sveglia(command.optInt("ora", -1), command.optInt("minuti", 0), command.optString("etichetta", "JBoss"))
                "timer" -> ComponiIntent.timer(command.optInt("secondi", 0), command.optString("etichetta", "JBoss"))
                else -> throw IllegalArgumentException("Tipo sconosciuto: «$tipo» (whatsapp, mail, sms, chiama, telegram, link, mappe, calendario, google, sveglia, timer)")
            }
        }
        apriPiano(id, context, piano, attesaExtraMs = if (tipo == "whatsapp" || tipo == "mail" || tipo == "email") 1200 else 700)
    }

    /** Apre la ricerca dentro un'app (con il suo profilo, o il primo campo), scrive e cerca. */
    private fun cercaInApp(id: String, service: JarvisAccessibilityService, command: JSONObject, context: Context) {
        val testo = command.optString("testo")
        val nomeApp = command.optString("app")
        var tornate = 0
        fun cerca() {
            val pkg = service.pacchettoAttivo()
            val profilo = ProfiliApp.di(pkg)
            val nodi = service.nodiVisibili()
            val infos = nodi.map { service.info(it) }
            // Mai il campo di un messaggio: in una chat aperta «cerca» scriveva nella bozza (provato
            // il 2026-10-07 su WhatsApp). Se c'è solo quello, si torna indietro alla lista.
            fun composer(n: AccessibilityNodeInfo) = ParoleInvio.campoMessaggio(n.viewIdResourceName)
            // 0.1.1: dentro una chat (c'è il campo del messaggio) si torna alla lista PRIMA di tutto,
            // anche se un altro campo ha il fuoco: il 2026-10-07 la ricerca finiva nella barra
            // «cerca emoji e sticker» della tastiera di WhatsApp (#search_bar) e la chat non si trovava.
            if (profilo != null && nodi.any { it.isEditable && composer(it) } && tornate < 3) {
                tornate++
                service.pressKey("BACK")
                principale.postDelayed({ cerca() }, 900)
                return
            }
            val giaCampo = nodi.firstOrNull { it.isEditable && it.isFocused && !composer(it) }
            if (giaCampo == null && profilo != null) {
                nodi.getOrNull(Bersaglio.trova(profilo.ricerca, infos))?.let { service.clicca(it) }
            }
            fun campoRicerca(): AccessibilityNodeInfo? {
                val nodi2 = service.nodiVisibili().filter { it.isEditable && !composer(it) }
                return nodi2.firstOrNull { it.isFocused }
                    ?: profilo?.let { p -> nodi2.getOrNull(Bersaglio.trova(p.campo, nodi2.map { service.info(it) })) }
                    ?: nodi2.firstOrNull()
            }
            // Il campo può metterci un po' a comparire dopo il tocco sulla lente: fino a 4 secondi.
            var campo: AccessibilityNodeInfo? = null
            // Alcune app aprono la ricerca in due tocchi (Spotify: scheda Cerca, poi la barra;
            // X: Esplora, poi Cerca): se il campo non arriva si ritocca la prossima chiave del profilo.
            var giri = 0
            ripeti(6000, 300, prova = {
                campo = campoRicerca()
                giri++
                if (campo == null && profilo != null && giri % 5 == 0) {
                    val n3 = service.nodiVisibili()
                    n3.getOrNull(Bersaglio.trova(profilo.ricerca, n3.map { service.info(it) }))?.let { service.clicca(it) }
                }
                campo != null
            }) { trovato ->
                val c = campo
                if (!trovato || c == null) {
                    reply(id, error = "Non trovo un campo di ricerca in ${pkg ?: "questa app"}. Schermo:\n" + service.leggiSchermo())
                    return@ripeti
                }
                val campo = c
                service.scrivi(campo, testo, aggiungi = false)
                if (command.optBoolean("invio", true)) principale.postDelayed({ service.invioTastiera() }, 300)
                principale.postDelayed({
                    segna(pkg, "cerca_in_app", "${testo.length} caratteri", "ok")
                    reply(id, result = service.leggiSchermo())
                }, 1800)
            }
        }
        if (nomeApp.isEmpty()) { cerca(); return }
        val app = AppTelefono.apri(context, nomeApp) ?: run { reply(id, error = failureMessage("open_app", app = nomeApp)); return }
        ripeti(7000, 250, prova = { service.pacchettoAttivo() == app.pacchetto }) { principale.postDelayed({ cerca() }, 900) }
    }

    /** Apre Gemini, scrive la domanda, la manda e aspetta che la risposta smetta di crescere. */
    private fun geminiChiedi(id: String, service: JarvisAccessibilityService, command: JSONObject, context: Context) {
        val domanda = command.optString("domanda")
        if (domanda.isBlank()) { reply(id, error = "Domanda vuota"); return }
        val pkg = listOf(ComponiIntent.GEMINI).firstOrNull { AppTelefono.installata(context, it) }
            ?: run { reply(id, error = "L'app Gemini (com.google.android.apps.bard) non è installata."); return }
        val lancio = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: run { reply(id, error = "Gemini non si apre dal launcher."); return }
        context.startActivity(lancio)
        val profilo = ProfiliApp.di(ComponiIntent.GEMINI)
        val maxMs = command.optLong("attesa_s", 45).coerceIn(5, 120) * 1000
        // Su alcuni telefoni Gemini gira dentro l'app Google (provato su Samsung il 2026-10-07).
        val pacchettiGemini = setOf(ComponiIntent.GEMINI, ComponiIntent.GOOGLE_APP)
        ripeti(8000, 300, prova = { service.pacchettoAttivo() in pacchettiGemini }) { arrivato ->
            if (!arrivato) { reply(id, error = "Gemini non è arrivata in primo piano."); return@ripeti }
            principale.postDelayed({
                var nodi = service.nodiVisibili()
                var campo = nodi.firstOrNull { it.isEditable }
                if (campo == null && profilo != null) {
                    nodi.getOrNull(Bersaglio.trova(profilo.campo, nodi.map { service.info(it) }))?.let { service.clicca(it) }
                    nodi = service.nodiVisibili()
                    campo = nodi.firstOrNull { it.isEditable }
                }
                if (campo == null || !service.scrivi(campo, domanda, aggiungi = false)) {
                    reply(id, error = "Non trovo il campo dove scrivere a Gemini. Schermo:\n" + service.leggiSchermo())
                    return@postDelayed
                }
                principale.postDelayed({
                    val n2 = service.nodiVisibili()
                    val invio = n2.getOrNull(Bersaglio.trova(profilo?.invio ?: listOf("Invia", "Send"), n2.map { service.info(it) }))
                    val mandato = (invio?.let { service.clicca(it) } ?: false) || service.invioTastiera()
                    if (!mandato) { reply(id, error = "Non riesco a premere invio in Gemini. Schermo:\n" + service.leggiSchermo()); return@postDelayed }
                    segna(pkg, "gemini_chiedi", "${domanda.length} caratteri", "inviata")
                    // La risposta è pronta quando il testo a schermo resta uguale per due giri.
                    var ultimo = ""
                    var uguali = 0
                    val inizio = SystemClock.elapsedRealtime()
                    ripeti(maxMs, 2000, prova = {
                        val ora = service.nodiVisibili().mapNotNull { it.text?.toString() }.joinToString("\n")
                        if (ora == ultimo && SystemClock.elapsedRealtime() - inizio > 5000) uguali++ else uguali = 0
                        ultimo = ora
                        uguali >= 2
                    }) { _ -> reply(id, result = service.leggiSchermo()) }
                }, 500)
            }, 1200)
        }
    }

    // ================================================================ invio con conferma

    /**
     * La bozza è già nell'app vera (dopo componi o scrivi). Il telefono trova «Invia», mostra
     * il pannello e aspetta Boss. Solo dopo il suo sì preme Invia, lui stesso.
     */
    private fun inviaBozza(id: String, service: JarvisAccessibilityService, command: JSONObject) {
        val pkg = service.pacchettoAttivo()
        val invio = trovaInvio(service, command.optString("pulsante"))
        if (invio == null) {
            segna(pkg, "invia_bozza", "", "pulsante non trovato")
            reply(id, error = "Non trovo il pulsante Invia sullo schermo. Schermo:\n" + service.leggiSchermo())
            return
        }
        // 0.7.0 (Boss 08/10): ordini e acquisti su tutte le app, senza tetto; JBoss fa tutto e Boss conferma alla fine
        // con un tocco, dopo il riepilogo dettagliato letto dallo schermo (totale, articoli, indirizzo, pagamento).
        val acquisto = RegoleConferma.pulsanteDiAcquisto(service.info(invio))
        val app = command.optString("app").ifEmpty { pkg ?: "app" }
        val r = CancelloInvio.Richiesta(
            id, app, command.optString("destinatario"),
            if (acquisto) RegoleConferma.riepilogoAcquisto(service.testiDelloSchermo(), command.optString("bozza")) else command.optString("bozza"),
            pkg, toccaDaSolo = true, creataMs = System.currentTimeMillis(), acquisto = acquisto,
        )
        if (acquisto) segna(pkg, "invia_bozza", "pagamento", "riepilogo a Boss, aspetta il tocco")
        proponi(r, service.testiNeiCampi(), command.optString("pulsante"))
    }

    private fun trovaInvio(service: JarvisAccessibilityService, pulsante: String?): AccessibilityNodeInfo? {
        val nodi = service.nodiVisibili()
        val infos = nodi.map { service.info(it) }
        val profilo = ProfiliApp.di(service.pacchettoAttivo())
        var i = if (!pulsante.isNullOrBlank()) Bersaglio.trova(listOf(pulsante), infos) else -1
        if (i < 0 && profilo != null) i = Bersaglio.trova(profilo.invio, infos)
        if (i < 0) i = ParoleInvio.trovaPulsante(infos, pulsante)
        return nodi.getOrNull(i)
    }

    private fun proponi(r: CancelloInvio.Richiesta, testiCampi: List<String>, pulsante: String?) {
        cancello.proponi(r)?.let { vecchia ->
            reply(vecchia.id, error = "Sostituita da una nuova bozza: questa NON è stata inviata.")
        }
        segna(r.pacchetto, "bozza in attesa", "${r.app} → ${r.destinatario}", "attende Boss")
        // 0.1.1: l'istante del pannello nel logcat (per misurare frase → pannello). Mai testo né nomi.
        Log.i(TAG_PROVA, "pannello: bozza in attesa su ${r.app} (${r.bozza.length} caratteri)")
        val chiamata = r.app == "Telefono"
        val titolo = if (r.acquisto) "Pago su ${r.app}?" else if (chiamata) "Chiamo ${r.destinatario}?" else "Invio su ${r.app}" + if (r.destinatario.isNotBlank()) " a ${r.destinatario}" else ""
        val corpo = buildString {
            if (r.bozza.isNotBlank()) append(r.bozza)
            if (testiCampi.isNotEmpty() && testiCampi.none { it.trim() == r.bozza.trim() }) {
                if (isNotEmpty()) append("\n\n")
                append("Nel campo c'è: ").append(testiCampi.joinToString(" / ").take(400))
            }
        }.ifEmpty { "(bozza vuota)" }
        // 0.7.0: lo stesso sì anche nel box della chat di JBoss (Conferma / Annulla), sopra il campo.
        Comandi.registro.chiediConferma(
            RegistroComandi.Conferma("bozza:${r.id}", RegistroComandi.TipoConferma.BOZZA, titolo, corpo, if (r.acquisto) "Paga" else if (chiamata) "Chiama" else "Invia"),
        )
        JarvisAccessibilityService.instance?.pannello?.mostra(
            titolo, corpo, if (r.acquisto) "Paga" else "Invia",
            onSi = { risolvi(r.id, CancelloInvio.Esito.INVIA, "tocco su Invia") },
            onNo = { risolvi(r.id, CancelloInvio.Esito.ANNULLA, "tocco su Annulla") },
        )
        notificaConferma(r, corpo)
        JarvisService.instance?.statoBolla(
            TestiBolla.perStrumento("invia_bozza") { k -> when (k) { "app" -> r.app; "destinatario" -> r.destinatario; else -> "" } }
                ?: TestiBolla.ASPETTO_INVIA
        )
        JarvisService.instance?.chiediConferma(
            // 1.2.2: niente «invia» nella domanda. «Dico invia?» rientrava nel microfono aperto subito
            // dopo e Whisper lo trascriveva «In via.» (07/10 16:32, somiglianza 0,25: non era Boss):
            // con una parola di conferma nella domanda Jarvis rischiava di confermarsi da solo.
            "Bozza pronta" + (if (r.destinatario.isNotBlank()) " per ${r.destinatario}" else "") + " su ${r.app}. Aspetto la tua risposta."
        )
        val scadenza = Runnable {
            cancello.chiudi(r.id)?.let {
                chiudiInterfaccia()
                segna(r.pacchetto, "bozza", r.app, "scaduta")
                JarvisService.instance?.confermaScaduta()
                reply(r.id, error = "Boss non ha confermato entro 2 minuti: niente è stato inviato. " +
                    "Gliel'ho già detto a voce: rispondi in una frase breve, senza riproporre la bozza.")
            }
        }
        scadenzaSospesa?.let { principale.removeCallbacks(it) }
        scadenzaSospesa = scadenza
        principale.postDelayed(scadenza, confirmTimeoutMs)
    }

    /**
     * Quello che Boss ha detto (a voce nell'app o scritto nel sito) mentre una bozza aspetta.
     * true = consumato qui (non va mandato alla VPS come messaggio nuovo).
     */
    fun rispostaDiBoss(testo: String): Boolean {
        val r = cancello.sospesa() ?: return false
        val esito = CancelloInvio.interpreta(testo)
        // 0.7.0: un pagamento non parte a voce (una voce o un audio qualunque potrebbe dire «invia»): serve il tocco.
        if (r.acquisto && esito == CancelloInvio.Esito.INVIA) {
            segna(r.pacchetto, "conferma", "a voce", "pagamento: serve il tocco")
            JarvisService.instance?.statoBolla(TestiBolla.Riga("Per pagare tocca Paga", "a voce non pago", TestiBolla.Tono.ATTESA))
            return true
        }
        risolvi(r.id, esito, testo)
        return true
    }

    private fun risolvi(id: String, esito: CancelloInvio.Esito, parole: String) {
        val r = cancello.chiudi(id) ?: return
        chiudiInterfaccia()
        Log.i(TAG_PROVA, "pannello: $esito su ${r.app}")
        when (esito) {
            CancelloInvio.Esito.INVIA -> {
                segna(r.pacchetto, "conferma", parole.take(40), "sì")
                JarvisService.instance?.statoBolla(TestiBolla.invio("invia"))
                if (r.toccaDaSolo) eseguiInvio(r) else {
                    cancello.concediPermesso()
                    reply(r.id, result = "confermato da Boss: ora tocca Invia (un tocco, entro 60 secondi)")
                }
            }
            CancelloInvio.Esito.ANNULLA -> {
                segna(r.pacchetto, "conferma", parole.take(40), "annullato")
                annullaOperazione(parole.take(40), r)
            }
            CancelloInvio.Esito.ALTRO -> {
                segna(r.pacchetto, "conferma", "altra frase", "non inviato")
                JarvisService.instance?.statoBolla(TestiBolla.invio("altro"))
                reply(r.id, error = "Boss NON ha confermato l'invio. Ha detto: «$parole». Niente è stato inviato: " +
                    "fai quello che chiede (correggi la bozza nell'app) e poi richiama invia_bozza.")
            }
        }
    }

    private fun eseguiInvio(r: CancelloInvio.Richiesta) {
        val service = JarvisAccessibilityService.instance ?: run { reply(r.id, error = MSG_SERVIZIO_SPENTO); return }
        val ctx = appContext
        if (ctx != null && Prefs.isManiFerme(ctx)) { reply(r.id, error = MSG_MANI_FERME); return }
        val ora = service.pacchettoAttivo()
        if (r.pacchetto != null && ora != r.pacchetto) {
            reply(r.id, error = "L'app in primo piano è cambiata ($ora invece di ${r.pacchetto}): non ho inviato. Riapri la bozza e riprova.")
            return
        }
        val nodo = trovaInvio(service, null) ?: run {
            reply(r.id, error = "Il pulsante Invia non c'è più sullo schermo: non ho inviato.")
            return
        }
        val campiPrima = service.testiNeiCampi()
        val ok = service.clicca(nodo)
        if (!ok) {
            segna(ora, "invio", r.app, "tocco fallito")
            JarvisService.instance?.statoBolla(TestiBolla.Riga("Errore: il tocco su Invia non è andato", "non ho inviato", TestiBolla.Tono.ERRORE))
            JarvisService.instance?.segnale(Suoni.Tipo.ERRORE)
            reply(r.id, error = "Il tocco su Invia non è andato a segno.")
            return
        }
        principale.postDelayed({
            val verifica = CancelloInvio.verificaInvio(
                appCambiata = service.pacchettoAttivo() != ora,
                campiPrima = campiPrima, campiDopo = service.testiNeiCampi(), bozza = r.bozza,
            )
            segna(ora, "invio", "${r.app} → ${r.destinatario}", "inviato ($verifica)")
            JarvisService.instance?.statoBolla(TestiBolla.Riga("Inviato", TestiBolla.accorcia("${r.app} · $verifica"), TestiBolla.Tono.FATTO))
            reply(r.id, result = JSONObject().put("inviato", true).put("verifica", verifica))
        }, 1500)
    }

    private fun chiudiInterfaccia() {
        scadenzaSospesa?.let { principale.removeCallbacks(it) }
        scadenzaSospesa = null
        JarvisAccessibilityService.instance?.pannello?.nascondi()
        notificaSospesa?.let { n -> appContext?.getSystemService(NotificationManager::class.java)?.cancel(n) }
        notificaSospesa = null
        // 0.7.0: il box Conferma/Annulla della chat sparisce con il pannello.
        Comandi.registro.confermaInAttesa()?.takeIf { it.tipo == RegistroComandi.TipoConferma.BOZZA }?.let { Comandi.registro.confermaChiusa(it.chiave) }
    }

    /**
     * 0.7.0: Conferma o Annulla toccato nel box della chat di JBoss. Fa quello che fanno i pulsanti del pannello sopra
     * l'app. false = nessuna bozza in attesa (già scaduta o chiusa altrove).
     */
    fun confermaDaChat(si: Boolean): Boolean {
        val r = cancello.sospesa() ?: return false
        risolvi(r.id, if (si) CancelloInvio.Esito.INVIA else CancelloInvio.Esito.ANNULLA, if (si) "Conferma in chat" else "Annulla in chat")
        return true
    }

    /**
     * «Annulla» (pannello, notifica, a voce) e, per compatibilità, il riquadro «Ferma Jarvis»
     * della tendina e lo strumento `emergenza` della VPS: toglie la bozza in attesa e rifiuta le
     * azioni di questa richiesta per al massimo 2 minuti, o finché la richiesta finisce (risposta
     * di Jarvis) o Boss chiede altro ([nuovaRichiesta]). Jarvis resta acceso e in ascolto.
     * [giaChiusa]: la bozza che [risolvi] ha già tolto dal cancello.
     */
    fun annullaOperazione(da: String, giaChiusa: CancelloInvio.Richiesta? = null) {
        val r = cancello.annullaRichiesta(ANNULLATA_MS) ?: giaChiusa
        r?.let { reply(it.id, error = MSG_ANNULLATA) }
        chiudiInterfaccia()
        segna(r?.pacchetto, "annulla", da, "niente inviato, azioni di questa richiesta rifiutate, JBoss resta attivo")
        JarvisService.instance?.operazioneAnnullata()
    }

    /** Nome di prima (riquadro «Ferma Jarvis», VPS): è [annullaOperazione]. */
    fun fermaOperazione(da: String) = annullaOperazione(da)

    /** Boss ha chiesto una cosa nuova, o la richiesta annullata è finita: le azioni ripartono. */
    fun nuovaRichiesta() {
        cancello.nuovaRichiesta()
    }

    /** Compatibilità (banco ADB, codice vecchio): fermare = [fermaOperazione]; niente stato permanente. */
    fun impostaEmergenza(context: Context, ferme: Boolean, da: String) {
        Prefs.setManiFerme(context, false)
        if (ferme) fermaOperazione(da) else nuovaRichiesta()
        runCatching { TileService.requestListeningState(context, ComponentName(context, InterruttoreTile::class.java)) }
    }

    // ================================================================ banco tecnico

    private fun rispondiSulModoTecnico(id: String, action: String, command: JSONObject) {
        val context = appContext
        if (context == null) {
            reply(id, error = "Il servizio di JBoss non è avviato su questo telefono.")
            return
        }
        if (action == "modo_tecnico" && command.has("acceso")) {
            val acceso = command.optBoolean("acceso")
            if (acceso) Prefs.setDebug(context, true) else Debug.spegni(context)
        }
        val stato = JSONObject()
            .put("modo_tecnico", Debug.acceso(context))
            .put("versione", versioneApp(context))
            .put("accessibilita", JarvisAccessibilityService.instance != null)
            .put("mani_ferme", Prefs.isManiFerme(context))
            .put("rubrica", AppTelefono.rubricaPermessa(context))
            .put("accesso_uso", AppTelefono.usoPermesso(context))
            .put("posta_predefinita", AppTelefono.postaPredefinita(context) ?: "nessuna (il telefono chiede)")
            .put("bozza_in_attesa", cancello.sospesa()?.app ?: "")
            .put("app_in_primo_piano", JarvisAccessibilityService.instance?.pacchettoAttivo() ?: "")
            .put("finestre", if (command.optBoolean("finestre")) JarvisAccessibilityService.instance?.finestre() ?: "" else "")
        reply(id, result = stato)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun versioneApp(context: Context): String = BuildConfig.VERSION_NAME

    /**
     * Cosa si dice a Claude quando un'azione sul telefono non va a segno.
     * Prende stringhe e non un JSONObject apposta: così si può provare senza Android.
     */
    internal fun failureMessage(action: String, app: String = "", tasto: String = ""): String = when (action) {
        "open_app" ->
            "Non ho trovato sul telefono un'app che corrisponde a \"$app\"."
        "tap" ->
            "Il tocco in quel punto dello schermo non è andato a segno."
        "type_text" ->
            "Non c'è nessun campo di testo selezionato in questo momento sullo schermo: niente da scrivere."
        "key" ->
            if (tasto.uppercase() in setOf("HOME", "BACK", "RECENTS", "NOTIFICHE", "IMPOSTAZIONI_RAPIDE"))
                "Il telefono ha rifiutato il tasto $tasto (su alcune marche succede, per esempio Samsung One UI) e non c'è una freccia «Indietro» da toccare: usa read_screen e tocca la X o la freccia dell'app."
            else "Tasto di sistema non riconosciuto: \"$tasto\" (valgono HOME, BACK, RECENTS, NOTIFICHE, IMPOSTAZIONI_RAPIDE)."
        else -> "Azione non riuscita sul telefono: $action"
    }

    // ================================================================ vecchio flusso e notifica

    /**
     * request_send_confirmation (vecchio flusso): Boss conferma, il telefono concede UN tocco
     * su Invia per 60 secondi. Resta per compatibilità; il nuovo è invia_bozza.
     */
    private fun askForConfirmation(callId: String, command: JSONObject) {
        val r = CancelloInvio.Richiesta(
            callId, command.optString("app"), command.optString("destinatario"), command.optString("bozza"),
            JarvisAccessibilityService.instance?.pacchettoAttivo(), toccaDaSolo = false, creataMs = System.currentTimeMillis(),
        )
        proponi(r, JarvisAccessibilityService.instance?.testiNeiCampi().orEmpty(), null)
    }

    private fun notificaConferma(r: CancelloInvio.Richiesta, corpo: String) {
        val context = appContext ?: return
        val notificationId = confirmNotificationId++
        fun intento(azione: String) = Intent(context, ConfirmActionReceiver::class.java).apply {
            action = azione
            putExtra(EXTRA_CALL_ID, r.id)
            putExtra("notification_id", notificationId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val si = PendingIntent.getBroadcast(context, notificationId * 3, intento(ACTION_CONFIRM), flags)
        val no = PendingIntent.getBroadcast(context, notificationId * 3 + 1, intento(ACTION_CANCEL), flags)
        val notification = NotificationCompat.Builder(context, CONFIRM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Invio su ${r.app}" + if (r.destinatario.isNotBlank()) " a ${r.destinatario}" else "")
            .setContentText(corpo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(corpo))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // 1.2.4: due scelte sole, le stesse del pannello (CancelloInvio.SCELTE).
            .addAction(0, "Invia", si)
            .addAction(0, "Annulla", no)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(notificationId, notification) }
        notificaSospesa = notificationId
    }

    /** Dalla notifica (ConfirmActionReceiver). */
    fun resolveConfirmation(callId: String, confirmed: Boolean) {
        risolvi(callId, if (confirmed) CancelloInvio.Esito.INVIA else CancelloInvio.Esito.ANNULLA, if (confirmed) "notifica: Invia" else "notifica: Annulla")
    }

    private fun reply(id: String, result: Any? = null, error: String? = null) {
        val payload = JSONObject().put("type", "tool_result").put("id", id)
        if (error != null) payload.put("error", error) else payload.put("result", result)
        if (id.startsWith("adb-")) {
            // Banco di prova: nel logcat, JSON su una riga a pezzi da 3000 (logcat taglia oltre i 4000): si ricuciono senza a capo.
            payload.toString().chunked(3000).forEach { android.util.Log.i(TAG_PROVA, it) }
            return
        }
        sender?.invoke(payload.toString())
    }

    private fun createConfirmChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CONFIRM_CHANNEL_ID, "Conferme di invio", NotificationManager.IMPORTANCE_HIGH
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

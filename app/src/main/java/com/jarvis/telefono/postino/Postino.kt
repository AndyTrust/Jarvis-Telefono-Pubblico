package com.jarvis.telefono.postino

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.telefono.JarvisService
import com.jarvis.telefono.nucleo.Comandi
import com.jarvis.telefono.nucleo.Cronologia
import com.jarvis.telefono.nucleo.RegistroComandi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * L'aggancio Android della posta unica ([PostaCondivisa], Boss 09/10): un canale per l'app, la pagina del Postino e la
 * chat di JBoss sullo stesso stato. Qui stanno solo le cose di Android: il canale del modulo VPS, il filo di JBoss
 * (cronologia e voce), il box del sì ([RegistroComandi]), la pillola della Home ([ConteggioPostino]).
 */
object Postino {
    private const val TAG = "JarvisPostino"
    /** Gli scambi della posta nel filo: agente «postino», così il chip Postino della Home li trova. */
    const val AGENTE = "postino"
    /** L'esito di una riga d'avviso: apre uno scambio suo nel filo ([com.jarvis.telefono.nucleo.FiltroCronologia]). */
    const val AVVISO = com.jarvis.telefono.nucleo.FiltroCronologia.AVVISO

    private val principale = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    @Volatile private var hub: PostaCondivisa? = null
    private var demo: PostaCondivisa? = null
    private var ultimoConteggio: Pair<String, Int>? = null
    /**
     * 09/10: chi ha la conversazione, JBoss o il Postino («passa al Postino», «torna a JBoss»). Con la pagina del
     * Postino davanti la finestra dei 15 minuti non scade.
     */
    val passaggio = PassaggioPostino(paginaDavanti = { com.jarvis.telefono.bolla.PrimoPiano.paginaPostino })
    /** Chi guarda il passaggio (la chat di JBoss ridisegna la riga di stato). */
    private val suPassaggio = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** Chi chiude la pagina del Postino quando Boss torna a JBoss (a voce, scritto nella chat o nella pagina). */
    private val suRitorno = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun osservaPassaggio(f: () -> Unit): () -> Unit { suPassaggio += f; return { suPassaggio -= f } }

    /** La pagina del Postino: [f] quando Boss torna a JBoss con una frase (la pagina si chiude e si va alla chat). */
    fun osservaRitorno(f: () -> Unit): () -> Unit { suRitorno += f; return { suRitorno -= f } }

    /** Il ritorno a JBoss dopo 15 minuti senza frasi: lo dice la riga in alto della chat di JBoss appena scade. */
    private val controllaScadenza = object : Runnable {
        override fun run() {
            val fra = passaggio.scadeFra()
            if (fra != null) { principale.postDelayed(this, fra); return }
            suPassaggio.forEach { runCatching { it() } }
        }
    }

    private fun pianificaScadenza() {
        principale.removeCallbacks(controllaScadenza)
        passaggio.scadeFra()?.let { principale.postDelayed(controllaScadenza, it) }
    }
    /** Lavoro o conferma della VPS → comando della chat di JBoss che aspetta la risposta. */
    private val comandi = mutableMapOf<String, Long>()

    private fun pianifica(ms: Long, f: () -> Unit) { principale.postDelayed({ f() }, ms) }

    /** La posta unica dell'app, con il canale del modulo VPS se è pronto (si riprova a ogni chiamata finché non lo è). */
    @Synchronized
    fun hub(c: Context): PostaCondivisa {
        app = c.applicationContext
        val h = hub ?: PostaCondivisa(pianifica = ::pianifica).also { h ->
            hub = h
            h.suEvento = { e -> principale.post { evento(e) } }
            h.osserva { conteggio(h) }
        }
        if (h.canale == null) {
            if (FornitoreCanale.fabbrica == null) com.jarvis.telefono.vps.CanalePostinoVps.registra()
            FornitoreCanale.fabbrica?.invoke(app)?.let { h.collegaCanale(it) }
        }
        return h
    }

    /** La prova con dati finti (extra «demo» della pagina): un'altra posta, mai mescolata con quella vera. */
    fun demo(): PostaCondivisa = demo ?: PostaCondivisa(pianifica = ::pianifica).also {
        demo = it
        it.collegaCanale(CanaleDemo { ms, f -> principale.postDelayed(f, ms) })
    }

    fun vistaAperta(c: Context, pagina: Boolean) = hub(c).vistaAperta(pagina)

    fun vistaChiusa(c: Context, pagina: Boolean) = hub(c).vistaChiusa(pagina)

    /**
     * Una frase di JBoss: se è posta la fa il Postino sul canale unico e torna la risposta; null = non è posta.
     * [comando]: la riga della chat di JBoss; resta aperta finché la VPS non risponde (lavoro o conferma).
     */
    fun perJBoss(c: Context, frase: String, comando: Long): PostaCondivisa.RispostaJBoss? {
        // Senza VPS configurata e accesa, «apri la posta» apre l'app di posta del telefono (regole), non il Postino.
        if (ComandiPostaJBoss.apreLaPosta(frase) &&
            !(com.jarvis.telefono.vps.ConfigVps.acceso(c) && com.jarvis.telefono.vps.ConfigVps.dati(c).completa)) return null
        val h = hub(c)
        val cmd = ComandiPostaJBoss.capisci(frase, h.inPosta()) ?: return null
        Log.i(TAG, "JBoss → Postino: ${cmd.javaClass.simpleName}")
        val r = h.perJBoss(cmd)
        (r.idLavoro ?: r.chiaveConferma)?.let { comandi[it] = comando }
        return r
    }

    /**
     * 09/10 (Boss): «passa al Postino» / «torna a JBoss» dette o scritte a JBoss. true = era un passaggio: la frase e la
     * riga «Ora parli con il Postino» sono nel filo e il comando è chiuso. false = non è un passaggio (o «JBoss» detto
     * mentre JBoss ha già la conversazione): la frase segue il giro normale.
     */
    fun passa(c: Context, frase: String, origine: String, comando: Long): Boolean {
        val verso = PassaggioPostino.capisci(frase) ?: return false
        val e = passaggio.passa(verso) ?: return false
        Log.i(TAG, "passaggio: ${if (e.conPostino) "al Postino" else "a JBoss"}")
        val ctx = c.applicationContext
        app = ctx
        if (e.conPostino) hub(ctx)   // il canale unico del Postino si prepara subito (si collega con la chat aperta)
        io.launch {
            runCatching {
                val cron = Cronologia.di(ctx)
                cron.aggiungi(Cronologia.BOSS, frase, esito = origine)
                cron.aggiungi(Cronologia.JARVIS, e.testo, if (e.conPostino) AGENTE else "regole", "ok", 0L, if (e.conPostino) AGENTE else "")
            }
        }
        Comandi.registro.chiudi(comando, "ok", false, e.testo)
        JarvisService.instance?.rispondi(e.testo, false, if (e.conPostino) AGENTE else null, false)
        suPassaggio.forEach { runCatching { it() } }
        // Boss 09/10: «passa al Postino» apre anche la sua pagina; «torna a JBoss» la chiude e riporta alla chat.
        if (e.apriPagina) apriPagina(ctx)
        if (e.chiudiPagina) suRitorno.forEach { runCatching { it() } }
        pianificaScadenza()
        return true
    }

    /** La pagina del Postino davanti (nuova o quella già aperta), con la conversazione già passata a lui. */
    private fun apriPagina(ctx: Context) {
        runCatching {
            ctx.startActivity(
                PostinoActivity.intento(ctx).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }.onFailure { Log.w(TAG, "pagina del Postino non aperta: ${it.javaClass.simpleName}") }
    }

    /** La pagina del Postino con «torna a JBoss»: la conversazione torna a JBoss e il filo lo dice. */
    fun tornaAJBoss(c: Context, frase: String) {
        val ctx = c.applicationContext
        app = ctx
        passaggio.torna()
        io.launch {
            runCatching {
                val cron = Cronologia.di(ctx)
                // «scritto»: la frase chiude la conversazione col Postino e si vede nel filo di JBoss, dove si torna
                cron.aggiungi(Cronologia.BOSS, frase, esito = "scritto")
                cron.aggiungi(Cronologia.JARVIS, PassaggioPostino.A_JBOSS, "regole", "ok", 0L, "")
            }
        }
        suPassaggio.forEach { runCatching { it() } }
        suRitorno.forEach { runCatching { it() } }
    }

    /** La pagina del Postino lasciata (onStop): i 15 minuti del ritorno a JBoss contano da adesso. */
    fun paginaLasciata() {
        passaggio.usato()
        pianificaScadenza()
    }

    /**
     * Con il Postino che ha la conversazione: ogni frase della chat di JBoss va a lui ([PostaCondivisa.perPostino]).
     * Torna la risposta, o una risposta «non capito» onesta (la frase non va al cervello della VPS).
     */
    fun perPostino(c: Context, frase: String, comando: Long): PostaCondivisa.RispostaJBoss {
        passaggio.usato()
        pianificaScadenza()
        val h = hub(c)
        // 09/10: detta con la pagina del Postino davanti, la frase e la risposta si vedono anche nella sua conversazione.
        val pagina = com.jarvis.telefono.bolla.PrimoPiano.paginaPostino
        val r = h.perPostino(frase, PostaCondivisa.Da.JBOSS, nellaPagina = pagina)
            ?: return PostaCondivisa.RispostaJBoss(NON_CAPITO, errore = true).also { if (pagina) h.dialogo(frase, it.dire, true) }
        Log.i(TAG, "Postino (conversazione passata): ${if (r.errore) "errore" else "ok"}")
        (r.idLavoro ?: r.chiaveConferma)?.let { comandi[it] = comando }
        return r
    }

    const val NON_CAPITO = "Il Postino non ha capito. Puoi dire «leggi le mail», «avanti», «cancella questa», «aggiorna la posta», " +
        "«cestina 3 e 5»; per tornare «torna a JBoss»."

    /** Il tocco su Conferma o Annulla di una cancellazione (filo di JBoss o pagina del Postino). */
    fun rispondiConferma(c: Context, chiave: String, si: Boolean): PostaCondivisa.Esito {
        val e = hub(c).rispondiConferma(chiave, si)
        Comandi.registro.confermaChiusa(chiave)
        val comando = comandi.remove(chiave)
        when (e) {
            is PostaCondivisa.Esito.Partito -> comando?.let { comandi[e.id] = it; Comandi.registro.lavoro(it, "Il Postino sposta la mail nel Cestino…") }
            PostaCondivisa.Esito.Annullato -> comando?.let { dire(it, "Annullato: non ho cancellato niente.", false, "annullato") }
            is PostaCondivisa.Esito.Errore -> comando?.let { dire(it, e.motivo, true, "errore") }
            is PostaCondivisa.Esito.DaConfermare -> {}
        }
        return e
    }

    /** Un lucchetto per «c'è già? allora scrivo»: due avvisi insieme (ponte e resoconto) non passano tutti e due. */
    private val avvisi = Any()

    /**
     * Un avviso del Postino nel filo di JBoss, come riga (niente popup dentro l'app).
     * 09/10 (verificatore: «Postino: 6 mail nuove» due volte, alle 14:06 e alle 14:07): lo stesso conteggio già scritto
     * negli ultimi 30 minuti non si riscrive, da qualunque strada arrivi (report del ponte, resoconto del telefono, app
     * riaperta). Si guarda il filo vero ([Cronologia]), così vale anche dopo un riavvio dell'app.
     * [unaVolta] = false per le righe che non sono avvisi delle mail (la mail letta nella pagina).
     */
    fun riga(c: Context, testo: String, unaVolta: Boolean = true) {
        val ctx = c.applicationContext
        io.launch {
            runCatching {
                val cron = Cronologia.di(ctx)
                synchronized(avvisi) {
                    if (unaVolta) {
                        val prima = cron.ultime(80).filter { it.esito == AVVISO && it.agenteEsecutore == AGENTE }.map { it.testo to it.quando }
                        if (PostaCondivisa.avvisoDoppione(testo, System.currentTimeMillis(), prima)) {
                            Log.i(TAG, "avviso del Postino già nel filo: non lo ripeto")
                            return@runCatching
                        }
                    }
                    cron.aggiungi(Cronologia.JARVIS, testo, AGENTE, AVVISO, 0L, AGENTE)
                }
            }
        }
    }

    private fun evento(e: PostaCondivisa.Evento) {
        if (!::app.isInitialized) return
        when (e) {
            is PostaCondivisa.Evento.FineJBoss -> {
                val comando = comandi.remove(e.idLavoro)
                if (comando != null) dire(comando, e.testo, e.errore, if (e.errore) "errore" else "ok")
                else rispondi(e.testo, e.errore, if (e.errore) "errore" else "ok")
            }
            is PostaCondivisa.Evento.NuoveMail -> riga(app, e.testo)
            is PostaCondivisa.Evento.Letta -> {
                // 09/10: la mail mostrata nella pagina va anche nel filo di JBoss; con la conversazione passata al Postino
                // (ascolto continuo) JBoss la dice anche a voce.
                riga(app, e.testo, unaVolta = false)
                if (passaggio.conPostino) JarvisService.instance?.rispondi(e.testo, false, AGENTE, false, vps = true)
            }
            is PostaCondivisa.Evento.Conferma -> {
                e.chiusa?.let { Comandi.registro.confermaChiusa(it) }
                e.richiesta?.let { r ->
                    Comandi.registro.chiediConferma(RegistroComandi.Conferma(r.chiave, RegistroComandi.TipoConferma.POSTA, r.titolo, r.corpo, r.etichettaSi))
                }
            }
        }
    }

    /** Chiude il comando di JBoss con la risposta: riga di stato, filo e voce. */
    private fun dire(comando: Long, testo: String, errore: Boolean, esito: String) {
        Comandi.registro.chiudi(comando, esito, errore, testo)
        rispondi(testo, errore, esito)
    }

    private fun rispondi(testo: String, errore: Boolean, esito: String) {
        io.launch { runCatching { Cronologia.di(app).aggiungi(Cronologia.JARVIS, testo, AGENTE, esito, 0L, AGENTE) } }
        JarvisService.instance?.rispondi(testo, errore, AGENTE, false, vps = true)
    }

    /**
     * La pillola del Postino in Home: IL numero della posta ([StatoPostino.numeroUnico], le mail da fare), lo stesso
     * della pagina e di JBoss. 09/10: si aggiorna a ogni cambio (una cancellazione lo fa scendere), non solo al resoconto.
     */
    private fun conteggio(h: PostaCondivisa) {
        val r = h.stato.reportId ?: return
        val n = h.stato.numeroUnico() ?: return
        if (ultimoConteggio == r to n) return
        ultimoConteggio = r to n
        ConteggioPostino.salva(app, n)
    }
}

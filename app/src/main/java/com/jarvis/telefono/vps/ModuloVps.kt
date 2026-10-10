package com.jarvis.telefono.vps

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.telefono.JarvisAccessibilityService
import com.jarvis.telefono.nucleo.Cronologia
import java.util.concurrent.atomic.AtomicInteger

/**
 * Il modulo VPS di Jarvis Telefono (0.3.0, 2026-10-07): i compiti lunghi girano sulla VPS, il telefono li
 * manda, segue i passi e i comandi, e decide le conferme. Spegnibile (Impostazioni → Modulo VPS): spento,
 * l'app lavora solo in locale e non apre nessun collegamento.
 *
 * Batteria e rete: il WebSocket si apre solo con il modulo acceso, con la rete, e solo se c'è qualcosa da
 * seguire (un lavoro aperto o la schermata dei lavori aperta). Finito l'ultimo lavoro si chiude dopo 30 s.
 * Mentre è aperto: un ping ogni 30 s; gli eventi sono testo (log tagliati a 4000 caratteri dalla VPS).
 *
 * Pezzi: [NucleoVps] (logica), [ClientVps] (OkHttp), [RegistroLavori] (SQLite), [NotificheVps],
 * [ModuloVpsUi] (punti di aggancio dell'interfaccia), [TerminaleVpsActivity] (pieno schermo).
 */
object ModuloVps {
    private const val TAG = "JarvisVps"
    private const val CHIUSURA_DOPO_MS = 30_000L

    @Volatile private var nucleo: NucleoVps? = null
    @Volatile private var client: ClientVps? = null
    private lateinit var app: Context
    private val uiAperte = AtomicInteger(0)
    private val principale = Handler(Looper.getMainLooper())
    private val chiusura = Runnable { client?.chiudiSeInutile() }

    /** 0.3.0: chi legge i messaggi grezzi (la chat Postino a numeri) e chi segue il collegamento. */
    private val grezzi = java.util.concurrent.CopyOnWriteArrayList<(org.json.JSONObject) -> Unit>()
    private val collegamenti = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** La chat Postino è in primo piano: i suoi lavori finiti non fanno notifica (Boss li vede già lì). */
    @Volatile var postinoVisibile = false

    /** Le schermate in primo piano che mostrano le conferme da sole (niente pannello di sistema allora). */
    private val confermeInSchermata = AtomicInteger(0)

    @Synchronized
    fun avvia(context: Context): NucleoVps {
        nucleo?.let { return it }
        app = context.applicationContext
        val registro = runCatching { RegistroLavori.di(app).also { it.pulisci() } }.getOrElse {
            Log.w(TAG, "registro lavori non disponibile (${it.javaClass.simpleName}): uso la memoria")
            ArchivioInMemoria()
        }
        val n = NucleoVps(registro)
        val c = ClientVps(
            url = { ConfigVps.dati(app).url },
            token = { ConfigVps.dati(app).token },
            nucleo = n,
            deveStareAperto = { deveStareAperto() },
            haRete = { haRete(app) },
            suMessaggio = { t ->
                val o = runCatching { org.json.JSONObject(t) }.getOrNull()
                if (o != null) principale.post { grezzi.forEach { f -> runCatching { f(o) } } }
            },
        )
        n.canale = c
        n.ascolta(object : NucleoVps.Ascoltatore {
            override fun conferma(c: ConfermaVps) {
                // 0.6.1: l'autonomia dell'agente decide qui. «Vai da solo» vale solo per il Ricercatore e solo per le
                // letture (Autonomie.confermaDaSola); tutto il resto arriva a Boss come sempre.
                val agente = runCatching { registro.lavoro(c.lavoroId)?.agente }.getOrNull()
                val autonomia = runCatching { com.jarvis.telefono.agenti.ArchivioAgenti.di(app).autonomiaDi(agente) }.getOrNull()
                if (com.jarvis.telefono.agenti.Autonomie.confermaDaSola(agente, autonomia, c.azione, c.motivo)) {
                    Log.i(TAG, "conferma ${c.azioneId}: data da sola (agente $agente, vai da solo, sola lettura)")
                    n.scegli(c.lavoroId, c.azioneId, ProtocolloVps.INVIA)
                    return
                }
                // 2026-10-10: una sola conferma. Il tocco su Invia/Elimina già fatto sul telefono per QUESTO lavoro vale
                // come il sì (postino/PreConferme.kt); se il testo non è quello visto, si chiede come sempre.
                when (com.jarvis.telefono.postino.PreConferme.verifica(c.lavoroId, c.azioneId, c.azione, c.destinatario, c.anteprima, c.motivo)) {
                    com.jarvis.telefono.postino.PreConferme.Esito.CONFERMATA_ORA -> {
                        Log.i(TAG, "conferma ${c.azioneId}: già data con il tocco sul telefono")
                        n.scegli(c.lavoroId, c.azioneId, ProtocolloVps.INVIA)
                        return
                    }
                    com.jarvis.telefono.postino.PreConferme.Esito.GIA_CONFERMATA -> return
                    com.jarvis.telefono.postino.PreConferme.Esito.DA_CONFERMARE -> {}
                }
                NotificheVps.conferma(app, c, n.collegato)
                // 0.7.0: lo stesso sì nel box della chat di JBoss.
                com.jarvis.telefono.nucleo.Comandi.registro.chiediConferma(
                    com.jarvis.telefono.nucleo.RegistroComandi.Conferma(
                        "vps:${c.lavoroId}:${c.azioneId}", com.jarvis.telefono.nucleo.RegistroComandi.TipoConferma.VPS,
                        c.titolo(), ModuloVpsUi.corpoConferma(c), "Invia", c.lavoroId, c.azioneId,
                    ),
                )
                // Con la chat di JBoss davanti il riquadro è già nel filo: niente secondo pannello sopra.
                if (confermeInSchermata.get() == 0 && !com.jarvis.telefono.bolla.PrimoPiano.chatJBoss) pannelloDiSistema(c)
            }

            override fun confermaChiusa(lavoroId: String, azioneId: String, scelta: String) {
                NotificheVps.togliConferma(app, lavoroId)
                com.jarvis.telefono.nucleo.Comandi.registro.confermaChiusa("vps:$lavoroId:$azioneId")
                principale.post { runCatching { JarvisAccessibilityService.instance?.pannello?.nascondi() } }
            }

            override fun collegamento(collegato: Boolean) {
                principale.post { collegamenti.forEach { f -> runCatching { f(collegato) } } }
            }

            override fun finito(l: LavoroLocale) {
                NotificheVps.togliConferma(app, l.id)
                // 09/10 (Boss): un avviso del Postino con l'app davanti non è una notifica a comparsa: resta la riga nel filo.
                val appDavanti = postinoVisibile || com.jarvis.telefono.bolla.PrimoPiano.app
                if (!com.jarvis.telefono.postino.PostaCondivisa.avvisoComeRiga(l.agente, appDavanti)) NotificheVps.fine(app, l)
                // I lavori della posta unica (pagina del Postino e JBoss) hanno già la loro riga nel filo (risposta di
                // JBoss, avviso delle nuove): niente «Finito: apri 3» per ogni comando. 09/10: riconosciuti anche senza
                // origine (job_lista, lavori di prima), se no il resoconto compariva due volte (LavoroLocale.dellaPostaUnica).
                if (!l.dellaPostaUnica) runCatching {
                    Cronologia.di(app).aggiungi(
                        Cronologia.JARVIS,
                        "[VPS · ${ModuloVpsUi.nomeAgente(l.agente)}] ${if (l.esito == "ok") "Finito" else if (l.esito == "annullato") "Annullato" else "Non riuscito"}: ${l.riassunto.orEmpty()}",
                        cervello = "vps", esito = l.esito ?: "",
                        ms = if (l.finito > l.creato) l.finito - l.creato else 0,
                        agenteEsecutore = agenteCronologia(l.agente),
                    )
                }
                programmaChiusura()
            }
        })
        nucleo = n
        client = c
        ascoltaRete()
        if (n.haLavoroAperto()) c.collega()
        return n
    }

    fun nucleo(context: Context): NucleoVps = avvia(context)

    fun registro(context: Context): ArchivioLavori = runCatching<ArchivioLavori> { RegistroLavori.di(context) }.getOrElse { ArchivioInMemoria() }

    fun acceso(context: Context): Boolean = ConfigVps.acceso(context)

    fun configurato(context: Context): Boolean = ConfigVps.dati(context).completa

    fun accendi(context: Context, si: Boolean) {
        ConfigVps.setAcceso(context, si)
        avvia(context)
        if (si) client?.riprovaSubito() else { client?.spegni(); ManiVps.chiudi() }
        Log.i(TAG, "modulo VPS ${if (si) "acceso" else "spento"}")
    }

    /** Una schermata dei lavori si apre: il collegamento serve. */
    fun uiAperta(context: Context) {
        avvia(context)
        uiAperte.incrementAndGet()
        principale.removeCallbacks(chiusura)
        client?.collega()
    }

    fun uiChiusa() {
        if (uiAperte.decrementAndGet() < 0) uiAperte.set(0)
        programmaChiusura()
    }

    /** La schermata in primo piano disegna lei le conferme (pannello sopra il log). */
    fun confermeMostrateDallaSchermata(si: Boolean) {
        if (si) confermeInSchermata.incrementAndGet() else if (confermeInSchermata.decrementAndGet() < 0) confermeInSchermata.set(0)
    }

    private fun programmaChiusura() {
        principale.removeCallbacks(chiusura)
        principale.postDelayed(chiusura, CHIUSURA_DOPO_MS)
    }

    private fun deveStareAperto(): Boolean {
        if (!::app.isInitialized) return false
        if (!ConfigVps.acceso(app) || !ConfigVps.dati(app).completa) return false
        return uiAperte.get() > 0 || nucleo?.haLavoroAperto() == true
    }

    fun collegato(): Boolean = nucleo?.collegato == true

    /** L'agente da mettere in cronologia (avatar): «generico» è JBoss stesso, cioè nessun esecutore. */
    fun agenteCronologia(agente: String): String = if (agente == "generico" || agente.isBlank()) "" else agente

    /** Modulo acceso e configurato (indirizzo e token presenti). */
    fun pronto(context: Context): Boolean = ConfigVps.acceso(context) && ConfigVps.dati(context).completa

    /** Il primo lavoro aperto (per il chip in Home), o null. */
    fun primoAperto(context: Context): LavoroLocale? =
        if (!ConfigVps.acceso(context)) null else runCatching { registro(context).aperti().maxByOrNull { it.creato } }.getOrNull()

    fun ascoltaGrezzi(f: (org.json.JSONObject) -> Unit): () -> Unit { grezzi += f; return { grezzi -= f } }
    fun ascoltaCollegamento(f: (Boolean) -> Unit): () -> Unit { collegamenti += f; return { collegamenti -= f } }

    /**
     * Un job_start già scritto (la chat Postino a numeri): parte solo se il socket è collegato; se parte, il lavoro
     * entra nel registro come gli altri. false = non collegato, non è partito niente.
     */
    fun mandaGrezzo(context: Context, jobStart: org.json.JSONObject): Boolean {
        val n = avvia(context)
        val c = client ?: return false
        if (!c.manda(jobStart.toString())) return false
        n.lavoroGiaMandato(jobStart.optString("id"), jobStart.optString("agente"), jobStart.optString("testo"),
            jobStart.optJSONObject("opzioni")?.optString("titolo").orEmpty().ifBlank { "Postino" },
            origine = com.jarvis.telefono.nucleo.FiltroCronologia.origineChat(jobStart.optString("agente").ifBlank { "postino" }))
        return true
    }

    /**
     * 0.3.1 (cassaforte): un messaggio account_* già scritto da ProtocolloAccount (lista, anteprima, salva, elimina).
     * Le risposte arrivano dai grezzi ([ascoltaGrezzi], ProtocolloAccount.leggi). false = non collegato, non è partito niente.
     * Il testo può contenere una password: non si logga.
     */
    fun mandaAccount(context: Context, messaggio: String): Boolean {
        if (!com.jarvis.telefono.cassaforte.ProtocolloAccount.puoPartire(ConfigVps.dati(context).url, messaggio)) {
            Log.w(TAG, "account: non parte, il collegamento non è cifrato (serve wss://)")
            return false
        }
        avvia(context)
        return client?.manda(messaggio) == true
    }

    /** job_segui per un lavoro (ripresa della chat Postino). */
    fun segui(id: String, ultimo: Int): Boolean = client?.manda(ProtocolloVps.jobSegui(id, ultimo)) == true

    /** In parole, per la barra della schermata. */
    fun statoTesto(context: Context): String = when {
        !ConfigVps.acceso(context) -> "modulo spento"
        !ConfigVps.dati(context).completa -> "manca la configurazione"
        client?.tokenRifiutato == true -> "token rifiutato dalla VPS"
        !haRete(context) -> "niente rete"
        collegato() -> "collegata"
        client?.aperto == true -> "mi collego…"
        else -> "a riposo"
    }

    /** Byte mandati e ricevuti dal modulo da quando l'app è aperta (misura indicativa). */
    fun traffico(): Pair<Long, Long> = (client?.byteMandati ?: 0L) to (client?.byteRicevuti ?: 0L)

    /**
     * [origine]: da dove parte (le parole dei comandi: «voce», «scritto», «chat-<agente>», «schermata-vps»). Decide
     * in quale chat si vede la scheda della delega. [titolo]: la richiesta come l'ha detta Boss (null = la prima riga).
     */
    fun mandaLavoro(context: Context, agente: String, testo: String, modello: String = "sonnet", origine: String = "", titolo: String? = null): String {
        val n = avvia(context)
        val id = n.nuovoLavoro(agente, testo, modello, titolo = titolo, origine = origine)
        Log.i(TAG, "lavoro $id mandato ($agente, ${testo.length} caratteri)")
        return id
    }

    fun scegli(context: Context, lavoroId: String, azioneId: String, scelta: String) {
        val partita = avvia(context).scegli(lavoroId, azioneId, scelta)
        Log.i(TAG, "conferma $azioneId: $scelta${if (partita) "" else " (in attesa del collegamento)"}")
        // Se non è partita la notifica resta: la scelta parte al collegamento, e se non arriva Boss la ritrova.
        if (partita) NotificheVps.togliConferma(context, lavoroId)
    }

    fun annulla(context: Context, id: String) {
        avvia(context).annulla(id)
    }

    /**
     * Il punto di aggancio per il nucleo (Nucleo.elabora): decide locale o VPS.
     * @return la frase da elaborare qui (senza «qui»), o null se l'ha presa il modulo VPS (mandata, in coda o rifiutata
     * con un messaggio onesto in cronologia).
     */
    /** 0.3.0: cosa dire a Boss quando [instrada] ha preso la frase (testo, errore). La bolla e la voce lo dicono. */
    @Volatile var ultimaRisposta: Pair<String, Boolean> = "" to false
        private set

    /**
     * 2026-10-10: la frase scritta nella chat di un agente («chat-social») resta nel filo di quell'agente anche quando va
     * alla VPS o viene rifiutata (prima finiva nel filo di JBoss e la chat dell'agente mostrava solo l'errore).
     */
    private fun esitoFrase(origine: String): String = Instradamento.esitoFrase(origine)

    fun instrada(context: Context, frase: String, agente: String? = null, origine: String = ""): String? {
        val ctx = context.applicationContext
        val ag = agente ?: Instradamento.agentePer(frase)
        val esito = Instradamento.decidi(
            frase, agente = agente, preferenza = ConfigVps.preferenza(ctx, ag),
            moduloAcceso = ConfigVps.acceso(ctx) && ConfigVps.dati(ctx).completa, rete = haRete(ctx),
        )
        val cron = runCatching { Cronologia.di(ctx) }.getOrNull()
        return when (esito) {
            is Instradamento.Esito.Locale -> esito.frase
            is Instradamento.Esito.Vps -> {
                cron?.aggiungi(Cronologia.BOSS, frase, esito = esitoFrase(origine))
                mandaLavoro(ctx, esito.agente, esito.frase, origine = origine, titolo = frase.trim().take(120))
                cron?.aggiungi(Cronologia.JARVIS, "[VPS · ${ModuloVpsUi.nomeAgente(esito.agente)}] In corso sulla VPS: ti avviso quando ha finito.", cervello = "vps", esito = "in corso", agenteEsecutore = agenteCronologia(esito.agente))
                ultimaRisposta = "Lo fa ${ModuloVpsUi.nomeAgente(esito.agente)} sulla VPS: ti avviso quando ha finito." to false
                null
            }
            is Instradamento.Esito.InCoda -> {
                cron?.aggiungi(Cronologia.BOSS, frase, esito = esitoFrase(origine))
                mandaLavoro(ctx, esito.agente, esito.frase, origine = origine, titolo = frase.trim().take(120))
                cron?.aggiungi(Cronologia.JARVIS, esito.messaggio, cervello = "vps", esito = "in coda", agenteEsecutore = agenteCronologia(esito.agente))
                ultimaRisposta = esito.messaggio to false
                null
            }
            is Instradamento.Esito.Rifiuto -> {
                cron?.aggiungi(Cronologia.BOSS, frase, esito = esitoFrase(origine))
                cron?.aggiungi(Cronologia.JARVIS, esito.messaggio, cervello = "vps", esito = "errore")
                ultimaRisposta = esito.messaggio to true
                null
            }
        }
    }

    /**
     * App non in primo piano: il pannello Invia/Annulla dell'accessibilità (lo stesso delle bozze), se il servizio
     * è acceso. Senza accessibilità resta la notifica con i due pulsanti.
     */
    private fun pannelloDiSistema(c: ConfermaVps) {
        principale.post {
            val p = runCatching { JarvisAccessibilityService.instance?.pannello }.getOrNull() ?: return@post
            runCatching {
                p.mostra(
                    c.titolo(),
                    ModuloVpsUi.corpoConferma(c),
                    "Invia",
                    onSi = { scegli(app, c.lavoroId, c.azioneId, ProtocolloVps.INVIA) },
                    onNo = { scegli(app, c.lavoroId, c.azioneId, ProtocolloVps.ANNULLA) },
                )
            }
        }
    }

    // ─── rete ──────────────────────────────────────────────────────────────────
    fun haRete(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private var reteAscoltata = false

    private fun ascoltaRete() {
        if (reteAscoltata) return
        reteAscoltata = runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (deveStareAperto()) {
                        Log.i(TAG, "rete tornata: mi ricollego")
                        client?.riprovaSubito()
                    }
                }
            })
            true
        }.getOrDefault(false)
    }
}

package com.jarvis.telefono

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.telefono.bolla.BollaStato
import com.jarvis.telefono.bolla.ContatorePrimoPiano
import com.jarvis.telefono.bolla.PrimoPiano
import com.jarvis.telefono.bolla.RegolePopup
import com.jarvis.telefono.bolla.Suoni
import com.jarvis.telefono.bolla.TestiBolla
import com.jarvis.telefono.nucleo.ConfigPersonale
import com.jarvis.telefono.nucleo.Nucleo
import com.jarvis.telefono.voce.Ascolto
import com.jarvis.telefono.voce.AzioneVoce
import com.jarvis.telefono.voce.CancelloAscolto
import com.jarvis.telefono.voce.Conversazione
import com.jarvis.telefono.voce.ModoVoce
import com.jarvis.telefono.voce.RegoleModo
import com.jarvis.telefono.voce.Batteria
import com.jarvis.telefono.voce.EmbedderSherpa
import com.jarvis.telefono.voce.Evento
import com.jarvis.telefono.voce.Glossario
import com.jarvis.telefono.voce.Impronta
import com.jarvis.telefono.voce.Microfono
import com.jarvis.telefono.voce.Modelli
import com.jarvis.telefono.voce.Modello
import com.jarvis.telefono.voce.Motore
import com.jarvis.telefono.voce.Opzioni
import com.jarvis.telefono.voce.PerLaVoce
import com.jarvis.telefono.voce.PulitoreGtcrn
import com.jarvis.telefono.voce.RilevatoreParolaSherpa
import com.jarvis.telefono.voce.Spezzatore
import com.jarvis.telefono.voce.StatoJarvis
import com.jarvis.telefono.voce.TrascrittoreWhisper
import com.jarvis.telefono.voce.TrascrittoreGoogle
import com.jarvis.telefono.voce.FrasiDebug
import com.jarvis.telefono.voce.Guadagno
import com.jarvis.telefono.voce.SceltaTrascrittore
import com.jarvis.telefono.voce.VadSilero
import com.jarvis.telefono.voce.Voce
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Il cuore dell'app (Rifondazione 1.0, seconda ondata, docs/RIFONDAZIONE-1.0.md).
 *
 * Resta acceso finché l'utente non tocca «Spegni»: nessuna logica lo ferma da sola,
 * e dopo un force-stop o un riavvio torna su (Prefs.isAttivo + BootReceiver).
 *
 * Il giro della voce, identico al computer:
 *   Microfono (un solo AudioRecord, blocchi da 300 ms)
 *     → Spezzatore (10 fotogrammi da 30 ms)
 *     → Motore (cancello di energia, GTCRN, KWS «Jarvis», VAD Silero, impronta)
 *     → Evento.Frase → Whisper offline → Glossario.postCorreggi
 *     → Nucleo (cervello del telefono, mani, cronologia) → rispondi → voce di sistema.
 *
 * Jarvis Telefono, passo A (2026-10-07): nessun ponte, nessuna VPS, nessun server. È la
 * copia dell'app 1.2.3 (tag v1.2.3-base) con il blocco del WebSocket tolto.
 *
 * Tutto lo stato che la schermata mostra sta in [StatoJarvis].
 *
 * Thread:
 * - principale: notifica, bolla, nucleo;
 * - «jarvis-microfono» (Microfono): Motore.feed, forzaCattura e chiudiOra,
 *   sempre e solo lui tocca il Motore. Deve tornare in meno di 300 ms: per
 *   questo il Motore gira senza verificatore e l'impronta non si calcola qui;
 * - «jarvis-voce-lavoro» (qui sotto): caricamento dei modelli Sherpa,
 *   somiglianza dell'impronta, trascrizione, apprendimento dell'impronta,
 *   rilascio dei modelli. Uno solo, in ordine.
 *
 * `onStartCommand` si può chiamare quante volte si vuole (la MainActivity lo
 * fa a ogni apertura se Jarvis è acceso): rimette in piedi solo quello che
 * manca e così fa uscire il servizio da GUASTO senza un secondo microfono e
 * senza ricaricare i modelli già in memoria.
 */
class JarvisService : Service() {

    companion object {
        const val ACTION_STOP = "com.jarvis.telefono.action.STOP"
        const val ACTION_LISTEN = "com.jarvis.telefono.action.LISTEN"
        /** 09/10 (voce senza mani): i tasti della notifica. In pausa e spenta il microfono è chiuso. */
        const val ACTION_PAUSA = "com.jarvis.telefono.action.PAUSA"
        const val ACTION_RIPRENDI = "com.jarvis.telefono.action.RIPRENDI"
        const val ACTION_SPEGNI_VOCE = "com.jarvis.telefono.action.SPEGNI_VOCE"
        /** La cattura che il popup riapre da solo: conta come la parola (impronta e filtro valgono). */
        private const val DA_CONVERSAZIONE = "conversazione"
        /** «JBoss» detto sopra la voce: anche questa è voce, non un dito (impronta e filtro valgono). */
        private const val DA_INTERRUZIONE = "interruzione"
        /** Dopo la voce di JBoss, prima di riaprire l'ascolto del popup (coda dell'eco). */
        private const val RIASCOLTO_DOPO_VOCE_MS = 700L
        /** Il popup in pausa o spento si chiude da solo dopo questo tempo (il modo resta quello). */
        private const val POPUP_FERMO_MS = 15_000L
        const val CHANNEL_ID = "jarvis_running"
        const val NOTIFICATION_ID = 1
        private const val TAG = "JarvisService"
        /** Un solo nuovo tentativo del microfono dopo un guasto. */
        private const val RITENTA_MICROFONO_MS = 30_000L
        /** Dieci minuti di microfono buono (2000 blocchi da 300 ms) azzerano il conto dei tentativi. */
        private const val BLOCCHI_MICROFONO_SANO = 2_000L
        /** Se dopo una risposta la voce non parte entro questo tempo, si torna in ascolto. */
        private const val GUARDIA_VOCE_MS = 15_000L
        /** La bolla «Fatto» resta questi ms dopo che Jarvis ha finito di parlare (Boss 09/10: «deve sparire subito»). */
        private const val BOLLA_FATTO_MS = 1_000L
        /** Fra il segnale «fatto» e la voce: le due cose non si sovrappongono. */
        private const val PAUSA_DOPO_SEGNALE_MS = 450L
        /** 0.3.3: quanti ms del pre-roll vanno davanti alla frase per la trascrizione. */
        private const val CODA_PRIMA_MS = 500L
        /** Paracadute del dettato dell'interfaccia: oltre questo il microfono del servizio si riapre da solo. */
        private const val PAUSA_DETTATO_MAX_MS = 120_000L

        var isRunning = false
            private set

        var instance: JarvisService? = null
            private set

        /**
         * 09/10: lo stato di JBoss in un punto solo ([presenzaJBoss]): lo leggono il tasto Parla (Home e chat) e
         * l'interruttore «JBoss acceso» (pagina Voce e card Voce delle Impostazioni). Sul thread principale.
         */
        fun presenza(c: android.content.Context): PresenzaJBoss =
            presenzaJBoss(StatoJarvis.corrente, instance != null, Prefs.isAttivo(c))
    }

    private val principale = Handler(Looper.getMainLooper())
    /** Solo per scaricare i modelli della voce da GitHub di k2-fsa (Impostazioni). */
    private val client: OkHttpClient by lazy { OkHttpClient() }
    private val lavoro: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "jarvis-voce-lavoro") }

    @Volatile
    private var spento = false

    // ------------------------------------------------------------ la voce
    // Dalla configurazione personale (config-boss.json) o di fabbrica: fine frase, filtro impronta.
    private lateinit var opzioni: Opzioni
    private lateinit var modelli: Modelli
    private lateinit var glossario: Glossario
    private lateinit var batteria: Batteria
    private var voce: Voce? = null
    private var microfono: Microfono? = null

    // Creati sul thread di lavoro, usati dal Motore sul thread del microfono.
    @Volatile
    private var motore: Motore? = null
    private var rilevatore: RilevatoreParolaSherpa? = null
    private var vad: VadSilero? = null
    private var pulitore: PulitoreGtcrn? = null
    private var embedder: EmbedderSherpa? = null
    @Volatile
    private var impronta: Impronta? = null
    // Solo sul thread di lavoro: Silero ha memoria, quello del Motore non si condivide.
    private var vadTrascrizione: VadSilero? = null
    private var trascrittore: TrascrittoreWhisper? = null
    // 0.3.3: Google sul telefono sull'audio già catturato (TrascrittoreGoogle), Whisper come ripiego.
    private var trascrittoreGoogle: TrascrittoreGoogle? = null

    /**
     * 0.3.3: dopo un «non ho capito» dalla voce il microfono si riapre da solo UNA volta. Vero = la
     * riapertura è già stata usata: il prossimo «non capito» non riapre (niente giri a vuoto).
     * Torna falso con una frase capita, con la parola o con un tocco. Solo sul thread principale.
     */
    private var riascoltoUsato = false

    /** Solo sul thread principale. */
    private var caricamentoAvviato = false
    private var tentativiMicrofono = 0

    /** Il flag «altoparlanti attivi» del Motore: lo mettono Voce.onInizio/onFine. */
    @Volatile
    private var altoparlanti = false

    /** Vero mentre il dettato dell'interfaccia usa il microfono (vedi [pausaPerDettato]). */
    @Volatile
    private var inPausaDettato = false
    private val fineSicurezzaDettato = Runnable {
        if (inPausaDettato) {
            diag("dettato dell'interfaccia: nessuna fine in tempo, riapro il microfono")
            riprendiDopoDettato()
        }
    }

    /** Il widget o «Ascolta» chiedono una cattura: la esegue il thread del microfono. */
    @Volatile
    private var catturaRichiesta: String? = null

    /** Il dito lascia il widget dopo un tocco lungo: chiudiOra, sul thread del microfono. */
    @Volatile
    private var chiusuraRichiesta = false

    // Solo sul thread del microfono.
    private var catturaDa: String? = null
    private var ultimoIstanteMs = 0L

    // 1.2.2: segnali udibili e bolla di stato (vedi bolla/).
    private var suoni: Suoni? = null
    private val bolla = BollaStato(
        finestra = { finestraBolla() },
        posizione = { posizioneBolla() },
        salvaPosizione = { x, y -> Prefs.setBollaPosizione(this, x, y) },
        suAzione = { azioneVoce(it, "popup") },
        suChiudiPopup = { chiudiConversazione("popup") },
        // Boss 09/10: il popup solo con l'app chiusa o in sottofondo, mai dentro l'app.
        consentita = { RegolePopup.puoMostrare(PrimoPiano.app) },
    )
    /** Chi smette di guardare l'app davanti o dietro ([suPrimoPiano]). */
    private var smettiPrimoPiano: (() -> Unit)? = null

    // ------------------------------------------------------------ 09/10: la voce senza mani
    /** Acceso, in pausa o spento (Prefs). Solo sul thread principale; il microfono si apre solo da acceso. */
    private var modo: ModoVoce = ModoVoce.ACCESO

    /**
     * 09/10: il cancello fra il modo e quello che il microfono ha già preso (blocchi in volo, frasi in trascrizione).
     * Si legge dal thread del microfono e da quello di lavoro; lo cambia solo [impostaModo] (e onCreate).
     */
    private val cancello = CancelloAscolto()

    /** L'ascolto continuo col popup aperto. Solo sul thread principale. */
    private val conversazione = Conversazione()

    /** Copia di conversazione.aperta per il thread del microfono. */
    @Volatile
    private var conversazioneAttiva = false

    /** Una risposta arrivata mentre Boss parlava nel popup: si dice dopo la sua frase (o si scarta). */
    private var rispostaTenuta: (() -> Unit)? = null

    private val chiudiPopupFermo = Runnable {
        if (!modo.ascolta && conversazione.aperta) chiudiConversazione("popup fermo da ${POPUP_FERMO_MS / 1000} s")
    }
    private val guardiaVoce = Runnable {
        if (StatoJarvis.corrente.ascolto == Ascolto.PENSO && voce?.staParlando != true) {
            diag("voce: la risposta non si è sentita (né ponte né voce di sistema)")
            mostra(Ascolto.ASCOLTO)
        }
    }

    private val lockNotifica = Any()
    private var ultimaNotifica: String? = null
    private var ultimaChiaveNotifica: String? = null

    // ================================================================ ciclo di vita

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // Da qui in poi Jarvis è acceso, e resta scritto sul telefono: se il
        // processo muore o il telefono si riavvia, deve tornare su da solo.
        Prefs.setAttivo(this, true)
        instance = this
        modo = Prefs.getModoVoce(this)
        cancello.imposta(modo)
        val cfg = ConfigPersonale.ricarica(this)
        opzioni = Opzioni(fineParlatoS = cfg.fineFraseS, sogliaImpronta = cfg.sogliaEffettiva)
        if (cfg.ascoltoSempreAcceso) Prefs.setParolaAttiva(this, true)
        createNotificationChannel()
        suoni = Suoni(this).also { s -> cfg.volumeSegnali?.let { s.volume = it } }
        Nucleo.prepara(this)
        smettiPrimoPiano = PrimoPiano.osserva { suPrimoPiano(it) }
        // 0.6.0: Collegamento Jarvis (giro delle notifiche ogni 15 minuti, memoria condivisa ogni 6 ore), solo se acceso.
        runCatching { com.jarvis.telefono.collegamento.CollegamentoJarvis.avvio(this) }
        // 0.6.1: il token della sveglia FCM della VPS (sveglia/SvegliaFcm.kt). Senza Firebase nell'APK non fa niente.
        com.jarvis.telefono.sveglia.Sveglia.preparaToken(this)

        modelli = Modelli(Inventario.cartellaModelli(this), client)
        glossario = Glossario(Inventario.cartellaGlossario(this)) { diag(it) }
        batteria = Batteria(this)
        voce = Voce(
            this,
            onInizio = {
                altoparlanti = true
                principale.removeCallbacks(guardiaVoce)
                mostra(Ascolto.PARLO)
            },
            onFine = {
                altoparlanti = false
                if (StatoJarvis.corrente.ascolto == Ascolto.PARLO) mostra(Ascolto.ASCOLTO)
            },
        )
        StatoJarvis.aggiorna {
            it.copy(
                ascolto = Ascolto.ASCOLTO,
                guasto = null,
                fineParlatoS = opzioni.fineParlatoS,
                fineParlatoLungoS = opzioni.fineParlatoLungoS,
                parlatoLungoS = opzioni.parlatoLungoS,
                whisperPresente = modelli.presente(Modello.WHISPER_SMALL),
                whisperByte = byteWhisper(),
                modoVoce = modo,
                conversazioneAperta = false,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            spento = true
            // L'unico caso in cui Jarvis deve restare spento: l'ha spento l'utente.
            Prefs.setAttivo(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification(ultimaNotifica ?: "Preparo la voce…"))
        // 09/10: Pausa, Riprendi e Spegni dalla notifica. Cambiano il modo e basta: niente cattura.
        when (intent?.action) {
            ACTION_PAUSA -> { azioneVoce(AzioneVoce.PAUSA, "notifica"); return START_STICKY }
            ACTION_RIPRENDI -> { azioneVoce(AzioneVoce.RIPRENDI, "notifica"); return START_STICKY }
            ACTION_SPEGNI_VOCE -> { azioneVoce(AzioneVoce.SPEGNI, "notifica"); return START_STICKY }
        }
        // Un avvio normale (l'app riaperta, BootReceiver, START_STICKY) rimette
        // in piedi quello che manca, anche un microfono guasto: «resta guasto
        // finché l'utente non riapre l'app».
        if (intent?.action != ACTION_LISTEN) tentativiMicrofono = 0
        avviaSeServe()
        if (intent?.action == ACTION_LISTEN) ascolta("notifica")
        // 0.2.0 (Boss 07/10): niente tasto flottante, l'ascolto della parola è sempre acceso.
        // 0.6.1: salvo che Boss l'abbia spento lui da Impostazioni → Voce («Ascolta «Hey Boss» sempre»).
        if (ConfigPersonale.di(this).ascoltoSempreAcceso && !Prefs.isParolaAttiva(this)) Prefs.setParolaAttiva(this, true)
        return START_STICKY
    }

    override fun onDestroy() {
        spento = true
        isRunning = false
        if (instance === this) instance = null
        principale.removeCallbacksAndMessages(null)
        microfono?.ferma()
        microfono = null
        voce?.rilascia()
        batteria.ferma()
        smettiPrimoPiano?.invoke(); smettiPrimoPiano = null
        conversazione.chiudi(); conversazioneAttiva = false; rispostaTenuta = null
        bolla.conversazione(null)
        bolla.nascondi()
        // Il thread di lavoro finisce quello che ha in coda (una trascrizione
        // in corso) e poi libera la memoria nativa di Sherpa.
        runCatching { lavoro.execute { rilasciaSherpa() } }
        lavoro.shutdown()
        StatoJarvis.aggiorna { it.copy(ascolto = Ascolto.SPENTO, guasto = null, conversazioneAperta = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================================================================ avvio della voce

    private fun micPermesso(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Sul thread principale: carica i modelli una volta, poi apre il microfono se è chiuso. */
    private fun avviaSeServe() {
        if (spento) return
        if (!micPermesso()) {
            diag("microfono: permesso non concesso, la voce resta ferma")
            mostra(Ascolto.GUASTO, "Manca il permesso microfono: apri l'app", guasto = "manca il permesso del microfono")
            return
        }
        if (!caricamentoAvviato) {
            caricamentoAvviato = true
            mostra(Ascolto.ASCOLTO, "Preparo la voce…")
            inLavoro { caricaVoce() }
            return
        }
        if (motore != null && microfono?.attivo != true) avviaMicrofono()
    }

    /** Sul thread di lavoro. I modelli Sherpa sono pesanti: mai sul principale. */
    private fun caricaVoce() {
        val inizio = SystemClock.elapsedRealtime()
        try {
            val ril = RilevatoreParolaSherpa(assets).also { rilevatore = it }
            val v = VadSilero(assets, soglia = opzioni.sogliaVad).also { vad = it }
            val p = if (opzioni.filtroRumore) PulitoreGtcrn(assets).also { pulitore = it } else null
            val imp = caricaImpronta()
            // Il Motore gira senza verificatore: la somiglianza (ERes2Net, anche
            // centinaia di ms) si calcola sul thread di lavoro, in verificaEPassa,
            // e non ferma il microfono. Motore.kt resta uguale al computer.
            motore = Motore(opzioni, ril, v, p, verificatore = null)
            glossario.termini() // legge i file già scaricati
            Inventario.rileggi(this)
            diag(
                "voce pronta in ${SystemClock.elapsedRealtime() - inizio} ms: filtro=${p != null}, " +
                    "impronta=${imp?.origine ?: "nessun modello"}, whisper=${modelli.presente(Modello.WHISPER_SMALL)}, " +
                    "fine parlato ${opzioni.fineParlatoS} s (${opzioni.fineParlatoLungoS} s dopo ${opzioni.parlatoLungoS} s)"
            )
            principale.post { avviaMicrofono() }
        } catch (t: Throwable) {
            Log.e(TAG, "caricamento della voce fallito", t)
            val testo = "voce non caricata: ${t.javaClass.simpleName}"
            diag(testo)
            // Via i pezzi caricati a metà, e al prossimo avvio (l'app riaperta)
            // si riprova da capo invece di restare in GUASTO per sempre.
            rilasciaSherpa()
            mostra(Ascolto.GUASTO, "Guasto: $testo", guasto = testo)
            principale.post { caricamentoAvviato = false }
        }
    }

    /** L'impronta c'è solo se ERes2Net è scaricato; altrimenti niente filtro della voce, e lo si dice. */
    private fun caricaImpronta(): Impronta? {
        val nome = Modello.ERES2NET.file.single().nome
        if (!modelli.presente(Modello.ERES2NET)) {
            diag("impronta: modello ERes2Net non scaricato, il filtro della voce è spento")
            StatoJarvis.aggiorna { it.copy(improntaPronta = false, improntaOrigine = "modello non scaricato") }
            return null
        }
        return try {
            val e = EmbedderSherpa(modelli.percorso(nome)).also { embedder = it }
            val imp = Impronta(e, File(filesDir, "voce"))
            impronta = imp
            if (!imp.pronta) diag("impronta: non ancora imparata (${imp.frasiImparate()} frasi), il filtro della voce è spento")
            pubblicaImpronta(imp)
            imp
        } catch (t: Throwable) {
            diag("impronta: non caricata (${t.javaClass.simpleName}), il filtro della voce è spento")
            StatoJarvis.aggiorna { it.copy(improntaPronta = false, improntaOrigine = "errore del modello") }
            null
        }
    }

    private fun pubblicaImpronta(imp: Impronta) {
        StatoJarvis.aggiorna { it.copy(improntaPronta = imp.pronta, improntaOrigine = imp.origine) }
    }

    /**
     * 0.6.1 (Boss 08/10: «voce regolabile dall'app»): rilegge la configurazione appena cambiata in Impostazioni → Voce
     * e la usa dal prossimo ascolto, senza riavviare: fine frase e soglia dell'impronta nel motore, volume dei segnali,
     * ascolto della parola acceso o spento. Il trascrittore e l'app della posta si leggono già a ogni frase.
     * Sul thread principale.
     */
    fun applicaConfigurazione() {
        val cfg = ConfigPersonale.ricarica(this)
        opzioni = opzioni.copy(fineParlatoS = cfg.fineFraseS, sogliaImpronta = cfg.sogliaEffettiva)
        motore?.opzioni = opzioni
        suoni?.volume = cfg.volumeSegnali ?: 1f
        StatoJarvis.aggiorna { it.copy(fineParlatoS = opzioni.fineParlatoS) }
        Log.i(TAG, "configurazione applicata senza riavvio: fine frase ${opzioni.fineParlatoS} s, soglia impronta ${opzioni.sogliaImpronta}, " +
            "volume segnali ${cfg.volumeSegnali ?: 1f}, parola ${if (Prefs.isParolaAttiva(this)) "accesa" else "spenta"}")
        if (!Prefs.isParolaAttiva(this)) {
            microfono?.ferma(); microfono = null
            mostra(Ascolto.ASCOLTO, "Non ascolto «Hey Boss»: riaccendi in Impostazioni, Voce")
        } else if (motore != null && microfono?.attivo != true) avviaMicrofono()
    }

    /** Sul thread principale. */
    private fun avviaMicrofono() {
        // Durante il dettato della schermata il microfono è di DettatoNativo (un solo AudioRecord).
        if (spento || motore == null || inPausaDettato) return
        // 09/10: in pausa o spenta la voce non apre il microfono (e lo dice la notifica).
        if (!modo.ascolta) {
            mostra(Ascolto.ASCOLTO)
            return
        }
        // 0.6.1: Boss ha spento l'ascolto della parola da Impostazioni → Voce.
        if (!Prefs.isParolaAttiva(this)) {
            mostra(Ascolto.ASCOLTO, "Non ascolto «Hey Boss»: riaccendi in Impostazioni, Voce")
            return
        }
        if (!micPermesso()) {
            mostra(Ascolto.GUASTO, "Manca il permesso microfono: apri l'app", guasto = "manca il permesso del microfono")
            return
        }
        // Il tipo microphone va dichiarato PRIMA di aprire l'AudioRecord, e
        // solo adesso che il permesso c'è (vedi startForegroundCompat).
        if (!startForegroundCompat(buildNotification(testoDi(Ascolto.ASCOLTO)))) {
            // Senza il tipo microphone l'AudioRecord riceverebbe solo silenzio:
            // meglio un GUASTO onesto. Riaprire l'app lo rimette in piedi.
            mostra(Ascolto.GUASTO, "Tocca per riaccendere il microfono", guasto = "microfono non concesso in sottofondo")
            return
        }
        val m = microfono ?: Microfono(this, ::suBlocco, ::suErroreMicrofono).also { microfono = it }
        if (m.attivo) return
        m.avvia()
        batteria.inizia(SystemClock.elapsedRealtime())
        mostra(Ascolto.ASCOLTO)
    }

    private fun rilasciaSherpa() {
        motore = null
        runCatching { rilevatore?.close() }
        runCatching { vad?.close() }
        runCatching { pulitore?.close() }
        runCatching { embedder?.close() }
        runCatching { trascrittore?.release() }
        runCatching { vadTrascrizione?.close() }
        rilevatore = null; vad = null; pulitore = null; embedder = null
        trascrittore = null; vadTrascrizione = null; impronta = null
    }

    // ================================================================ il microfono (thread «jarvis-microfono»)

    private fun suBlocco(blocco: ShortArray) {
        val m = motore ?: return
        if (spento) return
        // 09/10: in pausa o spento nessun blocco entra nel Motore, anche se il thread del microfono non si è ancora fermato.
        if (!cancello.bloccoAmmesso()) return
        // Orologio del motore: monotono, 30 ms a fotogramma.
        val t0 = maxOf(SystemClock.elapsedRealtime(), ultimoIstanteMs + Opzioni.FRAME_MS)
        catturaRichiesta?.let { da ->
            catturaRichiesta = null
            m.forzaCattura(t0)
            catturaDa = if (da == DA_CONVERSAZIONE || da == DA_INTERRUZIONE) null else da
        }
        val fotogrammi = Spezzatore.inFotogrammi(blocco)
        for ((i, f) in fotogrammi.withIndex()) {
            val t = Spezzatore.istante(t0, i)
            ultimoIstanteMs = t
            val ev = m.feed(f, t, altoparlanti) ?: continue
            suEvento(ev)
        }
        // Dopo il blocco, così la frase ha anche gli ultimi 300 ms col dito giù.
        if (chiusuraRichiesta) {
            chiusuraRichiesta = false
            m.chiudiOra(ultimoIstanteMs)?.let { suEvento(it) }
        }
        batteria.misuraOraria(t0)?.let { diag(batteria.rigaDiag(it)) }
    }

    private fun suEvento(ev: Evento) {
        when (ev) {
            is Evento.Parola -> {
                catturaDa = null
                // 09/10: la parola detta sopra la voce di JBoss (popup aperto) è un'interruzione.
                val sopraLaVoce = motore?.stato == Motore.Stato.MUTO
                principale.post { if (sopraLaVoce) interrompiConParola(ev.parola) else suParola(ev.parola) }
            }
            is Evento.NessunParlato -> {
                diag("nessun parlato dopo ${if (catturaDa != null) "il $catturaDa" else "la parola"}: torno ad aspettare")
                catturaDa = null
                if (conversazioneAttiva) {
                    principale.post { silenzioInConversazione() }
                } else {
                    mostra(Ascolto.ASCOLTO)
                    nessunParlato()
                }
            }
            is Evento.Frase -> {
                val da = catturaDa
                catturaDa = null
                suFrase(ev, da)
            }
        }
    }

    private fun suErroreMicrofono(testo: String) {
        val sano = (microfono?.blocchiLetti ?: 0L) >= BLOCCHI_MICROFONO_SANO
        diag("microfono: $testo")
        batteria.ferma()
        mostra(Ascolto.GUASTO, "Guasto: $testo", guasto = testo)
        principale.post {
            if (sano) tentativiMicrofono = 0
            if (!spento && tentativiMicrofono == 0) {
                tentativiMicrofono++
                principale.postDelayed({
                    if (!spento) {
                        diag("microfono: riprovo una volta")
                        avviaMicrofono()
                    }
                }, RITENTA_MICROFONO_MS)
            }
        }
    }

    // ================================================================ gli eventi della voce

    /** Sul thread principale: «Jarvis» sentito. */
    private fun suParola(parola: String) {
        if (spento) return
        if (!modo.ascolta) { diag("parola sentita con la voce ${modo.chiave}: ignorata"); return }
        diag("parola: $parola")
        riascoltoUsato = false
        apriCattura()
        // Boss 09/10: con l'app davanti la voce va nella chat di JBoss (domanda e risposta nel filo), non in un popup.
        if (RegolePopup.apriChatJBoss(PrimoPiano.adesso)) apriChatJBoss()
    }

    /** Apre la chat di JBoss sopra la schermata dell'app che è davanti. Sul thread principale. */
    private fun apriChatJBoss() {
        diag("app davanti: la voce va nella chat di JBoss")
        runCatching {
            startActivity(
                AgenteChatActivity.intento(this, com.jarvis.telefono.agenti.CatalogoAgenti.JARVIS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }.onFailure { diag("chat di JBoss non aperta: ${it.javaClass.simpleName}") }
    }

    /**
     * La chat di JBoss (o la pagina del Postino dopo «passa al Postino») è davanti (onResume): JBoss ascolta senza la
     * parola e l'ascolto continuo resta aperto finché l'app è davanti e la voce è accesa (Boss 09/10). Se è già
     * aperto (si arriva dalla chat alla pagina del Postino) non si riapre niente. In pausa o spenta niente.
     * Sul thread principale.
     */
    fun chatJBossAperta() {
        if (spento || !modo.ascolta || inPausaDettato) return
        if (conversazione.aperta) return
        if (motore == null || microfono?.attivo != true) return
        if (voce?.staParlando == true || StatoJarvis.corrente.ascolto == Ascolto.PENSO) {
            // JBoss sta già rispondendo: l'ascolto continuo riparte da solo a voce finita.
            apriConversazione()
            return
        }
        ascolta("chat JBoss")
    }

    /** JBoss sta lavorando a una richiesta: pensa, parla, un agente lavora o una conferma aspetta Boss. */
    private fun operazioneInCorso(): Boolean {
        val s = StatoJarvis.corrente
        return s.ascolto == Ascolto.PENSO || s.ascolto == Ascolto.PARLO || s.agenteAlLavoro != null ||
            PhoneActionExecutor.inSospeso()
    }

    /** L'app è passata davanti o dietro (Boss 09/10: il popup solo fuori dall'app, e sparisce al ritorno). */
    private fun suPrimoPiano(c: ContatorePrimoPiano.Cambio) {
        if (spento) return
        for (a in RegolePopup.alCambio(c, operazioneInCorso(), conversazione.aperta)) when (a) {
            RegolePopup.Azione.NASCONDI -> bolla.nascondi()
            RegolePopup.Azione.RIMOSTRA -> bolla.riprendi()
            RegolePopup.Azione.CHIUDI_CONVERSAZIONE -> chiudiConversazione("app chiusa, niente in corso")
        }
    }

    /**
     * «Ascolta» della notifica, Parla dell'app, il tocco su Jarvis o il tasto laterale:
     * cattura subito, senza aspettare la parola. Se Jarvis sta parlando, tace.
     * Sul thread principale.
     */
    fun ascolta(da: String) {
        if (spento) return
        // 09/10: in pausa o spenta JBoss non ascolta, neanche col tocco: il popup mostra lo stato e «Riprendi».
        // Boss 09/10: e non riapre il popup.
        if (!modo.ascolta) {
            diag("ascolto dal $da: la voce è ${modo.chiave}, non apro il microfono")
            return
        }
        if (motore == null || microfono?.attivo != true) {
            diag("ascolto dal $da: la voce non è pronta (${StatoJarvis.corrente.ascolto})")
            return
        }
        if (voce?.staParlando == true) {
            diag("ascolto dal $da: l'utente interrompe, Jarvis tace")
            voce?.zitto()
            altoparlanti = false
            motore?.parolaDuranteVoce = false
            conversazione.interrotta(SystemClock.elapsedRealtime())
        }
        diag("ascolto dal $da")
        if (da != "riprova") riascoltoUsato = false
        catturaRichiesta = da
        apriCattura(suono = da != DA_CONVERSAZIONE)
    }

    private fun apriCattura(suono: Boolean = true) {
        if (suono && opzioni.suonoAscolto) suoni?.suona(Suoni.Tipo.ATTIVO)
        bolla.mostra(if (PhoneActionExecutor.inSospeso()) TestiBolla.Riga("Ti ascolto…", "di' «invia» o «annulla»", TestiBolla.Tono.ASCOLTO) else TestiBolla.TI_ASCOLTO)
        mostra(Ascolto.CATTURA)
        apriConversazione()
    }

    // ================================================================ 09/10: la voce senza mani (popup e notifica)

    /**
     * Un tasto del popup o della notifica. Pausa, Riprendi, Spegni e l'interruttore cambiano il modo;
     * Ascolta apre la cattura (e interrompe JBoss se parla); Esci spegne tutto il servizio.
     * Da qualunque thread.
     */
    fun azioneVoce(azione: AzioneVoce, da: String) {
        sulPrincipale {
            when (azione) {
                AzioneVoce.ASCOLTA -> ascolta(da)
                AzioneVoce.ESCI -> {
                    diag("voce: esco dal $da")
                    spento = true
                    Prefs.setAttivo(this, false)
                    stopSelf()
                }
                else -> impostaModo(RegoleModo.dopo(modo, azione), da)
            }
        }
    }

    /** Il modo attuale della voce (acceso, pausa, spento). */
    fun modoVoce(): ModoVoce = modo

    /**
     * Cambia il modo e lo scrive in Prefs. Pausa e spento: microfono rilasciato, cattura buttata,
     * nessun riconoscimento in corso. Acceso: il microfono si riapre. Il popup, se aperto, mostra il
     * modo nuovo; la notifica cambia i tasti. Sul thread principale.
     */
    private fun impostaModo(nuovo: ModoVoce, da: String) {
        if (spento) return
        val prima = modo
        modo = nuovo
        // Prima di tutto il resto: da qui i blocchi in volo e le frasi in trascrizione non passano più.
        cancello.imposta(nuovo)
        Prefs.setModoVoce(this, nuovo)
        StatoJarvis.aggiorna { it.copy(modoVoce = nuovo) }
        diag("voce: ${prima.chiave} → ${nuovo.chiave} (dal $da)")
        principale.removeCallbacks(chiudiPopupFermo)
        if (!nuovo.ascolta) {
            fermaAscolto()
            if (conversazione.aperta) principale.postDelayed(chiudiPopupFermo, POPUP_FERMO_MS)
        } else {
            avviaSeServe()
        }
        if (StatoJarvis.corrente.ascolto == Ascolto.CATTURA || StatoJarvis.corrente.ascolto == Ascolto.ASCOLTO) mostra(Ascolto.ASCOLTO)
        aggiornaNotifica()
        aggiornaPopup()
        // «Riprendi» toccato nel popup: si torna subito ad ascoltare.
        if (nuovo.ascolta && !prima.ascolta && da == "popup" && conversazione.aperta) {
            principale.postDelayed({ ascolta("popup") }, 300L)
        } else if (nuovo.ascolta && !prima.ascolta &&
            RegolePopup.ascoltaDaSola(PrimoPiano.adesso, com.jarvis.telefono.postino.Postino.passaggio.conPostino)) {
            // Riprendi con la chat di JBoss (o la pagina del Postino dopo il passaggio) davanti: torna ad ascoltare.
            principale.postDelayed({ chatJBossAperta() }, 300L)
        }
    }

    /** Chiude il microfono e butta la cattura in corso. Sul thread principale. */
    private fun fermaAscolto() {
        catturaRichiesta = null
        chiusuraRichiesta = false
        motore?.parolaDuranteVoce = false
        microfono?.let { if (it.attivo) { it.ferma(); batteria.ferma() } }
        // Il thread del microfono è fermo: il Motore si può azzerare da qui.
        motore?.azzera()
        rispostaTenuta?.let { rispostaTenuta = null; it() }
    }

    /**
     * Apre l'ascolto continuo (solo con la voce accesa). Dentro l'app vive nella chat di JBoss, senza popup; fuori
     * dall'app solo se il popup si può disegnare.
     */
    private fun apriConversazione() {
        if (!modo.ascolta || !RegolePopup.conversazionePossibile(PrimoPiano.app, finestraBolla() != null)) return
        if (!conversazione.aperta) {
            conversazione.apri()
            conversazioneAttiva = true
            StatoJarvis.aggiorna { it.copy(conversazioneAperta = true) }
            diag("popup della conversazione aperto: ascolto continuo")
        }
        aggiornaPopup()
    }

    private fun aggiornaPopup() {
        if (!conversazione.aperta) return
        bolla.conversazione(BollaStato.Pannello(modo, conversazione.ultimeParole))
    }

    /** Chiude il popup (✕, «basta» a voce, silenzio, popup fermo). Il modo della voce resta quello. */
    fun chiudiConversazione(da: String) {
        sulPrincipale {
            if (!conversazione.aperta) return@sulPrincipale
            diag("popup della conversazione chiuso (dal $da)")
            conversazione.chiudi()
            conversazioneAttiva = false
            motore?.parolaDuranteVoce = false
            principale.removeCallbacks(chiudiPopupFermo)
            StatoJarvis.aggiorna { it.copy(conversazioneAperta = false) }
            bolla.conversazione(null)
            rispostaTenuta?.let { rispostaTenuta = null; it() }
            if (StatoJarvis.corrente.ascolto == Ascolto.ASCOLTO) aggiornaNotifica()
        }
    }

    /**
     * Col popup aperto JBoss riascolta da solo, senza la parola: dopo la sua risposta, mentre pensa
     * (per le aggiunte) e dopo un silenzio. Mai in pausa o spenta, mai con una conferma in attesa
     * (quella riapre il microfono per conto suo), mai sopra una cattura già aperta.
     * Sul thread principale.
     */
    private fun riascoltaInConversazione(motivo: String) {
        if (spento || !conversazione.aperta || !modo.ascolta || inPausaDettato) return
        if (motore == null || microfono?.attivo != true) return
        if (voce?.staParlando == true || altoparlanti) return
        if (PhoneActionExecutor.inSospeso()) return
        if (catturaRichiesta != null || motore?.stato == Motore.Stato.CATTURA) return
        diag("popup: riascolto ($motivo)")
        catturaRichiesta = DA_CONVERSAZIONE
        if (StatoJarvis.corrente.ascolto == Ascolto.PENSO) {
            // JBoss pensa: la bolla resta «Sto lavorando…», il microfono è aperto per le aggiunte.
            aggiornaPopup()
        } else {
            apriCattura(suono = false)
        }
    }

    /**
     * Finita la risposta con l'ascolto continuo aperto: dentro l'app (chat di JBoss, pagina del Postino, ogni schermata)
     * si riascolta; fuori dall'app l'operazione è finita e il popup sparisce subito (Boss 09/10). Sul thread principale.
     */
    private fun dopoLaRisposta(motivo: String) {
        if (RegolePopup.riascoltaDopoRisposta(PrimoPiano.adesso)) {
            principale.postDelayed({ riascoltaInConversazione(motivo) }, RIASCOLTO_DOPO_VOCE_MS)
        } else if (!PhoneActionExecutor.inSospeso()) {
            chiudiConversazione("operazione finita ($motivo)")
        }
    }

    /** Sul thread principale: una cattura del popup è finita senza parlato. */
    private fun silenzioInConversazione() {
        rispostaTenuta?.let { rispostaTenuta = null; it() }
        if (!conversazione.aperta) {
            if (StatoJarvis.corrente.ascolto == Ascolto.CATTURA) mostra(Ascolto.ASCOLTO)
            return
        }
        conversazione.silenzio(SystemClock.elapsedRealtime())
        if (RegolePopup.riascoltaDopoSilenzio(PrimoPiano.adesso)) {
            riascoltaInConversazione("silenzio")
        } else {
            chiudiConversazione("silenzio")
            if (StatoJarvis.corrente.ascolto == Ascolto.CATTURA) mostra(Ascolto.ASCOLTO)
            bolla.chiudiFra(1_000L, null)
        }
    }

    /** Sul thread principale: «JBoss» detto mentre JBoss parlava. Tace e ascolta. */
    private fun interrompiConParola(parola: String) {
        if (spento || !modo.ascolta) return
        diag("parola sopra la voce: $parola, interrompo")
        riascoltoUsato = false
        ascolta(DA_INTERRUZIONE)
    }

    /** Sul thread del microfono: la cattura è finita senza parlato (10 s dopo la parola). */
    private fun nessunParlato() {
        principale.post {
            if (PhoneActionExecutor.inSospeso()) {
                // La bozza aspetta ancora: niente errore, si torna alla bolla dell'attesa.
                bolla.mostra(TestiBolla.ASPETTO_INVIA)
                return@post
            }
            suoni?.suona(Suoni.Tipo.ERRORE)
            val r = TestiBolla.NON_SENTITO
            bolla.mostra(r)
            bolla.chiudiFra(3_000L, r)
        }
    }

    // ================================================================ bolla e segnali (1.2.2)

    /** Un errore detto con suono e bolla, che poi sparisce. Da qualunque thread. */
    private fun errore(motivo: String, dettaglio: String = "") {
        suoni?.suona(Suoni.Tipo.ERRORE)
        val r = TestiBolla.Riga("Errore: $motivo", dettaglio, TestiBolla.Tono.ERRORE)
        bolla.mostra(r)
        bolla.chiudiFra(5_000L, r)
    }

    /** Dove si disegna la bolla: la finestra dell'accessibilità (0.2.0: nessun permesso «sopra le altre app»). */
    private fun finestraBolla(): Pair<android.content.Context, Int>? =
        JarvisAccessibilityService.instance?.let { it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }

    private fun posizioneBolla(): Pair<Int, Int>? {
        val x = Prefs.getBollaX(this); val y = Prefs.getBollaY(this)
        return if (x >= 0 && y >= 0) x to y else null
    }

    /** «Annulla» (tocco, notifica o voce): si sente e si vede che niente è partito e Jarvis resta attivo. */
    fun operazioneAnnullata() {
        sulPrincipale {
            suoni?.suona(Suoni.Tipo.ERRORE)
            val r = TestiBolla.invio("annulla")
            bolla.mostra(r)
            bolla.chiudiFra(4_000L, r)
        }
    }

    /** Per PhoneActionExecutor: una riga nella bolla. Da qualunque thread. */
    fun statoBolla(riga: TestiBolla.Riga, chiudiDopoMs: Long? = null) = bolla.mostra(riga, chiudiDopoMs)

    /** Per PhoneActionExecutor: un segnale (suono e vibrazione). Da qualunque thread. */
    fun segnale(tipo: Suoni.Tipo) { suoni?.suona(tipo) }

    /**
     * Dice [testo] e, quando la voce ha finito (o non è mai partita entro 8 s), esegue [poi]
     * sul thread principale. Si guarda staParlando invece di onFine perché onFine di un turno
     * interrotto arriva mentre il turno nuovo non è ancora partito.
     */
    private fun parlaPoi(testo: String, poi: () -> Unit) {
        voce?.parla(testo)
        val inizio = SystemClock.elapsedRealtime()
        var partita = false
        val giro = object : Runnable {
            override fun run() {
                if (spento) return
                val parla = voce?.staParlando == true
                if (parla) partita = true
                val trascorso = SystemClock.elapsedRealtime() - inizio
                if ((partita && !parla) || (!partita && trascorso > 8_000L) || trascorso > 180_000L) {
                    poi()
                    return
                }
                principale.postDelayed(this, 250L)
            }
        }
        principale.postDelayed(giro, 300L)
    }

    /** La conferma d'invio è scaduta (PhoneActionExecutor, 2 minuti): lo si dice e si vede. */
    fun confermaScaduta() {
        sulPrincipale {
            suoni?.suona(Suoni.Tipo.ERRORE)
            val r = TestiBolla.invio("scaduta")
            bolla.mostra(r)
            principale.postDelayed({ parlaPoi("Non hai detto invia in due minuti: non ho mandato niente.") { bolla.chiudiFra(BOLLA_FATTO_MS, r) } }, PAUSA_DOPO_SEGNALE_MS)
        }
    }

    /**
     * Banco di prova da ADB (ProvaAdbReceiver, azione «voce_prova»): fa quello che succede
     * quando Boss dice «Jarvis» e poi la frase, saltando solo microfono e Whisper. Segnale,
     * bolla «Ti ascolto», poi la frase entra in [frasePronta], la stessa strada della voce vera.
     */
    fun provaFraseVocale(testo: String) {
        sulPrincipale {
            diag("prova adb: parola simulata")
            apriCattura()
            principale.postDelayed({
                mostra(Ascolto.PENSO)
                bolla.mostra(TestiBolla.TRASCRIVO)
                frasePronta(testo, "prova")
            }, 1_500L)
        }
    }

    /**
     * La frase di Boss è pronta (dopo Whisper, o dal banco di prova). Da qualunque thread.
     * Bolla «Ho sentito» → «Sto lavorando…», poi la frase va a [sendUserText].
     */
    private fun frasePronta(testo: String, origine: String = "voce", timbro: Long? = null) {
        sulPrincipale {
            // 09/10: la trascrizione dura secondi; se intanto Boss ha messo in pausa o spento, la frase non parte.
            if (timbro != null && fraseScaduta(timbro, "dopo la trascrizione")) {
                if (StatoJarvis.corrente.ascolto == Ascolto.PENSO || StatoJarvis.corrente.ascolto == Ascolto.CATTURA) mostra(Ascolto.ASCOLTO)
                return@sulPrincipale
            }
            fraseProntaQui(testo, origine)
        }
    }

    /**
     * Sul thread principale. 09/10: col popup aperto la frase passa da [Conversazione]: può allungare
     * la domanda di prima invece di aprirne una nuova, o chiudere il popup («basta»). Le risposte a
     * una conferma in attesa passano com'erano: la conferma resta di PhoneActionExecutor.
     */
    private fun fraseProntaQui(testo: String, origine: String) {
        if (spento) return
        if (testo.isBlank()) {
            if (conversazione.aperta) {
                silenzioInConversazione()
                return
            }
            nessunParlato()
            mostra(Ascolto.ASCOLTO)
            return
        }
        val inSospeso = PhoneActionExecutor.inSospeso()
        var daMandare = testo
        var accodata = false
        if (conversazione.aperta) {
            if (!inSospeso && Conversazione.chiudeIlPopup(testo)) {
                rispostaTenuta = null
                chiudiConversazione("voce")
                mostra(Ascolto.ASCOLTO)
                val r = TestiBolla.Riga(getString(R.string.voce_bolla_chiuso), "", TestiBolla.Tono.FATTO)
                bolla.mostra(r)
                bolla.chiudiFra(2_000L, r)
                return
            }
            val tenuta = rispostaTenuta
            rispostaTenuta = null
            val d = conversazione.nuovaFrase(testo, SystemClock.elapsedRealtime(), inSospeso)
            daMandare = d.testo
            accodata = d.accodata
            if (tenuta != null) {
                // La risposta arrivata mentre Boss parlava: con un'aggiunta è vecchia (si consuma lo scarto),
                // con una domanda nuova non serve più, con una conferma si dice.
                when {
                    d.scartaRispostaPrecedente -> conversazione.risposta(SystemClock.elapsedRealtime())
                    inSospeso -> tenuta()
                    else -> diag("popup: risposta tenuta scartata, Boss ha chiesto altro")
                }
            }
            if (accodata) diag("popup: aggiunta alla domanda di prima (${daMandare.length} caratteri)")
            aggiornaPopup()
        }
        val sentito = if (accodata) {
            TestiBolla.Riga(getString(R.string.voce_bolla_aggiunta), "«${TestiBolla.accorcia(testo)}»", TestiBolla.Tono.LAVORO)
        } else TestiBolla.hoSentito(testo)
        bolla.mostra(sentito)
        if (!inSospeso) bolla.poi(1_200L, sentito, TestiBolla.stoLavorando(daMandare))
        sendUserText(daMandare, origine)
        // Col popup aperto il microfono resta aperto mentre JBoss pensa: Boss può aggiungere.
        if (conversazione.aperta && !inSospeso) principale.postDelayed({ riascoltaInConversazione("aggiunte") }, 400L)
    }

    /**
     * Sul thread del microfono: una frase chiusa dal Motore. Si passa subito
     * al thread di lavoro; il microfono torna a leggere.
     */
    private fun suFrase(ev: Evento.Frase, da: String?) {
        val timbro = cancello.timbro()
        if (!cancello.fraseValida(timbro)) return
        inLavoro { verificaEPassa(ev, da, timbro) }
    }

    /** 09/10: la frase è di prima di una pausa o di uno spegnimento? Allora si butta, senza trascriverla né mandarla. */
    private fun fraseScaduta(timbro: Long, dove: String): Boolean {
        if (cancello.fraseValida(timbro)) return false
        diag("frase buttata ($dove): la voce è ${cancello.modo.chiave} o è cambiata dopo la cattura")
        return true
    }

    /**
     * Sul thread di lavoro. Quello che prima faceva Motore.chiudi con il
     * verificatore, con le stesse regole: impronta pronta e almeno mezzo
     * secondo di crudo, accettata sopra sogliaImpronta. Le frasi del widget
     * e dei tasti passano comunque (l'ha chiesto l'utente col dito).
     */
    private fun verificaEPassa(ev: Evento.Frase, da: String?, timbro: Long) {
        if (spento || fraseScaduta(timbro, "prima della verifica")) return
        val s = somiglianzaDi(ev.crudo)
        val accettata = s == null || s >= opzioni.sogliaImpronta
        val somiglianza = s?.let { String.format(Locale.ITALIAN, "%.2f", it) } ?: "-"
        if (!accettata && da == null) {
            diag("voce non riconosciuta ($somiglianza)")
            if (conversazioneAttiva) {
                principale.post { silenzioInConversazione() }
                return
            }
            mostra(Ascolto.ASCOLTO)
            return
        }
        diag(
            "frase: ${String.format(Locale.ITALIAN, "%.1f", ev.durataS)} s, ${ev.motivo}, " +
                "somiglianza $somiglianza${if (da != null) ", dal $da" else ""}"
        )
        mostra(Ascolto.PENSO)
        bolla.mostra(TestiBolla.TRASCRIVO)
        trascriviEInvia(ev, da, s, timbro)
    }

    /** Sul thread di lavoro. null = impronta non pronta, frase troppo corta o nessuna voce. */
    private fun somiglianzaDi(crudo: FloatArray): Float? {
        val imp = impronta ?: return null
        if (!imp.pronta || crudo.size < Opzioni.RATE / 2) return null
        return try {
            imp.somiglianza(crudo)
        } catch (t: Throwable) {
            Log.w(TAG, "impronta: somiglianza non calcolata (${t.javaClass.simpleName})")
            null
        }
    }

    /** Sul thread di lavoro. */
    private fun trascriviEInvia(ev: Evento.Frase, da: String?, somiglianza: Float?, timbro: Long) {
        if (spento || fraseScaduta(timbro, "prima della trascrizione")) return
        val inizio = SystemClock.elapsedRealtime()
        val scelta = SceltaTrascrittore.di(this, opzioni)
        val perGoogle = audioPerGoogle(ev)
        val perWhisper = audioPerWhisper(ev)
        var motoreUsato = "whisper"
        var crudo: String? = null
        // 0.3.3: prima Google sul telefono (sull'audio già catturato: nessun passaggio di microfono),
        // poi Whisper se Google non c'è, fallisce o non sente parole.
        if (scelta == SceltaTrascrittore.GOOGLE) {
            val g = trascrittoreGoogle ?: TrascrittoreGoogle(this).also { trascrittoreGoogle = it }
            crudo = runCatching { g.trascrivi(perGoogle) }.getOrNull()?.takeIf { it.isNotBlank() }
            if (crudo != null) motoreUsato = "google" else diag("google: ${g.ultimoEsito}, ripiego su Whisper")
        }
        if (crudo == null) {
            val tr = trascrittore()
            if (tr == null) {
                // La frase non si finge trascritta.
                diag("frase catturata ma Whisper non è scaricato: non la trascrivo")
                mostra(Ascolto.ASCOLTO, "Scarica i modelli dalle Impostazioni")
                errore("manca il modello della voce", "scaricalo dalle Impostazioni")
                return
            }
            crudo = try {
                tr.trascrivi(perWhisper)
            } catch (t: Throwable) {
                diag("trascrizione fallita: ${t.javaClass.simpleName}")
                mostra(Ascolto.ASCOLTO)
                errore("non sono riuscito a trascrivere", "riprova")
                return
            }
        }
        val (testo, correzioni) = glossario.postCorreggi(crudo.orEmpty())
        val ms = SystemClock.elapsedRealtime() - inizio
        diag(
            "trascritta da $motoreUsato in $ms ms" +
                (if (correzioni.isNotEmpty()) ", ${correzioni.size} correzioni del glossario" else "") +
                // Il testo nel logcat solo con lo strumento di misura acceso (come la 0.3.2 lo scriveva sempre).
                (if (FrasiDebug.acceso(this)) ": ${testo.take(80)}" else ", ${testo.length} caratteri")
        )
        // Lo strumento di misura (spento di default): l'audio e il testo restano nella cartella dell'app.
        runCatching {
            FrasiDebug.salva(
                this, ev.prima + ev.tutto, if (motoreUsato == "google") perGoogle else perWhisper,
                listOf(
                    "motore=$motoreUsato scelta=$scelta ms=$ms da=${da ?: "parola"} motivo=${ev.motivo} durata_s=${ev.durataS}",
                    "prima_ms=${ev.prima.size * 1000L / Opzioni.RATE} ignorati_ms=${opzioni.ignoraInizialiMs}",
                    "testo=$testo",
                ),
            )
        }
        frasePronta(testo, timbro = timbro)
        if (testo.isNotBlank()) imparaOArchivia(ev, testo, da, somiglianza)
    }

    /**
     * 0.3.3: a Google va il microfono crudo (Google ha i suoi filtri) dall'inizio della cattura, con
     * davanti [CODA_PRIMA_MS] di pre-roll: se Boss dice la frase di fila dopo la parola, l'inizio
     * non si perde (la parola che resta la toglie il cervello). Col guadagno: misurato sul S24, voce
     * lontana 59 → 61% di parole giuste per Google (per Whisper peggiora: a lui niente guadagno).
     */
    private fun audioPerGoogle(ev: Evento.Frase): FloatArray = Guadagno.normalizza(audioCrudo(ev))

    /** Il microfono crudo: gli ultimi [CODA_PRIMA_MS] del pre-roll più tutta la cattura. */
    private fun audioCrudo(ev: Evento.Frase): FloatArray {
        val n = minOf(ev.prima.size, (CODA_PRIMA_MS * Opzioni.RATE / 1000).toInt())
        val tutto = if (ev.tutto.isNotEmpty()) ev.tutto else ev.crudo
        return ev.prima.copyOfRange(ev.prima.size - n, ev.prima.size) + tutto
    }

    /**
     * 0.3.3: anche a Whisper va il microfono CRUDO con il pre-roll, non più l'audio passato dal filtro
     * GTCRN e senza i primi 300 ms. Banco sul Mac del 07/10 (45 comandi, voci di `say`, pulito e
     * lontano): con GTCRN e i 300 ms tagliati la prima parola giusta era il 2-22%, col crudo 27-30%,
     * e le parole giuste salivano di 11-30 punti. Il filtro resta per KWS e VAD.
     */
    private fun audioPerWhisper(ev: Evento.Frase): FloatArray = audioCrudo(ev)

    /**
     * Come sul computer: finché l'impronta non c'è, le frasi a mani libere di almeno
     * due parole la insegnano; le frasi del widget la insegnano sempre
     * (Opzioni.imparaDalTasto); le altre si archiviano soltanto.
     * Sul thread di lavoro.
     */
    private fun imparaOArchivia(ev: Evento.Frase, testo: String, da: String?, somiglianza: Float?) {
        val imp = impronta ?: return
        val parole = testo.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        try {
            when {
                da != null && opzioni.imparaDalTasto -> imp.impara(ev.crudo, testo, da)
                !imp.pronta && parole >= 2 -> imp.impara(ev.crudo, testo, "mani libere")
                else -> imp.archivia(ev.crudo, testo, "mani libere", somiglianza, null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "impronta: ${t.javaClass.simpleName}")
        }
        val eraPronta = StatoJarvis.corrente.improntaPronta
        pubblicaImpronta(imp)
        if (!eraPronta && imp.pronta) diag("impronta pronta: ${imp.origine}, da ora il filtro della voce è acceso")
    }

    /** Sul thread di lavoro. Whisper si carica alla prima frase, se è stato scaricato nel frattempo anche dopo l'avvio. */
    private fun trascrittore(): TrascrittoreWhisper? {
        trascrittore?.let { return it }
        val presente = modelli.presente(Modello.WHISPER_SMALL)
        StatoJarvis.aggiorna { it.copy(whisperPresente = presente, whisperByte = byteWhisper()) }
        if (!presente) return null
        val v = vadTrascrizione ?: VadSilero(assets, soglia = opzioni.sogliaVad).also { vadTrascrizione = it }
        return TrascrittoreWhisper(modelli, v).also { trascrittore = it }
    }

    private fun byteWhisper(): Long =
        Modello.WHISPER_SMALL.file.sumOf { f -> modelli.percorso(f.nome).let { if (it.isFile) it.length() else 0L } }

    // ================================================================ il dettato dell'interfaccia (1.1.0)

    /**
     * Il microfono del campo di testo (MainActivity → DettatoNativo): Google, o il suo Microfono
     * trascritto con il Whisper di qui.
     * Perché non ci siano due AudioRecord aperti, finché dura il dettato il microfono del servizio è
     * chiuso; si riapre con [riprendiDopoDettato], o da solo dopo [PAUSA_DETTATO_MAX_MS] se
     * l'interfaccia muore a metà. Sul thread principale.
     */
    fun pausaPerDettato() {
        if (spento) return
        inPausaDettato = true
        principale.removeCallbacks(fineSicurezzaDettato)
        principale.postDelayed(fineSicurezzaDettato, PAUSA_DETTATO_MAX_MS)
        microfono?.let { if (it.attivo) { it.ferma(); batteria.ferma() } }
        diag("dettato dell'interfaccia: microfono del servizio in pausa")
    }

    /** Sul thread principale. Se non era in pausa non fa niente. */
    fun riprendiDopoDettato() {
        principale.removeCallbacks(fineSicurezzaDettato)
        if (!inPausaDettato) return
        inPausaDettato = false
        diag("dettato dell'interfaccia finito: riapro il microfono")
        avviaSeServe()
    }

    /**
     * Trascrive [pcm] (float 16 kHz mono) con lo stesso Whisper e lo stesso glossario della voce, sul
     * thread di lavoro: il modello (375 MB) resta caricato una volta sola. [risposta] riceve null se
     * Whisper manca o la trascrizione fallisce. Il testo non va nei log.
     */
    fun trascriviPerDettato(pcm: FloatArray, risposta: (String?) -> Unit) {
        if (spento) return risposta(null)
        val accettato = runCatching {
            lavoro.execute {
                val testo = runCatching {
                    val tr = trascrittore() ?: return@runCatching null
                    glossario.postCorreggi(tr.trascrivi(pcm)).first
                }.getOrNull()
                risposta(testo)
            }
        }.isSuccess
        if (!accettato) risposta(null)
    }

    // ================================================================ la frase va al nucleo (niente ponte)

    /**
     * Una frase di Boss: dalla voce (dopo Whisper), dal banco ADB o dalla schermata.
     * Passo A di Jarvis Telefono: niente VPS. La frase va a [Nucleo.elabora], che fa decidere il
     * cervello del telefono e comanda le mani; la risposta torna in [rispondi]. Da qualunque thread.
     */
    fun sendUserText(text: String, origine: String = "voce") {
        sulPrincipale {
            if (!PhoneActionExecutor.inSospeso() && StatoJarvis.corrente.ascolto != Ascolto.GUASTO) mostra(Ascolto.PENSO)
            else mostra(Ascolto.ASCOLTO)
            Nucleo.elabora(this, text, origine)
        }
    }

    /**
     * Una bozza aspetta Boss: Jarvis lo dice e, finita la frase, apre il microfono così basta
     * dire «invia» senza la parola di attivazione. Da qualunque thread.
     */
    fun chiediConferma(testo: String) {
        sulPrincipale {
            parlaPoi(testo) {
                principale.postDelayed({
                    if (PhoneActionExecutor.inSospeso() && voce?.staParlando != true) ascolta("conferma d'invio")
                }, 1_500L) // la voce di sistema dice «finito» prima che l'altoparlante abbia finito
            }
        }
    }

    /**
     * La risposta del nucleo (era `assistant_message` del ponte): segnale, bolla «Fatto» o errore,
     * poi la voce di sistema; la bolla sparisce 3 s dopo la voce. Testo vuoto = niente da dire
     * (lo ha già detto qualcun altro, per esempio la conferma scaduta). Da qualunque thread.
     */
    fun rispondi(reply: String, errore: Boolean, agente: String? = null, riascolta: Boolean = false, vps: Boolean = false) {
        sulPrincipale {
            if (conversazione.aperta && motore?.utenteParla == true) {
                // 09/10: Boss sta parlando nel popup. La voce di JBoss gli taglierebbe la frase: la risposta
                // aspetta la fine della frase (fraseProntaQui decide se dirla o scartarla).
                diag("popup: risposta tenuta, Boss sta parlando")
                rispostaTenuta = { rispondiOra(reply, errore, agente, riascolta, vps) }
                return@sulPrincipale
            }
            rispondiOra(reply, errore, agente, riascolta, vps)
        }
    }

    /** Sul thread principale. */
    private fun rispondiOra(reply: String, errore: Boolean, agente: String?, riascolta: Boolean, vps: Boolean) {
        run {
            if (conversazione.aperta && !conversazione.risposta(SystemClock.elapsedRealtime())) {
                // La risposta alla domanda di prima dell'aggiunta: ne arriva una per la domanda intera.
                diag("popup: risposta alla domanda prima dell'aggiunta, non la dico")
                return
            }
            if (reply.isBlank()) {
                if (StatoJarvis.corrente.ascolto == Ascolto.PENSO) mostra(Ascolto.ASCOLTO)
                if (!PhoneActionExecutor.inSospeso()) bolla.chiudiFra(BOLLA_FATTO_MS, null)
                if (conversazione.aperta) dopoLaRisposta("risposta vuota")
                return
            }
            // Voce sintetica: si dice solo l'esito. Le risposte delle regole sono già brevi.
            val parlato = if (Prefs.isVoceSintetica(this)) {
                runCatching { PerLaVoce.perLaVoce(reply) }.getOrNull()?.takeIf { it.isNotBlank() } ?: reply
            } else reply
            val riga = TestiBolla.perRisposta(reply, errore).copy(agente = agente, daVps = vps)
            suoni?.suona(if (riga.tono == TestiBolla.Tono.ERRORE) Suoni.Tipo.ERRORE else Suoni.Tipo.FATTO)
            bolla.mostra(riga)
            // 0.3.3: «non ho capito» dalla voce → il microfono si riapre da solo, una volta sola.
            // 09/10: col popup aperto si riapre sempre (riascoltaInConversazione), senza contare.
            val inConversazione = conversazione.aperta
            val riapri = riascolta && !riascoltoUsato && !inConversazione
            if (riascolta && !inConversazione) riascoltoUsato = !riascoltoUsato
            // 09/10: col popup aperto «JBoss» detto sopra la voce la interrompe. Non se la risposta
            // contiene la parola stessa: l'eco la farebbe scattare da sola.
            motore?.parolaDuranteVoce = inConversazione && modo.ascolta && !parlato.contains("boss", ignoreCase = true)
            principale.postDelayed({
                parlaPoi(parlato) {
                    motore?.parolaDuranteVoce = false
                    bolla.chiudiFra(if (riga.tono == TestiBolla.Tono.ATTESA) 8_000L else BOLLA_FATTO_MS, riga)
                    if (riapri) principale.postDelayed({
                        if (!spento && !PhoneActionExecutor.inSospeso() && voce?.staParlando != true) ascolta("riprova")
                    }, 600L)
                    if (conversazione.aperta) dopoLaRisposta("dopo la risposta")
                }
            }, PAUSA_DOPO_SEGNALE_MS)
            principale.removeCallbacks(guardiaVoce)
            principale.postDelayed(guardiaVoce, GUARDIA_VOCE_MS + PAUSA_DOPO_SEGNALE_MS)
        }
    }

    /**
     * 0.3.3: il banco di confronto sul telefono (ProvaAdbReceiver «banco_trascrivi»): ogni WAV della
     * cartella esterna files/[nome]/ passa da Google sul telefono e da Whisper, con i tempi. Risultati
     * in files/[nome]/risultati.tsv; nel logcat solo i conteggi. Sul thread di lavoro.
     */
    fun bancoTrascrivi(nome: String) {
        inLavoro {
            val dir = getExternalFilesDir(null)?.let { File(it, nome) }
            val wav = dir?.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
            if (dir == null || wav.isEmpty()) { diag("banco: nessun WAV in ${dir?.absolutePath}"); return@inLavoro }
            val g = trascrittoreGoogle ?: TrascrittoreGoogle(this).also { trascrittoreGoogle = it }
            val tr = trascrittore()
            val guadagno = nome.endsWith("-guadagno")
            val righe = mutableListOf("file\tgoogle_ms\tgoogle_esito\tgoogle\twhisper_ms\twhisper")
            var nG = 0; var nW = 0; var msG = 0L; var msW = 0L
            for (f in wav) {
                val letto = FrasiDebug.leggiWav(f) ?: run { righe += "${f.name}\t-\tformato\t\t-\t"; null } ?: continue
                val pcm = if (guadagno) Guadagno.normalizza(letto) else letto
                val t0 = SystemClock.elapsedRealtime()
                val testoG = runCatching { g.trascrivi(pcm) }.getOrNull()
                val dG = SystemClock.elapsedRealtime() - t0
                val t1 = SystemClock.elapsedRealtime()
                val testoW = runCatching { tr?.trascrivi(pcm) }.getOrNull()
                val dW = SystemClock.elapsedRealtime() - t1
                if (testoG != null) { nG++; msG += dG }
                if (testoW != null) { nW++; msW += dW }
                righe += "${f.name}\t$dG\t${g.ultimoEsito}\t${testoG.orEmpty().replace('\t', ' ')}\t$dW\t${testoW.orEmpty().replace('\t', ' ')}"
            }
            File(dir, "risultati.tsv").writeText(righe.joinToString("\n", postfix = "\n"))
            diag("banco: ${wav.size} WAV, google ${nG} (media ${if (nG > 0) msG / nG else -1} ms), whisper ${nW} (media ${if (nW > 0) msW / nW else -1} ms) → ${dir.absolutePath}/risultati.tsv")
        }
    }

    /** 0.3.3: una frase capita chiude il giro dei «non ho capito» (la prossima volta si riascolta di nuovo). */
    fun capito() {
        sulPrincipale { riascoltoUsato = false }
    }

    /** Una riga di diagnosi, solo nel logcat. Mai il testo detto, mai segreti. Da qualunque thread. */
    fun diag(text: String) {
        Log.i(TAG, "diag: $text")
    }

    // ================================================================ compatibilità

    /** Il tasto laterale (JarvisVoiceInteractionSession) usa ancora questo nome. */
    @Deprecated("Usare ascolta()", ReplaceWith("ascolta(\"tasto laterale\")"))
    fun startVoiceSession(@Suppress("UNUSED_PARAMETER") timeoutMs: Long = 0L, onDone: (() -> Unit)? = null) {
        sulPrincipale {
            ascolta("tasto laterale")
            onDone?.invoke()
        }
    }

    // Il Manifest dichiara il servizio di tipo "microphone|dataSync", ma su
    // Android 14 (targetSdk 34) dichiarare "microphone" a startForeground()
    // senza avere GIA' il permesso RECORD_AUDIO concesso fa lanciare una
    // SecurityException e crasha il servizio. Qui si passa sempre il
    // sottoinsieme di tipi coerente col permesso reale in quel momento.
    // Anche col permesso, da Android 14 il tipo microphone non si può
    // dichiarare con l'app in sottofondo (avvio dal BootReceiver o
    // START_STICKY): il sistema lancia un'eccezione. Allora si resta in primo
    // piano col solo dataSync e si restituisce false: il microfono non si apre.
    private fun startForegroundCompat(notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return micPermesso()
        }
        if (micPermesso()) {
            try {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                return true
            } catch (e: Exception) {
                Log.w(TAG, "tipo microphone rifiutato (${e.javaClass.simpleName}): resto col solo dataSync")
            }
        }
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return false
    }

    // ================================================================ stato e notifica

    private fun testoDi(a: Ascolto): String = when {
        // 09/10: in pausa o spenta la notifica lo dice, qualunque cosa chieda il giro.
        (a == Ascolto.ASCOLTO || a == Ascolto.CATTURA) && modo == ModoVoce.PAUSA -> getString(R.string.voce_notifica_pausa)
        (a == Ascolto.ASCOLTO || a == Ascolto.CATTURA) && modo == ModoVoce.SPENTO -> getString(R.string.voce_notifica_spenta)
        a == Ascolto.ASCOLTO && conversazione.aperta -> getString(R.string.voce_notifica_conversazione)
        else -> testoBase(a)
    }

    private fun testoBase(a: Ascolto): String = when (a) {
        Ascolto.ASCOLTO -> "In ascolto: di' \"JBoss\""
        Ascolto.CATTURA -> "Ti ascolto"
        Ascolto.PENSO -> "Penso…"
        Ascolto.PARLO -> "Parlo"
        Ascolto.GUASTO -> "Guasto"
        Ascolto.SPENTO -> "Spento"
    }

    /**
     * Cambia lo stato in [StatoJarvis] e la notifica. [testo] sostituisce il
     * testo normale dello stato (per dire perché si è tornati lì).
     * Da qualunque thread.
     */
    private fun mostra(a: Ascolto, testo: String? = null, guasto: String? = null) {
        if (spento) return
        StatoJarvis.aggiorna { it.copy(ascolto = a, guasto = if (a == Ascolto.GUASTO) guasto else null) }
        mostraSoloNotifica(testo ?: testoDi(a))
    }

    /** Rifà la notifica col testo dello stato attuale e i tasti del modo attuale. Sul thread principale. */
    private fun aggiornaNotifica() {
        if (spento) return
        synchronized(lockNotifica) { ultimaChiaveNotifica = null }
        mostraSoloNotifica(testoDi(StatoJarvis.corrente.ascolto))
    }

    private fun mostraSoloNotifica(testo: String) {
        if (spento) return
        synchronized(lockNotifica) {
            // Il modo fa parte della chiave: stesso testo con tasti diversi va rifatto.
            val chiave = "${modo.chiave}|$testo"
            if (chiave == ultimaChiaveNotifica) return
            ultimaChiaveNotifica = chiave
            ultimaNotifica = testo
        }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(testo))
        }
    }

    /** Sul thread di lavoro; dopo lo spegnimento non accetta più niente, e non si lamenta. */
    private fun inLavoro(f: () -> Unit) {
        runCatching { lavoro.execute(f) }.onFailure { Log.i(TAG, "lavoro rifiutato: servizio spento") }
    }

    private fun sulPrincipale(f: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) f() else principale.post(f)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "JBoss attivo", NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // 09/10: i tasti dipendono dal modo (RegoleModo.tastiNotifica). «Spegni» spegne la voce, non il
        // servizio: la notifica resta con «Riprendi». «Esci» (solo da spenta) ferma tutto come prima «Spegni».
        for (azione in RegoleModo.tastiNotifica(modo)) {
            val (azioneIntent, codice, etichetta) = when (azione) {
                AzioneVoce.PAUSA -> Triple(ACTION_PAUSA, 2, R.string.voce_tasto_pausa)
                AzioneVoce.RIPRENDI -> Triple(ACTION_RIPRENDI, 3, R.string.voce_tasto_riprendi)
                AzioneVoce.SPEGNI -> Triple(ACTION_SPEGNI_VOCE, 4, R.string.voce_tasto_spegni)
                AzioneVoce.ASCOLTA -> Triple(ACTION_LISTEN, 1, R.string.voce_tasto_ascolta)
                AzioneVoce.ESCI -> Triple(ACTION_STOP, 0, R.string.voce_tasto_esci)
                AzioneVoce.INTERRUTTORE -> continue
            }
            val pi = PendingIntent.getService(
                this, codice, Intent(this, JarvisService::class.java).setAction(azioneIntent),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, getString(etichetta), pi)
        }
        return b.build()
    }
}

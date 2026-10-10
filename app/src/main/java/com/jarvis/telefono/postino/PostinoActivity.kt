package com.jarvis.telefono.postino

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.jarvis.telefono.DettatoNativo
import com.jarvis.telefono.Permessi
import com.jarvis.telefono.R

/**
 * La chat Postino (Boss, 2026-10-07): il resoconto numerato di tutte le caselle, letto dalla VPS;
 * Boss decide per numeri, scritti o detti; la VPS esegue, chiede il sì prima di ogni invio e
 * rilegge la casella; qui si vede per ogni numero «fatto» con la prova, o l'errore con il motivo.
 *
 * Si apre con `Intent(context, PostinoActivity::class.java)` (la schermata Agenti di telefono-ui la
 * innesta così). Con l'extra [EXTRA_DEMO] usa [CanaleDemo] (dati finti, lo dice in alto).
 * Senza modulo VPS registrato in [FornitoreCanale] mostra «collega la VPS» e non fa niente.
 *
 * 0.5.0 (Boss, 08/10/2026: «il Postino nella sua pagina NON lavora bene»):
 * - tocco su una mail = la apre (mittente, data, oggetto, riassunto di 3-5 righe che cita gli allegati
 *   letti sulla VPS, allegati, testo, destinazione dell'archivio); ◀ ▶ e i tasti del volume = precedente
 *   e successiva; il campo in basso parla con JBoss su quella mail («rispondi che arrivo lunedì»);
 * - bozza visibile e modificabile (destinatario e testo) prima dell'invio; l'invio passa SEMPRE dalla
 *   conferma Invia/Annulla della VPS, e «inviata» arriva solo dopo la rilettura di Inviata;
 * - tocco lungo = selezione multipla, poi tocco per aggiungere; «seleziona tutte le fatture»;
 * - archivio: la cartella proposta o una scelta fra le cartelle VERE della casella, mai inventate.
 *
 * 0.6.6 (Boss, 08/10/2026, layout approvato; la logica sta in [AzioniPostino]):
 * - swipe a SINISTRA = Elimina, SEMPRE con conferma sul telefono (anche una mail sola), poi quella della VPS;
 * - swipe a DESTRA = Archivia subito nella cartella proposta, senza conferma, con «Annulla» per 5 secondi:
 *   il comando parte allo scadere, quindi annullare lascia la mail dov'è; senza cartella vera si chiede;
 * - nella mail aperta: Rispondi (Boss detta o scrive, JBoss prepara la bozza, parte solo con Invia…),
 *   Fatto (segna letta, poi archivia), Dopo (resta in arrivo), Archivia, Spam… ed Elimina… (con conferma);
 * - il riassunto in due righe si vede sulla scheda e sopra i tasti, prima di scegliere.
 *
 * 0.7.2 (Boss, 09/10/2026: «ci sono interferenze, JBoss dovrebbe subito collegarsi al Postino e aggiornare la pagina»):
 * la conversazione, il canale, la mail corrente e le conferme stanno in [PostaCondivisa] ([Postino.hub]), la stessa che
 * usa JBoss. La pagina la guarda e si ridisegna a ogni cambio, anche se il comando è partito da JBoss o a pagina chiusa.
 * Elimina e Spam chiedono il sì nel riquadro della pagina (lo stesso box compare nel filo di JBoss), non in un popup.
 */
class PostinoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEMO = "demo"
        /** 0.5.0, solo da ADB (l'activity chiede android.permission.DUMP): un comando come se Boss l'avesse scritto. */
        const val EXTRA_COMANDO = "comando"
        /** 0.5.0, solo da ADB: «invia» o «annulla» sulla conferma in attesa, come il tocco sul pannello. */
        const val EXTRA_CONFERMA = "conferma"
        /** 09/10: la risposta della pagina a una frase non capita. Non parte niente, nessuna bozza. */
        const val NON_CAPITO_PAGINA = "Non ho capito. Puoi dire «leggi le mail», «avanti», «cancella questa», «torna a JBoss»."

        fun intento(c: Context, demo: Boolean = false) =
            Intent(c, PostinoActivity::class.java).putExtra(EXTRA_DEMO, demo)
    }

    private val principale = Handler(Looper.getMainLooper())
    /** La posta unica dell'app (o quella della prova con dati finti). */
    private lateinit var h: PostaCondivisa
    private val s: SessionePostino get() = h.sessione
    private val canale: CanalePostino? get() = h.canale
    private var smetti: (() -> Unit)? = null
    /** 09/10: «torna a JBoss» (a voce, nella chat o qui) chiude la pagina e riporta alla chat di JBoss. */
    private var smettiRitorno: (() -> Unit)? = null
    private var demo = false

    /** Per il segno «app in primo piano» ([com.jarvis.telefono.bolla.PrimoPiano]): la pagina vera, non la prova. */
    val paginaVera: Boolean get() = !demo
    private var filtro = StatoPostino.Filtro.DA_FARE
    private val selezione = sortedSetOf<Int>()
    private lateinit var barraAgente: com.jarvis.telefono.ui.ControlloreBarra

    private lateinit var lista: RecyclerView
    private lateinit var adattatore: Adattatore
    private lateinit var campo: EditText
    private lateinit var frase: TextView
    private lateinit var riassunto: TextView
    private lateinit var spia: TextView
    private lateinit var banner: TextView
    private lateinit var vuoto: TextView
    private lateinit var pannello: MaterialCardView
    private lateinit var barra: View
    private lateinit var filtri: ChipGroup
    private var confermaMostrata: String? = null

    // 0.5.0: mail aperta, archivio con destinazione, bozza
    private lateinit var dettaglio: View
    private lateinit var pannelloArchivio: MaterialCardView
    /** La mail aperta (numero del resoconto), o null sulla lista. */
    private var aperta: Int? = null
    private var testoAperto = false
    private val chiestaScheda = mutableSetOf<Int>()
    private val inizioApertura = mutableMapOf<Int, Long>()
    private var archivioNumeri: List<Int> = emptyList()
    private val archivioScelte = mutableMapOf<Int, String?>()
    /** Per ogni mail la versione di bozza già messa nei campi: i campi non si riscrivono sotto le dita di Boss. */
    private val bozzaMostrata = mutableMapOf<Int, Long>()
    private var bozzaToccata = false
    /** 09/10: Boss ha toccato Rispondi su questa mail: la frase che scrive è il testo della risposta (bozza). */
    private var attesaRisposta: Int? = null
    private var ultimaCategoria: String? = null

    // 0.6.6: archivi da swipe in attesa di partire (annullabili); le mail già partite le sa la posta unica
    private val coda = AzioniPostino.CodaAnnullabile()
    private val partenzaArchivi = Runnable { mandaArchivi(tutte = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_postino)
        demo = intent.getBooleanExtra(EXTRA_DEMO, false)
        h = if (demo) Postino.demo() else Postino.hub(this)

        lista = findViewById(R.id.lista)
        // 2026-10-10: la barra degli agenti (campo, Parla, Chiama, Invia), la stessa delle altre chat.
        barraAgente = com.jarvis.telefono.ui.ControlloreBarra(
            this, findViewById(android.R.id.content), com.jarvis.telefono.agenti.CatalogoAgenti.POSTINO,
            invia = { t, _ -> daCampo(t) },
            occupato = { s.inCorso.isNotEmpty() },
            avviso = { t, errore -> mostraFrase(t, errore) },
        )
        campo = barraAgente.campo
        frase = findViewById(R.id.frase)
        riassunto = findViewById(R.id.riassunto)
        spia = findViewById(R.id.spia)
        banner = findViewById(R.id.banner)
        vuoto = findViewById(R.id.vuoto)
        pannello = findViewById(R.id.pannelloConferma)
        barra = findViewById(R.id.barraSelezione)
        filtri = findViewById(R.id.filtri)
        findViewById<android.widget.ImageButton>(R.id.tasto_indietro).setOnClickListener { finish() }
        avatar()?.let { findViewById<ImageView>(R.id.avatar).setImageResource(it) }

        adattatore = Adattatore()
        lista.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = false }
        lista.adapter = adattatore
        lista.itemAnimator = DefaultItemAnimator().apply {
            val d = resources.getInteger(R.integer.postino_durata_media).toLong()
            addDuration = d; removeDuration = d; changeDuration = d; moveDuration = d
        }

        for (f in StatoPostino.Filtro.values()) {
            filtri.addView(Chip(this).apply {
                id = View.generateViewId()
                tag = f
                isCheckable = true
                isChecked = f == filtro
                text = f.etichetta
                setOnClickListener { filtro = f; ridisegna() }
            })
        }

        dettaglio = findViewById(R.id.dettaglio)
        pannelloArchivio = findViewById(R.id.pannelloArchivio)
        preparaDettaglio()
        preparaSwipe()
        findViewById<MaterialButton>(R.id.archivioAnnulla).setOnClickListener { chiudiArchivio() }
        findViewById<MaterialButton>(R.id.archivioConferma).setOnClickListener { confermaArchivio() }

        findViewById<MaterialButton>(R.id.scriviJBoss).setOnClickListener { scriviAJBoss() }
        findViewById<MaterialButton>(R.id.parlaJBoss).setOnClickListener { parlaAJBoss() }
        findViewById<MaterialButton>(R.id.chiamaJBoss).setOnClickListener { chiamaJBoss() }
        campo.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(t: android.text.Editable?) = anteprimaComando(t?.toString().orEmpty())
        })
        findViewById<MaterialButton>(R.id.confermaInvia).setOnClickListener { rispondiConferma("invia") }
        findViewById<MaterialButton>(R.id.confermaAnnulla).setOnClickListener { rispondiConferma("annulla") }
        preparaBarraSelezione()
        collega()
        ridisegna()
        if (!demo) smettiRitorno = Postino.osservaRitorno { principale.post { vaiAJBoss() } }
        daAdb(intent)
    }

    override fun onResume() {
        super.onResume()
        // Boss 09/10: dopo «passa al Postino» la voce resta accesa anche qui (l'ascolto continuo vive con l'app davanti):
        // se è già aperto (si arriva dalla chat di JBoss) continua, se no parte come nella chat di JBoss.
        if (!demo && Postino.passaggio.conPostino) com.jarvis.telefono.JarvisService.instance?.chatJBossAperta()
    }

    /** Si esce dalla schermata: gli archivi in attesa partono adesso, non si perdono. */
    override fun onPause() {
        mandaArchivi(tutte = true)
        super.onPause()
    }

    override fun onStop() {
        // i 15 minuti del ritorno automatico a JBoss contano da quando Boss lascia la pagina
        if (!demo) Postino.paginaLasciata()
        barraAgente.ferma()
        super.onStop()
    }

    override fun onDestroy() {
        smettiRitorno?.invoke(); smettiRitorno = null
        principale.removeCallbacks(partenzaArchivi)
        // 09/10: il canale è dell'app e resta in ascolto; la pagina smette solo di guardare.
        smetti?.invoke(); smetti = null
        h.vistaChiusa(pagina = true)
        barraAgente.rilascia()
        super.onDestroy()
    }

    /** L'avatar di telefono-ui se c'è (avatar_postino_128), altrimenti la busta. */
    private fun avatar(): Int? = resources.getIdentifier("avatar_postino_128", "drawable", packageName).takeIf { it != 0 }

    // ---------------------------------------------------------------- collegamento

    private fun collega() {
        // 09/10: la pagina guarda la posta unica: ogni evento della VPS (anche di un comando di JBoss) la ridisegna.
        smetti = h.osserva { principale.post { if (!isFinishing && !isDestroyed) dallaPosta() } }
        h.vistaAperta(pagina = true)
        val c = canale
        if (c == null) {
            banner.visibility = View.GONE
            spia.text = "● VPS spenta"
            spia.setTextColor(ContextCompat.getColor(this, R.color.spia_rosso))
            return
        }
        if (demo) {
            banner.text = getString(R.string.postino_demo)
            banner.visibility = View.VISIBLE
        }
        // 0.6.6 (catene Fatto), 0.3.0 (pillola della Home), ripresa dopo la riconnessione: li fa la posta unica.
        if (demo && s.stato.reportId == null && s.inCorso.isEmpty()) manda("controlla la posta")
        else resocontoSeVuoto()
    }

    /** La posta unica è cambiata (VPS, JBoss, un tocco qui). La mail aperta segue quella corrente di JBoss. */
    private fun dallaPosta() {
        // 0.4.2 (Boss: «mancano tutte le mail»): il resoconto parte da solo appena c'è il collegamento.
        resocontoSeVuoto()
        val c = h.corrente
        if (aperta != null && c != null && c != aperta) apriMail(c) else ridisegna()
    }

    private fun collegato() = canale?.collegato == true

    override fun onNewIntent(nuovo: Intent) {
        super.onNewIntent(nuovo)
        daAdb(nuovo)
    }

    /** Comando o conferma arrivati da ADB: si aspetta il collegamento (fino a 20 s), poi come dal campo o dal pannello. */
    private fun daAdb(i: Intent?) {
        val comando = i?.getStringExtra(EXTRA_COMANDO)?.takeIf { it.isNotBlank() }
        val conferma = i?.getStringExtra(EXTRA_CONFERMA)?.takeIf { it == "invia" || it == "annulla" }
        i?.removeExtra(EXTRA_COMANDO); i?.removeExtra(EXTRA_CONFERMA)
        if (comando == null && conferma == null) return
        val inizio = System.currentTimeMillis()
        fun prova() {
            if (isFinishing) return
            if (!collegato() && System.currentTimeMillis() - inizio < 20_000) { principale.postDelayed({ prova() }, 400); return }
            // 09/10: come scritto nel campo (stesso riconoscimento delle frasi libere, niente bozze da frasi non capite)
            if (conferma != null) rispondiConferma(conferma) else daCampo(comando!!)
        }
        prova()
    }

    private var resocontoChiesto = false

    /** 0.4.2: nessun resoconto e niente in corso → «controlla la posta», una volta per schermata. */
    private fun resocontoSeVuoto() {
        if (demo || resocontoChiesto || !collegato()) return
        if (s.stato.reportId != null || s.inCorso.isNotEmpty()) return
        resocontoChiesto = true
        manda("controlla la posta")
    }

    // ---------------------------------------------------------------- comandi

    private fun anteprimaComando(t: String) {
        if (t.isBlank()) { mostraFrase(s.stato.ultimaFrase.takeIf { it.isNotEmpty() && s.inCorso.isNotEmpty() }); return }
        if (PassaggioPostino.capisci(t) == PassaggioPostino.Verso.A_JBOSS) { mostraFrase("Torni alla chat di JBoss."); return }
        ComandiBrevi.capisci(t, aperta != null)?.let { mostraFrase("Scorciatoia: ${nomeBreve(it)}"); return }
        if (eFraseLibera(t)) { mostraFrase("Il Postino capisce: «${t.trim()}»."); return }
        if (aperta != null && !eComandoANumeri(t)) {
            if (ComandiBrevi.istruzione(t, aperta, attesaRisposta == aperta) != null) mostraFrase("JBoss prepara la bozza per la mail $aperta: non parte niente senza il tuo Invia.")
            else mostraFrase(null)
            return
        }
        try {
            mostraFrase(ComandiPostino.anteprima(ComandiPostino.capisci(t)))
        } catch (e: ComandiPostino.Errore) {
            mostraFrase(e.message, errore = true)
        }
    }

    /** Manda un comando alla VPS e torna l'id del lavoro (null se non è partito).
     *  [silenzioso] (0.6.6, comandi partiti da soli): non svuota il campo né la selezione e non scorre la lista. */
    private fun manda(testo: String, silenzioso: Boolean = false): String? {
        if (canale == null) { mostraFrase("La VPS non è collegata: non mando niente.", errore = true); return null }
        if (testo.isBlank()) return null
        val id = esito(h.manda(testo), silenzioso) ?: return null
        if (!silenzioso) {
            campo.setText("")
            selezione.clear()
        }
        ridisegna()
        if (!silenzioso) lista.post { lista.scrollToPosition(adattatore.itemCount - 1) }
        return id
    }

    /** 0.6.6: il primo comando subito, i successivi solo dopo che la VPS ha verificato il primo. */
    private fun mandaCatena(comandi: List<String>, silenzioso: Boolean = false): String? {
        if (canale == null) { mostraFrase("La VPS non è collegata: non mando niente.", errore = true); return null }
        val id = esito(h.mandaCatena(comandi), silenzioso) ?: return null
        ridisegna()
        return id
    }

    /** L'esito di un comando: l'id se è partito; cestina e spam aspettano il sì nel riquadro (09/10). */
    private fun esito(e: PostaCondivisa.Esito, silenzioso: Boolean): String? = when (e) {
        is PostaCondivisa.Esito.Partito -> e.id
        is PostaCondivisa.Esito.Errore -> { mostraFrase(e.motivo, errore = true); null }
        is PostaCondivisa.Esito.DaConfermare -> {
            if (!silenzioso) campo.setText("")
            mostraFrase("Prima il tuo sì: tocca ${e.richiesta.etichettaSi} nel riquadro qui sotto.")
            ridisegna()
            null
        }
        PostaCondivisa.Esito.Annullato -> null
    }

    /** 0.5.0: il campo in basso. Prima le scorciatoie (sul telefono), poi sulla mail aperta una frase per
     *  JBoss («istruisci N: …») se non è un comando a numeri, altrimenti il comando così com'è. */
    private fun daCampo(t: String) {
        val testo = t.trim()
        if (testo.isEmpty()) return
        if (!demo) Postino.passaggio.usato()
        // 09/10 (Boss): «torna a JBoss» (o «JBoss») dalla pagina del Postino: si torna alla chat di JBoss, e il filo lo dice.
        when (PassaggioPostino.capisci(testo)) {
            PassaggioPostino.Verso.A_JBOSS -> { campo.setText(""); tornaAJBoss(testo); return }
            PassaggioPostino.Verso.AL_POSTINO -> { campo.setText(""); dialogo(testo, PassaggioPostino.GIA_POSTINO.substringBefore(" Per")); return }
            null -> {}
        }
        ComandiBrevi.capisci(testo, aperta != null)?.let { eseguiBreve(it); campo.setText(""); return }
        val n = aperta
        // 09/10 (Boss: «dal Postino devo poter parlare libero»): «leggi le mail», «avanti», «cancella questa»,
        // «aggiorna la posta», «cancella le mail 3 e 5», lo stesso riconoscimento della chat di JBoss.
        if (eFraseLibera(testo)) { libera(testo); return }
        // 09/10 (verificatore: «resoconto» con la mail aperta diventava «istruisci 1: resoconto», cioè una bozza): sulla mail
        // aperta una bozza nasce SOLO da un testo di risposta chiaro ([ComandiBrevi.istruzione]); una frase non capita no.
        if (n != null && !eComandoANumeri(testo)) {
            val istruzione = ComandiBrevi.istruzione(testo, n, attesaRisposta == n)
            if (istruzione != null) { attesaRisposta = null; manda(istruzione) }
            else { campo.setText(""); dialogo(testo, NON_CAPITO_PAGINA, errore = true) }
        }
        else if (!eComandoANumeri(testo) && !eComando(testo)) { campo.setText(""); dialogo(testo, NON_CAPITO_PAGINA, errore = true) }
        else manda(testo)
    }

    /** La frase è di quelle libere del Postino ([ComandiPostaJBoss]): con la mail aperta le frasi corte sono già scorciatoie. */
    private fun eFraseLibera(t: String): Boolean {
        val c = ComandiPostaJBoss.capisci(t, inPosta = true) ?: return false
        // «controlla la posta» nella pagina resta il resoconto nuovo, come prima; «leggi le mail» legge
        return !(c == ComandiPostaJBoss.Comando.Leggi && Regex("^\\s*(controlla|resoconto)", RegexOption.IGNORE_CASE).containsMatchIn(t))
    }

    /** Un comando del parser a numeri, anche senza numeri («controlla la posta», «invia tutte le bozze»). */
    private fun eComando(t: String): Boolean = try { ComandiPostino.capisci(t); true } catch (e: ComandiPostino.Errore) {
        // un verbo giusto con un pezzo sbagliato («sposta 3» senza cartella): l'errore del parser serve, lo mostra manda()
        !(e.message ?: "").startsWith("non capisco")
    }

    /**
     * Una frase libera: la fa la posta unica ([PostaCondivisa.perPostino], la stessa strada della chat di JBoss). La
     * domanda e la risposta restano nella conversazione della pagina; la mail corrente si apre; una cancellazione
     * mostra il riquadro Annulla/Elimina e non parte senza il tocco.
     */
    private fun libera(testo: String) {
        if (canale == null) { mostraFrase("La VPS non è collegata: non mando niente.", errore = true); return }
        // la domanda e la risposta (se non c'è un lavoro nuovo con le sue bolle) le mette nella conversazione la posta
        // unica, una volta sola: la stessa strada delle frasi dette con la pagina davanti
        val r = h.perPostino(testo, PostaCondivisa.Da.PAGINA) ?: return
        campo.setText("")
        val c = h.corrente
        if (c != null && c != aperta && !r.errore && r.chiaveConferma == null) apriMail(c) else ridisegna()
        lista.post { if (aperta == null) lista.scrollToPosition(adattatore.itemCount - 1) }
    }

    /** Una domanda di Boss e la risposta del Postino nella conversazione della pagina. */
    private fun dialogo(domanda: String, risposta: String, errore: Boolean = false) {
        s.dialogo(domanda, risposta, errore)
        ridisegna()
        lista.post { lista.scrollToPosition(adattatore.itemCount - 1) }
    }

    /**
     * «torna a JBoss» scritto qui: la conversazione torna a JBoss e il filo lo dice ([Postino.tornaAJBoss]); la pagina
     * si chiude da [smettiRitorno], come quando la frase è detta a voce o scritta nella chat.
     */
    // ---------------------------------------------------------------- 2026-10-10: le tre strade verso JBoss

    /** La voce di JBoss è pronta? Se no lo dice nella pagina e restituisce null. */
    private fun voceDiJBoss(): com.jarvis.telefono.JarvisService? {
        val s = com.jarvis.telefono.JarvisService.instance
        val p = com.jarvis.telefono.JarvisService.presenza(this)
        if (s == null || p != com.jarvis.telefono.PresenzaJBoss.ACCESO) {
            mostraFrase(PostinoTesti.voceNonPronta(p.name), errore = true)
            return null
        }
        return s
    }

    /** «Scrivi a JBoss»: la conversazione torna a JBoss e si apre la sua chat, con il campo per scrivere. */
    private fun scriviAJBoss() {
        if (!demo) Postino.passaggio.torna()
        vaiAJBoss()
    }

    /** «Parla a JBoss»: JBoss ascolta UNA frase adesso; le frasi sulla posta tornano qui, il resto lo fa JBoss. */
    private fun parlaAJBoss() {
        val s = voceDiJBoss() ?: return
        s.ascolta("pagina postino")
        mostraFrase(PostinoTesti.PARLA)
    }

    /** «Chiama JBoss»: la chat di JBoss a mani libere (ascolto continuo finché non dici «basta» o tocchi ✕). */
    private fun chiamaJBoss() {
        val s = voceDiJBoss() ?: return
        if (!demo) Postino.passaggio.torna()
        vaiAJBoss()
        android.os.Handler(mainLooper).postDelayed({ s.chatJBossAperta() }, 600L)
    }

    private fun tornaAJBoss(frase: String) {
        if (demo) vaiAJBoss() else Postino.tornaAJBoss(this, frase)
    }

    /**
     * Si chiude la pagina e si torna alla chat di JBoss: quella già aperta sotto (CLEAR_TOP, il filo resta com'era),
     * o una nuova se la pagina era stata aperta da fuori.
     */
    private fun vaiAJBoss() {
        if (isFinishing || isDestroyed) return
        startActivity(
            com.jarvis.telefono.AgenteChatActivity.intento(this, com.jarvis.telefono.agenti.CatalogoAgenti.JARVIS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun eComandoANumeri(t: String): Boolean =
        Regex("\\d").containsMatchIn(t.substringBefore(':')) && try { ComandiPostino.capisci(t); true } catch (e: ComandiPostino.Errore) { false }

    private fun nomeBreve(b: ComandiBrevi.Breve): String = when (b) {
        ComandiBrevi.Breve.Successiva -> "mail successiva"
        ComandiBrevi.Breve.Precedente -> "mail precedente"
        ComandiBrevi.Breve.Chiudi -> "torna alla lista"
        ComandiBrevi.Breve.Archivia -> "archivia questa mail"
        ComandiBrevi.Breve.Rispondi -> "apri la bozza di risposta"
        ComandiBrevi.Breve.Invia -> "invia la bozza (con conferma)"
        ComandiBrevi.Breve.Cestina -> "elimina questa mail (con conferma)"
        ComandiBrevi.Breve.Spam -> "spam (con conferma)"
        ComandiBrevi.Breve.Fatto -> "fatto: segna letta e archivia"
        ComandiBrevi.Breve.Dopo -> "dopo: resta in arrivo"
        ComandiBrevi.Breve.Deseleziona -> "togli la selezione"
        is ComandiBrevi.Breve.Seleziona -> "seleziona ${b.nome}"
    }

    private fun eseguiBreve(b: ComandiBrevi.Breve) {
        when (b) {
            ComandiBrevi.Breve.Successiva -> vai(+1)
            ComandiBrevi.Breve.Precedente -> vai(-1)
            ComandiBrevi.Breve.Chiudi -> chiudiMail()
            ComandiBrevi.Breve.Archivia -> aperta?.let { archiviaDaMail(it) }
            ComandiBrevi.Breve.Rispondi -> aperta?.let { rispondi(it) }
            ComandiBrevi.Breve.Invia -> aperta?.let { inviaBozza(it) }
            ComandiBrevi.Breve.Cestina -> aperta?.let { n -> conConferma(AzioniPostino.Azione.ELIMINA, listOf(n)) }
            ComandiBrevi.Breve.Spam -> aperta?.let { n -> conConferma(AzioniPostino.Azione.SPAM, listOf(n)) }
            ComandiBrevi.Breve.Fatto -> aperta?.let { fatto(it) }
            ComandiBrevi.Breve.Dopo -> aperta?.let { dopo(it) }
            ComandiBrevi.Breve.Deseleziona -> { selezione.clear(); ridisegna() }
            is ComandiBrevi.Breve.Seleziona -> {
                val nn = ComandiBrevi.numeri(b, s.stato.filtra(filtro))
                selezione.addAll(nn)
                mostraFrase(if (nn.isEmpty()) "Nessuna mail fra ${b.nome} in questo filtro." else "Selezionate ${nn.size}: ${b.nome}.")
                ridisegna()
            }
        }
    }

    private fun rispondiConferma(scelta: String) {
        val conf = s.stato.conferma
        if (conf == null) {
            // 09/10: il sì del telefono per una cancellazione (lo stesso box del filo di JBoss)
            val r = h.daConfermare ?: return
            val e = if (demo) h.rispondiConferma(r.chiave, scelta == "invia") else Postino.rispondiConferma(this, r.chiave, scelta == "invia")
            if (e == PostaCondivisa.Esito.Annullato) mostraFrase("Annullato: non ho toccato niente.") else esito(e, silenzioso = true)
            ridisegna()
            return
        }
        canale?.conferma(conf.idLavoro, conf.azioneId, scelta)
        s.stato.confermaRisolta()
        ridisegna()
    }

    private fun preparaBarraSelezione() {
        val contenitore = findViewById<LinearLayout>(R.id.azioniSelezione)
        listOf("archivia" to "Archivia", "cestina" to "Elimina…", "spam" to "Spam…", "letta" to "Letta", "tutte" to "Tutte", "" to "✕")
            .forEach { (verbo, nome) ->
                contenitore.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = nome
                    isAllCaps = false
                    minWidth = 0
                    setTextColor(ContextCompat.getColor(context, R.color.jarvis_accento))
                    setOnClickListener {
                        when (verbo) {
                            "" -> { selezione.clear(); ridisegna() }
                            "archivia" -> apriArchivio(selezione.toList())
                            "tutte" -> selezionaCategoria()
                            // 0.6.6: Elimina e Spam chiedono SEMPRE la conferma sul telefono
                            "cestina" -> { conConferma(AzioniPostino.Azione.ELIMINA, selezione.toList()); selezione.clear(); ridisegna() }
                            "spam" -> { conConferma(AzioniPostino.Azione.SPAM, selezione.toList()); selezione.clear(); ridisegna() }
                            else -> manda(ComandiPostino.daSelezione(verbo, selezione))
                        }
                    }
                })
            }
    }

    // ---------------------------------------------------------------- 0.5.0: la mail aperta

    private fun preparaDettaglio() {
        findViewById<MaterialButton>(R.id.dettChiudi).setOnClickListener { chiudiMail() }
        findViewById<MaterialButton>(R.id.dettPrecedente).setOnClickListener { vai(-1) }
        findViewById<MaterialButton>(R.id.dettSuccessiva).setOnClickListener { vai(+1) }
        findViewById<MaterialButton>(R.id.dettArchivia).setOnClickListener { aperta?.let { archiviaDaMail(it) } }
        findViewById<MaterialButton>(R.id.dettRispondi).setOnClickListener { aperta?.let { rispondi(it) } }
        findViewById<MaterialButton>(R.id.dettFatto).setOnClickListener { aperta?.let { fatto(it) } }
        findViewById<MaterialButton>(R.id.dettDopo).setOnClickListener { aperta?.let { dopo(it) } }
        findViewById<MaterialButton>(R.id.dettSpam).setOnClickListener {
            aperta?.let { n -> conConferma(AzioniPostino.Azione.SPAM, listOf(n)) }
        }
        findViewById<MaterialButton>(R.id.dettCestina).setOnClickListener {
            aperta?.let { n -> conConferma(AzioniPostino.Azione.ELIMINA, listOf(n)) }
        }
        findViewById<TextView>(R.id.dettTestoTitolo).setOnClickListener {
            testoAperto = !testoAperto
            disegnaDettaglio()
        }
        findViewById<MaterialButton>(R.id.bozzaSalva).setOnClickListener { aperta?.let { salvaBozza(it, poiInvia = false) } }
        findViewById<MaterialButton>(R.id.bozzaInvia).setOnClickListener { aperta?.let { inviaBozza(it) } }
        val segna = object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(t: android.text.Editable?) {
                if (findViewById<EditText>(R.id.bozzaTesto).hasFocus() || findViewById<EditText>(R.id.bozzaA).hasFocus()) bozzaToccata = true
            }
        }
        findViewById<EditText>(R.id.bozzaTesto).addTextChangedListener(segna)
        findViewById<EditText>(R.id.bozzaA).addTextChangedListener(segna)
        findViewById<Spinner>(R.id.dettCartella).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val n = aperta ?: return
                val scelte = p?.tag as? List<*> ?: return
                archivioScelte[n] = (scelte.getOrNull(pos) as? String)?.takeIf { pos > 0 }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** Apre la mail n: subito quello che il telefono sa (mittente, oggetto), poi la scheda della VPS. */
    private fun apriMail(n: Int, conBozza: Boolean = false) {
        if (aperta != n) { testoAperto = false; bozzaToccata = false; attesaRisposta = null }
        aperta = n
        // 09/10: la mail che la pagina mostra va anche nel filo di JBoss (una volta per mail), non solo qui
        h.apri(n, if (demo) null else PostaCondivisa.Da.PAGINA)
        campo.hint = "Cosa faccio con la mail $n?"
        if (dettaglio.visibility != View.VISIBLE) {
            dettaglio.visibility = View.VISIBLE
            dettaglio.alpha = 0f
            dettaglio.animate().alpha(1f).setDuration(resources.getInteger(R.integer.postino_durata_breve).toLong()).start()
        }
        findViewById<ScrollView>(R.id.dettScorri).scrollTo(0, 0)
        if (s.stato.schede[n] == null && n !in chiestaScheda && collegato()) {
            chiestaScheda.add(n)
            inizioApertura[n] = System.currentTimeMillis()
            manda("apri $n")
        }
        if (conBozza) mostraBozza(n, nuova = true)
        ridisegna()
    }

    private fun chiudiMail() {
        aperta = null
        attesaRisposta = null
        campo.hint = com.jarvis.telefono.ui.TestiBarra.suggerimento(com.jarvis.telefono.agenti.CatalogoAgenti.POSTINO)
        dettaglio.visibility = View.GONE
        ridisegna()
    }

    /** ◀ ▶, i tasti del volume, «successiva»/«precedente»: si scorre fra le mail del filtro attuale. */
    private fun vai(passo: Int) {
        val n = aperta ?: return
        val elenco = visibili().ifEmpty { s.stato.voci().map { it.numero } }
        val i = elenco.indexOf(n)
        val j = if (i < 0) 0 else i + passo
        if (j !in elenco.indices) { mostraFrase(if (passo > 0) "Era l'ultima." else "Era la prima."); return }
        apriMail(elenco[j])
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (aperta != null && !campo.hasFocus()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { vai(+1); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { vai(-1); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("onBackPressed: la mail aperta si chiude prima di uscire dalla chat")
    override fun onBackPressed() {
        when {
            pannelloArchivio.visibility == View.VISIBLE -> chiudiArchivio()
            aperta != null -> chiudiMail()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun disegnaDettaglio() {
        val n = aperta ?: return
        val v = s.stato.voce(n)
        val sc = s.stato.schede[n]
        val elenco = s.stato.filtra(filtro).map { it.numero }
        val pos = elenco.indexOf(n)
        findViewById<TextView>(R.id.dettPosizione).text = if (pos >= 0) "Mail $n · ${pos + 1} di ${elenco.size}" else "Mail $n"
        findViewById<TextView>(R.id.dettDa).text = sc?.da?.ifEmpty { null } ?: v?.da.orEmpty()
        findViewById<TextView>(R.id.dettInfo).text = listOf(sc?.rispondiA?.ifEmpty { null } ?: v?.indirizzo.orEmpty(), v?.casella.orEmpty(),
            v?.data.orEmpty(), v?.categoria.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
        findViewById<TextView>(R.id.dettOggetto).text = sc?.oggetto?.ifEmpty { null } ?: v?.oggetto.orEmpty()
        val riass = findViewById<TextView>(R.id.dettRiassunto)
        riass.text = when {
            sc == null -> if (collegato()) "Apro la mail sulla VPS: scarico gli allegati e preparo il riassunto…" else "La VPS non è collegata."
            sc.riassuntoInArrivo -> sc.riassunto + "\n\n… il riassunto completo arriva fra pochi secondi."
            else -> sc.riassunto
        }
        if (sc != null && !sc.riassuntoInArrivo) inizioApertura.remove(n)?.let {
            Log.i("PostinoTempi", "mail $n: riassunto completo dopo ${System.currentTimeMillis() - it} ms (VPS ${sc.secondi} s)")
        }
        val alleg = findViewById<TextView>(R.id.dettAllegati)
        if (sc != null && sc.allegati.isNotEmpty()) {
            alleg.visibility = View.VISIBLE
            alleg.text = "Allegati (letti sulla VPS):\n" + sc.allegati.joinToString("\n") { a ->
                "• ${a.nome} · ${a.kb} KB" + (a.nota?.let { " · $it" } ?: "") +
                    (a.estratto.takeIf { it.isNotBlank() }?.let { "\n   " + it.lines().filter { l -> l.isNotBlank() }.take(3).joinToString(" / ").take(220) } ?: "")
            }
        } else alleg.visibility = View.GONE
        // destinazione dell'archivio: la proposta o «scegli…», fra le cartelle vere della casella
        val scelte = (sc?.scelte?.ifEmpty { null } ?: s.stato.cartellePer(n))
        val proposta = archivioScelte[n] ?: sc?.cartella ?: v?.proposta?.cartella
        val spinner = findViewById<Spinner>(R.id.dettCartella)
        val voci = listOf("scegli la cartella…") + scelte
        if (spinner.tag != voci) {
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voci)
            spinner.tag = voci
        }
        val i = proposta?.let { voci.indexOf(it) } ?: -1
        if (i > 0 && spinner.selectedItemPosition != i) spinner.setSelection(i)
        findViewById<TextView>(R.id.dettPerche).text = when {
            proposta != null && (sc?.perche ?: v?.proposta?.perche).orEmpty().isNotEmpty() -> "Proposta: ${sc?.perche ?: v?.proposta?.perche}"
            proposta == null -> "Nessuna cartella chiara per questa mail: sceglila tu."
            else -> ""
        }
        val breve = findViewById<TextView>(R.id.dettBreve)
        breve.text = AzioniPostino.riassuntoBreve(sc?.riassunto?.ifEmpty { null } ?: v?.anteprima)
        breve.visibility = if (breve.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val stato = v?.stato
        val libera = (stato?.fatto != true || stato.verbo == "rispondi") && v?.inCorso == null && !inCorsoQui(n) && !coda.contiene(n)
        for (id in listOf(R.id.dettArchivia, R.id.dettFatto, R.id.dettDopo, R.id.dettSpam, R.id.dettCestina)) {
            findViewById<MaterialButton>(id).isEnabled = libera
        }
        val testo = findViewById<TextView>(R.id.dettTesto)
        findViewById<TextView>(R.id.dettTestoTitolo).text = (if (testoAperto) "▾" else "▸") + " Testo della mail"
        testo.visibility = if (testoAperto) View.VISIBLE else View.GONE
        testo.text = (sc?.testo?.ifEmpty { null } ?: v?.anteprima.orEmpty()) + "\n\n" + getString(R.string.postino_avviso_dato)
        disegnaBozza(n)
    }

    /**
     * 0.6.6 Rispondi: si apre la bozza al mittente e il campo in basso aspetta cosa vuole dire Boss
     * (detto o scritto). JBoss lo riscrive (tono, lunghezza, destinatario) e lo mette nelle Bozze
     * («istruisci N: …», lo stesso flusso delle bozze di prima); la bozza si vede qui e parte SOLO con
     * Invia… e la conferma della VPS. Il testo si può anche scrivere a mano nella bozza.
     */
    private fun rispondi(n: Int) {
        attesaRisposta = n
        mostraBozza(n, nuova = true)
        if (s.stato.bozze[n] == null) {
            bozzaToccata = false              // campi vuoti: la bozza di JBoss li deve poter riempire
            campo.hint = "Cosa rispondo alla mail $n?"
            campo.requestFocus()
            mostraFrase("Dimmi cosa rispondere: JBoss scrive la bozza, non parte niente senza il tuo Invia.")
        }
    }

    /** Mostra il riquadro della bozza: quella già arrivata dalla VPS, o una nuova al mittente. */
    private fun mostraBozza(n: Int, nuova: Boolean) {
        val riquadro = findViewById<MaterialCardView>(R.id.dettBozza)
        if (riquadro.visibility != View.VISIBLE) riquadro.visibility = View.VISIBLE
        val b = s.stato.bozze[n]
        if (b == null && nuova) {
            val a = s.stato.schede[n]?.rispondiA?.ifEmpty { null } ?: s.stato.voce(n)?.indirizzo.orEmpty()
            findViewById<EditText>(R.id.bozzaA).setText(a)
            findViewById<EditText>(R.id.bozzaTesto).setText("")
            bozzaToccata = true
            findViewById<EditText>(R.id.bozzaTesto).requestFocus()
        }
        disegnaBozza(n)
        findViewById<ScrollView>(R.id.dettScorri).post {
            findViewById<ScrollView>(R.id.dettScorri).smoothScrollTo(0, riquadro.top)
        }
    }

    private fun disegnaBozza(n: Int) {
        val riquadro = findViewById<MaterialCardView>(R.id.dettBozza)
        val b = s.stato.bozze[n]
        if (b == null) {
            if (riquadro.visibility == View.VISIBLE) findViewById<TextView>(R.id.bozzaTitolo).text = "Nuova risposta"
            return
        }
        riquadro.visibility = View.VISIBLE
        // i campi si riempiono da una versione NUOVA della VPS, mai mentre Boss sta scrivendo
        if (bozzaMostrata[n] != b.versione && !bozzaToccata) {
            findViewById<EditText>(R.id.bozzaA).setText(b.a)
            findViewById<EditText>(R.id.bozzaTesto).setText(b.testo)
            bozzaMostrata[n] = b.versione
        } else if (bozzaMostrata[n] != b.versione && b.salvata) {
            bozzaMostrata[n] = b.versione
            bozzaToccata = false
        }
        findViewById<TextView>(R.id.bozzaTitolo).text = when {
            b.inviata -> "Inviata"
            b.tipo == "inoltro" -> "Inoltro"
            else -> "Risposta"
        }
        findViewById<TextView>(R.id.bozzaStato).text = when {
            b.inviata -> "✓ inviata · ${b.prova.orEmpty()}"
            b.domanda != null -> b.domanda + if (b.scelte.isNotEmpty()) "\nTocca il campo A e scrivi l'indirizzo giusto." else ""
            b.motivo != null -> "! ${b.motivo}"
            b.salvata && !bozzaToccata -> "✓ bozza nelle Bozze (verificata) · ${b.prova.orEmpty()}\nPuoi cambiarla: Salva bozza la sostituisce. Invia la spedisce così com'è: è il tuo sì, non ne chiedo un altro."
            bozzaToccata -> "Modificata: Salva bozza la mette nelle Bozze. Invia la salva e la spedisce così com'è (il tuo tocco è la conferma)."
            else -> ""
        }
        if (b.domanda != null && b.scelte.isNotEmpty() && findViewById<EditText>(R.id.bozzaA).text.isBlank()) {
            findViewById<EditText>(R.id.bozzaA).hint = b.scelte.joinToString(" o ") { it.second }
        }
    }

    private fun campiBozza(n: Int): Pair<String, String>? {
        val a = findViewById<EditText>(R.id.bozzaA).text.toString().trim()
        val t = findViewById<EditText>(R.id.bozzaTesto).text.toString().trim()
        if (t.isEmpty()) { mostraFrase("La bozza è vuota: scrivi il testo o chiedilo a JBoss qui sotto.", errore = true); return null }
        if (a.isNotEmpty() && !Regex("[^@\\s<>,;:]+@[^@\\s<>,;:]+\\.[^@\\s<>,;:]+").matches(a)) {
            mostraFrase("«$a» non è un indirizzo: scrivilo intero (nome@dominio.it).", errore = true); return null
        }
        if (a.isEmpty() && s.stato.bozze[n]?.tipo == "inoltro") { mostraFrase("A chi la inoltro? Scrivi l'indirizzo.", errore = true); return null }
        return a to t
    }

    private fun salvaBozza(n: Int, poiInvia: Boolean) {
        val (a, t) = campiBozza(n) ?: return
        val inoltro = s.stato.bozze[n]?.tipo == "inoltro"
        val verbo = (if (inoltro) "inoltra" else "rispondi") + (if (poiInvia) " e invia" else "")
        val dest = if (a.isNotEmpty()) " a $a" else ""
        bozzaToccata = false
        val id = manda("$verbo $n$dest: $t")
        // Una sola conferma: il tocco su Invia, con la bozza intera davanti, vale come il sì (PreConferme).
        if (poiInvia && id != null) PreConferme.registra(id, "invio", t, a, n)
    }

    /** Invia…: se la bozza nelle Bozze è quella che si vede, «invia N»; se Boss l'ha cambiata, prima la salva.
     *  In tutti e due i casi la VPS chiede la conferma con l'anteprima: senza Invia non parte niente. */
    private fun inviaBozza(n: Int) {
        val b = s.stato.bozze[n]
        if (b != null && b.salvata && !b.inviata && !bozzaToccata) {
            val id = manda("invia $n")
            if (id != null) PreConferme.registra(id, "invio", b.testo, b.a, n)
        }
        else salvaBozza(n, poiInvia = true)
    }

    // ---------------------------------------------------------------- 0.5.0: archivio con destinazione

    /** Il riquadro dell'archivio: per ogni mail la cartella proposta, o «scegli…»; si sposta solo dopo «Archivia». */
    private fun apriArchivio(numeri: List<Int>) {
        if (numeri.isEmpty()) return
        archivioNumeri = numeri.sorted()
        val righe = findViewById<LinearLayout>(R.id.archivioRighe)
        righe.removeAllViews()
        for (n in archivioNumeri) {
            val v = s.stato.voce(n)
            val scelte = s.stato.schede[n]?.scelte?.ifEmpty { null } ?: s.stato.cartellePer(n)
            val voci = listOf("scegli…") + scelte
            // la proposta vale solo se è una cartella vera di quella casella; se no si chiede
            archivioScelte[n] = (archivioScelte[n] ?: s.stato.schede[n]?.cartella ?: v?.proposta?.cartella)?.takeIf { it in scelte }
            righe.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "$n · ${(v?.oggetto ?: "").take(38)}"
                    setTextColor(ContextCompat.getColor(context, R.color.jarvis_testo))
                    textSize = 13f
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Spinner(context).apply {
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, voci)
                    contentDescription = "Cartella per la mail $n"
                    archivioScelte[n]?.let { setSelection(voci.indexOf(it)) }
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>?, vv: View?, pos: Int, id: Long) {
                            archivioScelte[n] = if (pos > 0) voci[pos] else null
                            aggiornaArchivio()
                        }
                        override fun onNothingSelected(p: AdapterView<*>?) {}
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
            })
        }
        val scorri = findViewById<ScrollView>(R.id.archivioScorri)
        scorri.layoutParams = scorri.layoutParams.apply {
            height = if (archivioNumeri.size > 4) (240 * resources.displayMetrics.density).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        pannelloArchivio.visibility = View.VISIBLE
        aggiornaArchivio()
    }

    private fun aggiornaArchivio() {
        val mancano = archivioNumeri.filter { archivioScelte[it] == null }
        findViewById<TextView>(R.id.archivioTitolo).text =
            "Archivio ${archivioNumeri.size} ${if (archivioNumeri.size == 1) "mail" else "mail"}: controlla dove vanno"
        findViewById<TextView>(R.id.archivioNota).text =
            if (mancano.isEmpty()) "" else "Manca la cartella per ${ComandiPostino.elencoCorto(mancano)}: sceglila."
        findViewById<MaterialButton>(R.id.archivioConferma).isEnabled = mancano.isEmpty()
    }

    private fun chiudiArchivio() {
        pannelloArchivio.visibility = View.GONE
        archivioNumeri = emptyList()
    }

    /** «sposta 3 in Fatture-Fornitori; sposta 7 in Rumore»: la destinazione mostrata è quella usata. */
    private fun confermaArchivio() {
        if (archivioNumeri.any { archivioScelte[it] == null }) { aggiornaArchivio(); return }
        val perCartella = archivioNumeri.groupBy { archivioScelte[it]!! }
        val comando = perCartella.entries.joinToString("; ") { (c, nn) -> "sposta ${ComandiPostino.elencoCorto(nn)} in $c" }
        Log.i("PostinoTempi", "archivio di ${archivioNumeri.size} mail mandato alle ${System.currentTimeMillis()}")
        chiudiArchivio()
        selezione.clear()
        manda(comando)
    }

    private fun selezionaCategoria() {
        val cat = ultimaCategoria ?: s.stato.voce(selezione.firstOrNull() ?: return)?.categoria ?: return
        val nn = s.stato.filtra(filtro).filter { it.categoria == cat }.map { it.numero }
        selezione.addAll(nn)
        mostraFrase("Selezionate tutte «$cat»: ${nn.size}.")
        ridisegna()
    }

    // ---------------------------------------------------------------- 0.5.0: swipe

    /** 0.6.6: sinistra = Elimina (con conferma), destra = Archivia subito (annullabile). Vedi [AzioniPostino.perSwipe]. */
    private fun preparaSwipe() {
        val densita = resources.displayMetrics.density
        val fondo = Paint(Paint.ANTI_ALIAS_FLAG)
        val scritta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@PostinoActivity, R.color.jarvis_su_accento)
            textSize = 15 * resources.displayMetrics.scaledDensity
            isFakeBoldText = true
        }
        val cb = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                if (vh !is SchedaVH || s.stato.conferma != null || h.daConfermare != null || selezione.isNotEmpty()) return 0
                val v = s.stato.voce(vh.numero) ?: return 0
                val chiusa = v.stato?.fatto == true && v.stato.verbo != "rispondi"
                return if (chiusa || v.inCorso != null || inCorsoQui(v.numero)) 0 else super.getSwipeDirs(rv, vh)
            }
            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.35f
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val n = (vh as? SchedaVH)?.numero
                @Suppress("DEPRECATION") adattatore.notifyItemChanged(vh.adapterPosition)
                if (n == null) return
                val verso = if (dir == ItemTouchHelper.LEFT) AzioniPostino.Swipe.SINISTRA else AzioniPostino.Swipe.DESTRA
                when (AzioniPostino.perSwipe(verso)) {
                    AzioniPostino.Azione.ELIMINA -> conConferma(AzioniPostino.Azione.ELIMINA, listOf(n))
                    AzioniPostino.Azione.ARCHIVIA -> archiviaSubito(n)
                    else -> {}
                }
            }
            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, stato: Int, attivo: Boolean) {
                if (stato == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    val v = vh.itemView
                    val azione = AzioniPostino.perSwipe(if (dX < 0) AzioniPostino.Swipe.SINISTRA else AzioniPostino.Swipe.DESTRA)
                    fondo.color = ContextCompat.getColor(this@PostinoActivity,
                        if (azione == AzioniPostino.Azione.ELIMINA) R.color.spia_rosso else R.color.spia_verde)
                    val r = if (dX > 0) RectF(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat())
                    else RectF(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())
                    c.drawRoundRect(r, 12 * densita, 12 * densita, fondo)
                    val etichetta = if (azione == AzioniPostino.Azione.ELIMINA) "Elimina…" else "Archivia"
                    val margine = 20 * densita
                    val x = if (dX > 0) v.left + margine else v.right - margine - scritta.measureText(etichetta)
                    val y = v.top + v.height / 2f - (scritta.descent() + scritta.ascent()) / 2f
                    c.save(); c.clipRect(r); c.drawText(etichetta, x, y, scritta); c.restore()
                }
                super.onChildDraw(c, rv, vh, dX, dY, stato, attivo)
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(lista)
    }

    // ---------------------------------------------------------------- 0.6.6: azioni rapide

    /** Le mail del filtro che si vedono: senza quelle in attesa di archivio e quelle già partite in un lavoro in corso. */
    private fun visibili(): List<Int> = s.stato.filtra(filtro).map { it.numero }.filter { !coda.contiene(it) && !inCorsoQui(it) }

    /** La mail n è in un lavoro mandato (da qui o da JBoss) e non ancora finito. */
    private fun inCorsoQui(n: Int): Boolean = h.inViaggio(n)

    /** Dopo un'azione sulla mail aperta: la successiva del filtro (o la precedente), o la lista se era l'ultima. */
    private fun avanzaDopo(n: Int) {
        if (aperta != n) return
        val elenco = s.stato.filtra(filtro).map { it.numero }.filter { it == n || (!coda.contiene(it) && !inCorsoQui(it)) }
        val i = elenco.indexOf(n)
        val prossima = if (i < 0) elenco.firstOrNull { it != n } else (elenco.getOrNull(i + 1) ?: elenco.getOrNull(i - 1))
        if (prossima == null || prossima == n) chiudiMail() else apriMail(prossima)
    }

    /** La cartella vera per archiviare la mail n senza chiedere, o null. */
    private fun cartellaPer(n: Int): String? {
        val sc = s.stato.schede[n]
        val vere = sc?.scelte?.ifEmpty { null } ?: s.stato.cartellePer(n)
        return AzioniPostino.cartellaArchivio(archivioScelte[n], sc?.cartella, s.stato.voce(n)?.proposta?.cartella, vere)
    }

    /**
     * Elimina e Spam: SEMPRE il sì sul telefono, anche per una mail sola. 09/10: nel riquadro della pagina (lo stesso
     * box compare nel filo di JBoss), non in un popup. Solo dopo il tocco il comando parte; la VPS poi chiede la sua
     * conferma. Dopo il sì la mail aperta passa alla prossima da sola (la mail corrente della posta unica).
     */
    private fun conConferma(a: AzioniPostino.Azione, numeri: List<Int>) {
        if (numeri.isEmpty() || !AzioniPostino.chiedeConferma(a)) return
        if (!collegato()) { mostraFrase("La VPS non è collegata: non mando niente.", errore = true); return }
        aperta?.takeIf { it in numeri }?.let { h.apri(it) }
        val r = h.chiediConferma(a, numeri, PostaCondivisa.Da.PAGINA) ?: return
        mostraFrase("Prima il tuo sì: tocca ${r.etichettaSi} nel riquadro qui sotto.")
        ridisegna()
    }

    /** Swipe a destra: subito fuori dalla lista, il comando parte fra 5 secondi; «Annulla» la rimette. */
    private fun archiviaSubito(n: Int) {
        val cartella = cartellaPer(n)
        if (cartella == null) {
            mostraFrase("Per la mail $n non c'è una cartella vera proposta: sceglila.", errore = true)
            apriArchivio(listOf(n))
            return
        }
        coda.metti(n, cartella)
        ridisegna()
        pianificaArchivi()
        val inAttesa = coda.numeri().toList()
        val testo = if (inAttesa.size == 1) "Mail $n archiviata in $cartella"
        else "Archiviate ${ComandiPostino.elencoCorto(inAttesa)}"
        Snackbar.make(findViewById(android.R.id.content), testo, AzioniPostino.ATTESA_ANNULLA_MS.toInt())
            .setActionTextColor(ContextCompat.getColor(this, R.color.jarvis_accento))
            .setAction("Annulla") {
                val tolte = inAttesa.filter { coda.annulla(it) }
                val partite = inAttesa - tolte.toSet()
                mostraFrase(when {
                    partite.isEmpty() -> "Annullato: ${ComandiPostino.elencoCorto(tolte)} resta in arrivo."
                    tolte.isEmpty() -> "Troppo tardi: ${ComandiPostino.elencoCorto(partite)} è già partita per la VPS."
                    else -> "Annullato ${ComandiPostino.elencoCorto(tolte)}; ${ComandiPostino.elencoCorto(partite)} era già partita."
                }, errore = tolte.isEmpty())
                ridisegna()
                pianificaArchivi()
            }
            .show()
    }

    /** Archivia dal dettaglio (tasto o «archivia»): subito con la cartella mostrata, poi la mail successiva. */
    private fun archiviaDaMail(n: Int) {
        if (cartellaPer(n) == null) { apriArchivio(listOf(n)); return }
        avanzaDopo(n)
        archiviaSubito(n)
    }

    /** Fatto: segna letta e poi archivia (due lavori in fila, il secondo dopo la verifica del primo). */
    private fun fatto(n: Int) {
        val cartella = cartellaPer(n)
        if (cartella == null) { mostraFrase("Fatto vuole la cartella dell'archivio: sceglila sopra.", errore = true); return }
        if (mandaCatena(AzioniPostino.comandi(AzioniPostino.Azione.FATTO, listOf(n), cartella), silenzioso = true) != null) avanzaDopo(n)
    }

    /** Dopo: la mail resta in arrivo («tieni»), si passa alla successiva. */
    private fun dopo(n: Int) {
        if (mandaCatena(AzioniPostino.comandi(AzioniPostino.Azione.DOPO, listOf(n)), silenzioso = true) != null) avanzaDopo(n)
    }

    private fun pianificaArchivi() {
        principale.removeCallbacks(partenzaArchivi)
        coda.prossimaFra()?.let { principale.postDelayed(partenzaArchivi, it + 50) }
    }

    /** Gli archivi scaduti (o tutti, uscendo) partono in UN comando. Se la VPS non c'è, le mail tornano in lista. */
    private fun mandaArchivi(tutte: Boolean) {
        val via = coda.scadute(tutte)
        AzioniPostino.comandoArchivi(via)?.let { comando ->
            val id = manda(comando, silenzioso = true)
            if (id == null) mostraFrase("Archivio non partito: ${ComandiPostino.elencoCorto(via.keys.toList())} resta in arrivo.", errore = true)
            else h.segnaInViaggio(via.keys, id)
        }
        if (via.isNotEmpty() && !isFinishing) ridisegna()
        if (!tutte) pianificaArchivi()
    }

    // ---------------------------------------------------------------- disegno

    private fun mostraFrase(t: String?, errore: Boolean = false) {
        if (t.isNullOrBlank()) { frase.visibility = View.GONE; return }
        frase.text = t
        frase.setTextColor(ContextCompat.getColor(this, if (errore) R.color.spia_rosso else R.color.jarvis_testo_tenue))
        frase.visibility = View.VISIBLE
    }

    private fun ridisegna() {
        val st = s.stato
        riassunto.text = st.rigaRiassunto()
        if (canale != null) {
            spia.text = if (collegato()) (if (demo) "● prova" else "● VPS") else "● VPS scollegata"
            spia.setTextColor(ContextCompat.getColor(this, if (collegato()) R.color.spia_verde else R.color.spia_rosso))
        }
        for (i in 0 until filtri.childCount) {
            val chip = filtri.getChildAt(i) as Chip
            val f = chip.tag as StatoPostino.Filtro
            chip.text = if (st.reportId != null) "${f.etichetta} ${st.conta(f)}" else f.etichetta
            chip.isChecked = f == filtro
        }
        val senzaCanale = canale == null
        vuoto.visibility = if (senzaCanale || (s.bolle.isEmpty() && st.reportId == null)) View.VISIBLE else View.GONE
        vuoto.text = getString(if (senzaCanale) R.string.postino_vps_spenta else R.string.postino_vuoto)
        campo.isEnabled = !senzaCanale
        listOf(R.id.tasto_invia, R.id.tasto_parla, R.id.tasto_chiama).forEach { b ->
            findViewById<android.view.View>(b).apply { isEnabled = !senzaCanale; alpha = if (senzaCanale) 0.45f else 1f }
        }
        adattatore.aggiorna()
        aggiornaConferma()
        if (aperta != null) disegnaDettaglio()
        barra.visibility = if (selezione.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.selezionate).text =
            "${selezione.size} ${if (selezione.size == 1) "selezionata" else "selezionate"}: ${ComandiPostino.elencoCorto(selezione.toList())}"
        if (campo.text.isNullOrBlank()) mostraFrase(st.ultimaFrase.takeIf { s.inCorso.isNotEmpty() && it.isNotEmpty() })
        st.testoMail?.let { (n, testo) ->
            st.dimenticaMail()
            AlertDialog.Builder(this).setTitle("Mail $n")
                .setMessage(getString(R.string.postino_avviso_dato) + "\n\n" + testo)
                .setPositiveButton("Chiudi", null).show()
        }
    }

    private fun aggiornaConferma() {
        s.stato.conferma?.let { c ->
            // Il tocco su Invia (o su Elimina) fatto prima sul telefono vale come il sì: niente secondo riquadro.
            when (PreConferme.verifica(c.idLavoro, c.azioneId, c.azione, c.destinatario, c.anteprima, c.domanda)) {
                PreConferme.Esito.CONFERMATA_ORA -> { canale?.conferma(c.idLavoro, c.azioneId, "invia"); s.stato.confermaRisolta() }
                PreConferme.Esito.GIA_CONFERMATA -> s.stato.confermaRisolta()
                PreConferme.Esito.DA_CONFERMARE -> {}
            }
        }
        val conf = s.stato.conferma
        val durata = resources.getInteger(R.integer.postino_durata_media).toLong()
        val delTelefono = if (conf == null) h.daConfermare else null
        if (delTelefono != null) {
            // 09/10: il sì del telefono per una cancellazione, nello stesso riquadro delle conferme della VPS
            findViewById<TextView>(R.id.confermaDomanda).text = delTelefono.titolo
            findViewById<TextView>(R.id.confermaDettagli).text = "Parte solo con il tuo tocco: a voce non cancello."
            findViewById<TextView>(R.id.confermaAnteprima).text = delTelefono.corpo
            findViewById<MaterialButton>(R.id.confermaInvia).text = delTelefono.etichettaSi
            if (confermaMostrata != delTelefono.chiave) {
                confermaMostrata = delTelefono.chiave
                pannello.visibility = View.VISIBLE
                pannello.alpha = 0f
                pannello.translationY = 40f
                pannello.animate().alpha(1f).translationY(0f).setDuration(durata).start()
            }
            return
        }
        if (conf == null) {
            if (pannello.visibility == View.VISIBLE) {
                pannello.animate().alpha(0f).translationY(pannello.height / 3f).setDuration(durata)
                    .withEndAction { pannello.visibility = View.GONE; pannello.alpha = 1f; pannello.translationY = 0f }.start()
            }
            confermaMostrata = null
            return
        }
        findViewById<TextView>(R.id.confermaDomanda).text = conf.domanda
        findViewById<TextView>(R.id.confermaDettagli).text = buildList {
            conf.numero?.let { add("Numero $it") }
            if (conf.destinatario.isNotEmpty()) add("a ${conf.destinatario}")
            conf.oggetto?.let { add("«$it»") }
        }.joinToString(" · ")
        findViewById<TextView>(R.id.confermaAnteprima).text = conf.anteprima.ifEmpty { "(nessuna anteprima)" }
        findViewById<MaterialButton>(R.id.confermaInvia).text = if (conf.azione == "invio") "Invia" else "Sì, procedi"
        if (confermaMostrata != conf.azioneId) {
            confermaMostrata = conf.azioneId
            pannello.visibility = View.VISIBLE
            pannello.alpha = 0f
            pannello.translationY = 40f
            pannello.animate().alpha(1f).translationY(0f).setDuration(durata).start()
        }
    }

    // ---------------------------------------------------------------- la lista: bolle e schede

    private sealed class Riga {
        data class B(val bolla: SessionePostino.Bolla) : Riga()
        data class V(val voce: StatoPostino.Voce) : Riga()
    }

    private inner class Adattatore : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var righe: List<Riga> = emptyList()

        fun aggiorna() {
            // distinctBy: una scheda per numero, sempre (Boss 09/10: «dopo resoconto la lista è duplicata»)
            val schede = s.stato.filtra(filtro).filter { !coda.contiene(it.numero) && !inCorsoQui(it.numero) }
                .distinctBy { it.numero }.map { Riga.V(it) }
            val bolle = s.bolle.map { Riga.B(it.copy()) }
            val taglio = (s.dopoBolla + 1).coerceIn(0, bolle.size)
            val nuove = bolle.take(taglio) + schede + bolle.drop(taglio)
            val vecchie = righe
            righe = nuove
            androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = vecchie.size
                override fun getNewListSize() = nuove.size
                override fun areItemsTheSame(a: Int, b: Int): Boolean {
                    val x = vecchie[a]; val y = nuove[b]
                    return when {
                        x is Riga.V && y is Riga.V -> x.voce.numero == y.voce.numero
                        x is Riga.B && y is Riga.B -> a == b && x.bolla.daBoss == y.bolla.daBoss
                        else -> false
                    }
                }
                override fun areContentsTheSame(a: Int, b: Int) = vecchie[a] == nuove[b]
            }).dispatchUpdatesTo(this)
        }

        override fun getItemCount() = righe.size
        override fun getItemViewType(p: Int) = if (righe[p] is Riga.V) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, tipo: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (tipo == 1) SchedaVH(inf.inflate(R.layout.item_postino_voce, parent, false))
            else BollaVH(inf.inflate(R.layout.item_postino_bolla, parent, false))
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
            when (val r = righe[p]) {
                is Riga.B -> (h as BollaVH).lega(r.bolla)
                is Riga.V -> (h as SchedaVH).lega(r.voce)
            }
        }
    }

    private inner class BollaVH(v: View) : RecyclerView.ViewHolder(v) {
        private val riga = v.findViewById<LinearLayout>(R.id.riga)
        private val av = v.findViewById<ImageView>(R.id.avatarBolla)
        private val testo = v.findViewById<TextView>(R.id.testo)

        fun lega(b: SessionePostino.Bolla) {
            testo.text = b.testo
            if (b.daBoss) {
                riga.gravity = Gravity.END or Gravity.BOTTOM
                av.visibility = View.GONE
                testo.setBackgroundResource(R.drawable.postino_bolla_boss)
                testo.setTextColor(ContextCompat.getColor(itemView.context, R.color.jarvis_su_accento))
            } else {
                riga.gravity = Gravity.START or Gravity.BOTTOM
                av.visibility = View.VISIBLE
                avatar()?.let { av.setImageResource(it) }
                testo.setBackgroundResource(R.drawable.postino_bolla)
                testo.setTextColor(ContextCompat.getColor(itemView.context, if (b.errore) R.color.spia_rosso else R.color.jarvis_testo))
            }
        }
    }

    private inner class SchedaVH(v: View) : RecyclerView.ViewHolder(v) {
        var numero: Int = 0
            private set
        private val scheda = v as MaterialCardView
        private val numeroTesto = v.findViewById<TextView>(R.id.numero)
        private val da = v.findViewById<TextView>(R.id.da)
        private val casella = v.findViewById<TextView>(R.id.casella)
        private val urgenza = v.findViewById<TextView>(R.id.urgenza)
        private val oggetto = v.findViewById<TextView>(R.id.oggetto)
        private val anteprima = v.findViewById<TextView>(R.id.anteprima)
        private val categoria = v.findViewById<TextView>(R.id.categoria)
        private val proposta = v.findViewById<TextView>(R.id.proposta)
        private val stato = v.findViewById<TextView>(R.id.stato)
        private val inviaBozza = v.findViewById<MaterialButton>(R.id.inviaBozza)

        fun lega(x: StatoPostino.Voce) {
            val c = itemView.context
            this.numero = x.numero
            numeroTesto.text = "${x.numero}"
            da.text = x.da
            casella.text = listOf(x.casella, x.data, if (x.allegati > 0) "${x.allegati} ${if (x.allegati == 1) "allegato" else "allegati"}" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            urgenza.text = if (x.urgenza == "alta") "● urgente" else ""
            urgenza.setTextColor(ContextCompat.getColor(c, R.color.spia_rosso))
            oggetto.text = x.oggetto
            anteprima.text = x.stato?.testoBozza?.let { "Bozza: $it" } ?: AzioniPostino.riassuntoBreve(x.anteprima)
            categoria.text = x.categoria
            proposta.text = x.inCorso?.let { "… $it in corso" } ?: if (x.stato == null) StatoPostino.rigaProposta(x.proposta) else ""
            val st = x.stato
            if (st == null) {
                stato.visibility = View.GONE
            } else {
                stato.visibility = View.VISIBLE
                stato.text = StatoPostino.rigaStato(st)
                stato.setTextColor(ContextCompat.getColor(c, when (st.esito) {
                    "ok" -> R.color.spia_verde
                    "annullato" -> R.color.spia_grigio
                    else -> R.color.spia_rosso
                }))
            }
            val bozzaPronta = st?.fatto == true && st.verbo == "rispondi"
            inviaBozza.visibility = if (bozzaPronta) View.VISIBLE else View.GONE
            inviaBozza.setOnClickListener { manda("invia ${x.numero}") }
            scheda.isChecked = x.numero in selezione
            scheda.strokeColor = ContextCompat.getColor(c, if (x.numero in selezione) R.color.jarvis_accento else R.color.jarvis_linea)
            scheda.alpha = if (st?.fatto == true && st.verbo != "rispondi") 0.72f else 1f
            // 0.5.0: tocco = apre la mail; tocco lungo = selezione, poi ogni tocco aggiunge o toglie.
            scheda.setOnClickListener {
                if (selezione.isEmpty()) { apriMail(x.numero); return@setOnClickListener }
                if (!selezione.add(x.numero)) selezione.remove(x.numero)
                ultimaCategoria = x.categoria
                it.animate().scaleX(0.97f).scaleY(0.97f).setDuration(resources.getInteger(R.integer.postino_durata_breve) / 2L)
                    .withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(resources.getInteger(R.integer.postino_durata_breve) / 2L).start() }
                    .start()
                ridisegna()
            }
            scheda.setOnLongClickListener {
                if (!selezione.add(x.numero)) selezione.remove(x.numero)
                ultimaCategoria = x.categoria
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                ridisegna()
                true
            }
        }
    }
}

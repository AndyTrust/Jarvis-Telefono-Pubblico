package com.jarvis.telefono.configura

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.snackbar.Snackbar
import com.jarvis.telefono.BuildConfig
import com.jarvis.telefono.R
import com.jarvis.telefono.cassaforte.Sblocco
import com.jarvis.telefono.ui.Tema
import java.util.concurrent.Executors

/**
 * Un pezzo di schermata di configurazione (permessi, modelli, cervello, posta, prova, VPS, Google, sicurezza).
 * Lo stesso pannello vive nella procedura guidata e nella sua pagina delle Impostazioni: niente doppioni.
 */
abstract class Pannello(protected val a: BaseConfigura) {
    /** Ci sono segreti sullo schermo: FLAG_SECURE (niente screenshot né anteprima nelle app recenti). */
    open val conSegreti: Boolean = false
    abstract fun costruisci(dentro: LinearLayout)
    /** Ad ogni ritorno sulla schermata: i permessi e la cassaforte si rileggono. */
    open fun aggiorna() {}
    open fun chiudi() {}
}

/**
 * La base delle schermate di configurazione (0.4.0): intestazione con «indietro», pagina che scorre e non finisce
 * sotto la tastiera (adjustResize + spazio per l'IME), richieste di permesso, documenti, sblocco con impronta o PIN,
 * lavori in un thread a parte. Le sottoclassi mettono i pannelli in [pagina].
 */
abstract class BaseConfigura : AppCompatActivity() {

    protected lateinit var radice: LinearLayout
    protected lateinit var scorri: ScrollView
    lateinit var pagina: LinearLayout
        protected set
    protected lateinit var titoloTesta: TextView
    protected val pannelli = ArrayList<Pannello>()

    private val lavoro = Executors.newSingleThreadExecutor { r -> Thread(r, "jboss-configura").apply { isDaemon = true } }
    private val principale = Handler(Looper.getMainLooper())

    private var dopoPermesso: ((Boolean) -> Unit)? = null
    private val permesso = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> dopoPermesso?.invoke(ok); dopoPermesso = null }

    private var dopoRisultato: ((Int, Intent?) -> Unit)? = null
    private val risultato = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> dopoRisultato?.invoke(r.resultCode, r.data); dopoRisultato = null }

    private var dopoCrea: ((Uri?) -> Unit)? = null
    private val creaDocumento = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { u -> dopoCrea?.invoke(u); dopoCrea = null }

    private var dopoApri: ((Uri?) -> Unit)? = null
    private val apriDocumento = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> dopoApri?.invoke(u); dopoApri = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        radice = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Mattoni.col(c, R.color.jarvis_fondo)) }
        val testa = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Mattoni.dp(c, 8), Mattoni.dp(c, 8), Mattoni.dp(c, 16), 0)
        }
        testa.addView(ImageButton(c).apply {
            setImageResource(R.drawable.ic_indietro)
            setBackgroundResource(R.drawable.bg_icona)
            contentDescription = "Indietro"
            layoutParams = LinearLayout.LayoutParams(Mattoni.dp(c, 48), Mattoni.dp(c, 48))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        titoloTesta = TextView(c).apply {
            setTextAppearance(R.style.Jarvis_TitoloScheda)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        testa.addView(titoloTesta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Mattoni.dp(c, 8) })
        aggiungiInTesta(testa)
        radice.addView(testa)
        sottoTesta(radice)
        scorri = ScrollView(c).apply { isFillViewport = true; clipToPadding = false }
        pagina = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Mattoni.dp(c, 16), Mattoni.dp(c, 8), Mattoni.dp(c, 16), Mattoni.dp(c, 24))
        }
        scorri.addView(pagina)
        radice.addView(scorri, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        sottoPagina(radice)
        setContentView(radice)
        // Barre di sistema e tastiera: la pagina si accorcia, il campo attivo resta visibile.
        ViewCompat.setOnApplyWindowInsetsListener(radice) { v, ins ->
            val barre = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(barre.left, barre.top, barre.right, maxOf(barre.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Un posto in più nell'intestazione (la procedura guidata ci mette «Chiudi»). */
    protected open fun aggiungiInTesta(testa: LinearLayout) {}
    /** Tra l'intestazione e la pagina (la procedura guidata ci mette l'avanzamento). */
    protected open fun sottoTesta(radice: LinearLayout) {}
    /** Sotto la pagina, fisso (la procedura guidata ci mette «Salta» e «Avanti»). */
    protected open fun sottoPagina(radice: LinearLayout) {}

    fun mettiPannello(p: Pannello, dentro: LinearLayout = pagina) {
        pannelli += p
        p.costruisci(dentro)
        aggiornaSicurezza()
    }

    fun togliPannelli() {
        pannelli.forEach { runCatching { it.chiudi() } }
        pannelli.clear()
        pagina.removeAllViews()
        aggiornaSicurezza()
    }

    /** FLAG_SECURE finché sullo schermo c'è un pannello con segreti (non nel pacchetto di prova, vedi build.gradle). */
    private fun aggiornaSicurezza() {
        if (pannelli.any { it.conSegreti } && BuildConfig.SCHERMO_SICURO) Sblocco.proteggiSchermo(this)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        pannelli.forEach { runCatching { it.aggiorna() } }
    }

    override fun onDestroy() {
        pannelli.forEach { runCatching { it.chiudi() } }
        lavoro.shutdownNow()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        Tema.chiudi(this)
    }

    // ------------------------------------------------------------ servizi per i pannelli

    fun chiediPermesso(p: String, poi: (Boolean) -> Unit) {
        dopoPermesso = poi
        permesso.launch(p)
    }

    fun apriPerRisultato(i: Intent, poi: (Int, Intent?) -> Unit) {
        dopoRisultato = poi
        runCatching { risultato.launch(i) }.onFailure { dopoRisultato = null; avviso("Questa pagina non c'è su questo telefono.") }
    }

    fun creaFile(nome: String, poi: (Uri?) -> Unit) { dopoCrea = poi; creaDocumento.launch(nome) }
    fun scegliFile(poi: (Uri?) -> Unit) { dopoApri = poi; apriDocumento.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }

    fun apri(i: Intent) {
        runCatching { startActivity(i) }.onFailure { avviso("Questa pagina non c'è su questo telefono.") }
    }

    fun avviso(s: String) {
        Snackbar.make(radice, s, Snackbar.LENGTH_LONG).also { (it.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text))?.maxLines = 6 }.show()
    }

    /** [lavora] su un thread a parte, [poi] sul principale solo se la schermata c'è ancora. */
    fun <T> inSfondo(lavora: () -> T, poi: (Result<T>) -> Unit) {
        runCatching {
            lavoro.execute {
                val r = runCatching(lavora)
                principale.post { if (!isFinishing && !isDestroyed) poi(r) }
            }
        }
    }

    fun dopo(ms: Long, f: () -> Unit) { principale.postDelayed({ if (!isFinishing && !isDestroyed) f() }, ms) }

    /**
     * Impronta o PIN prima di mostrare o mandare un segreto (valido 60 s, [Sblocco]). Android 8-9: la schermata
     * di conferma del blocco schermo. Senza blocco schermo: lo dice e non va avanti.
     */
    fun chiediSblocco(titolo: String, ok: () -> Unit) {
        Sblocco.chiedi(this, titolo, ok) { motivo ->
            if (motivo == "vecchio") {
                val i = Sblocco.intentConfermaVecchi(this, titolo)
                if (i == null) { avviso("Imposta un blocco schermo (PIN o impronta) per continuare."); return@chiedi }
                apriPerRisultato(i) { codice, _ -> if (codice == Activity.RESULT_OK) { Sblocco.finestra.segnaSbloccato(); ok() } else avviso("Sblocco annullato.") }
            } else avviso(motivo)
        }
    }

    /** Per chi lancia una pagina di configurazione dall'esterno con lo scorrimento della casa. */
    fun nascondi(v: View) { v.visibility = View.GONE }
}

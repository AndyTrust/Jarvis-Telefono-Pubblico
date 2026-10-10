package com.jarvis.telefono.vps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.jarvis.telefono.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Il terminale della VPS a PIENO SCHERMO (0.3.0): il log vero di un lavoro (passi, comandi, uscite, testo,
 * risultato) come lo manda la VPS, non come lo racconta il modello.
 *
 * Scelta (2026-10-07, agente telefono-vps): vista NATIVA degli eventi del protocollo, non una WebView sul
 * terminale del sito (ttyd dietro login + TOTP e forward_auth di Caddy). La nativa è più leggera (nessuna
 * pagina, nessun xterm.js, funziona con la tastiera di Android), riprende da sola dopo una caduta di rete, e
 * soprattutto passa dalle CONFERME strutturate: un terminale root nel telefono le salterebbe.
 *
 * Barra in alto: indietro, agente e stato, Cerca, Copia, Ruota, Pieno, Ferma. Log a larghezza piena (fondo scuro,
 * monospazio; pressione lunga su una riga = copia la riga). In basso un campo per mandare un compito nuovo alla VPS
 * (la tastiera spinge su il campo). Le CONFERME compaiono in un pannello sopra il log, sempre cliccabile, anche a
 * pieno schermo e in orizzontale. Rotazione senza ricreare la schermata (configChanges nel Manifest).
 */
class TerminaleVpsActivity : AppCompatActivity() {

    private var lavoroId: String? = null
    private var ultimoN = 0
    private var pieno = false
    private lateinit var controllo: WindowInsetsControllerCompat
    private val io = Executors.newSingleThreadExecutor()
    private var smetti: (() -> Unit)? = null

    private lateinit var titolo: TextView
    private lateinit var sottotitolo: TextView
    private lateinit var lista: RecyclerView
    private lateinit var adattatore: RigheAdapter
    private lateinit var ricerca: EditText
    private lateinit var pannello: LinearLayout
    private lateinit var pannelloTitolo: TextView
    private lateinit var pannelloCorpo: TextView
    private lateinit var campo: EditText
    private lateinit var tastoFerma: MaterialButton
    private var confermaMostrata: ConfermaVps? = null

    private val ora = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controllo = WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        lavoroId = savedInstanceState?.getString("id") ?: intent.getStringExtra(NotificheVps.EXTRA_LAVORO)
        setContentView(costruisci())
        if (savedInstanceState?.getBoolean("pieno") == true) alternaPieno()
        ModuloVps.avvia(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(NotificheVps.EXTRA_LAVORO)?.let { mostraLavoro(it) }
    }

    override fun onStart() {
        super.onStart()
        ModuloVps.uiAperta(this)
        ModuloVps.confermeMostrateDallaSchermata(true)
        smetti = ModuloVps.nucleo(this).ascolta(object : NucleoVps.Ascoltatore {
            override fun cambiato(id: String) { if (id == lavoroId || lavoroId == null) runOnUiThread { ricarica() } }
            override fun collegamento(collegato: Boolean) { runOnUiThread { aggiornaTitolo() } }
            override fun conferma(c: ConfermaVps) { runOnUiThread { ricarica() } }
            override fun confermaChiusa(lavoroId: String, azioneId: String, scelta: String) { runOnUiThread { ricarica() } }
        })
        if (lavoroId == null) io.execute {
            val ultimo = ModuloVps.registro(this).elenco(1).firstOrNull()?.id
            runOnUiThread { if (lavoroId == null && ultimo != null) mostraLavoro(ultimo) else ricarica() }
        } else ricarica()
    }

    override fun onStop() {
        super.onStop()
        smetti?.invoke()
        smetti = null
        ModuloVps.confermeMostrateDallaSchermata(false)
        ModuloVps.uiChiusa()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("id", lavoroId)
        outState.putBoolean("pieno", pieno)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "rotazione: ${if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "orizzontale" else "verticale"}")
    }

    // ─── interfaccia ───────────────────────────────────────────────────────────
    private fun dp(v: Int) = ModuloVpsUi.dp(this, v)
    private fun col(id: Int) = ModuloVpsUi.col(this, id)

    private fun tasto(t: String, descr: String, azione: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = t
        isAllCaps = false
        // 0.6.1: ogni tasto almeno 48 x 48 dp (prima 44 dp di altezza).
        minWidth = dp(48)
        minimumWidth = dp(48)
        setPadding(dp(8), 0, dp(8), 0)
        insetTop = 0; insetBottom = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        setTextColor(col(R.color.jarvis_testo))
        contentDescription = descr
        setOnClickListener { azione() }
    }

    private fun costruisci(): View {
        val radice = FrameLayout(this).apply { setBackgroundColor(col(R.color.jarvis_fondo)) }
        val colonna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        radice.addView(colonna, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // barra in alto
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(col(R.color.jarvis_fondo))
        }
        barra.addView(tasto("←", "Indietro") { finish() })
        val testi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        titolo = TextView(this).apply { setTextColor(col(R.color.jarvis_testo)); textSize = 16f; setTypeface(Typeface.SERIF, Typeface.BOLD); maxLines = 1; text = "Terminale VPS" }
        sottotitolo = TextView(this).apply { setTextColor(col(R.color.jarvis_testo_tenue)); textSize = 12f; maxLines = 1 }
        testi.addView(titolo); testi.addView(sottotitolo)
        barra.addView(testi)
        barra.addView(tasto("Cerca", "Cerca nel log") { alternaRicerca() })
        barra.addView(tasto("Copia", "Copia tutto il log") { copiaTutto() })
        barra.addView(tasto("Ruota", "Ruota lo schermo") { ruota() })
        barra.addView(tasto("Pieno", "Pieno schermo") { alternaPieno() })
        tastoFerma = tasto("Ferma", "Ferma il lavoro") { ferma() }.apply { setTextColor(col(R.color.spia_rosso)) }
        barra.addView(tastoFerma)
        colonna.addView(barra)

        ricerca = EditText(this).apply {
            hint = "Cerca nel log"
            visibility = View.GONE
            setSingleLine()
            textSize = 14f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { adattatore.filtra(s?.toString().orEmpty()) }
            })
        }
        colonna.addView(ricerca, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12) })

        // il log: larghezza piena, fondo scuro
        adattatore = RigheAdapter()
        lista = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TerminaleVpsActivity).apply { stackFromEnd = true }
            adapter = adattatore
            setBackgroundColor(ModuloVpsUi.Terminale.FONDO)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            clipToPadding = false
            // 0.3.0: niente animazioni sulle righe del log (in movimento le righe lunghe si sovrapponevano).
            itemAnimator = null
        }
        colonna.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // in basso: un compito nuovo
        val basso = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setBackgroundColor(col(R.color.jarvis_fondo))
        }
        campo = EditText(this).apply {
            hint = "Un compito per la VPS…"
            textSize = 15f
            maxLines = 4
            // 0.3.0: in orizzontale la tastiera non copre tutto con il suo editor: il log resta in vista.
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { manda(); true } else false }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        basso.addView(campo)
        basso.addView(MaterialButton(this).apply { text = "Manda"; isAllCaps = false; setOnClickListener { manda() } })
        colonna.addView(basso)

        // il pannello della conferma: ultimo figlio del FrameLayout = sopra a tutto, e consuma i tocchi
        pannello = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(col(R.color.jarvis_rialzo))
                setStroke(dp(2), col(R.color.jarvis_accento))
            }
            elevation = dp(24).toFloat()
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        pannelloTitolo = TextView(this).apply { setTextColor(col(R.color.jarvis_testo)); textSize = 17f; setTypeface(typeface, Typeface.BOLD) }
        pannelloCorpo = TextView(this).apply { setTextColor(col(R.color.jarvis_testo)); textSize = 14f; setTextIsSelectable(true) }
        val scorre = ScrollView(this).apply { addView(pannelloCorpo) }
        pannello.addView(pannelloTitolo)
        pannello.addView(scorre, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(10), 0, 0) }
        riga.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Annulla"; isAllCaps = false; contentDescription = "Annulla l'azione"
            setOnClickListener { scegli(ProtocolloVps.ANNULLA) }
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) }
        })
        riga.addView(MaterialButton(this).apply {
            text = "Invia"; isAllCaps = false; contentDescription = "Conferma l'azione"
            setOnClickListener { scegli(ProtocolloVps.INVIA) }
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        })
        pannello.addView(riga)
        radice.addView(pannello, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(12)
        })

        // barre di sistema, ritaglio e tastiera
        ViewCompat.setOnApplyWindowInsetsListener(radice) { _, ins ->
            val sis = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            barra.updatePadding(top = sis.top)
            colonna.updatePadding(left = sis.left, right = sis.right)
            basso.updatePadding(bottom = maxOf(sis.bottom, ime.bottom) + dp(6))
            (pannello.layoutParams as FrameLayout.LayoutParams).bottomMargin = maxOf(sis.bottom, ime.bottom) + dp(12)
            pannello.requestLayout()
            ins
        }
        return radice
    }

    // ─── dati ──────────────────────────────────────────────────────────────────
    private fun mostraLavoro(id: String) {
        if (id == lavoroId && adattatore.itemCount > 0) return
        lavoroId = id
        ultimoN = 0
        adattatore.svuota()
        ricarica()
    }

    private fun ricarica() {
        val id = lavoroId
        io.execute {
            val reg = ModuloVps.registro(this)
            val l = id?.let { reg.lavoro(it) }
            val nuovi = if (id != null) reg.eventi(id, ultimoN) else emptyList()
            runOnUiThread {
                if (isFinishing || id != lavoroId) return@runOnUiThread
                val freschi = nuovi.filter { it.n > ultimoN } // due ricariche di fila non duplicano le righe
                if (freschi.isNotEmpty()) {
                    ultimoN = freschi.last().n
                    val inFondo = !lista.canScrollVertically(1)
                    adattatore.aggiungi(freschi.map { riga(it) })
                    if (inFondo) lista.scrollToPosition(adattatore.itemCount - 1)
                }
                aggiornaTitolo(l)
                aggiornaConferma(l)
            }
        }
    }

    private fun aggiornaTitolo(l: LavoroLocale? = lavoroId?.let { ModuloVps.registro(this).lavoro(it) }) {
        titolo.text = if (l == null) "Terminale VPS" else "${ModuloVpsUi.nomeAgente(l.agente)} · ${l.titolo}"
        sottotitolo.text = buildString {
            append("VPS ").append(ModuloVps.statoTesto(this@TerminaleVpsActivity))
            if (l != null) append(" · ").append(ModuloVpsUi.testoStato(l)).append(" · ").append(ModuloVpsUi.durata(l))
        }
        tastoFerma.isEnabled = l != null && l.aperto
    }

    private fun aggiornaConferma(l: LavoroLocale?) {
        val c = l?.conferma
        if (c == null || l.confermaMandata != null) {
            if (pannello.visibility == View.VISIBLE) Log.i(TAG, "pannello conferma nascosto")
            pannello.visibility = View.GONE
            confermaMostrata = null
            return
        }
        if (confermaMostrata?.azioneId == c.azioneId && pannello.visibility == View.VISIBLE) return
        confermaMostrata = c
        pannelloTitolo.text = c.titolo()
        pannelloCorpo.text = ModuloVpsUi.corpoConferma(c)
        pannello.visibility = View.VISIBLE
        pannello.bringToFront()
        Log.i(TAG, "pannello conferma mostrato (${c.azione}, pieno schermo $pieno)")
    }

    private fun scegli(scelta: String) {
        val c = confermaMostrata ?: return
        Log.i(TAG, "tocco sul pannello: $scelta (${c.azioneId})")
        pannello.visibility = View.GONE
        confermaMostrata = null
        io.execute { ModuloVps.scegli(this, c.lavoroId, c.azioneId, scelta) }
        Toast.makeText(this, if (scelta == ProtocolloVps.INVIA) "Sì mandato alla VPS" else "Annullato: l'azione non parte", Toast.LENGTH_SHORT).show()
    }

    private fun manda() {
        val t = campo.text.toString().trim()
        if (t.isEmpty()) return
        if (!ModuloVps.acceso(this) || !ModuloVps.configurato(this)) {
            Toast.makeText(this, "Il Collegamento Jarvis è spento o non configurato: Impostazioni, Collegamento Jarvis. Non l'ho mandato.", Toast.LENGTH_LONG).show()
            return
        }
        campo.setText("")
        val agente = Instradamento.agentePer(t)
        io.execute {
            val id = ModuloVps.mandaLavoro(this, agente, t, origine = SchedaDelega.ORIGINE_SCHERMATA)
            runOnUiThread { mostraLavoro(id) }
        }
    }

    private fun ferma() {
        val id = lavoroId ?: return
        io.execute { ModuloVps.annulla(this, id) }
        Toast.makeText(this, "Fermo il lavoro…", Toast.LENGTH_SHORT).show()
    }

    private fun alternaRicerca() {
        val vis = ricerca.visibility != View.VISIBLE
        ricerca.visibility = if (vis) View.VISIBLE else View.GONE
        if (!vis) { ricerca.setText(""); adattatore.filtra("") } else ricerca.requestFocus()
    }

    private fun copiaTutto() {
        val testo = adattatore.tutto()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log VPS", testo))
        Toast.makeText(this, "Log copiato (${testo.length} caratteri)", Toast.LENGTH_SHORT).show()
    }

    private fun alternaPieno() {
        pieno = !pieno
        if (pieno) controllo.hide(WindowInsetsCompat.Type.systemBars()) else controllo.show(WindowInsetsCompat.Type.systemBars())
        Log.i(TAG, "pieno schermo: $pieno")
    }

    private fun ruota() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ─── righe del log ─────────────────────────────────────────────────────────
    data class Riga(val testo: String, val colore: Int, val grassetto: Boolean = false)

    private fun riga(e: EventoLavoro): Riga {
        val t = ora.format(Date(e.ts))
        return when (e.kind) {
            "comando" -> Riga("$t $ ${e.testo}", ModuloVpsUi.Terminale.COMANDO, true)
            "log" -> {
                val err = e.datiJson().optBoolean("errore")
                Riga((if (err) "$t ! " else "$t   ") + e.testo.trimEnd(), if (err) ModuloVpsUi.Terminale.ERRORE else ModuloVpsUi.Terminale.TENUE)
            }
            "testo" -> Riga("$t · ${e.testo}", ModuloVpsUi.Terminale.TESTO)
            "conferma" -> Riga("$t ? ${e.testo}", ModuloVpsUi.Terminale.CONFERMA, true)
            "risultato" -> Riga("$t ✓ RISULTATO\n${e.testo}", ModuloVpsUi.Terminale.OK)
            "errore" -> Riga("$t ! ${e.testo}", ModuloVpsUi.Terminale.ERRORE, true)
            "fine" -> {
                val esito = e.datiJson().optString("esito")
                Riga("$t ■ FINE ($esito) ${e.testo}", if (esito == "ok") ModuloVpsUi.Terminale.OK else ModuloVpsUi.Terminale.ERRORE, true)
            }
            else -> Riga("$t - ${e.testo}", ModuloVpsUi.Terminale.TENUE)
        }
    }

    private inner class RigheAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val tutte = ArrayList<Riga>()
        private var viste: List<Riga> = tutte
        private var filtro = ""

        fun aggiungi(r: List<Riga>) {
            tutte.addAll(r)
            if (filtro.isEmpty()) notifyItemRangeInserted(tutte.size - r.size, r.size) else filtra(filtro)
        }

        fun svuota() { tutte.clear(); viste = tutte; notifyDataSetChanged() }

        fun filtra(f: String) {
            filtro = f.trim()
            viste = if (filtro.isEmpty()) tutte else tutte.filter { it.testo.contains(filtro, ignoreCase = true) }
            notifyDataSetChanged()
        }

        fun tutto(): String = viste.joinToString("\n") { it.testo }

        override fun getItemCount() = viste.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12.5f
                setPadding(0, dp(2), 0, dp(2))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnLongClickListener { v ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("riga VPS", (v as TextView).text))
                    Toast.makeText(this@TerminaleVpsActivity, "Riga copiata", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = viste[position]
            (holder.itemView as TextView).apply {
                text = r.testo
                setTextColor(r.colore)
                setTypeface(Typeface.MONOSPACE, if (r.grassetto) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    companion object {
        private const val TAG = "JarvisVpsTerminale"
    }
}

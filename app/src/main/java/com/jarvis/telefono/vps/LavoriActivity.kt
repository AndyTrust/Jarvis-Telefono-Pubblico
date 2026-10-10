package com.jarvis.telefono.vps

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.jarvis.telefono.R
import java.util.concurrent.Executors

/**
 * «Lavori sulla VPS» (0.3.0): la schermata che apre il pulsante monitor della chat.
 *
 * ```
 * ←  Lavori sulla VPS      ● collegata
 * [ In corso 2 ][ Finiti 14 ]   [ Pieno schermo ]
 * (R)◐ Ricercatore web · in corso · 4 min
 *     ultimo: curl fonte 3 di 8
 *     [Comandi] [Ferma]
 * ```
 * Modulo spento: il link alle Impostazioni (l'interruttore è lì, uno solo) e una riga che dice cosa fa. Niente collegamento finché è spento.
 */
class LavoriActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private var smetti: (() -> Unit)? = null
    private lateinit var stato: TextView
    private lateinit var elenco: LinearLayout
    private lateinit var interruttore: MaterialButton
    private lateinit var gruppo: MaterialButtonToggleGroup
    private lateinit var tastoInCorso: MaterialButton
    private lateinit var tastoFiniti: MaterialButton
    private var soloFiniti = false

    private fun dp(v: Int) = ModuloVpsUi.dp(this, v)
    private fun col(id: Int) = ModuloVpsUi.col(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val radice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.jarvis_fondo)) }
        val barra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(12), 0) }
        barra.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "←"; contentDescription = "Indietro"; setTextColor(col(R.color.jarvis_testo))
            // 0.6.1: tocco da 48 dp (prima minWidth = 0).
            minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
            setOnClickListener { finish() }
        })
        barra.addView(TextView(this).apply {
            text = "Lavori sulla VPS"; textSize = 20f; setTypeface(Typeface.SERIF, Typeface.BOLD); setTextColor(col(R.color.jarvis_testo))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        stato = TextView(this).apply { textSize = 13f; setTextColor(col(R.color.jarvis_testo_tenue)) }
        barra.addView(stato)
        radice.addView(barra)

        val contenuto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(16)) }
        // 0.6.1: l'interruttore del collegamento sta in UN posto solo (Impostazioni, Collegamento Jarvis): qui il link.
        interruttore = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener {
                com.jarvis.telefono.ui.Tema.apri(this@LavoriActivity,
                    com.jarvis.telefono.configura.SchedaActivity.intento(this@LavoriActivity, com.jarvis.telefono.configura.SchedaActivity.COLLEGAMENTO))
            }
        }
        contenuto.addView(interruttore)

        val righe = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        gruppo = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        tastoInCorso = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); isAllCaps = false; text = "In corso" }
        tastoFiniti = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); isAllCaps = false; text = "Finiti" }
        gruppo.addView(tastoInCorso); gruppo.addView(tastoFiniti)
        gruppo.check(tastoInCorso.id)
        gruppo.addOnButtonCheckedListener { _, id, checked -> if (checked) { soloFiniti = id == tastoFiniti.id; aggiorna() } }
        righe.addView(gruppo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        righe.addView(MaterialButton(this).apply {
            text = "Terminale"; isAllCaps = false; contentDescription = "Apri il terminale a pieno schermo"
            setOnClickListener { ModuloVpsUi.apriLavoro(this@LavoriActivity, null) }
        })
        contenuto.addView(righe)

        elenco = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contenuto.addView(elenco)
        val scorre = ScrollView(this).apply { addView(contenuto) }
        radice.addView(scorre, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(radice)
        ViewCompat.setOnApplyWindowInsetsListener(radice) { _, ins ->
            val sis = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            radice.updatePadding(left = sis.left, top = sis.top, right = sis.right, bottom = sis.bottom)
            ins
        }
    }

    override fun onStart() {
        super.onStart()
        ModuloVps.uiAperta(this)
        smetti = ModuloVps.nucleo(this).ascolta(object : NucleoVps.Ascoltatore {
            override fun cambiato(id: String) { runOnUiThread { aggiorna() } }
            override fun collegamento(collegato: Boolean) { runOnUiThread { aggiorna() } }
        })
        aggiorna()
    }

    override fun onStop() {
        super.onStop()
        smetti?.invoke()
        smetti = null
        ModuloVps.uiChiusa()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun aggiorna() {
        if (io.isShutdown) return
        io.execute {
            val tutti = ModuloVps.registro(this).elenco(100)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val acceso = ModuloVps.acceso(this)
                interruttore.text = if (acceso) "Collegamento Jarvis acceso · cambia" else "Collegamento Jarvis spento · accendilo"
                val st = ModuloVps.statoTesto(this)
                stato.text = "● $st"
                stato.setTextColor(col(if (st == "collegata") R.color.spia_verde else if (st == "modulo spento") R.color.spia_grigio else R.color.spia_giallo))
                val aperti = tutti.filter { it.aperto }
                val finiti = tutti.filter { !it.aperto }
                tastoInCorso.text = "In corso ${aperti.size}"
                tastoFiniti.text = "Finiti ${finiti.size}"
                elenco.removeAllViews()
                if (!acceso) {
                    elenco.addView(nota("Spento: JBoss lavora solo sul telefono e non apre nessun collegamento. Acceso: i compiti lunghi (ricerche, posta in massa, lavori pesanti) girano sulla VPS e tornano qui con passi, comandi e risultato; le azioni delicate aspettano il tuo Invia."))
                    if (!ModuloVps.configurato(this)) elenco.addView(nota("Manca il collegamento con la VPS: in Impostazioni, Collegamento Jarvis, inquadra il QR di Jarvis."))
                }
                val mostrati = if (soloFiniti) finiti else aperti
                if (mostrati.isEmpty()) elenco.addView(nota(if (soloFiniti) "Nessun lavoro finito." else "Nessun lavoro in corso. Scrivi «sulla VPS …» in chat o apri il Terminale."))
                for (l in mostrati) elenco.addView(schedaLavoro(l))
            }
        }
    }

    private fun nota(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(col(R.color.jarvis_testo_tenue)); setPadding(0, dp(12), 0, dp(4))
    }

    private fun schedaLavoro(l: LavoroLocale): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        box.addView(ModuloVpsUi.schedaInCorso(this, l))
        val tasti = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        tasti.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = if (l.aperto) "Comandi" else "Apri risultato"; isAllCaps = false
            setOnClickListener { ModuloVpsUi.apriLavoro(this@LavoriActivity, l.id) }
        })
        if (l.aperto) tasti.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Ferma"; isAllCaps = false; setTextColor(col(R.color.spia_rosso))
            setOnClickListener { io.execute { ModuloVps.annulla(this@LavoriActivity, l.id) } }
        }) else if (l.esito == "errore") tasti.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Ritenta"; isAllCaps = false
            setOnClickListener { io.execute { ModuloVps.mandaLavoro(this@LavoriActivity, l.agente, l.testo, l.modello, origine = l.origine.ifBlank { SchedaDelega.ORIGINE_SCHERMATA }, titolo = l.titolo) } }
        })
        box.addView(tasti)
        return box
    }
}

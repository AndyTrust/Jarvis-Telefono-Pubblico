package com.jarvis.telefono.configura

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.google.android.material.button.MaterialButton
import com.jarvis.telefono.Prefs
import com.jarvis.telefono.R
import com.jarvis.telefono.ui.Movimento

/**
 * La procedura guidata di JBoss (0.4.0, layout ASCII approvati da Boss il 07/10): 1 Benvenuto e permessi,
 * 2 Modelli vocali, 3 Cervello, 4 Account mail, 5 Prova guidata. Ogni passo si salta, lo stato ✓/○ resta chiaro;
 * l'avanzamento in alto si colora (verde fatto, terracotta adesso, grigio da fare) con un'animazione breve.
 * Parte da sola al primo avvio ([StatoConfigurazione.modo]) e si riapre da Impostazioni → Configurazione guidata.
 */
class ConfigurazioneActivity : BaseConfigura() {

    private var passo = Passo.BENVENUTO
    private var modo = ModoAvvio.NUOVA
    private lateinit var contatore: TextView
    private lateinit var sottotitolo: TextView
    private val segmenti = ArrayList<View>()
    private lateinit var avanti: MaterialButton
    private lateinit var salta: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modo = runCatching { ModoAvvio.valueOf(intent.getStringExtra(EXTRA_MODO) ?: "NUOVA") }.getOrDefault(ModoAvvio.NUOVA)
        val f = StatoConfigurazione.fatti(this)
        passo = savedInstanceState?.getString(STATO_PASSO)?.let { Passo.da(it) }
            ?: if (modo == ModoAvvio.RIPRESA) Passi.primoDaFare(f, StatoConfigurazione.saltati(this)) else Passo.BENVENUTO
        sottotitolo.text = Passi.sottotitolo(modo, f)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val prima = Passo.values().getOrNull(passo.ordinal - 1)
                if (prima != null) vaiA(prima) else { isEnabled = false; finish() }
            }
        })
        vaiA(passo, anima = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATO_PASSO, passo.chiave)
    }

    override fun aggiungiInTesta(testa: LinearLayout) {
        contatore = TextView(this).apply {
            setTextColor(Mattoni.col(this@ConfigurazioneActivity, R.color.jarvis_testo_tenue)); textSize = 14f
        }
        testa.addView(contatore)
        testa.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Chiudi"; isAllCaps = false; minHeight = Mattoni.dp(context, 48)
            setTextColor(Mattoni.col(context, R.color.jarvis_accento))
            contentDescription = "Chiudi la configurazione guidata"
            setOnClickListener { chiudi() }
        })
    }

    override fun sottoTesta(radice: LinearLayout) {
        val c = this
        val barra = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Mattoni.dp(c, 16), Mattoni.dp(c, 4), Mattoni.dp(c, 16), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        repeat(Passo.TOTALE) { i ->
            val s = View(c).apply {
                background = GradientDrawable().apply { cornerRadius = Mattoni.dp(c, 3).toFloat(); setColor(Mattoni.col(c, R.color.jarvis_linea)) }
                pivotX = 0f
            }
            segmenti += s
            barra.addView(s, LinearLayout.LayoutParams(0, Mattoni.dp(c, 6), 1f).apply { if (i > 0) marginStart = Mattoni.dp(c, 6) })
        }
        radice.addView(barra)
        sottotitolo = TextView(c).apply {
            setTextColor(Mattoni.col(c, R.color.jarvis_testo_tenue)); textSize = 13f
            setPadding(Mattoni.dp(c, 16), Mattoni.dp(c, 8), Mattoni.dp(c, 16), 0)
        }
        radice.addView(sottotitolo)
    }

    override fun sottoPagina(radice: LinearLayout) {
        val c = this
        val fondo = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Mattoni.dp(c, 16), Mattoni.dp(c, 8), Mattoni.dp(c, 16), Mattoni.dp(c, 12))
            setBackgroundColor(Mattoni.col(c, R.color.jarvis_fondo))
            elevation = Mattoni.dp(c, 2).toFloat()
        }
        salta = Mattoni.bottone(c, "Salta", pieno = false) { salta() }
        avanti = Mattoni.bottone(c, "Avanti") { avanti() }
        fondo.addView(salta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fondo.addView(avanti, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Mattoni.dp(c, 12) })
        radice.addView(fondo)
    }

    private fun pannello(p: Passo): Pannello = when (p) {
        Passo.BENVENUTO -> PannelloPermessi(this)
        Passo.MODELLI -> PannelloModelli(this)
        Passo.CERVELLO -> PannelloCervello(this)
        Passo.POSTA -> PannelloPosta(this)
        Passo.PROVA -> PannelloProva(this)
    }

    private fun vaiA(p: Passo, anima: Boolean = true) {
        val avanza = p.ordinal >= passo.ordinal
        passo = p
        val costruisci = {
            togliPannelli()
            mettiPannello(pannello(p))
            scorri.scrollTo(0, 0)
            titoloTesta.text = p.titolo
            contatore.text = "${p.numero}/${Passo.TOTALE}"
            avanti.text = if (p == Passo.PROVA) "Fine" else "Avanti"
            salta.visibility = if (p == Passo.PROVA) View.INVISIBLE else View.VISIBLE
            titoloTesta.contentDescription = "Passo ${p.numero} di ${Passo.TOTALE}: ${p.titolo}"
            disegnaAvanzamento(anima)
            if (anima) Movimento.compari(pagina, Mattoni.dp(this, if (avanza) 16 else -16).toFloat())
            titoloTesta.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
        if (anima && pagina.childCount > 0) Movimento.scompari(pagina, Mattoni.dp(this, if (avanza) -16 else 16).toFloat()) { costruisci() }
        else costruisci()
    }

    /** Verde = fatto, terracotta = adesso, grigio = da fare o saltato. Il segmento attuale «cresce» da sinistra (220 ms). */
    private fun disegnaAvanzamento(anima: Boolean) {
        val f = StatoConfigurazione.fatti(this)
        StatoConfigurazione.ricordaCompletati(this, f)
        val saltati = StatoConfigurazione.saltati(this)
        Passo.values().forEachIndexed { i, p ->
            val v = segmenti[i]
            val colore = when {
                p == passo -> R.color.jarvis_accento
                Passi.stato(p, f, saltati) == StatoPasso.FATTO -> R.color.spia_verde
                else -> R.color.jarvis_linea
            }
            ((v.background as? GradientDrawable)?.mutate() as? GradientDrawable)?.setColor(Mattoni.col(this, colore))
            if (p == passo && anima && Movimento.attive(this)) {
                v.scaleX = 0.2f
                v.animate().scaleX(1f).setDuration(Movimento.media(this)).setInterpolator(Movimento.CURVA).start()
            } else v.scaleX = 1f
        }
    }

    override fun onResume() {
        super.onResume()
        if (segmenti.isNotEmpty()) disegnaAvanzamento(anima = false)
    }

    private fun avanti() {
        StatoConfigurazione.salta(this, passo, si = false)
        val dopo = Passo.values().getOrNull(passo.ordinal + 1)
        if (dopo == null) fine() else vaiA(dopo)
    }

    private fun salta() {
        val f = StatoConfigurazione.fatti(this)
        if (!Passi.fatto(passo, f)) StatoConfigurazione.salta(this, passo)
        Passo.values().getOrNull(passo.ordinal + 1)?.let { vaiA(it) } ?: fine()
    }

    private fun fine() {
        StatoConfigurazione.segnaChiusa(this)
        Prefs.setPrimoAvvioFatto(this, true)
        finish()
    }

    private fun chiudi() {
        // «Chiudi» vale come «ci penso dopo»: non riparte da sola, si riapre da Impostazioni.
        StatoConfigurazione.segnaChiusa(this)
        Prefs.setPrimoAvvioFatto(this, true)
        finish()
    }

    companion object {
        const val EXTRA_MODO = "modo"
        private const val STATO_PASSO = "passo"
    }
}

package com.jarvis.telefono.vps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jarvis.telefono.R

/**
 * I punti di aggancio dell'interfaccia del modulo VPS, indipendenti dalla Home (che nel blocco 1 la sta
 * rifacendo telefono-ui). Dopo il merge (docs/MODULO-VPS.md):
 * - pulsante monitor in alto a destra nella chat: `setOnClickListener { ModuloVpsUi.apri(this) }`;
 * - segno «in corso sulla VPS» nella chat: `ModuloVpsUi.schedaInCorso(this, lavoro)` (con l'avatar dell'agente
 *   se la Home passa il drawable, altrimenti il monogramma);
 * - Impostazioni: dalla 0.6.0 la sezione è `collegamento/CollegamentoUi.aggiungiSezione` (Collegamento Jarvis).
 * Viste scritte in codice e colori della palette di base (jarvis_*): nessun layout XML da fondere.
 */
object ModuloVpsUi {

    private val NOMI = mapOf(
        "postino" to "Postino", "ricercatore" to "Ricercatore web", "social" to "Social",
        "mani" to "Mani", "scrittore" to "Scrittore", "generico" to "JBoss",
        "crm" to "CRM di lavoro", "patrimonio" to "Patrimonio",
    )

    fun nomeAgente(id: String): String = NOMI[id] ?: id.replaceFirstChar { it.uppercase() }

    /** Il monogramma dell'agente (la Home del blocco 1 ha gli avatar: dopo il merge si passa quello). */
    fun iniziale(id: String): String = nomeAgente(id).take(1).uppercase()

    /** Il testo del pannello e della notifica di conferma: cosa, verso chi, anteprima, scadenza. */
    fun corpoConferma(c: ConfermaVps): String = buildString {
        append(c.domanda.ifBlank { c.motivo })
        if (c.destinatario.isNotBlank()) append("\nVerso: ").append(c.destinatario)
        if (c.anteprima.isNotBlank()) append("\n\n").append(c.anteprima.take(600))
        if (c.scadeTs > 0) {
            val min = ((c.scadeTs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
            append("\n\nSenza risposta entro $min minuti vale «Annulla».")
        }
    }

    /** Apre la schermata dei lavori (o quella del modulo spento, con l'interruttore). */
    fun apri(context: Context) {
        context.startActivity(Intent(context, LavoriActivity::class.java).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    fun apriLavoro(context: Context, id: String?) {
        context.startActivity(Intent(context, TerminaleVpsActivity::class.java).putExtra(NotificheVps.EXTRA_LAVORO, id)
            .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    fun dp(c: Context, v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()
    fun col(c: Context, id: Int): Int = ContextCompat.getColor(c, id)

    fun scheda(c: Context): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(c, 14).toFloat()
        setColor(col(c, R.color.jarvis_rialzo))
        setStroke(dp(c, 1), col(c, R.color.jarvis_linea))
    }

    /**
     * L'avatar dell'agente (0.3.0: le immagini di JBoss, «generico» = JBoss) con l'anello dello stato
     * (terracotta = al lavoro, giallo = aspetta te, grigio = finito). Senza immagine: l'iniziale.
     */
    fun monogramma(c: Context, agente: String, stato: String): View {
        val anello = when (stato) {
            LavoroLocale.ATTESA -> col(c, R.color.spia_giallo)
            LavoroLocale.FINITO -> col(c, R.color.spia_grigio)
            else -> col(c, R.color.jarvis_accento)
        }
        val fondo = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col(c, R.color.jarvis_fondo)); setStroke(dp(c, 3), anello) }
        val id = if (agente == "generico" || agente.isBlank()) com.jarvis.telefono.agenti.CatalogoAgenti.JARVIS else agente
        val lp = LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)).apply { marginEnd = dp(c, 12) }
        if (com.jarvis.telefono.agenti.Avatar.risorsa(id, false) != null) {
            return android.widget.ImageView(c).apply {
                background = fondo
                setPadding(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3))
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                com.jarvis.telefono.agenti.Avatar.metti(this, id)
                contentDescription = nomeAgente(agente)
                layoutParams = lp
            }
        }
        return TextView(c).apply {
            text = iniziale(agente)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(col(c, R.color.jarvis_testo))
            background = fondo
            layoutParams = lp
        }
    }

    fun testoStato(l: LavoroLocale): String = when (l.stato) {
        LavoroLocale.DA_MANDARE -> "in coda sul telefono (parte con la rete)"
        LavoroLocale.INVIATO -> "mandato alla VPS"
        LavoroLocale.CODA -> "in coda sulla VPS"
        LavoroLocale.ATTESA -> "aspetta il tuo sì"
        LavoroLocale.LAVORO -> "in corso sulla VPS"
        else -> when (l.esito) { "ok" -> "finito"; "annullato" -> "annullato"; else -> "non riuscito" }
    }

    fun durata(l: LavoroLocale, adesso: Long = System.currentTimeMillis()): String {
        val fine = if (l.finito > 0) l.finito else adesso
        val s = ((fine - l.creato) / 1000).coerceAtLeast(0)
        return if (s < 60) "$s s" else "${s / 60} min"
    }

    /**
     * Il segno «in corso sulla VPS» per la chat: avatar (monogramma), agente, durata, ultimo passo, tocco = pieno schermo.
     */
    fun schedaInCorso(c: Context, l: LavoroLocale): View {
        val riga = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
            background = scheda(c)
            isClickable = true
            setOnClickListener { apriLavoro(c, l.id) }
            contentDescription = "${nomeAgente(l.agente)}: ${testoStato(l)}"
        }
        riga.addView(monogramma(c, l.agente, l.stato))
        val colonna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        colonna.addView(TextView(c).apply {
            text = "${nomeAgente(l.agente)} · ${testoStato(l)} · ${durata(l)}"
            setTextColor(col(c, R.color.jarvis_testo)); setTypeface(typeface, Typeface.BOLD); textSize = 14f
        })
        colonna.addView(TextView(c).apply {
            text = (if (l.stato == LavoroLocale.FINITO) l.riassunto else l.ultimo.ifBlank { l.titolo }).orEmpty()
            setTextColor(col(c, R.color.jarvis_testo_tenue)); textSize = 13f; maxLines = 2
        })
        riga.addView(colonna)
        return riga
    }

    /**
     * 09/10 (accordo con Boss): la scheda di una delega a Jarvis sulla VPS nel filo di JBoss. Titolo e ora, la richiesta,
     * lo stato in parole, l'esito, e la riga del browser solo se la VPS l'ha mandata. Solo testi di [SchedaDelega]:
     * niente comandi, log o nomi interni. Tocco = il lavoro a pieno schermo (stesso tocco di [schedaInCorso]).
     */
    fun schedaDelega(c: Context, s: SchedaDelega, quando: String): View {
        val riga = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
            background = scheda(c)
            isClickable = true
            isFocusable = true
            setOnClickListener { apriLavoro(c, s.id) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(c, 6); bottomMargin = dp(c, 6) }
        }
        riga.addView(monogramma(c, s.agente, s.statoLavoro))
        val colonna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        fun testo(t: String, colore: Int, grassetto: Boolean = false, dim: Float = 13f, righe: Int = 1) = TextView(c).apply {
            text = t
            setTextColor(colore); textSize = dim; maxLines = righe
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (grassetto) setTypeface(typeface, Typeface.BOLD)
        }
        val tenue = col(c, R.color.jarvis_testo_tenue)
        val coloreStato = when (s.fase) {
            SchedaDelega.Fase.FATTO -> col(c, R.color.spia_verde)
            SchedaDelega.Fase.ERRORE -> col(c, R.color.spia_rosso)
            SchedaDelega.Fase.ANNULLATO -> tenue
            SchedaDelega.Fase.IN_CORSO -> if (s.statoLavoro == LavoroLocale.ATTESA) col(c, R.color.spia_giallo) else col(c, R.color.jarvis_accento)
        }
        colonna.addView(testo("${s.titolo} · $quando", tenue, dim = 12f))
        colonna.addView(testo(s.richiesta, col(c, R.color.jarvis_testo), grassetto = true, dim = 14f, righe = 2))
        colonna.addView(testo("Stato: ${s.testoStato}", coloreStato, grassetto = true))
        colonna.addView(testo("Esito: ${s.esito}", col(c, R.color.jarvis_testo), righe = 2))
        s.rigaBrowser?.let { colonna.addView(testo(it, tenue, righe = 2)) }
        riga.addView(colonna)
        riga.contentDescription = listOfNotNull(s.titolo, s.richiesta, "Stato: ${s.testoStato}", "Esito: ${s.esito}", s.rigaBrowser).joinToString(". ")
        return riga
    }

    /** Colori del log a pieno schermo (fondo scuro come un terminale, leggibile in entrambi i temi). */
    object Terminale {
        val FONDO = Color.parseColor("#1F1E1B")
        val TESTO = Color.parseColor("#EDEAE0")
        val TENUE = Color.parseColor("#A9A498")
        val COMANDO = Color.parseColor("#F0B48C")
        val ERRORE = Color.parseColor("#FF8A80")
        val OK = Color.parseColor("#8FD19E")
        val CONFERMA = Color.parseColor("#FFD27A")
    }
}

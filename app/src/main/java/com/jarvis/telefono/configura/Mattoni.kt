package com.jarvis.telefono.configura

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.telefono.R
import com.jarvis.telefono.ui.Movimento

/**
 * I mattoni delle schermate di configurazione (0.4.0): viste scritte in codice con i colori e i token del tema
 * Claude (values/colors.xml, values-night/colors.xml), come ModuloVpsUi. Tocchi da 48 dp, descrizioni per TalkBack.
 */
object Mattoni {

    fun dp(c: Context, v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()
    fun col(c: Context, id: Int): Int = ContextCompat.getColor(c, id)

    private fun lp(c: Context, sopra: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, sopra) }

    fun colonna(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(c) }

    /** Una scheda: fondo rialzato, bordo sottile, angoli da 16 dp. */
    fun scheda(c: Context, sopra: Int = 12): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        background = GradientDrawable().apply {
            cornerRadius = dp(c, 16).toFloat()
            setColor(col(c, R.color.jarvis_rialzo))
            setStroke(dp(c, 1), col(c, R.color.jarvis_linea))
        }
        layoutParams = lp(c, sopra)
    }

    fun titolo(c: Context, s: String): TextView = TextView(c).apply {
        text = s
        setTextAppearance(R.style.Jarvis_Titolo)
        isFocusable = true
        accessibilityHeading()
    }

    fun sezione(c: Context, s: String): TextView = TextView(c).apply {
        text = s
        setTextAppearance(R.style.JarvisSectionTitle)
        accessibilityHeading()
    }

    private fun TextView.accessibilityHeading() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    fun testo(c: Context, s: String, sopra: Int = 6): TextView = TextView(c).apply {
        text = s
        setTextColor(col(c, R.color.jarvis_testo)); textSize = 15f
        setLineSpacing(0f, 1.15f)
        layoutParams = lp(c, sopra)
    }

    fun nota(c: Context, s: String, sopra: Int = 6): TextView = TextView(c).apply {
        text = s
        setTextColor(col(c, R.color.jarvis_testo_tenue)); textSize = 13f
        setLineSpacing(0f, 1.1f)
        layoutParams = lp(c, sopra)
    }

    /** Il riquadro con l'esito di una prova: verde se è andata, rosso se no. TalkBack lo legge da solo. */
    fun esito(c: Context): TextView = TextView(c).apply {
        textSize = 14f
        visibility = View.GONE
        setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        layoutParams = lp(c, 10)
        if (android.os.Build.VERSION.SDK_INT >= 19) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun mostraEsito(v: TextView, ok: Boolean?, s: String) {
        val c = v.context
        v.text = s
        v.setTextColor(col(c, R.color.jarvis_testo))
        val tinta = col(c, when (ok) { true -> R.color.spia_verde; false -> R.color.spia_rosso; null -> R.color.spia_grigio })
        v.background = GradientDrawable().apply {
            cornerRadius = dp(c, 12).toFloat()
            setColor(col(c, R.color.jarvis_superficie_2))
            setStroke(dp(c, 2), tinta)
        }
        if (v.visibility != View.VISIBLE) { v.visibility = View.VISIBLE; Movimento.compari(v, dp(c, 6).toFloat()) }
    }

    fun bottone(c: Context, s: String, pieno: Boolean = true, click: (View) -> Unit): MaterialButton {
        val b = if (pieno) MaterialButton(c) else MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        return b.apply {
            text = s
            isAllCaps = false
            letterSpacing = 0f
            cornerRadius = dp(c, 24)
            minHeight = dp(c, 48)
            if (!pieno) {
                strokeColor = ContextCompat.getColorStateList(c, R.color.jarvis_accento)
                setTextColor(col(c, R.color.jarvis_accento))
            }
            setOnClickListener(click)
            Movimento.pressione(this)
        }
    }

    /** Due o più bottoni in fila, divisi in parti uguali (si vanno a capo da soli se il testo è grande? no: si stringono). */
    fun fila(c: Context, vararg v: View, sopra: Int = 8): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp(c, sopra)
        v.forEachIndexed { i, x ->
            // Altezza piena: due pulsanti vicini restano alti uguali anche se uno va a capo.
            addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(c, 8) })
        }
    }

    enum class Tipo { TESTO, EMAIL, PASSWORD, NUMERO, INDIRIZZO_WEB, CODICE }

    /**
     * Un campo con l'etichetta che sale. Le password: niente suggerimenti né apprendimento della tastiera
     * (IME_FLAG_NO_PERSONALIZED_LEARNING), ma visibili al gestore di password (autofillHints: Passbolt li compila).
     */
    fun campo(c: Context, etichetta: String, tipo: Tipo, autofill: String? = null): TextInputLayout {
        val t = LayoutInflater.from(c).inflate(R.layout.configura_campo, null, false) as TextInputLayout
        t.hint = etichetta
        t.layoutParams = lp(c, 8)
        val e = t.editText as TextInputEditText
        e.inputType = when (tipo) {
            Tipo.TESTO -> InputType.TYPE_CLASS_TEXT
            Tipo.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            Tipo.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            Tipo.NUMERO -> InputType.TYPE_CLASS_NUMBER
            Tipo.INDIRIZZO_WEB -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            Tipo.CODICE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (tipo == Tipo.PASSWORD) {
            t.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            e.imeOptions = e.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            e.typeface = Typeface.DEFAULT
        }
        if (tipo == Tipo.CODICE || tipo == Tipo.INDIRIZZO_WEB) e.imeOptions = e.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            if (autofill != null) { e.setAutofillHints(autofill); e.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES }
            else e.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        return t
    }

    fun valore(t: TextInputLayout): String = t.editText?.text?.toString().orEmpty()

    /** Il cerchio di stato: ✓ verde (fatto), ○ grigio (da fare), – (saltato). */
    fun spia(c: Context): TextView = TextView(c).apply {
        textSize = 20f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(dp(c, 32), dp(c, 48))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun coloraSpia(v: TextView, stato: StatoPasso, anima: Boolean = true) {
        val c = v.context
        val (s, colore) = when (stato) {
            StatoPasso.FATTO -> "✓" to R.color.spia_verde
            StatoPasso.SALTATO -> "–" to R.color.spia_grigio
            StatoPasso.DA_FARE -> "◯" to R.color.jarvis_testo_tenue
        }
        // Il cerchio grande (◯) è più largo del segno: un po' più piccolo, così i due stanno nella stessa colonna.
        v.textSize = if (stato == StatoPasso.DA_FARE) 16f else 20f
        val cambiato = v.text != s
        v.text = s
        v.setTextColor(col(c, colore))
        if (anima && cambiato && stato == StatoPasso.FATTO && Movimento.attive(c)) {
            v.scaleX = 0.6f; v.scaleY = 0.6f
            v.animate().scaleX(1f).scaleY(1f).setDuration(Movimento.media(c)).setInterpolator(Movimento.CURVA).start()
        }
    }

    /** Una riga «✓ Microfono — a cosa serve — [Concedi]». */
    class Riga(c: Context, etichetta: String, spiega: String, testoBottone: String?, click: ((View) -> Unit)?) {
        val vista: LinearLayout = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(c, 56)
            layoutParams = lp(c, 4)
        }
        val spia = spia(c)
        private val nome = TextView(c).apply { text = etichetta; setTextColor(col(c, R.color.jarvis_testo)); textSize = 15f }
        private val spiegazione = TextView(c).apply { text = spiega; setTextColor(col(c, R.color.jarvis_testo_tenue)); textSize = 13f }
        private val etichettaBase = etichetta
        val bottone: MaterialButton? = testoBottone?.let { tb ->
            MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = tb; isAllCaps = false; letterSpacing = 0f; cornerRadius = dp(c, 24); minHeight = dp(c, 48); minWidth = dp(c, 96)
                strokeColor = ContextCompat.getColorStateList(c, R.color.jarvis_accento)
                setTextColor(col(c, R.color.jarvis_accento))
                setOnClickListener { click?.invoke(it) }
                contentDescription = "$tb: $etichetta"
            }
        }

        init {
            vista.addView(spia)
            val testi = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(nome); addView(spiegazione) }
            vista.addView(testi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(c, 4); marginEnd = dp(c, 8) })
            bottone?.let { vista.addView(it) }
        }

        fun aggiorna(stato: StatoPasso, testoBottone: String? = null, bottoneAttivo: Boolean = true, spiega: String? = null) {
            coloraSpia(spia, stato)
            spiega?.let { spiegazione.text = it }
            bottone?.let { b -> testoBottone?.let { b.text = it }; b.isEnabled = bottoneAttivo; b.alpha = if (bottoneAttivo) 1f else 0.45f }
            vista.contentDescription = etichettaBase + ": " + when (stato) { StatoPasso.FATTO -> "fatto"; StatoPasso.SALTATO -> "saltato"; StatoPasso.DA_FARE -> "da fare" } +
                ". " + spiegazione.text
        }
    }

    fun chip(c: Context, s: String): Chip = (LayoutInflater.from(c).inflate(R.layout.configura_chip, null, false) as Chip).apply {
        text = s
        isCheckable = true
    }
}

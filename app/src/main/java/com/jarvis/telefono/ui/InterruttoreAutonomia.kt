package com.jarvis.telefono.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.jarvis.telefono.R
import com.jarvis.telefono.agenti.Autonomia

/**
 * L'interruttore a due posizioni «Chiedi prima | Vai da solo» delle schede Agenti (0.2.0).
 * Un cursore terracotta scorre sotto la scelta (durata dai token, niente animazione se Boss le
 * ha tolte). Con [bloccato] «Vai da solo» porta il lucchetto e non si può scegliere: il tocco
 * scuote l'interruttore e chiama [suBloccato].
 *
 * Per TalkBack le due metà sono pulsanti di scelta con «selezionato».
 */
class InterruttoreAutonomia @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val cursore = View(context)
    private val chiedi = etichetta(context.getString(R.string.autonomia_chiedi))
    private val daSolo = etichetta(context.getString(R.string.autonomia_da_solo))

    var valore: Autonomia = Autonomia.CHIEDI_PRIMA
        private set
    var bloccato: Boolean = false
        private set

    /** Boss ha scelto (solo cambi veri). */
    var suCambio: ((Autonomia) -> Unit)? = null

    /** Boss ha toccato «Vai da solo» bloccato. */
    var suBloccato: (() -> Unit)? = null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    init {
        background = ContextCompat.getDrawable(context, R.drawable.bg_binario)
        minimumHeight = resources.getDimensionPixelSize(R.dimen.tocco_minimo)
        cursore.background = ContextCompat.getDrawable(context, R.drawable.bg_cursore)
        // 09/10: altezza fissa. Con MATCH_PARENT il cursore, misurato dal FrameLayout in wrap_content, prendeva tutta
        // l'altezza disponibile (nella scheda in basso mezzo schermo) e i due «pulsanti» si allungavano con lui.
        addView(cursore, LayoutParams(0, resources.getDimensionPixelSize(R.dimen.tocco_minimo)))
        val riga = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        riga.addView(chiedi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        riga.addView(daSolo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(riga, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.tocco_minimo)))
        setPadding(dp(4), dp(4), dp(4), dp(4))
        chiedi.setOnClickListener { scegli(Autonomia.CHIEDI_PRIMA) }
        daSolo.setOnClickListener {
            if (bloccato) { Movimento.scuoti(this); suBloccato?.invoke() } else scegli(Autonomia.DA_SOLO)
        }
        disegna(animato = false)
    }

    private fun etichetta(t: String) = TextView(context).apply {
        text = t
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.testo_corpo))
        minHeight = resources.getDimensionPixelSize(R.dimen.tocco_minimo) - dp(8)
        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.RadioButton"
                info.isCheckable = true
                info.isChecked = host.isSelected
            }
        })
    }

    /** Dal modello: senza avvisare [suCambio]. */
    fun imposta(v: Autonomia, bloccato: Boolean, animato: Boolean) {
        val cambia = v != valore || bloccato != this.bloccato
        valore = v
        this.bloccato = bloccato
        if (cambia || cursore.width == 0) disegna(animato)
    }

    private fun scegli(v: Autonomia) {
        if (v == valore) return
        valore = v
        disegna(animato = true)
        suCambio?.invoke(v)
    }

    /**
     * 09/10: misure fisse e leggibili, qualunque cosa chieda il genitore: alto quanto un tocco più il bordo,
     * largo al massimo [LARGHEZZA_MAX_DP] (o meno se lo spazio non c'è).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val disponibile = MeasureSpec.getSize(widthMeasureSpec)
        val massima = dp(LARGHEZZA_MAX_DP)
        val larghezza = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) massima else minOf(disponibile, massima)
        val altezza = resources.getDimensionPixelSize(R.dimen.tocco_minimo) + paddingTop + paddingBottom
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(larghezza, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(altezza, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val meta = (width - paddingLeft - paddingRight) / 2
        if (cursore.layoutParams.width != meta && meta > 0) {
            cursore.layoutParams = (cursore.layoutParams as LayoutParams).apply { width = meta }
            post { disegna(animato = false) }
        }
    }

    private fun disegna(animato: Boolean) {
        val meta = (width - paddingLeft - paddingRight) / 2f
        val x = if (valore == Autonomia.DA_SOLO) meta else 0f
        cursore.animate().cancel()
        if (animato && Movimento.attive() && meta > 0) {
            cursore.animate().translationX(x).setDuration(Movimento.media(context)).setInterpolator(Movimento.CURVA).start()
        } else cursore.translationX = x
        val su = ContextCompat.getColor(context, R.color.jarvis_su_accento)
        val testo = ContextCompat.getColor(context, R.color.jarvis_testo)
        val tenue = ContextCompat.getColor(context, R.color.jarvis_testo_tenue)
        chiedi.setTextColor(if (valore == Autonomia.CHIEDI_PRIMA) su else testo)
        daSolo.setTextColor(if (valore == Autonomia.DA_SOLO) su else if (bloccato) tenue else testo)
        chiedi.isSelected = valore == Autonomia.CHIEDI_PRIMA
        daSolo.isSelected = valore == Autonomia.DA_SOLO
        val lucchetto = if (bloccato) ContextCompat.getDrawable(context, R.drawable.ic_lucchetto)?.mutate()?.apply {
            setTint(tenue)
            setBounds(0, 0, dp(16), dp(16))
        } else null
        daSolo.setCompoundDrawablesRelative(null, null, lucchetto, null)
        daSolo.compoundDrawablePadding = dp(4)
        daSolo.contentDescription = context.getString(R.string.autonomia_da_solo) +
            if (bloccato) ", " + context.getString(R.string.autonomia_bloccata_breve) else ""
    }

    companion object {
        /** La larghezza massima dell'interruttore: due etichette leggibili, niente barra da bordo a bordo. */
        const val LARGHEZZA_MAX_DP = 296
    }
}

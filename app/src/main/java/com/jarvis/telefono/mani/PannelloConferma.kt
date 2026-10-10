package com.jarvis.telefono.mani

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jarvis.telefono.R

/**
 * Il pannello che compare SOPRA l'app vera (WhatsApp, Gmail…) quando una bozza aspetta
 * «invia»: chi, cosa, e due pulsanti — Annulla e Invia (1.2.4: «Ferma» tolto, Boss 07/10: creava
 * confusione; «Annulla» fa quello che faceva «Ferma»). È una finestra
 * dell'accessibilità (TYPE_ACCESSIBILITY_OVERLAY): non serve il permesso «sopra le altre app»
 * e non ruba il fuoco all'app sotto, così Boss vede la bozza vera sotto il pannello.
 */
class PannelloConferma(private val servizio: AccessibilityService) {

    private var vista: View? = null
    private val wm get() = servizio.getSystemService(WindowManager::class.java)

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), servizio.resources.displayMetrics).toInt()

    fun visibile(): Boolean = vista != null

    fun mostra(
        titolo: String,
        corpo: String,
        etichettaSi: String,
        onSi: () -> Unit,
        onNo: () -> Unit,
    ) {
        nascondi()
        val ctx = servizio
        // 0.2.0: colori del tema Claude (chiaro o scuro col sistema), stessa disposizione di prima.
        fun col(id: Int) = ContextCompat.getColor(ctx, id)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = ctx.resources.getDimension(R.dimen.raggio_scheda)
                setColor(col(R.color.jarvis_bolla_fondo))
                setStroke(dp(2), col(R.color.jarvis_accento))
            }
            elevation = dp(4).toFloat()
        }
        box.addView(TextView(ctx).apply {
            text = titolo
            setTextColor(col(R.color.jarvis_testo))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val scorre = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        }
        scorre.addView(TextView(ctx).apply {
            text = corpo
            setTextColor(col(R.color.jarvis_testo))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.END
        })
        box.addView(scorre)
        box.addView(TextView(ctx).apply {
            text = "Di' «invia» o «annulla», o tocca. Qualsiasi altra frase torna a JBoss."
            setTextColor(col(R.color.jarvis_testo_tenue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(4))
        })
        val riga = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        // Annulla: pillola neutra col bordo; Invia: pillola terracotta. 48 dp di tocco.
        fun pulsante(t: String, pieno: Boolean, azione: () -> Unit) = Button(ctx).apply {
            text = t
            isAllCaps = false
            setTextColor(if (pieno) col(R.color.jarvis_su_accento) else col(R.color.jarvis_testo))
            background = GradientDrawable().apply {
                cornerRadius = ctx.resources.getDimension(R.dimen.raggio_pillola)
                if (pieno) setColor(col(R.color.jarvis_accento))
                else { setColor(col(R.color.jarvis_superficie_2)); setStroke(dp(1), col(R.color.jarvis_linea)) }
            }
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) }
            setOnClickListener { nascondi(); azione() }
        }
        riga.addView(pulsante(CancelloInvio.SCELTE[0], false, onNo))
        riga.addView(pulsante(etichettaSi, true, onSi))
        box.addView(riga)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            // 1.2.2: sotto la bolla di stato (BollaStato.Y_DP + la sua altezza).
            y = dp(100)
            horizontalMargin = 0.03f
        }
        runCatching { wm.addView(box, lp); vista = box }
    }

    fun nascondi() {
        val v = vista ?: return
        vista = null
        runCatching { wm.removeView(v) }
    }
}

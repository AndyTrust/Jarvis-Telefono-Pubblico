package com.jarvis.telefono.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import com.jarvis.telefono.R
import com.jarvis.telefono.Tinte
import com.jarvis.telefono.agenti.StatoAgente

/**
 * I colori dal tema (values/colors.xml e values-night/colors.xml): così il modo scuro segue il
 * sistema senza codice in più. [Tinte] resta per le funzioni pure (TestiStato) e qui si traduce
 * nel colore giusto per chiaro o scuro.
 */
object Tema {

    fun colore(c: Context, id: Int): Int = ContextCompat.getColor(c, id)

    fun scuro(c: Context): Boolean =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Una tinta di [Tinte] (palette chiara) nel colore del tema corrente. */
    fun daTinta(c: Context, t: Int): Int = colore(
        c,
        when (t) {
            Tinte.VERDE -> R.color.spia_verde
            Tinte.GIALLO -> R.color.spia_giallo
            Tinte.ROSSO -> R.color.spia_rosso
            Tinte.GRIGIO -> R.color.spia_grigio
            Tinte.ACCENTO -> R.color.jarvis_accento
            Tinte.TESTO -> R.color.jarvis_testo
            else -> R.color.jarvis_testo_tenue
        },
    )

    fun perStato(c: Context, s: StatoAgente): Int = colore(
        c,
        when (s) {
            StatoAgente.PRONTO -> R.color.spia_verde
            StatoAgente.LAVORA -> R.color.jarvis_accento
            StatoAgente.ERRORE -> R.color.spia_rosso
            StatoAgente.SPENTO, StatoAgente.NON_COLLEGATO, StatoAgente.NON_ATTIVO -> R.color.spia_grigio
        },
    )

    /** Colora un pallino (forma ovale) senza toccare il bordo. */
    fun pallino(v: View, colore: Int) {
        (v.background?.mutate() as? GradientDrawable)?.setColor(colore)
    }

    /** Apre [intent] con lo scorrimento Home → Agenti (niente animazione se Boss le ha tolte). */
    fun apri(a: Activity, intent: Intent) {
        a.startActivity(intent)
        @Suppress("DEPRECATION")
        if (Movimento.attive(a)) a.overridePendingTransition(R.anim.entra_da_destra, R.anim.resta)
        else a.overridePendingTransition(0, 0)
    }

    /** Da chiamare dopo finish() delle schermate aperte con [apri]. */
    fun chiudi(a: Activity) {
        @Suppress("DEPRECATION")
        if (Movimento.attive(a)) a.overridePendingTransition(R.anim.resta, R.anim.esci_a_destra)
        else a.overridePendingTransition(0, 0)
    }
}

package com.jarvis.telefono.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import com.jarvis.telefono.R

/**
 * Le animazioni dell'app (0.2.0): sobrie, 150-300 ms, durate dai token (values/tokens.xml).
 *
 * Rispettano le scelte di sistema: Android moltiplica ogni durata per la scala delle animazioni
 * (Opzioni sviluppatore) e con «Rimuovi animazioni» dell'accessibilità la scala è zero:
 * allora [attive] è falso e qui non parte niente, si mette subito lo stato finale.
 * I loop (pulsazione) li ferma chi li ha avviati in onPause/onStop: niente batteria a schermo spento.
 */
object Movimento {

    /** Curva standard di Material (veloce all'inizio, morbida alla fine). */
    val CURVA = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** 0.6.1: «Animazioni ridotte» di Impostazioni → Aspetto (la imposta JBossApp all'avvio e la pagina Aspetto). */
    @Volatile var ridotte: Boolean = false

    /** Falso con «Rimuovi animazioni», scala a zero o «Animazioni ridotte» scelto nell'app. */
    fun attive(): Boolean = !ridotte && ValueAnimator.areAnimatorsEnabled()

    /** La scala di sistema (Settings.Global.ANIMATOR_DURATION_SCALE), 1 se non si legge. */
    fun scala(c: Context): Float = runCatching {
        android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    /** Le animazioni di finestra (Home ↔ Agenti) seguono la stessa scelta. */
    fun attive(c: Context): Boolean = attive() && animazioniAttive(scala(c))

    fun breve(c: Context): Long = c.resources.getInteger(R.integer.durata_breve).toLong()
    fun media(c: Context): Long = c.resources.getInteger(R.integer.durata_media).toLong()
    fun lunga(c: Context): Long = c.resources.getInteger(R.integer.durata_lunga).toLong()
    fun pulsazione(c: Context): Long = c.resources.getInteger(R.integer.durata_pulsazione).toLong()

    /**
     * Pressione e rilascio: la vista si rimpicciolisce un poco sotto il dito e torna.
     * Non consuma il tocco: click e pressione lunga restano quelli della vista.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun pressione(v: View) {
        val scala = v.resources.getInteger(R.integer.scala_pressione_millesimi) / 1000f
        v.setOnTouchListener { vista, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> verso(vista, scala, breve(vista.context))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> verso(vista, 1f, media(vista.context))
            }
            false
        }
    }

    private fun verso(v: View, scala: Float, ms: Long) {
        v.animate().cancel()
        if (!attive()) { v.scaleX = 1f; v.scaleY = 1f; return }
        v.animate().scaleX(scala).scaleY(scala).setDuration(ms).setInterpolator(CURVA).start()
    }

    /** Comparsa: scorre di [dyPx] verso il suo posto e si accende. */
    fun compari(v: View, dyPx: Float, ritardoMs: Long = 0) {
        v.animate().cancel()
        if (!attive()) { v.alpha = 1f; v.translationY = 0f; return }
        v.alpha = 0f
        v.translationY = dyPx
        v.animate().alpha(1f).translationY(0f).setStartDelay(ritardoMs)
            .setDuration(media(v.context)).setInterpolator(DecelerateInterpolator()).start()
    }

    /** Scomparsa: si spegne scorrendo di [dyPx]; poi [fine] (subito, senza animazioni). */
    fun scompari(v: View, dyPx: Float, fine: () -> Unit) {
        v.animate().cancel()
        if (!attive()) { fine(); return }
        v.animate().alpha(0f).translationY(dyPx).setStartDelay(0)
            .setDuration(breve(v.context)).setInterpolator(AccelerateInterpolator())
            .withEndAction { fine() }.start()
    }

    /**
     * L'alone dell'agente che lavora: cresce e sfuma, all'infinito. Restituisce l'animazione da
     * fermare con [ferma] (null se le animazioni sono spente: l'alone resta fisso e visibile).
     */
    fun pulsa(alone: View): Animator? {
        alone.visibility = View.VISIBLE
        if (!attive()) { alone.alpha = 0.6f; alone.scaleX = 1f; alone.scaleY = 1f; return null }
        val ms = pulsazione(alone.context)
        val a = ObjectAnimator.ofFloat(alone, View.ALPHA, 0.85f, 0.2f)
        val sx = ObjectAnimator.ofFloat(alone, View.SCALE_X, 0.92f, 1.12f)
        val sy = ObjectAnimator.ofFloat(alone, View.SCALE_Y, 0.92f, 1.12f)
        listOf(a, sx, sy).forEach { it.repeatCount = ValueAnimator.INFINITE; it.repeatMode = ValueAnimator.REVERSE }
        return AnimatorSet().apply { playTogether(a, sx, sy); duration = ms; start() }
    }

    fun ferma(anim: Animator?, alone: View?) {
        anim?.cancel()
        alone?.apply { visibility = View.GONE; alpha = 1f; scaleX = 1f; scaleY = 1f }
    }

    /** Piccola scossa orizzontale: «questo non si può» (autonomia bloccata). */
    fun scuoti(v: View) {
        if (!attive()) return
        val d = 6f * v.resources.displayMetrics.density
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, d, -d, d / 2, 0f).apply {
            duration = lunga(v.context)
            start()
        }
    }
}

/** Funzioni pure, provate in MovimentoTest. */
fun animazioniAttive(scala: Float): Boolean = scala > 0f

/** La durata che vede Boss: quella del token per la scala di sistema. */
fun durataEffettiva(baseMs: Long, scala: Float): Long = if (scala <= 0f) 0L else (baseMs * scala).toLong()

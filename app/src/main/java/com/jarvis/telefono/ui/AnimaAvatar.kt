package com.jarvis.telefono.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.LightingColorFilter
import android.os.PowerManager
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.jarvis.telefono.R

/**
 * Gli avatar vivi (0.2.0, Boss 07/10: «anima gli avatar»). Sono bitmap: si animano solo con
 * trasformazioni (scala, rotazione, luce), niente fotogrammi nuovi.
 *
 *  - RIPOSO: respiro lento, scala 1,00-1,03 (1200 ms per verso).
 *  - ASCOLTO: si inclina e pulsa, anello terracotta che si accende e si spegne (600 ms).
 *  - LAVORO: oscilla piano (900 ms) e l'anello a coda gira (1200 ms al giro).
 *  - [luccica]: lampo di luce breve al completamento (400 ms).
 *
 * Le durate passano dalla scala di sistema (ANIMATOR_DURATION_SCALE); con le animazioni tolte
 * resta tutto fermo e l'anello si vede fisso. [ferma] in onStop; a schermo spento non parte niente.
 */
class AnimaAvatar(private val avatar: ImageView, private val anello: View?) {

    enum class Modo { RIPOSO, ASCOLTO, LAVORO }

    var modo: Modo? = null
        private set
    private var giro: Animator? = null

    private val ctx: Context get() = avatar.context

    private fun schermoAcceso(): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive != false

    fun imposta(m: Modo) {
        if (m == modo && (giro != null || !Movimento.attive())) return
        ferma()
        modo = m
        anello?.apply {
            visibility = if (m == Modo.RIPOSO) View.GONE else View.VISIBLE
            background = ContextCompat.getDrawable(ctx, if (m == Modo.LAVORO) R.drawable.bg_anello_lavoro else R.drawable.bg_anello_ascolto)
            alpha = 1f
        }
        if (!Movimento.attive() || !schermoAcceso()) return
        giro = when (m) {
            Modo.RIPOSO -> insieme(1200, LinearInterpolator(),
                avanti(ObjectAnimator.ofFloat(avatar, View.SCALE_X, 1f, 1.03f)),
                avanti(ObjectAnimator.ofFloat(avatar, View.SCALE_Y, 1f, 1.03f)))
            Modo.ASCOLTO -> insieme(600, null,
                avanti(ObjectAnimator.ofFloat(avatar, View.ROTATION, -3f, 3f)),
                avanti(ObjectAnimator.ofFloat(avatar, View.SCALE_X, 1f, 1.06f)),
                avanti(ObjectAnimator.ofFloat(avatar, View.SCALE_Y, 1f, 1.06f)),
                *listOfNotNull(anello?.let { avanti(ObjectAnimator.ofFloat(it, View.ALPHA, 0.35f, 1f)) }).toTypedArray())
            Modo.LAVORO -> {
                val oscilla = avanti(ObjectAnimator.ofFloat(avatar, View.ROTATION, -4f, 4f)).apply { duration = 900 }
                val gira = anello?.let {
                    ObjectAnimator.ofFloat(it, View.ROTATION, 0f, 360f).apply {
                        duration = 1200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                    }
                }
                AnimatorSet().apply { playTogether(listOfNotNull(oscilla, gira)); start() }
            }
        }
    }

    /** Lampo di luce breve: il lavoro è finito bene. */
    fun luccica() {
        if (!Movimento.attive()) return
        ValueAnimator.ofInt(0, 90, 0).apply {
            duration = 400
            addUpdateListener {
                val v = it.animatedValue as Int
                avatar.colorFilter = if (v == 0) null else LightingColorFilter(0xFFFFFF, (v shl 16) or (v shl 8) or v)
            }
            start()
        }
        avatar.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).withEndAction {
            avatar.animate().scaleX(1f).scaleY(1f).setDuration(250).setInterpolator(OvershootInterpolator()).start()
        }.start()
    }

    /** Tutto fermo e al suo posto (onStop, schermo spento, vista che sparisce). */
    fun ferma() {
        giro?.cancel()
        giro = null
        modo = null
        avatar.rotation = 0f; avatar.scaleX = 1f; avatar.scaleY = 1f; avatar.colorFilter = null
        anello?.rotation = 0f
    }

    private fun avanti(a: ObjectAnimator) = a.apply { repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE }

    private fun insieme(ms: Long, interp: android.animation.TimeInterpolator?, vararg a: Animator): Animator =
        AnimatorSet().apply {
            playTogether(*a); duration = ms
            if (interp != null) interpolator = interp
            start()
        }

    companion object {
        /** Comparsa del chip di un agente: piccolo rimbalzo (300 ms). */
        fun rimbalza(v: View) {
            v.animate().cancel()
            v.visibility = View.VISIBLE
            if (!Movimento.attive()) { v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; return }
            v.alpha = 0f; v.scaleX = 0.7f; v.scaleY = 0.7f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(2f)).start()
        }

        /** Uscita del chip: dissolvenza e scorrimento, poi sparisce. */
        fun esci(v: View, fine: () -> Unit = {}) {
            Movimento.scompari(v, -v.resources.getDimension(R.dimen.spazio_1)) {
                v.visibility = View.GONE; v.alpha = 1f; v.translationY = 0f; fine()
            }
        }
    }
}

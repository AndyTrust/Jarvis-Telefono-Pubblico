package com.jarvis.telefono

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.jarvis.telefono.bolla.PrimoPiano
import com.jarvis.telefono.ui.Movimento

/**
 * 0.6.1: all'avvio del processo applica le scelte di Impostazioni → Aspetto (tema e animazioni ridotte), così
 * valgono per ogni schermata, anche quelle aperte da una notifica.
 */
class JBossApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applicaAspetto(this)
        // Boss 09/10: il popup della voce solo con l'app chiusa. Il segno «app in primo piano» nasce qui.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) = PrimoPiano.avviata(eChatJBoss(a), ePostino(a))
            override fun onActivityStopped(a: android.app.Activity) =
                PrimoPiano.fermata(eChatJBoss(a), cambioConfigurazione = a.isChangingConfigurations, ePostino = ePostino(a))
            override fun onActivityCreated(a: android.app.Activity, s: android.os.Bundle?) = Unit
            override fun onActivityResumed(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, s: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
    }

    private fun eChatJBoss(a: android.app.Activity): Boolean = (a as? AgenteChatActivity)?.chatDiJBoss == true

    /** 09/10: la pagina del Postino vera (non la prova con dati finti): lì la voce resta accesa dopo «passa al Postino». */
    private fun ePostino(a: android.app.Activity): Boolean = (a as? com.jarvis.telefono.postino.PostinoActivity)?.paginaVera == true

    companion object {
        fun modoNotte(tema: String): Int = when (tema) {
            Prefs.TEMA_CHIARO -> AppCompatDelegate.MODE_NIGHT_NO
            Prefs.TEMA_SCURO -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        fun applicaAspetto(c: android.content.Context) {
            Movimento.ridotte = Prefs.isAnimazioniRidotte(c)
            val modo = modoNotte(Prefs.getTema(c))
            if (AppCompatDelegate.getDefaultNightMode() != modo) AppCompatDelegate.setDefaultNightMode(modo)
        }
    }
}

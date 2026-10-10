package com.jarvis.telefono.configura

import android.content.Intent
import android.widget.LinearLayout
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jarvis.telefono.BuildConfig
import com.jarvis.telefono.Debug
import com.jarvis.telefono.JBossApp
import com.jarvis.telefono.LicenzeActivity
import com.jarvis.telefono.Prefs
import com.jarvis.telefono.R
import com.jarvis.telefono.ui.Movimento
import com.jarvis.telefono.ui.Tema

/**
 * Impostazioni → Aspetto (0.6.1, layout approvato): tema del sistema, chiaro o scuro, e «Animazioni ridotte».
 * Il tema cambia subito (la pagina si ridisegna); le animazioni si spengono in tutta l'app.
 */
class PannelloAspetto(a: BaseConfigura) : Pannello(a) {
    private lateinit var tema: ChipGroup
    private lateinit var ridotte: SwitchMaterial
    private val ids = HashMap<String, Int>()

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        val b = Mattoni.scheda(c)
        b.addView(Mattoni.sezione(c, "TEMA"))
        tema = ChipGroup(c).apply {
            isSingleSelection = true
            isSelectionRequired = true
            for ((k, nome) in listOf(Prefs.TEMA_SISTEMA to "Come il telefono", Prefs.TEMA_CHIARO to "Chiaro", Prefs.TEMA_SCURO to "Scuro")) {
                val chip = Mattoni.chip(c, nome).apply { id = android.view.View.generateViewId(); setEnsureMinTouchTargetSize(true) }
                ids[k] = chip.id
                addView(chip)
            }
        }
        b.addView(tema)
        b.addView(Mattoni.nota(c, "I colori di Claude, in chiaro e in scuro, con il contrasto giusto per leggere."))
        dentro.addView(b)

        val b2 = Mattoni.scheda(c)
        b2.addView(Mattoni.sezione(c, "MOVIMENTO"))
        ridotte = SwitchMaterial(c).apply {
            text = "Animazioni ridotte"
            textSize = 15f
            minHeight = Mattoni.dp(c, 48)
            setTextColor(Mattoni.col(c, R.color.jarvis_testo))
        }
        b2.addView(ridotte)
        b2.addView(Mattoni.nota(c, "Niente movimenti nelle schermate. Vale anche «Rimuovi animazioni» delle impostazioni di Android."))
        dentro.addView(b2)
        aggiorna()
    }

    override fun aggiorna() {
        tema.setOnCheckedStateChangeListener(null)
        ids[Prefs.getTema(a)]?.let { tema.check(it) }
        tema.setOnCheckedStateChangeListener { _, sel ->
            val k = ids.entries.firstOrNull { it.value == sel.firstOrNull() }?.key ?: return@setOnCheckedStateChangeListener
            if (k == Prefs.getTema(a)) return@setOnCheckedStateChangeListener
            Prefs.setTema(a, k)
            android.util.Log.i("JarvisConfig", "tema scelto dall'app: $k")
            JBossApp.applicaAspetto(a) // ricrea le schermate aperte con il tema nuovo
        }
        ridotte.setOnCheckedChangeListener(null)
        ridotte.isChecked = Prefs.isAnimazioniRidotte(a)
        ridotte.setOnCheckedChangeListener { _, si ->
            Prefs.setAnimazioniRidotte(a, si)
            Movimento.ridotte = si
            android.util.Log.i("JarvisConfig", "animazioni ridotte: ${if (si) "sì" else "no"}")
        }
    }
}

/** Impostazioni → Informazioni: versione e licenze, in un posto solo (prima anche nella schermata Agenti). */
class PannelloInfo(a: BaseConfigura) : Pannello(a) {
    override fun costruisci(dentro: LinearLayout) {
        val c = a
        val b = Mattoni.scheda(c)
        b.addView(Mattoni.sezione(c, "VERSIONE"))
        b.addView(Mattoni.testo(c, c.getString(R.string.versione_app, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)))
        b.addView(Mattoni.bottone(c, c.getString(R.string.info_licenze), pieno = false) { Tema.apri(a, Intent(a, LicenzeActivity::class.java)) })
        dentro.addView(b)
    }
}

/**
 * Il Modo tecnico in Sicurezza, solo da leggere: lo accende chi sviluppa l'app (dal computer o da Jarvis), e le
 * diagnosi restano nel telefono.
 */
class PannelloTecnico(a: BaseConfigura) : Pannello(a) {
    private lateinit var riga: Mattoni.Riga

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        val b = Mattoni.scheda(c)
        b.addView(Mattoni.sezione(c, "MODO TECNICO"))
        riga = Mattoni.Riga(c, "Modo tecnico", "", null, null)
        b.addView(riga.vista)
        b.addView(Mattoni.nota(c, c.getString(R.string.spiega_tecnico)))
        dentro.addView(b)
        aggiorna()
    }

    override fun aggiorna() {
        val acceso = Debug.acceso(a)
        riga.aggiorna(if (acceso) StatoPasso.FATTO else StatoPasso.SALTATO,
            spiega = a.getString(if (acceso) R.string.tecnico_acceso else R.string.tecnico_spento))
    }
}

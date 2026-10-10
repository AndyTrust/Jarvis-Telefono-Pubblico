package com.jarvis.telefono.collegamento

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jarvis.telefono.R
import com.jarvis.telefono.vps.ModuloVpsUi
import com.jarvis.telefono.vps.ModuloVpsUi.col
import com.jarvis.telefono.vps.ModuloVpsUi.dp

/**
 * La sezione «Collegamento Jarvis» delle Impostazioni (0.6.0): UN interruttore per lavori, cervello, notifiche,
 * webapp e memoria; lo stato in parole; i pulsanti; i disaccordi della memoria da decidere (decide Boss);
 * l'avviso e il pulsante per togliere l'app Jarvis vecchia. Viste in codice, palette di base (jarvis_*).
 */
object CollegamentoUi {

    fun aggiungiSezione(activity: Activity, vicina: View) {
        var p: View? = vicina
        while (p != null && p.parent !is ScrollView) p = p.parent as? View
        val pagina = p as? LinearLayout ?: return
        val c = activity
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14))
            background = ModuloVpsUi.scheda(c)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, 12) }
        }
        box.addView(TextView(c).apply { text = "COLLEGAMENTO JARVIS"; setTextAppearance(R.style.JarvisSectionTitle) })
        box.addView(TextView(c).apply {
            text = "JBoss parla con Jarvis (VPS, Mac e Windows) su un solo canale: lavori lunghi, cervello per le frasi " +
                "difficili, report e avvisi in una sola coda, la webapp, la memoria condivisa. CRM di lavoro e Patrimonio " +
                "si seguono in sola lettura."
            setTextColor(col(c, R.color.jarvis_testo_tenue)); textSize = 13f
        })
        val stato = TextView(c).apply { setTextColor(col(c, R.color.jarvis_testo)); textSize = 13f; setPadding(0, dp(c, 8), 0, dp(c, 4)) }
        val conflitti = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val vecchia = MaterialButton(c).apply {
            text = "Disinstalla l'app Jarvis vecchia"; isAllCaps = false
            setOnClickListener { CollegamentoJarvis.disinstallaAppVecchia(c) }
        }

        fun disegnaConflitti() {
            conflitti.removeAllViews()
            val e = CollegamentoJarvis.ultimaMemoria(c) ?: return
            if (e.conflitti.isEmpty()) return
            conflitti.addView(TextView(c).apply {
                text = "Da decidere (il telefono e Jarvis non sono d'accordo):"
                setTextColor(col(c, R.color.jarvis_testo)); textSize = 13f; setPadding(0, dp(c, 8), 0, 0)
            })
            for (k in e.conflitti) {
                conflitti.addView(TextView(c).apply {
                    text = "${k.chiave}: telefono «${k.telefono}», Jarvis «${k.jarvis}»"
                    setTextColor(col(c, R.color.jarvis_testo_tenue)); textSize = 13f
                })
                val riga = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                fun scegli(telefono: Boolean) {
                    Thread {
                        val ok = CollegamentoJarvis.decidi(c, k, telefono)
                        c.runOnUiThread {
                            android.widget.Toast.makeText(c, if (ok) "Decisione salvata" else "Non salvata: controlla la rete", android.widget.Toast.LENGTH_SHORT).show()
                            disegnaConflitti()
                        }
                    }.start()
                }
                riga.addView(MaterialButton(c, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "Tieni il telefono"; isAllCaps = false; setOnClickListener { scegli(true) }
                })
                riga.addView(MaterialButton(c, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "Tieni Jarvis"; isAllCaps = false; setOnClickListener { scegli(false) }
                })
                conflitti.addView(riga)
            }
        }

        fun aggiorna() {
            stato.text = CollegamentoJarvis.statoTesto(c)
            vecchia.visibility = if (CollegamentoJarvis.appVecchiaInstallata(c)) View.VISIBLE else View.GONE
            disegnaConflitti()
        }

        box.addView(SwitchMaterial(c).apply {
            text = "Collegamento Jarvis acceso"
            isChecked = CollegamentoJarvis.acceso(c)
            setOnCheckedChangeListener { _, si -> CollegamentoJarvis.accendi(c, si); aggiorna() }
        })
        box.addView(stato)
        fun bottone(t: String, f: () -> Unit) = MaterialButton(c).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
        box.addView(bottone("Apri la webapp Jarvis") { CollegamentoJarvis.apriWebapp(c) })
        box.addView(bottone("Lavori sulla VPS") { ModuloVpsUi.apri(c) })
        box.addView(bottone("Allinea la memoria adesso") {
            Thread {
                val e = CollegamentoJarvis.allineaMemoria(c)
                c.runOnUiThread {
                    android.widget.Toast.makeText(c, if (e == null) "Non riuscito: collegamento spento o niente rete" else "Memoria allineata: ${e.voci.size} regole, ${e.conflitti.size} da decidere", android.widget.Toast.LENGTH_SHORT).show()
                    aggiorna()
                }
            }.start()
        })
        // integra-jarvis: «Cerca aggiornamenti» dell'app 1.2.4, per JBoss.
        val esitoAgg = TextView(c).apply {
            setTextColor(col(c, R.color.jarvis_testo_tenue)); textSize = 13f
            // 09/10: la versione compilata nell'app, non il nome dell'APK installato (un APK di prova portava un nome vecchio).
            text = "Versione " + AggiornamentiJBoss.versioneAttuale() +
                (AggiornamentiJBoss.ultimoEsito(c)?.let { ". $it" } ?: "")
        }
        box.addView(bottone("Cerca aggiornamenti di JBoss") {
            esitoAgg.text = "Controllo…"
            Thread { val e = AggiornamentiJBoss.controllaOra(c); c.runOnUiThread { esitoAgg.text = e } }.start()
        })
        box.addView(esitoAgg)
        box.addView(conflitti)
        box.addView(vecchia)
        aggiorna()
        pagina.addView(box, (pagina.childCount - 1).coerceAtLeast(0))
    }
}

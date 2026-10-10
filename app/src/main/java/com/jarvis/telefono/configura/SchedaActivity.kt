package com.jarvis.telefono.configura

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import com.jarvis.telefono.configura.IndiceImpostazioni.Gruppo

/**
 * Una pagina delle Impostazioni, 0.6.1 (layout approvato da Boss il 08/10): uno dei 7 gruppi dell'indice, ognuno con
 * i suoi pannelli. Ogni impostazione sta in un posto solo:
 * - Voce: ascolto, parola, fine frase, impronta, volume, posta, WhatsApp, trascrittore ([PannelloVoce]), i modelli
 *   e i permessi;
 * - Postino e mail: le caselle e le app Google;
 * - Collegamento Jarvis: UN interruttore (sezione del collegamento), indirizzo, token e QR della VPS;
 * - Account e accessi: cervello, accessi dei siti, account Google (solo informazione);
 * - Sicurezza: blocco schermo, segreti, copia cifrata, registro, modo tecnico;
 * - Aspetto: tema e animazioni; Informazioni: versione e licenze.
 */
class SchedaActivity : BaseConfigura() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = Gruppo.da(intent.getStringExtra(EXTRA)) ?: Gruppo.SICUREZZA
        titoloTesta.text = g.titolo
        when (g) {
            Gruppo.VOCE -> {
                mettiPannello(PannelloVoce(this))
                mettiPannello(PannelloModelli(this))
                mettiPannello(PannelloPermessi(this, conAvatar = false))
            }
            Gruppo.POSTA -> {
                mettiPannello(PannelloPosta(this))
                mettiPannello(PannelloGoogle(this, PannelloGoogle.Parte.APP))
            }
            Gruppo.COLLEGAMENTO -> {
                // La sezione del Collegamento Jarvis (in codice, del ramo jboss-unica) si aggancia a una vista della pagina.
                val ancora = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 1) }
                pagina.addView(ancora)
                com.jarvis.telefono.collegamento.CollegamentoUi.aggiungiSezione(this, ancora)
                mettiPannello(PannelloVps(this))
            }
            Gruppo.ACCOUNT -> {
                mettiPannello(PannelloCervello(this, conQr = false))
                mettiPannello(PannelloSicurezza(this, PannelloSicurezza.Parte.ACCESSI))
                mettiPannello(PannelloGoogle(this, PannelloGoogle.Parte.ACCOUNT))
            }
            Gruppo.SICUREZZA -> {
                mettiPannello(PannelloSicurezza(this, PannelloSicurezza.Parte.SICUREZZA))
                mettiPannello(PannelloTecnico(this))
            }
            Gruppo.ASPETTO -> mettiPannello(PannelloAspetto(this))
            Gruppo.INFO -> mettiPannello(PannelloInfo(this))
        }
    }

    companion object {
        const val EXTRA = "pagina"
        val VOCE = Gruppo.VOCE.chiave
        val POSTA = Gruppo.POSTA.chiave
        val COLLEGAMENTO = Gruppo.COLLEGAMENTO.chiave
        val ACCOUNT = Gruppo.ACCOUNT.chiave
        val SICUREZZA = Gruppo.SICUREZZA.chiave
        val ASPETTO = Gruppo.ASPETTO.chiave
        val INFO = Gruppo.INFO.chiave

        fun intento(c: Context, quale: String): Intent = Intent(c, SchedaActivity::class.java).putExtra(EXTRA, quale)
    }
}

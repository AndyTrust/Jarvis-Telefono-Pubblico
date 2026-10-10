package com.jarvis.telefono.configura

import android.content.Intent
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.jarvis.telefono.JarvisService
import com.jarvis.telefono.Permessi
import com.jarvis.telefono.Prefs
import com.jarvis.telefono.nucleo.Cronologia

/**
 * Passo 5, la prova guidata: «Hey Boss, che ore sono» (● ti ascolto → ✓ risposta), «apri Spotify», una bozza
 * WhatsApp a sé stessi da annullare. Le righe diventano verdi leggendo la cronologia, senza toccare la voce.
 */
class PannelloProva(a: BaseConfigura) : Pannello(a) {
    private val dal = StatoConfigurazione.inizioProva(a)
    private val righe = HashMap<ProvaGuidata.Prova, Mattoni.Riga>()
    private lateinit var stato: Mattoni.Riga
    private var smetti: (() -> Unit)? = null
    private var smettiStato: (() -> Unit)? = null
    private var segnata = false

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        dentro.addView(Mattoni.testo(c, TestiConfigura.PROVA))
        val b0 = Mattoni.scheda(c)
        stato = Mattoni.Riga(c, "JBoss in ascolto", "", "Accendi") { accendi() }
        b0.addView(stato.vista)
        dentro.addView(b0)
        val b = Mattoni.scheda(c)
        b.addView(Mattoni.sezione(c, "DI' A JBOSS"))
        ProvaGuidata.Prova.values().forEach { p ->
            val r = Mattoni.Riga(c, p.frase, p.spiega, null, null)
            righe[p] = r
            b.addView(r.vista)
        }
        b.addView(Mattoni.nota(c, "Nessun messaggio parte senza il tuo «Invia». Se vai nella Home e torni qui (entro un'ora), le frasi già dette contano.", sopra = 10))
        dentro.addView(b)
        smetti = Cronologia.di(c).osserva { a.runOnUiThread { aggiorna() } }
        // 2026-10-10: Pausa o Spegni dalla notifica cambiano la riga subito (prima restava «JBoss ti sente»).
        smettiStato = com.jarvis.telefono.voce.StatoJarvis.osserva { a.runOnUiThread { aggiorna() } }
        aggiorna()
    }

    /**
     * Lo stesso gesto dell'interruttore Voce (PannelloVoce.accendiJBoss): accende il servizio E rimette la voce in
     * «acceso» se era in pausa o spenta dalla notifica, e chiede il microfono se manca. Prima qui si avviava solo il
     * servizio: con la voce in pausa la riga diventava verde ma JBoss non ascoltava.
     */
    private fun accendi() {
        PannelloVoce.accendiJBoss(a, true) { aggiorna() }
        a.dopo(800) { aggiorna() }
    }

    override fun aggiorna() {
        val acceso = ProvaGuidata.ascoltoAcceso(Prefs.isAttivo(a), Permessi.microfono(a), Prefs.getModoVoce(a))
        stato.aggiorna(if (acceso) StatoPasso.FATTO else StatoPasso.DA_FARE, if (acceso) "Acceso" else "Accendi", !acceso,
            if (acceso) "JBoss ti sente: parla pure" else "spento: tocca Accendi")
        val voci = runCatching { Cronologia.di(a).ultime(40) }.getOrDefault(emptyList())
        val fasi = ProvaGuidata.fasi(voci, dal)
        fasi.forEach { (p, f) ->
            val r = righe[p] ?: return@forEach
            when (f) {
                ProvaGuidata.Fase.FATTA -> r.aggiorna(StatoPasso.FATTO, spiega = "fatto")
                ProvaGuidata.Fase.ASCOLTATA -> r.aggiorna(StatoPasso.DA_FARE, spiega = "● ti ascolto… aspetto la risposta")
                ProvaGuidata.Fase.ATTESA -> r.aggiorna(StatoPasso.DA_FARE, spiega = p.spiega)
            }
        }
        if (!segnata && ProvaGuidata.tutteFatte(fasi)) { segnata = true; StatoConfigurazione.segnaProvaFatta(a) }
    }

    override fun chiudi() { smetti?.invoke(); smetti = null; smettiStato?.invoke(); smettiStato = null }
}

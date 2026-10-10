package com.jarvis.telefono.configura

import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.Voce
import com.jarvis.telefono.vps.ConfigVps
import com.jarvis.telefono.vps.ModuloVps

/**
 * Impostazioni → VPS: indirizzo e token (dalla cassaforte, il token solo dopo impronta o PIN), stato del collegamento
 * in tempo reale, modulo acceso/spento, «Prova il collegamento», abbinamento con QR o incolla, togli dal telefono.
 */
class PannelloVps(a: BaseConfigura) : Pannello(a) {
    override val conSegreti = true
    private val canale = CanaleAccount(a)
    private lateinit var indirizzo: Mattoni.Riga
    private lateinit var token: TextView
    private lateinit var stato: TextView
    private lateinit var esito: TextView
    private lateinit var esitoAbb: TextView
    private var smetti: (() -> Unit)? = null

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        dentro.addView(Mattoni.nota(c, TestiConfigura.VPS_SPIEGA))
        val box = Mattoni.scheda(c)
        box.addView(Mattoni.sezione(c, "COLLEGAMENTO"))
        indirizzo = Mattoni.Riga(c, "Indirizzo", "", null, null)
        box.addView(indirizzo.vista)
        token = Mattoni.nota(c, "")
        box.addView(token)
        box.addView(Mattoni.bottone(c, "Mostra il token", pieno = false) { mostraToken() })
        stato = Mattoni.testo(c, "", sopra = 12)
        box.addView(stato)
        // 0.6.1: l'interruttore del collegamento è uno solo, nella sezione «Collegamento Jarvis» di questa stessa pagina.
        esito = Mattoni.esito(c)
        box.addView(Mattoni.bottone(c, "Prova il collegamento") { prova() })
        box.addView(esito)
        dentro.addView(box)

        val b2 = Mattoni.scheda(c)
        b2.addView(Mattoni.sezione(c, "ABBINA CON CODICE QR"))
        b2.addView(Mattoni.nota(c, "Apri il codice QR di Jarvis sul computer. Il QR ha l'indirizzo e un codice valido 5 minuti, mai il token."))
        esitoAbb = Mattoni.esito(c)
        val abb = Abbinatore(a, esitoAbb) { aggiorna() }
        b2.addView(Mattoni.fila(c, Mattoni.bottone(c, "Scansiona QR") { abb.scansiona() }, Mattoni.bottone(c, "Incolla", pieno = false) { abb.incolla() }))
        b2.addView(esitoAbb)
        b2.addView(Mattoni.bottone(c, "Togli la VPS da questo telefono", pieno = false) { togli() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = Mattoni.dp(c, 16)
        })
        dentro.addView(b2)
        smetti = ModuloVps.ascoltaCollegamento { scriviStato() }
        aggiorna()
    }

    override fun aggiorna() {
        val d = ConfigVps.dati(a)
        if (d.completa) indirizzo.aggiorna(StatoPasso.FATTO, spiega = d.url.substringBefore('?'))
        else indirizzo.aggiorna(StatoPasso.DA_FARE, spiega = "non abbinata")
        token.text = if (d.token.isNotBlank()) "Token: ${Voce.mascherato(d.token)} (nella cassaforte)" else "Token: nessuno"
        scriviStato()
    }

    private fun scriviStato() {
        stato.text = "Stato: " + ModuloVps.statoTesto(a)
    }

    private fun prova() {
        canale.motivoNo()?.let { Mattoni.mostraEsito(esito, false, it); return }
        Mattoni.mostraEsito(esito, null, "Mi collego alla VPS…")
        canale.lista { l ->
            scriviStato()
            if (l == null) Mattoni.mostraEsito(esito, false, "Nessuna risposta: " + ModuloVps.statoTesto(a) + ".")
            else Mattoni.mostraEsito(esito, true, "Collegata. Sulla VPS: ${l.caselle.size} caselle per il Postino, " +
                "token di Claude Code ${if (l.cervello["CLAUDE_CODE_OAUTH_TOKEN"] == true) "presente" else "assente"}, " +
                "chiave OpenAI ${if (l.cervello["OPENAI_API_KEY"] == true) "presente" else "assente"}.")
        }
    }

    private fun mostraToken() {
        val t = ConfigVps.dati(a).token
        if (t.isBlank()) { a.avviso("Non c'è nessun token."); return }
        a.chiediSblocco("Mostra il token della VPS") { PannelloSicurezza.mostraValore(a, "Token della VPS", t) }
    }

    private fun togli() {
        AlertDialog.Builder(a)
            .setTitle("Togliere la VPS da questo telefono?")
            .setMessage("Indirizzo e token si cancellano dal telefono e il modulo VPS si spegne. Sulla VPS non cambia niente: per rifarlo basta un QR nuovo.")
            .setPositiveButton("Togli") { _, _ ->
                a.chiediSblocco("Conferma") {
                    canale.chiudi()
                    com.jarvis.telefono.collegamento.CollegamentoJarvis.accendi(a, false)
                    ConfigVps.togli(a)
                    RegistroAccessi.di(a).segna("tolta", "VPS")
                    aggiorna()
                    Mattoni.mostraEsito(esitoAbb, true, "Tolta dal telefono.")
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    override fun chiudi() { smetti?.invoke(); smetti = null; canale.chiudi() }
}

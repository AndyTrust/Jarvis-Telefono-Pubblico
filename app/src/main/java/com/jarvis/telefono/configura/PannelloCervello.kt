package com.jarvis.telefono.configura

import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.ChipGroup
import com.jarvis.telefono.R
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.Cervello
import com.jarvis.telefono.cassaforte.ConfiguraCervelloHttp
import com.jarvis.telefono.cassaforte.ProtocolloAccount
import com.jarvis.telefono.cassaforte.ProviderCervello
import com.jarvis.telefono.cassaforte.RispostaAccount
import com.jarvis.telefono.cassaforte.TestiGuida
import com.jarvis.telefono.cassaforte.Voce
import com.jarvis.telefono.ui.Movimento
import com.jarvis.telefono.vps.ConfigVps

/**
 * Passo 3, «Come pensa JBoss?»: la mia VPS (abbinata col QR, Claude Code con il proprio abbonamento), una chiave API
 * (Anthropic o OpenAI, resta nel telefono) o Codex/OpenAI sulla VPS. I token verso la VPS partono solo dopo
 * l'anteprima e l'impronta o il PIN; il riavvio del ponte lo decide Boss, l'app non lo fa.
 */
/**
 * 0.6.1: [conQr] = false nelle Impostazioni (Account e accessi): l'abbinamento con QR sta in un posto solo,
 * Collegamento Jarvis; qui resta un link. La configurazione guidata lo tiene (è un percorso a passi).
 */
class PannelloCervello(a: BaseConfigura, private val conQr: Boolean = true) : Pannello(a) {
    override val conSegreti = true

    private val canale = CanaleAccount(a)
    private val cassaforte get() = Cassaforte.di(a)
    private lateinit var scelta: RadioGroup
    private lateinit var parteVps: LinearLayout
    private lateinit var parteApi: LinearLayout
    private lateinit var parteCodex: LinearLayout
    private lateinit var statoVps: Mattoni.Riga
    private lateinit var statoToken: TextView
    private lateinit var esitoVps: TextView

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        dentro.addView(Mattoni.testo(c, TestiConfigura.CERVELLO_DOMANDA))
        val box = Mattoni.scheda(c)
        scelta = RadioGroup(c)
        val rVps = radio(TestiConfigura.CERVELLO_VPS, R.id.scelta_vps)
        val rApi = radio(TestiConfigura.CERVELLO_API, R.id.scelta_api)
        val rCodex = radio(TestiConfigura.CERVELLO_CODEX, R.id.scelta_codex)
        scelta.addView(rVps); scelta.addView(rApi); scelta.addView(rCodex)
        box.addView(scelta)
        dentro.addView(box)

        parteVps = Mattoni.scheda(c); costruisciVps(parteVps)
        parteApi = Mattoni.scheda(c); costruisciApi(parteApi)
        parteCodex = Mattoni.scheda(c); costruisciCodex(parteCodex)
        dentro.addView(parteVps); dentro.addView(parteApi); dentro.addView(parteCodex)
        dentro.addView(Mattoni.nota(c, "ⓘ " + TestiConfigura.CERVELLO_ABBONAMENTO, sopra = 12))

        scelta.setOnCheckedChangeListener { _, id -> mostra(id, anima = true) }
        val iniziale = when {
            ConfigVps.dati(c).completa -> R.id.scelta_vps
            cassaforte.cervello(ProviderCervello.API_ANTHROPIC) != null || cassaforte.cervello(ProviderCervello.API_OPENAI) != null -> R.id.scelta_api
            cassaforte.cervello(ProviderCervello.CODEX) != null -> R.id.scelta_codex
            else -> R.id.scelta_vps
        }
        scelta.check(iniziale)
        mostra(iniziale, anima = false)
    }

    private fun radio(testo: String, id: Int) = RadioButton(a).apply {
        this.id = id; text = testo; textSize = 15f; minHeight = Mattoni.dp(a, 48)
        setTextColor(Mattoni.col(a, R.color.jarvis_testo))
    }

    private fun mostra(id: Int, anima: Boolean) {
        listOf(R.id.scelta_vps to parteVps, R.id.scelta_api to parteApi, R.id.scelta_codex to parteCodex).forEach { (k, v) ->
            if (k == id) { v.visibility = View.VISIBLE; if (anima) Movimento.compari(v, Mattoni.dp(a, 8).toFloat()) } else v.visibility = View.GONE
        }
        if (id == R.id.scelta_vps) leggiVps()
    }

    // ------------------------------------------------------------ la mia VPS + Claude Code

    private fun costruisciVps(box: LinearLayout) {
        val c = a
        box.addView(Mattoni.sezione(c, "LA MIA VPS"))
        statoVps = Mattoni.Riga(c, "VPS", "non abbinata", null, null)
        box.addView(statoVps.vista)
        esitoVps = Mattoni.esito(c)
        if (conQr) {
            box.addView(Mattoni.nota(c, TestiConfigura.VPS_SPIEGA))
            val abb = Abbinatore(a, esitoVps) { leggiVps() }
            box.addView(Mattoni.fila(c, Mattoni.bottone(c, "Scansiona QR") { abb.scansiona() }, Mattoni.bottone(c, "Incolla", pieno = false) { abb.incolla() }))
        } else {
            box.addView(Mattoni.nota(c, "La VPS si abbina con il codice QR in Impostazioni, Collegamento Jarvis."))
            box.addView(Mattoni.bottone(c, "Collegamento Jarvis →", pieno = false) { com.jarvis.telefono.ui.Tema.apri(a, SchedaActivity.intento(a, SchedaActivity.COLLEGAMENTO)) })
        }
        box.addView(esitoVps)

        box.addView(Mattoni.sezione(c, "CLAUDE CODE SULLA VPS").apply { setPadding(0, Mattoni.dp(c, 16), 0, Mattoni.dp(c, 8)) })
        statoToken = Mattoni.nota(c, "")
        box.addView(statoToken)
        val guida = Mattoni.nota(c, TestiGuida.CERVELLO_CLAUDE_CODE).apply { visibility = View.GONE }
        box.addView(Mattoni.bottone(c, "Come si prende il token", pieno = false) {
            guida.visibility = if (guida.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (guida.visibility == View.VISIBLE) Movimento.compari(guida, Mattoni.dp(c, 6).toFloat())
        })
        box.addView(guida)
        val campo = Mattoni.campo(c, "Token dell'abbonamento Claude", Mattoni.Tipo.PASSWORD, autofill = "password")
        box.addView(campo)
        val esito = Mattoni.esito(c)
        box.addView(Mattoni.fila(c,
            Mattoni.bottone(c, "Prova", pieno = false) { provaForma(ProviderCervello.CLAUDE_CODE_VPS, Mattoni.valore(campo), esito) },
            Mattoni.bottone(c, "Invia alla VPS") { inviaAllaVps(Cervello(ProviderCervello.CLAUDE_CODE_VPS, Mattoni.valore(campo).trim()), esito) { campo.editText?.setText("") } },
        ))
        box.addView(esito)
    }

    private fun leggiVps() {
        val d = ConfigVps.dati(a)
        if (!d.completa) {
            statoVps.aggiorna(StatoPasso.DA_FARE, spiega = "non abbinata: genera il QR sul Mac")
            statoToken.text = "Prima abbina la VPS: poi qui vedi se ha già il token."
            return
        }
        val host = d.url.removePrefix("wss://").substringBefore('/')
        statoVps.aggiorna(StatoPasso.FATTO, spiega = "abbinata a $host")
        statoToken.text = "Chiedo alla VPS se ha già il token…"
        canale.lista { l ->
            statoToken.text = when {
                l == null -> canale.motivoNo() ?: "La VPS non risponde adesso: riprova più tardi."
                l.cervello["CLAUDE_CODE_OAUTH_TOKEN"] == true -> "✓ La tua VPS ha già il token di Claude Code: non serve fare altro."
                else -> "○ La VPS non ha ancora il token di Claude Code: incollalo qui sotto."
            }
        }
    }

    // ------------------------------------------------------------ chiave API

    private fun costruisciApi(box: LinearLayout) {
        val c = a
        box.addView(Mattoni.sezione(c, "CHIAVE API"))
        box.addView(Mattoni.nota(c, TestiGuida.CERVELLO_API))
        val gruppo = ChipGroup(c).apply { isSingleSelection = true; isSelectionRequired = true }
        val anth = Mattoni.chip(c, "Anthropic").apply { id = View.generateViewId() }
        val oai = Mattoni.chip(c, "OpenAI").apply { id = View.generateViewId() }
        gruppo.addView(anth); gruppo.addView(oai)
        gruppo.check(if (cassaforte.cervello(ProviderCervello.API_OPENAI) != null && cassaforte.cervello(ProviderCervello.API_ANTHROPIC) == null) oai.id else anth.id)
        box.addView(gruppo)
        val gia = Mattoni.nota(c, "")
        fun provider() = if (gruppo.checkedChipId == oai.id) ProviderCervello.API_OPENAI else ProviderCervello.API_ANTHROPIC
        fun leggiGia() {
            val v = cassaforte.cervello(provider())
            gia.text = if (v != null) "✓ Nel telefono c'è già una chiave ${if (provider() == ProviderCervello.API_OPENAI) "OpenAI" else "Anthropic"} (${Voce.mascherato(v.token)})." else "○ Nessuna chiave salvata."
        }
        gruppo.setOnCheckedStateChangeListener { _, _ -> leggiGia() }
        box.addView(gia)
        val campo = Mattoni.campo(c, "Chiave API di Anthropic o di OpenAI", Mattoni.Tipo.PASSWORD, autofill = "password")
        box.addView(campo)
        val esito = Mattoni.esito(c)
        box.addView(Mattoni.fila(c,
            Mattoni.bottone(c, "Prova la chiave", pieno = false) { provaVera(Cervello(provider(), Mattoni.valore(campo).trim()), esito) },
            Mattoni.bottone(c, "Salva nel telefono") {
                val cv = Cervello(provider(), Mattoni.valore(campo).trim())
                val f = ConfiguraCervelloHttp().controllaForma(cv.provider, cv.token)
                if (f != null) { Mattoni.mostraEsito(esito, false, "Non salvata: $f."); return@bottone }
                cassaforte.salva(cv)
                RegistroAccessi.di(a).segna("salvata", cv.etichetta)
                campo.editText?.setText("")
                leggiGia()
                Mattoni.mostraEsito(esito, true, "Salvata nella cassaforte del telefono. Resta qui: non va alla VPS.")
            },
        ))
        box.addView(esito)
        leggiGia()
    }

    // ------------------------------------------------------------ Codex

    private fun costruisciCodex(box: LinearLayout) {
        val c = a
        box.addView(Mattoni.sezione(c, "CODEX / OPENAI SULLA VPS"))
        box.addView(Mattoni.nota(c, TestiGuida.CERVELLO_CODEX))
        val campo = Mattoni.campo(c, "Chiave OpenAI (sk-…), facoltativa", Mattoni.Tipo.PASSWORD, autofill = "password")
        box.addView(campo)
        val esito = Mattoni.esito(c)
        box.addView(Mattoni.fila(c,
            Mattoni.bottone(c, "Prova la chiave", pieno = false) { provaVera(Cervello(ProviderCervello.CODEX, Mattoni.valore(campo).trim()), esito) },
            Mattoni.bottone(c, "Invia alla VPS") { inviaAllaVps(Cervello(ProviderCervello.CODEX, Mattoni.valore(campo).trim()), esito) { campo.editText?.setText("") } },
        ))
        box.addView(esito)
    }

    // ------------------------------------------------------------ azioni comuni

    private fun provaForma(p: ProviderCervello, token: String, esito: TextView) {
        val f = ConfiguraCervelloHttp().controllaForma(p, token.trim())
        if (f == null) Mattoni.mostraEsito(esito, true, "La forma è giusta (${token.trim().length} caratteri). La prova vera la fa la VPS quando lo usa.")
        else Mattoni.mostraEsito(esito, false, "Così non va: $f.")
    }

    /** «Prova la chiave»: chiede solo l'elenco dei modelli (non consuma crediti). */
    private fun provaVera(cv: Cervello, esito: TextView) {
        val f = ConfiguraCervelloHttp().controllaForma(cv.provider, cv.token)
        if (f != null) { Mattoni.mostraEsito(esito, false, "Così non va: $f."); return }
        Mattoni.mostraEsito(esito, null, "Provo la chiave (chiedo solo l'elenco dei modelli)…")
        a.inSfondo({ ConfiguraCervelloHttp().prova(cv) }) { r ->
            val e = r.getOrNull()
            Mattoni.mostraEsito(esito, e?.ok == true, e?.messaggio ?: "Prova non riuscita.")
        }
    }

    /** Anteprima senza token → Boss legge → impronta o PIN → il token parte una volta sola (wss). */
    private fun inviaAllaVps(cv: Cervello, esito: TextView, pulisci: () -> Unit) {
        ConfiguraCervelloHttp().controllaForma(cv.provider, cv.token)?.let { Mattoni.mostraEsito(esito, false, "Così non va: $it."); return }
        canale.motivoNo()?.let { Mattoni.mostraEsito(esito, false, it); return }
        Mattoni.mostraEsito(esito, null, "Chiedo alla VPS cosa cambierebbe…")
        val rid = CanaleAccount.nuovoId()
        canale.manda(ProtocolloAccount.anteprimaCervello(rid, cv), rid) { r ->
            when (r) {
                is RispostaAccount.Anteprima -> confermaEInvia(cv, r, esito, pulisci)
                is RispostaAccount.Errore -> Mattoni.mostraEsito(esito, false, r.motivo)
                else -> Mattoni.mostraEsito(esito, false, "La VPS non ha risposto: controlla che sia collegata (pagina VPS).")
            }
        }
    }

    private fun confermaEInvia(cv: Cervello, ant: RispostaAccount.Anteprima, esito: TextView, pulisci: () -> Unit) {
        val testo = (ant.cosa + ant.avvisi.map { "Attenzione: $it" }).joinToString("\n• ", prefix = "• ")
        AlertDialog.Builder(a)
            .setTitle(if (ant.azione == "sostituisce") "Sostituire il token sulla VPS?" else "Mandare il token alla VPS?")
            .setMessage(testo + "\n\nDopo serve applicarlo e riavviare il ponte: lo decidi tu.")
            .setPositiveButton("Conferma") { _, _ ->
                a.chiediSblocco("Conferma l'invio alla VPS") {
                    val rid = CanaleAccount.nuovoId()
                    Mattoni.mostraEsito(esito, null, "Invio…")
                    canale.manda(ProtocolloAccount.salvaCervello(rid, cv, sostituisci = ant.azione == "sostituisce"), rid) { r ->
                        when {
                            r is RispostaAccount.Esito && r.ok -> {
                                cassaforte.salva(cv)
                                RegistroAccessi.di(a).segna("mandato alla VPS", cv.etichetta)
                                pulisci()
                                Mattoni.mostraEsito(esito, true, TestiConfigura.RIAVVIO_DECIDE_BOSS)
                                leggiVps()
                            }
                            r is RispostaAccount.Esito -> Mattoni.mostraEsito(esito, false, r.testo.ifBlank { "La VPS non l'ha scritto." })
                            r is RispostaAccount.Errore -> Mattoni.mostraEsito(esito, false, r.motivo)
                            else -> Mattoni.mostraEsito(esito, false, "Nessuna risposta dalla VPS: non so se l'ha scritto. Guarda la pagina VPS.")
                        }
                    }
                }
            }
            .setNegativeButton("Annulla") { _, _ -> Mattoni.mostraEsito(esito, null, "Annullato: non è partito niente.") }
            .show()
    }

    override fun aggiorna() { if (::scelta.isInitialized && scelta.checkedRadioButtonId == R.id.scelta_vps) leggiVps() }
    override fun chiudi() = canale.chiudi()
}

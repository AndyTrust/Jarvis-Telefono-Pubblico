package com.jarvis.telefono.configura

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.telefono.cassaforte.AccountMail
import com.jarvis.telefono.cassaforte.Cartelle
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.PresetPosta
import com.jarvis.telefono.cassaforte.ProvaImap
import com.jarvis.telefono.cassaforte.Sicurezza
import com.jarvis.telefono.cassaforte.TestiGuida
import com.jarvis.telefono.cassaforte.TipoMail
import com.jarvis.telefono.ui.Movimento

/**
 * Aggiungi o modifica una casella (0.4.0): gestore (preset) → indirizzo → password (o password per app, con la
 * guida breve) → server già compilati → «Prova il collegamento» → «Salva» → «Invia al Postino sulla VPS»
 * (anteprima e conferma con impronta o PIN). FLAG_SECURE: c'è la password.
 */
class CasellaActivity : BaseConfigura() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        titoloTesta.text = if (id == null) "Aggiungi casella" else "Casella"
        mettiPannello(PannelloCasella(this, id))
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_PRESET = "preset"
        fun intento(c: Context, id: String?): Intent = Intent(c, CasellaActivity::class.java).putExtra(EXTRA_ID, id)
        fun conPreset(c: Context, preset: String): Intent = Intent(c, CasellaActivity::class.java).putExtra(EXTRA_PRESET, preset)
    }
}

class PannelloCasella(a: BaseConfigura, private val idEsistente: String?) : Pannello(a) {
    override val conSegreti = true

    private val canale = CanaleAccount(a)
    private val invio = InvioPosta(a, canale)
    private var preset: PresetPosta = PresetPosta.di("generico")!!
    private var sceltoAMano = false
    private var cartelle = Cartelle()
    private var provata = false
    private var salvata: AccountMail? = null

    private lateinit var gruppo: ChipGroup
    private lateinit var notaPreset: TextView
    private lateinit var indirizzo: TextInputLayout
    private lateinit var password: TextInputLayout
    private lateinit var guidaApp: LinearLayout
    private lateinit var server: LinearLayout
    private lateinit var imapHost: TextInputLayout
    private lateinit var imapPorta: TextInputLayout
    private lateinit var smtpHost: TextInputLayout
    private lateinit var smtpPorta: TextInputLayout
    private lateinit var utente: TextInputLayout
    private lateinit var sicurezzaImap: ChipGroup
    private lateinit var esito: TextView
    private lateinit var esitoVps: TextView
    private val idChip = HashMap<Int, PresetPosta>()
    private val idSic = HashMap<Int, Sicurezza>()

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        val esistente = idEsistente?.let { Cassaforte.di(c).leggi(it) as? AccountMail }
        salvata = esistente

        val b1 = Mattoni.scheda(c)
        b1.addView(Mattoni.sezione(c, "1 · GESTORE"))
        gruppo = ChipGroup(c).apply { isSingleSelection = true; isSelectionRequired = true }
        PresetPosta.TUTTI.forEach { p ->
            val ch = Mattoni.chip(c, p.nome).apply { id = View.generateViewId() }
            idChip[ch.id] = p
            gruppo.addView(ch)
        }
        b1.addView(gruppo)
        notaPreset = Mattoni.nota(c, "")
        b1.addView(notaPreset)
        dentro.addView(b1)

        val b2 = Mattoni.scheda(c)
        b2.addView(Mattoni.sezione(c, "2 · INDIRIZZO E PASSWORD"))
        indirizzo = Mattoni.campo(c, "Indirizzo (nome@dominio.it)", Mattoni.Tipo.EMAIL, autofill = View.AUTOFILL_HINT_EMAIL_ADDRESS)
        password = Mattoni.campo(c, "Password", Mattoni.Tipo.PASSWORD, autofill = View.AUTOFILL_HINT_PASSWORD)
        b2.addView(indirizzo); b2.addView(password)
        guidaApp = Mattoni.colonna(c)
        b2.addView(guidaApp)
        dentro.addView(b2)

        server = Mattoni.scheda(c)
        server.addView(Mattoni.sezione(c, "3 · SERVER (GIÀ COMPILATI)"))
        imapHost = Mattoni.campo(c, "Server IMAP (lettura)", Mattoni.Tipo.INDIRIZZO_WEB)
        imapPorta = Mattoni.campo(c, "Porta IMAP", Mattoni.Tipo.NUMERO)
        sicurezzaImap = ChipGroup(c).apply { isSingleSelection = true; isSelectionRequired = true }
        listOf(Sicurezza.SSL to "SSL", Sicurezza.STARTTLS to "STARTTLS", Sicurezza.NESSUNA to "Nessuna (solo prove)").forEach { (s, n) ->
            val ch = Mattoni.chip(c, n).apply { id = View.generateViewId() }
            idSic[ch.id] = s; sicurezzaImap.addView(ch)
        }
        smtpHost = Mattoni.campo(c, "Server SMTP (invio)", Mattoni.Tipo.INDIRIZZO_WEB)
        smtpPorta = Mattoni.campo(c, "Porta SMTP", Mattoni.Tipo.NUMERO)
        utente = Mattoni.campo(c, "Nome utente (vuoto = l'indirizzo)", Mattoni.Tipo.TESTO, autofill = View.AUTOFILL_HINT_USERNAME)
        listOf(imapHost, imapPorta).forEach { server.addView(it) }
        server.addView(sicurezzaImap)
        listOf(smtpHost, smtpPorta, utente).forEach { server.addView(it) }
        dentro.addView(server)

        val b4 = Mattoni.scheda(c)
        b4.addView(Mattoni.sezione(c, "4 · PROVA E SALVA"))
        esito = Mattoni.esito(c)
        b4.addView(Mattoni.fila(c,
            Mattoni.bottone(c, "Prova il collegamento", pieno = false) { prova() },
            Mattoni.bottone(c, "Salva") { salva() },
        ))
        b4.addView(esito)
        b4.addView(Mattoni.bottone(c, "Invia al Postino sulla VPS", pieno = false) { inviaVps() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = Mattoni.dp(c, 8)
        })
        esitoVps = Mattoni.esito(c)
        b4.addView(esitoVps)
        b4.addView(Mattoni.nota(c, "La prova entra nella casella e guarda le cartelle: non legge e non tocca nessuna mail."))
        dentro.addView(b4)

        gruppo.setOnCheckedStateChangeListener { _, ids -> idChip[ids.firstOrNull()]?.let { sceltoAMano = true; applica(it, daUtente = true) } }
        indirizzo.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, co: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, be: Int, co: Int) {}
            override fun afterTextChanged(s: Editable?) {
                indirizzo.error = null
                if (!sceltoAMano) PresetPosta.perIndirizzo(s.toString())?.let { p -> seleziona(p); applica(p, daUtente = false) }
            }
        })

        if (esistente != null) {
            val p = PresetPosta.TUTTI.firstOrNull { it.tipo == esistente.tipo && it.imap?.host == esistente.imap?.host }
                ?: PresetPosta.TUTTI.firstOrNull { it.tipo == esistente.tipo } ?: preset
            sceltoAMano = true
            seleziona(p); applica(p, daUtente = false)
            indirizzo.editText?.setText(esistente.indirizzo)
            esistente.imap?.let { imapHost.editText?.setText(it.host); imapPorta.editText?.setText(it.porta.toString()); selezionaSic(it.sicurezza) }
            esistente.smtp?.let { smtpHost.editText?.setText(it.host); smtpPorta.editText?.setText(it.porta.toString()) }
            utente.editText?.setText(esistente.utente)
            cartelle = esistente.cartelle
            password.helperText = if (esistente.password.isNotEmpty()) "Password già salvata: lascia vuoto per non cambiarla." else null
        } else {
            val iniziale = a.intent.getStringExtra(CasellaActivity.EXTRA_PRESET)?.let { PresetPosta.di(it) } ?: PresetPosta.di("gmail")!!
            sceltoAMano = a.intent.hasExtra(CasellaActivity.EXTRA_PRESET)
            seleziona(iniziale); applica(iniziale, daUtente = false)
        }
    }

    private fun seleziona(p: PresetPosta) {
        idChip.entries.firstOrNull { it.value.chiave == p.chiave }?.let { if (gruppo.checkedChipId != it.key) gruppo.check(it.key) }
    }

    private fun selezionaSic(s: Sicurezza) { idSic.entries.firstOrNull { it.value == s }?.let { sicurezzaImap.check(it.key) } }

    /** Compila i server del gestore e mostra solo i campi che servono. */
    private fun applica(p: PresetPosta, daUtente: Boolean) {
        preset = p
        provata = false
        notaPreset.text = p.nota
        val soloApp = p.tipo == TipoMail.SAMSUNG
        password.visibility = if (soloApp) View.GONE else View.VISIBLE
        server.visibility = if (soloApp) View.GONE else View.VISIBLE
        if (daUtente || imapHost.editText?.text.isNullOrBlank()) {
            imapHost.editText?.setText(p.imap?.host.orEmpty())
            imapPorta.editText?.setText(p.imap?.porta?.toString() ?: "993")
            smtpHost.editText?.setText(p.smtp?.host.orEmpty())
            smtpPorta.editText?.setText(p.smtp?.porta?.toString() ?: "465")
            selezionaSic(p.imap?.sicurezza ?: Sicurezza.SSL)
        }
        password.hint = if (p.passwordPerApp) "Password per app" else "Password"
        guidaApp.removeAllViews()
        if (p.passwordPerApp) {
            val c = a
            val (testo, url) = when (p.tipo) {
                TipoMail.GMAIL -> TestiGuida.GMAIL_PASSWORD_PER_APP to "https://myaccount.google.com/apppasswords"
                TipoMail.ICLOUD -> "Apple vuole una «password per app»: account.apple.com → Accesso e sicurezza → Password specifiche per le app → crea, e copiala qui." to "https://account.apple.com"
                else -> "Questo gestore vuole una password per app (account con verifica in due passaggi)." to null
            }
            guidaApp.addView(Mattoni.nota(c, testo))
            if (url != null) guidaApp.addView(Mattoni.bottone(c, "Apri la pagina della password per app", pieno = false) { a.apri(Intent(Intent.ACTION_VIEW, Uri.parse(url))) })
            if (daUtente) Movimento.compari(guidaApp, Mattoni.dp(c, 6).toFloat())
        }
    }

    /** Dai campi alla casella. null = un campo non va (l'errore è già sotto il campo). */
    private fun leggiCampi(perProva: Boolean): AccountMail? {
        val ind = Mattoni.valore(indirizzo).trim()
        Campi.indirizzo(ind)?.let { indirizzo.error = it; indirizzo.requestFocus(); return null }
        val esistente = salvata
        val pw = Campi.pulisciPasswordPerApp(Mattoni.valore(password)).ifEmpty { esistente?.password.orEmpty() }
        val soloApp = preset.tipo == TipoMail.SAMSUNG
        if (!soloApp) Campi.password(pw, preset.passwordPerApp)?.let { if (perProva || pw.isEmpty()) { password.error = it; password.requestFocus(); return null } }
        password.error = null
        val sic = idSic[sicurezzaImap.checkedChipId] ?: Sicurezza.SSL
        val imap = if (soloApp) null else Campi.server(Mattoni.valore(imapHost), Mattoni.valore(imapPorta), sic).getOrElse { imapHost.error = it.message; return null }
        imapHost.error = null
        val smtpSic = if (Mattoni.valore(smtpPorta).trim() == "587") Sicurezza.STARTTLS else Sicurezza.SSL
        val smtp = if (soloApp || Mattoni.valore(smtpHost).isBlank()) null else Campi.server(Mattoni.valore(smtpHost), Mattoni.valore(smtpPorta), smtpSic).getOrElse { smtpHost.error = it.message; return null }
        smtpHost.error = null
        val id = esistente?.id ?: Campi.idCasella(ind, Cassaforte.di(a).elenco().map { it.id }.toSet())
        return AccountMail(
            id = id, etichetta = preset.nome, indirizzo = ind, utente = Mattoni.valore(utente).trim(), password = pw,
            imap = imap, smtp = smtp, cartelle = cartelle, tipo = preset.tipo,
            suVps = esistente?.suVps == true && esistente.indirizzo == ind,
        )
    }

    private fun prova() {
        val m = leggiCampi(perProva = true) ?: return
        Mattoni.mostraEsito(esito, null, "Mi collego a ${m.imap?.host ?: "…"}…")
        a.inSfondo({ ProvaImap().prova(m) }) { r ->
            val e = r.getOrNull()
            provata = e?.ok == true
            if (e != null && e.ok) {
                cartelle = Cartelle(
                    inviata = e.cartellaSpeciale("\\Sent").orEmpty(),
                    cestino = e.cartellaSpeciale("\\Trash").orEmpty(),
                    bozze = e.cartellaSpeciale("\\Drafts").orEmpty(),
                )
            }
            Mattoni.mostraEsito(esito, e?.ok, (e?.messaggio ?: "Prova non riuscita.") + if (e?.ok == true) " Ora tocca «Salva»." else "")
        }
    }

    private fun salva(): AccountMail? {
        val m = leggiCampi(perProva = false) ?: return null
        Cassaforte.di(a).salva(m)
        RegistroAccessi.di(a).segna(if (salvata == null) "aggiunta" else "modificata", m.indirizzo)
        salvata = m
        password.editText?.setText("")
        password.helperText = if (m.password.isNotEmpty()) "Password salvata nella cassaforte." else null
        Mattoni.mostraEsito(esito, true, "Salvata nel telefono" + if (provata) ", collegamento provato." else ". Non l'hai ancora provata: tocca «Prova il collegamento».")
        return m
    }

    private fun inviaVps() {
        val m = salvata?.takeIf { Mattoni.valore(password).isEmpty() } ?: salva() ?: return
        invio.invia(m, { ok, t -> Mattoni.mostraEsito(esitoVps, ok, t) }) { salvata = it }
    }

    override fun chiudi() = canale.chiudi()
}

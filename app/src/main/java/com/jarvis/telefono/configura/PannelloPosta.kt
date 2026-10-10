package com.jarvis.telefono.configura

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.jarvis.telefono.R
import com.jarvis.telefono.cassaforte.AccountMail
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.PresetPosta
import com.jarvis.telefono.cassaforte.ProvaImap
import com.jarvis.telefono.ui.Tema

/** Passo 4 e Impostazioni → Account mail: le caselle nella cassaforte, aggiungi, prova, manda al Postino, elimina. Google in fondo. */
class PannelloPosta(a: BaseConfigura) : Pannello(a) {
    private val canale = CanaleAccount(a)
    private val invio = InvioPosta(a, canale)
    private lateinit var elenco: LinearLayout
    private lateinit var esito: TextView
    private lateinit var suVps: TextView

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        dentro.addView(Mattoni.testo(c, TestiConfigura.POSTA))
        val box = Mattoni.scheda(c)
        box.addView(Mattoni.bottone(c, "+ Aggiungi casella") { Tema.apri(a, Intent(a, CasellaActivity::class.java)) })
        elenco = Mattoni.colonna(c)
        box.addView(elenco)
        esito = Mattoni.esito(c)
        box.addView(esito)
        box.addView(Mattoni.nota(c, TestiConfigura.PRESET_RIGA, sopra = 10))
        suVps = Mattoni.nota(c, "")
        box.addView(suVps)
        dentro.addView(box)
        // 0.6.1: Google sta subito sotto, nella stessa pagina (PannelloGoogle, parte APP): niente secondo riquadro qui.
        aggiorna()
    }

    override fun aggiorna() {
        val c = a
        elenco.removeAllViews()
        val caselle = runCatching { Cassaforte.di(c).mail() }.getOrDefault(emptyList())
        if (caselle.isEmpty()) elenco.addView(Mattoni.nota(c, "Nessuna casella ancora.", sopra = 10))
        caselle.forEach { elenco.addView(riga(it)) }
        if (canale.motivoNo() == null) {
            canale.lista { l ->
                suVps.text = if (l == null) "" else "Il Postino sulla VPS legge ${l.caselle.size} caselle (${l.caselle.count { it.daApp }} messe da qui)."
                if (l != null) allinea(l.caselle)
            }
        } else suVps.text = ""
    }

    /** 0.5.0: le caselle del Postino sulla VPS entrano nella cassaforte (senza password) e si vedono qui. */
    private fun allinea(caselle: List<com.jarvis.telefono.cassaforte.CasellaVps>) {
        val c = a
        val nuove = runCatching {
            val cf = Cassaforte.di(c)
            com.jarvis.telefono.cassaforte.AllineaCaselle.daSalvare(cf.mail(), caselle).onEach { cf.salva(it) }
        }.getOrDefault(emptyList())
        if (nuove.isEmpty()) return
        RegistroAccessi.di(c).segna("allineate dalla VPS", "${nuove.size} caselle")
        elenco.removeAllViews()
        Cassaforte.di(c).mail().forEach { elenco.addView(riga(it)) }
        Mattoni.mostraEsito(esito, true, "Allineate ${nuove.size} caselle dal Postino della VPS (le password restano sulla VPS).")
    }

    private fun riga(m: AccountMail): View {
        val c = a
        val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Mattoni.dp(c, 56) }
        val spia = Mattoni.spia(c)
        Mattoni.coloraSpia(spia, if (m.suVps) StatoPasso.FATTO else StatoPasso.DA_FARE, anima = false)
        r.addView(spia)
        val testi = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        testi.addView(TextView(c).apply { text = Campi.corto(m.indirizzo, 26); textSize = 15f; setTextColor(Mattoni.col(c, R.color.jarvis_testo)); maxLines = 1 })
        val gestore = PresetPosta.TUTTI.firstOrNull { it.tipo == m.tipo }?.nome ?: m.tipo.chiave
        testi.addView(TextView(c).apply {
            text = gestore + " · " + if (m.suVps && m.password.isEmpty() && m.tipo != com.jarvis.telefono.cassaforte.TipoMail.SAMSUNG) "la legge il Postino · password sulla VPS" else if (m.suVps) "la legge il Postino" else if (m.password.isEmpty()) "senza password" else "solo nel telefono"
            textSize = 13f; setTextColor(Mattoni.col(c, R.color.jarvis_testo_tenue)); maxLines = 1
        })
        r.addView(testi, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val menu = ImageButton(c).apply {
            setImageResource(R.drawable.ic_altro)
            setBackgroundResource(R.drawable.bg_icona)
            contentDescription = "Azioni per ${m.indirizzo}"
            layoutParams = LinearLayout.LayoutParams(Mattoni.dp(c, 48), Mattoni.dp(c, 48))
            setOnClickListener { v -> menu(v, m) }
        }
        r.addView(menu)
        r.contentDescription = "${m.indirizzo}, $gestore, " + if (m.suVps) "la legge il Postino" else "solo nel telefono"
        r.setOnClickListener { Tema.apri(a, CasellaActivity.intento(a, m.id)) }
        r.setBackgroundResource(R.drawable.bg_riga)
        return r
    }

    private fun menu(v: View, m: AccountMail) {
        val p = PopupMenu(a, v)
        p.menu.add(0, 1, 0, "Modifica")
        p.menu.add(0, 2, 1, "Prova il collegamento")
        p.menu.add(0, 3, 2, "Invia al Postino sulla VPS")
        p.menu.add(0, 4, 3, "Elimina")
        p.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> Tema.apri(a, CasellaActivity.intento(a, m.id))
                2 -> prova(m)
                3 -> invio.invia(m, { ok, t -> Mattoni.mostraEsito(esito, ok, t) }) { aggiorna() }
                4 -> elimina(m)
            }
            true
        }
        p.show()
    }

    private fun prova(m: AccountMail) {
        Mattoni.mostraEsito(esito, null, "Provo ${m.indirizzo}…")
        a.inSfondo({ ProvaImap().prova(m) }) { r ->
            val e = r.getOrNull()
            Mattoni.mostraEsito(esito, e?.ok, e?.messaggio ?: "Prova non riuscita.")
        }
    }

    private fun elimina(m: AccountMail) {
        val b = AlertDialog.Builder(a)
            .setTitle("Eliminare ${m.indirizzo}?")
            .setMessage(if (m.suVps) "La tolgo dal telefono. Vuoi toglierla anche dal Postino sulla VPS (password compresa)?" else "La tolgo dal telefono, password compresa.")
            .setNegativeButton("Annulla", null)
        fun via(anche: Boolean) = a.chiediSblocco("Conferma l'eliminazione") {
            val togliTel = {
                Cassaforte.di(a).elimina(m.id)
                RegistroAccessi.di(a).segna("eliminata", m.indirizzo)
                aggiorna()
            }
            if (anche) invio.togliDallaVps(m) { ok, t -> Mattoni.mostraEsito(esito, ok, t); if (ok) togliTel() }
            else { togliTel(); Mattoni.mostraEsito(esito, true, "Eliminata dal telefono.") }
        }
        if (m.suVps) {
            b.setPositiveButton("Anche dalla VPS") { _, _ -> via(true) }
            b.setNeutralButton("Solo dal telefono") { _, _ -> via(false) }
        } else b.setPositiveButton("Elimina") { _, _ -> via(false) }
        b.show()
    }

    override fun chiudi() = canale.chiudi()
}

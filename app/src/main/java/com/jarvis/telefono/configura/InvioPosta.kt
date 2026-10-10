package com.jarvis.telefono.configura

import androidx.appcompat.app.AlertDialog
import com.jarvis.telefono.cassaforte.AccountMail
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.ProtocolloAccount
import com.jarvis.telefono.cassaforte.RispostaAccount
import com.jarvis.telefono.cassaforte.TipoMail

/**
 * «Invia al Postino sulla VPS» e «togli dalla VPS» per una casella (elenco e schermata della casella).
 * Giro: anteprima SENZA password → finestra con cosa cambia → «Conferma» → impronta o PIN → la password parte una volta.
 */
class InvioPosta(private val a: BaseConfigura, private val canale: CanaleAccount) {

    fun invia(m: AccountMail, esito: (Boolean?, String) -> Unit, fatto: (AccountMail) -> Unit) {
        if (m.tipo == TipoMail.SAMSUNG) { esito(false, "Samsung Email è l'app del telefono: alla VPS va la casella vera (Gmail, Hostinger…)."); return }
        if (m.password.isEmpty()) { esito(false, "Manca la password: aprila e aggiungila prima di mandarla."); return }
        canale.motivoNo()?.let { esito(false, it); return }
        esito(null, "Chiedo alla VPS cosa cambierebbe (la password non parte ancora)…")
        val rid = CanaleAccount.nuovoId()
        canale.manda(ProtocolloAccount.anteprimaCasella(rid, m), rid) { r ->
            when (r) {
                is RispostaAccount.Anteprima -> conferma(m, r, esito, fatto)
                is RispostaAccount.Errore -> esito(false, r.motivo)
                else -> esito(false, "La VPS non ha risposto: controlla che sia collegata (pagina VPS).")
            }
        }
    }

    private fun conferma(m: AccountMail, ant: RispostaAccount.Anteprima, esito: (Boolean?, String) -> Unit, fatto: (AccountMail) -> Unit) {
        val righe = ant.cosa.map { "• $it" } + ant.avvisi.map { "• Attenzione: $it" }
        AlertDialog.Builder(a)
            .setTitle(if (ant.azione == "sostituisce") "Aggiornare la casella sul Postino?" else "Dare la casella al Postino?")
            .setMessage(righe.joinToString("\n") + "\n\nLa password parte una volta, cifrata, dopo l'impronta o il PIN.")
            .setPositiveButton("Conferma") { _, _ ->
                a.chiediSblocco("Conferma l'invio al Postino") {
                    esito(null, "Invio…")
                    val rid = CanaleAccount.nuovoId()
                    canale.manda(ProtocolloAccount.salvaCasella(rid, m, sostituisci = ant.azione == "sostituisce"), rid) { r ->
                        when {
                            r is RispostaAccount.Esito && r.ok -> {
                                val nuova = m.copy(suVps = true)
                                Cassaforte.di(a).salva(nuova)
                                RegistroAccessi.di(a).segna("mandata al Postino", m.indirizzo)
                                esito(true, (listOf(r.testo.ifBlank { "Fatto: il Postino la legge dal prossimo giro." }) + r.avvisi).joinToString("\n"))
                                fatto(nuova)
                            }
                            r is RispostaAccount.Esito -> esito(false, r.testo.ifBlank { "La VPS non l'ha scritta." })
                            r is RispostaAccount.Errore -> esito(false, r.motivo)
                            else -> esito(false, "Nessuna risposta dalla VPS: non so se l'ha scritta. Guarda la pagina VPS.")
                        }
                    }
                }
            }
            .setNegativeButton("Annulla") { _, _ -> esito(null, "Annullato: non è partito niente.") }
            .show()
    }

    /** Toglie la casella dalla VPS (password e casella). Solo quelle mandate dall'app. */
    fun togliDallaVps(m: AccountMail, poi: (Boolean, String) -> Unit) {
        canale.motivoNo()?.let { poi(false, it); return }
        val rid = CanaleAccount.nuovoId()
        canale.manda(ProtocolloAccount.eliminaCasella(rid, ProtocolloAccount.idVps(m)), rid) { r ->
            when {
                r is RispostaAccount.Esito && r.ok -> { RegistroAccessi.di(a).segna("tolta dal Postino", m.indirizzo); poi(true, "Tolta anche dalla VPS.") }
                r is RispostaAccount.Esito -> poi(false, r.testo)
                r is RispostaAccount.Errore -> poi(false, r.motivo)
                else -> poi(false, "La VPS non ha risposto: sulla VPS la casella potrebbe esserci ancora.")
            }
        }
    }
}

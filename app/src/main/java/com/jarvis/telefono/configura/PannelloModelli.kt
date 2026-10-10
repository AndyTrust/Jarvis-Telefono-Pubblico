package com.jarvis.telefono.configura

import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.jarvis.telefono.ImpostazioniActivity
import com.jarvis.telefono.Inventario
import com.jarvis.telefono.voce.EsitoImport
import com.jarvis.telefono.voce.Modello
import com.jarvis.telefono.voce.StatoJarvis

/**
 * Passo 2: i modelli della voce. Usa le API già in main (0.3.2, ramo modelli-fix): lo scarico di
 * ImpostazioniActivity (un filo solo, stessa barra in StatoJarvis), «Importa da cartella» con
 * Inventario.importaModelli (file portati con scripts/importa-modelli.sh). Nessuna logica nuova di download qui.
 */
class PannelloModelli(a: BaseConfigura) : Pannello(a) {
    private lateinit var whisper: Mattoni.Riga
    private lateinit var impronta: Mattoni.Riga
    private lateinit var barra: ProgressBar
    private lateinit var percento: TextView
    private lateinit var esito: TextView
    private var smetti: (() -> Unit)? = null
    private var messaggio: String? = null

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        dentro.addView(Mattoni.testo(c, TestiConfigura.MODELLI))
        val box = Mattoni.scheda(c)
        box.addView(Mattoni.sezione(c, "MODELLI VOCALI"))
        whisper = Mattoni.Riga(c, "Riconoscimento della voce", "circa 375 MB", null, null)
        impronta = Mattoni.Riga(c, "Impronta della tua voce", "circa 40 MB", null, null)
        box.addView(whisper.vista); box.addView(impronta.vista)
        val riga = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        barra = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; contentDescription = "Avanzamento dello scarico" }
        percento = TextView(c).apply { setTextColor(Mattoni.col(c, com.jarvis.telefono.R.color.jarvis_testo_tenue)); textSize = 13f; minWidth = Mattoni.dp(c, 44) }
        riga.addView(barra, LinearLayout.LayoutParams(0, Mattoni.dp(c, 24), 1f))
        riga.addView(percento, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = Mattoni.dp(c, 8) })
        box.addView(riga)
        val scarica = Mattoni.bottone(c, "Scarica") { premiScarica() }
        val importa = Mattoni.bottone(c, "Prendi i file già copiati", pieno = false) { premiImporta() }
        box.addView(Mattoni.fila(c, scarica, importa))
        box.addView(Mattoni.nota(c, "Senza rete: chi ti aiuta dal computer copia i file nel telefono, poi tocca «Prendi i file già copiati»."))
        esito = Mattoni.esito(c)
        box.addView(esito)
        dentro.addView(box)
        smetti = StatoJarvis.osserva { disegna() }
        disegna()
    }

    private fun premiScarica() {
        if (ImpostazioniActivity.scaricando()) { ImpostazioniActivity.annullaScarico(); messaggio = "Annullato: tocca di nuovo per riprendere."; disegna(); return }
        val motivo = ImpostazioniActivity.scaricaDaFuori(a)
        messaggio = motivo
        disegna()
    }

    private fun premiImporta() {
        messaggio = "Importo i modelli…"
        disegna()
        a.inSfondo({ Inventario.importaModelli(a).also { runCatching { Inventario.rileggi(a) } } }) { r ->
            messaggio = when (val e = r.getOrNull()) {
                EsitoImport.Niente -> "Nel telefono non ci sono ancora i file della voce: vanno copiati prima dal computer."
                is EsitoImport.Fatto -> "Modelli importati: ${e.copiati.size} file copiati."
                is EsitoImport.Errore -> "Importazione non riuscita: ${e.motivo}"
                null -> "Importazione non riuscita."
            }
            disegna()
        }
    }

    private fun disegna() {
        val s = StatoJarvis.corrente
        val m = runCatching { Inventario.modelli(a) }.getOrNull()
        val w = m?.presente(Modello.WHISPER_SMALL) == true
        val e = m?.presente(Modello.ERES2NET) == true
        whisper.aggiorna(if (w) StatoPasso.FATTO else StatoPasso.DA_FARE)
        impronta.aggiorna(if (e) StatoPasso.FATTO else StatoPasso.DA_FARE)
        val p = s.scaricoPercento
        if (p != null) {
            barra.progress = p
            percento.text = "$p%"
        } else {
            barra.progress = if (w && e) 100 else 0
            percento.text = if (w && e) "100%" else ""
        }
        val testo = when {
            p != null -> "Scarico in corso: puoi andare avanti, continua da solo."
            messaggio != null -> messaggio
            ImpostazioniActivity.esitoModelli() != null -> ImpostazioniActivity.esitoModelli()
            w && e -> "Tutto pronto: JBoss capisce la voce anche senza internet."
            else -> null
        }
        val ok: Boolean? = when {
            w && e -> true
            p != null -> null
            testo != null && (" non " in " $testo" || "Serve" in testo) -> false
            else -> null
        }
        if (testo != null) Mattoni.mostraEsito(esito, ok, testo)
        else esito.visibility = View.GONE
    }

    override fun aggiorna() = disegna()
    override fun chiudi() { smetti?.invoke(); smetti = null }
}

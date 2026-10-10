package com.jarvis.telefono.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import com.jarvis.telefono.DettatoNativo
import com.jarvis.telefono.R
import com.jarvis.telefono.voce.Ascolto
import com.jarvis.telefono.voce.StatoJarvis

/**
 * La barra degli agenti in una schermata (2026-10-10): la stessa per la chat di JBoss, degli agenti e la pagina del
 * Postino. [invia] manda una frase all'agente; [occupato] dice se l'agente sta ancora lavorando alla risposta;
 * [avviso] mostra un messaggio (errore = true per gli errori). La logica della chiamata è in [ChiamataAgente].
 */
class ControlloreBarra(
    private val activity: Activity,
    radice: View,
    private val agente: String,
    private val invia: (testo: String, daVoce: Boolean) -> Unit,
    private val occupato: () -> Boolean,
    private val avviso: (testo: String, errore: Boolean) -> Unit,
) {
    val campo: EditText = radice.findViewById(R.id.campo_frase)
    private val parla: ImageButton = radice.findViewById(R.id.tasto_parla)
    private val chiama: ImageButton = radice.findViewById(R.id.tasto_chiama)
    private val freccia: ImageButton = radice.findViewById(R.id.tasto_invia)
    private val principale = Handler(Looper.getMainLooper())
    private val chiamata = ChiamataAgente()
    private var dettato: DettatoNativo? = null
    private var mandatoMs = 0L

    /** Controlla ogni mezzo secondo se la risposta è arrivata (comando chiuso, voce finita). */
    private val controllaRisposta = object : Runnable {
        override fun run() {
            if (chiamata.stato != ChiamataAgente.Stato.ATTESA_RISPOSTA) return
            val passato = SystemClock.elapsedRealtime() - mandatoMs
            val parla = StatoJarvis.corrente.ascolto == Ascolto.PARLO || StatoJarvis.corrente.ascolto == Ascolto.PENSO
            if (passato > 1200 && !occupato() && !parla) esegui(chiamata.rispostaArrivata())
            else principale.postDelayed(this, 500L)
        }
    }

    init {
        campo.hint = TestiBarra.suggerimento(agente)
        VistaComando.invioDaTastiera(campo) { daCampo() }
        freccia.setOnClickListener { daCampo() }
        parla.setOnClickListener { parla() }
        chiama.setOnClickListener { if (chiamata.aperta) esegui(chiamata.ferma()) else apriChiamata() }
        listOf(parla, chiama, freccia).forEach { Movimento.pressione(it) }
    }

    /** Cambia il testo guida (per esempio la chat di JBoss quando la conversazione passa al Postino). */
    fun suggerimento(testo: String) { if (suggerimentoNormale != null) suggerimentoNormale = testo else campo.hint = testo }

    private fun daCampo() {
        val t = campo.text.toString().trim()
        if (t.isEmpty()) { avviso(TestiBarra.VUOTO, false); return }
        campo.text.clear()
        invia(t, false)
    }

    // ---------------------------------------------------------------- Parla: dettatura nel campo, poi invio

    private fun dettato(): DettatoNativo = dettato ?: DettatoNativo(
        activity,
        parziale = { t -> campo.setText(t); campo.setSelection(campo.text.length) },
    ) { testo -> finito(testo) }.also { dettato = it }

    private fun parla() {
        if (chiamata.aperta) { esegui(chiamata.ferma()); return }
        val d = dettato()
        if (d.fase == DettatoNativo.Fase.REGISTRA) { d.ferma(); return }
        if (d.avvia()) mostraAscolto(true) else avviso(TestiBarra.motivoDettato(d.motivo), true)
    }

    private fun finito(testo: String) {
        mostraAscolto(false)
        if (chiamata.aperta) {
            val guasto = testo.isBlank() && dettato?.motivo.let { it == "microfono_negato" || it == "microfono_guasto" || it == "whisper_assente" }
            esegui(chiamata.dettato(testo, guasto))
            return
        }
        if (testo.isNotBlank()) { campo.text.clear(); invia(testo.trim(), true) }
    }

    // ---------------------------------------------------------------- Chiama: mani libere con questo agente

    private fun apriChiamata() {
        avviso(TestiBarra.chiamataAperta(agente), false)
        mostraChiamata(true)
        esegui(chiamata.avvia())
    }

    private fun esegui(p: ChiamataAgente.Passo) {
        when (p) {
            ChiamataAgente.Passo.Ascolta -> principale.postDelayed({
                if (!chiamata.aperta) return@postDelayed
                val d = dettato()
                if (d.fase != DettatoNativo.Fase.PRONTO) return@postDelayed
                if (d.avvia()) mostraAscolto(true) else {
                    esegui(chiamata.ferma())
                    avviso(TestiBarra.motivoDettato(d.motivo), true)
                }
            }, 350L)
            is ChiamataAgente.Passo.Manda -> {
                campo.text.clear()
                invia(p.testo, true)
                mandatoMs = SystemClock.elapsedRealtime()
                principale.removeCallbacks(controllaRisposta)
                principale.postDelayed(controllaRisposta, 500L)
            }
            is ChiamataAgente.Passo.Chiudi -> {
                principale.removeCallbacks(controllaRisposta)
                dettato?.annulla()
                campo.text.clear()   // niente pezzi di frase rimasti nel campo a chiamata chiusa
                mostraAscolto(false)
                mostraChiamata(false)
                when (p.perche) {
                    "" -> avviso(TestiBarra.chiamataChiusa(agente), false)
                    "guasto" -> avviso(TestiBarra.motivoDettato(dettato?.motivo ?: ""), true)
                    else -> avviso(p.perche, false)
                }
            }
            ChiamataAgente.Passo.Niente -> {}
        }
    }

    private var suggerimentoNormale: CharSequence? = null

    /** Mentre ascolta: microfono rame pieno e «Ti ascolto…» nel campo. */
    private fun mostraAscolto(si: Boolean) {
        parla.setBackgroundResource(if (si) R.drawable.bg_icona_piena else R.drawable.bg_barra_parla)
        parla.imageTintList = android.content.res.ColorStateList.valueOf(
            activity.getColor(if (si) R.color.jarvis_su_accento else R.color.barra_parla_icona))
        parla.contentDescription = activity.getString(if (si) R.string.detta_stop else R.string.barra_parla)
        if (si) {
            if (suggerimentoNormale == null) suggerimentoNormale = campo.hint
            campo.hint = TestiBarra.ASCOLTO
        } else suggerimentoNormale?.let { campo.hint = it; suggerimentoNormale = null }
    }

    private fun mostraChiamata(si: Boolean) {
        chiama.setBackgroundResource(if (si) R.drawable.bg_barra_chiama_attiva else R.drawable.bg_barra_chiama)
        chiama.imageTintList = android.content.res.ColorStateList.valueOf(
            activity.getColor(if (si) R.color.jarvis_su_accento else R.color.barra_chiama_icona))
        chiama.contentDescription = activity.getString(if (si) R.string.barra_chiama_fine else R.string.barra_chiama)
    }

    /** onStop: niente microfono aperto con la pagina nascosta. */
    fun ferma() {
        if (chiamata.aperta) esegui(chiamata.ferma())
        dettato?.annulla()
        mostraAscolto(false)
    }

    fun rilascia() {
        principale.removeCallbacksAndMessages(null)
        dettato?.rilascia(); dettato = null
    }
}

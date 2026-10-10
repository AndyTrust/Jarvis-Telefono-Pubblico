package com.jarvis.telefono.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.jarvis.telefono.MainActivity
import com.jarvis.telefono.PhoneActionExecutor
import com.jarvis.telefono.R
import com.jarvis.telefono.Tinte
import com.jarvis.telefono.nucleo.Comandi
import com.jarvis.telefono.nucleo.RegistroComandi
import com.jarvis.telefono.vps.ModuloVps
import com.jarvis.telefono.vps.ProtocolloVps

/**
 * JBoss 0.7.0 (2026-10-08): la riga del comando sopra il campo (in corso / da confermare / fatto / errore) e il box
 * del sì con Conferma e Annulla (layout vista_comando). Legge [Comandi.registro]; i tocchi fanno quello che fanno
 * il pannello sopra l'app e la notifica. Si usa nella Home e nella chat di un agente.
 */
class VistaComando(
    private val activity: Activity,
    radice: View,
    /** 09/10: nella chat di un agente solo i suoi comandi ([RegistroComandi.diChat]); null = tutti (la Home). */
    private val filtro: ((RegistroComandi.Comando) -> Boolean)? = null,
) {
    private val riga: View = radice.findViewById(R.id.riga_comando)
    private val spia: View = radice.findViewById(R.id.spia_comando)
    private val testo: TextView = radice.findViewById(R.id.testo_comando)
    private val box: MaterialCardView = radice.findViewById(R.id.box_conferma)
    private val titolo: TextView = radice.findViewById(R.id.titolo_conferma)
    private val corpo: TextView = radice.findViewById(R.id.corpo_conferma)
    private val si: MaterialButton = radice.findViewById(R.id.tasto_si_conferma)
    private val no: MaterialButton = radice.findViewById(R.id.tasto_annulla_conferma)

    private val principale = Handler(Looper.getMainLooper())
    private var smetti: (() -> Unit)? = null
    private var conferma: RegistroComandi.Conferma? = null

    /** La riga «in corso» conta i secondi: si ridisegna ogni secondo finché c'è un comando aperto. */
    private val battito = object : Runnable {
        override fun run() {
            disegna(Comandi.registro.istantanea())
            if (Comandi.registro.attivi() > 0) principale.postDelayed(this, 1_000L)
        }
    }

    init {
        si.setOnClickListener { scegli(true) }
        no.setOnClickListener { scegli(false) }
        Movimento.pressione(si); Movimento.pressione(no)
    }

    fun avvia() {
        smetti = Comandi.registro.osserva { i -> principale.post { disegna(i); riparti() } }
        disegna(Comandi.registro.istantanea())
        riparti()
    }

    fun ferma() {
        smetti?.invoke(); smetti = null
        principale.removeCallbacks(battito)
    }

    private fun riparti() {
        principale.removeCallbacks(battito)
        if (Comandi.registro.attivi() > 0) principale.postDelayed(battito, 1_000L)
    }

    private fun disegna(tutti: RegistroComandi.Istantanea) {
        if (activity.isFinishing) return
        val i = filtro?.let { Comandi.registro.istantanea(it) } ?: tutti
        // Una bozza chiusa altrove (scaduta, «annulla» a voce) non resta nel box.
        val k = i.conferma?.takeUnless { it.tipo == RegistroComandi.TipoConferma.BOZZA && !PhoneActionExecutor.inSospeso() }
        conferma = k
        val r = RegistroComandi.riga(i.copy(conferma = k))
        if (r == null) riga.visibility = View.GONE else {
            riga.visibility = View.VISIBLE
            testo.text = r
            val stato = if (k != null) RegistroComandi.Stato.DA_CONFERMARE else i.comando?.stato
            MainActivity.colora(spia, tintaDi(stato))
        }
        if (k == null) { box.visibility = View.GONE; return }
        titolo.text = k.titolo
        corpo.text = k.corpo
        corpo.visibility = if (k.corpo.isBlank()) View.GONE else View.VISIBLE
        si.text = k.etichettaSi
        box.visibility = View.VISIBLE
    }

    private fun scegli(conferma: Boolean) {
        val k = this.conferma ?: return
        val ok = when (k.tipo) {
            RegistroComandi.TipoConferma.BOZZA -> PhoneActionExecutor.confermaDaChat(conferma)
            RegistroComandi.TipoConferma.VPS -> {
                ModuloVps.scegli(activity, k.lavoroId, k.azioneId, if (conferma) ProtocolloVps.INVIA else ProtocolloVps.ANNULLA)
                true
            }
            // 09/10: la cancellazione del Postino parte solo da qui (o dal riquadro della sua pagina), mai a voce.
            RegistroComandi.TipoConferma.POSTA ->
                com.jarvis.telefono.postino.Postino.rispondiConferma(activity, k.chiave, conferma) !is com.jarvis.telefono.postino.PostaCondivisa.Esito.Errore
        }
        Comandi.registro.confermaChiusa(k.chiave)
        if (!ok) Toast.makeText(activity, R.string.conferma_scaduta, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** La tinta del pallino (palette di [Tinte], tradotta per il modo scuro da MainActivity.colora). */
        fun tintaDi(s: RegistroComandi.Stato?): Int = when (s) {
            RegistroComandi.Stato.IN_CORSO, RegistroComandi.Stato.ALLA_VPS -> Tinte.ACCENTO
            RegistroComandi.Stato.DA_CONFERMARE -> Tinte.GIALLO
            RegistroComandi.Stato.FATTO -> Tinte.VERDE
            RegistroComandi.Stato.ERRORE -> Tinte.ROSSO
            RegistroComandi.Stato.ANNULLATO, null -> Tinte.GRIGIO
        }

        /**
         * 0.7.0: il tasto Invio della tastiera manda la frase. Con `textMultiLine` Android aggiunge da solo
         * IME_FLAG_NO_ENTER_ACTION e il tasto diventa «a capo» (provato sull'emulatore: imeOptions=0x40000004, nessun
         * invio). Qui il campo resta su più righe (va a capo da solo, al massimo 4) ma la tastiera mostra Invia.
         */
        fun invioDaTastiera(campo: EditText, invia: () -> Unit) {
            campo.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
            campo.imeOptions = EditorInfo.IME_ACTION_SEND
            campo.setHorizontallyScrolling(false)
            campo.maxLines = 4
            campo.setOnEditorActionListener { _, azione, evento ->
                val invio = azione == EditorInfo.IME_ACTION_SEND || azione == EditorInfo.IME_ACTION_DONE ||
                    (azione == EditorInfo.IME_NULL && evento?.keyCode == KeyEvent.KEYCODE_ENTER)
                if (!invio) return@setOnEditorActionListener false
                // Il tasto fisico manda due eventi (giù e su): si invia una volta sola.
                if (evento == null || evento.action == KeyEvent.ACTION_DOWN) invia()
                true
            }
        }
    }
}

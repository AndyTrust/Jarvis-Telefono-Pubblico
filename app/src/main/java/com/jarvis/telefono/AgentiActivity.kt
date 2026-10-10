package com.jarvis.telefono

import android.animation.Animator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.jarvis.telefono.agenti.Agente
import com.jarvis.telefono.agenti.ArchivioAgenti
import com.jarvis.telefono.agenti.Autonomie
import com.jarvis.telefono.agenti.Avatar
import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.agenti.Competenze
import com.jarvis.telefono.agenti.Situazione
import com.jarvis.telefono.agenti.StatiAgenti
import com.jarvis.telefono.agenti.StatoAgente
import com.jarvis.telefono.ui.InterruttoreAutonomia
import com.jarvis.telefono.ui.Movimento
import com.jarvis.telefono.ui.Tema
import com.jarvis.telefono.voce.StatoJarvis
import com.jarvis.telefono.vps.ModuloVps

/**
 * La schermata Agenti, 0.6.1 (layout approvato da Boss il 08/10): un elenco compatto. In cima JBoss, il capo; poi
 * i 5 agenti con avatar, nome, missione e stato vero. Un tocco apre la chat dell'agente (il capo riporta in Home),
 * la pressione lunga apre la scheda: missione, dove lavora, stato del collegamento e l'interruttore dell'autonomia.
 *
 * Lo stato degli agenti sulla VPS viene da [ModuloVps]: pronto con il Collegamento Jarvis acceso e configurato,
 * «VPS collegata» quando il canale è aperto. Fino alla 0.6.0 non si passava e risultavano sempre «non collegati».
 * L'autonomia ha effetto davvero: la legge ModuloVps a ogni conferma chiesta dalla VPS ([Autonomie.confermaDaSola]).
 */
class AgentiActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AGENTE = "agente"
    }

    private lateinit var lista: LinearLayout
    private val righe = LinkedHashMap<String, View>()
    private val pulsazioni = HashMap<View, Animator>()
    private var agenti: List<Agente> = emptyList()
    private var smettiAgenti: (() -> Unit)? = null
    private var smettiStato: (() -> Unit)? = null
    private var smettiCollegamento: (() -> Unit)? = null
    private var inPrimoPiano = false
    private var daMostrare: String? = null
    private var capo: View? = null
    private var scheda: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agenti)
        lista = findViewById(R.id.lista_schede)
        findViewById<ImageButton>(R.id.tasto_indietro).setOnClickListener { finish() }
        daMostrare = if (savedInstanceState == null) intent.getStringExtra(EXTRA_AGENTE) else null
    }

    override fun onStart() {
        super.onStart()
        val archivio = ArchivioAgenti.di(this)
        smettiAgenti = archivio.osserva { mostra(it, primaVolta = false) }
        archivio.carica { mostra(it, primaVolta = righe.isEmpty()) }
        smettiStato = StatoJarvis.osserva { aggiornaStati() }
        smettiCollegamento = ModuloVps.ascoltaCollegamento { aggiornaStati() }
    }

    override fun onStop() {
        smettiAgenti?.invoke(); smettiAgenti = null
        smettiStato?.invoke(); smettiStato = null
        smettiCollegamento?.invoke(); smettiCollegamento = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        inPrimoPiano = true
        aggiornaStati()
    }

    override fun onPause() {
        inPrimoPiano = false
        pulsazioni.forEach { (al, anim) -> Movimento.ferma(anim, al) }
        pulsazioni.clear()
        super.onPause()
    }

    override fun onDestroy() {
        scheda?.dismiss(); scheda = null
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        Tema.chiudi(this)
    }

    /** Quello che il telefono sa adesso: accessibilità, mani ferme, chi lavora, il Collegamento Jarvis. */
    private fun situazione() = Situazione(
        accessibilita = JarvisAccessibilityService.instance != null,
        maniFerme = Prefs.isManiFerme(this),
        alLavoro = StatoJarvis.corrente.agenteAlLavoro,
        moduloVps = ModuloVps.pronto(this),
        vpsCollegata = ModuloVps.collegato(),
    )

    private fun mostra(nuovi: List<Agente>, primaVolta: Boolean) {
        if (isFinishing || isDestroyed) return
        // Boss, 2026-10-09: nell'app ci sono solo tre avatar, JBoss, Postino e la webapp. Gli altri li comanda JBoss.
        agenti = nuovi.filter { it.id == CatalogoAgenti.POSTINO }
        val inf = LayoutInflater.from(this)
        val dy = resources.getDimension(R.dimen.spazio_2)
        if (capo == null) capo = rigaCapo(inf).also { lista.addView(it, 0); if (primaVolta) Movimento.compari(it, dy) }
        nuovi.forEachIndexed { i, a ->
            val v = righe.getOrPut(a.id) {
                inf.inflate(R.layout.item_agente_riga, lista, false).also { nuova ->
                    Avatar.metti(nuova.findViewById(R.id.avatar), a.id)
                    lista.addView(nuova)
                    if (primaVolta) Movimento.compari(nuova, dy, ritardoMs = 30L * (i + 1))
                }
            }
            lega(v, a)
        }
        aggiornaStati()
        daMostrare?.let { id -> daMostrare = null; nuovi.firstOrNull { it.id == id }?.let { apriScheda(it) } }
    }

    /** JBoss, il capo: il tocco apre la sua chat a tutto schermo (0.7.1), la pressione lunga dice come smista. */
    private fun rigaCapo(inf: LayoutInflater): View = inf.inflate(R.layout.item_agente_riga, lista, false).apply {
        Avatar.metti(findViewById(R.id.avatar), CatalogoAgenti.JARVIS)
        findViewById<TextView>(R.id.nome).text = getString(R.string.nome_capo) + " (" + getString(R.string.capo).lowercase() + ")"
        findViewById<TextView>(R.id.missione).setText(R.string.capo_missione)
        contentDescription = getString(R.string.capo) + ", " + getString(R.string.nome_capo)
        setOnClickListener { Tema.apri(this@AgentiActivity, AgenteChatActivity.intento(this@AgentiActivity, CatalogoAgenti.JARVIS)) }
        setOnLongClickListener { apriSchedaCapo(); true }
        Movimento.pressione(this)
    }

    private fun lega(v: View, a: Agente) {
        v.findViewById<TextView>(R.id.nome).text = a.nome
        v.findViewById<TextView>(R.id.missione).text = a.missione
        v.setOnClickListener {
            Tema.apri(this, if (a.id == CatalogoAgenti.POSTINO) PostinoActivity.intento(this) else AgenteChatActivity.intento(this, a.id))
        }
        v.setOnLongClickListener { apriScheda(agenti.firstOrNull { it.id == a.id } ?: a); true }
        Movimento.pressione(v)
    }

    private fun aggiornaStati() {
        capo?.let { v ->
            val st = StatoJarvis.corrente
            Tema.pallino(v.findViewById(R.id.pallino), Tema.daTinta(this, coloreSpia(st)))
            v.findViewById<TextView>(R.id.stato).text = testoSpia(st)
        }
        if (agenti.isEmpty()) return
        val s = situazione()
        for (a in agenti) {
            val v = righe[a.id] ?: continue
            val st = StatiAgenti.stato(a, s)
            Tema.pallino(v.findViewById(R.id.pallino), Tema.perStato(this, st))
            val testo = StatiAgenti.testo(a, st, s)
            v.findViewById<TextView>(R.id.stato).text = testo
            v.contentDescription = getString(R.string.chat_di, a.nome) + ", " + testo
            val al = v.findViewById<View>(R.id.alone)
            if (st == StatoAgente.LAVORA && inPrimoPiano) {
                if (pulsazioni[al] == null) Movimento.pulsa(al)?.let { pulsazioni[al] = it }
            } else Movimento.ferma(pulsazioni.remove(al), al)
        }
    }

    // ------------------------------------------------------------ la scheda (pressione lunga)

    private fun nuovaScheda(): Pair<BottomSheetDialog, View> {
        scheda?.dismiss()
        val d = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.item_agente_scheda, null, false)
        d.setContentView(v)
        d.setOnDismissListener { if (scheda === d) scheda = null }
        scheda = d
        return d to v
    }

    private fun apriSchedaCapo() {
        val (d, v) = nuovaScheda()
        Avatar.metti(v.findViewById(R.id.avatar), CatalogoAgenti.JARVIS, grande = true)
        v.findViewById<TextView>(R.id.nome).text = getString(R.string.capo) + " · " + getString(R.string.nome_capo)
        v.findViewById<TextView>(R.id.missione).apply { setText(R.string.capo_missione); maxLines = 2 }
        Tema.pallino(v.findViewById(R.id.pallino), Tema.daTinta(this, coloreSpia(StatoJarvis.corrente)))
        v.findViewById<TextView>(R.id.stato).text = testoSpia(StatoJarvis.corrente)
        v.findViewById<TextView>(R.id.dove).text = "Lavora nel telefono"
        v.findViewById<TextView>(R.id.modulo).apply {
            maxLines = 8
            text = Competenze.IN_PAROLE.joinToString("\n") { (id, cosa) -> "→ ${Competenze.nomeCorto(id)}: $cosa" }
        }
        v.findViewById<View>(R.id.etichetta_autonomia).visibility = View.GONE
        v.findViewById<View>(R.id.autonomia).visibility = View.GONE
        v.findViewById<TextView>(R.id.nota_autonomia).setText(R.string.capo_spiegazione)
        d.show()
    }

    private fun apriScheda(a: Agente) {
        val (d, v) = nuovaScheda()
        val s = situazione()
        val st = StatiAgenti.stato(a, s)
        Avatar.metti(v.findViewById(R.id.avatar), a.id, grande = true)
        v.findViewById<TextView>(R.id.nome).text = a.nome
        v.findViewById<TextView>(R.id.missione).apply { text = a.missione; maxLines = 2 }
        Tema.pallino(v.findViewById(R.id.pallino), Tema.perStato(this, st))
        v.findViewById<TextView>(R.id.stato).text = StatiAgenti.testo(a, st, s)
        v.findViewById<TextView>(R.id.dove).text = StatiAgenti.dove(a)
        v.findViewById<TextView>(R.id.modulo).apply {
            val m = StatiAgenti.moduloEsterno(a, s)
            visibility = if (m == null) View.GONE else View.VISIBLE
            text = m.orEmpty()
        }
        v.findViewById<InterruttoreAutonomia>(R.id.autonomia).apply {
            imposta(a.autonomia, a.autonomiaBloccata, animato = false)
            suCambio = { nuova -> ArchivioAgenti.di(this@AgentiActivity).cambia { l -> l.map { if (it.id == a.id) Autonomie.cambia(it, nuova) else it } } }
            suBloccato = { Snackbar.make(v, R.string.autonomia_bloccata, Snackbar.LENGTH_LONG).show() }
        }
        v.findViewById<TextView>(R.id.nota_autonomia).setText(
            if (a.autonomiaBloccata) R.string.autonomia_nota_bloccata else R.string.autonomia_nota_ricercatore,
        )
        d.show()
    }
}

package com.jarvis.telefono.ui

import android.app.Activity
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.jarvis.telefono.R
import com.jarvis.telefono.agenti.Avatar
import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.agenti.Competenze
import com.jarvis.telefono.nucleo.Cronologia
import com.jarvis.telefono.nucleo.FiltroCronologia
import com.jarvis.telefono.quando
import com.jarvis.telefono.secondi

/**
 * La cronologia (layout vista_cronologia), la stessa nella Home e nella chat di un agente.
 * Righe con avatar piccolo, «Jarvis → agente», esito letto dal sistema e orario; un tocco apre il
 * dettaglio. Filtro a chip (Home: Tutto · Jarvis · Postino) e ricerca. Nella chat il filtro è
 * fisso sull'agente ([filtroFisso]) e i chip non si vedono. Le righe nuove compaiono scorrendo.
 *
 * 0.7.1: la chat a tutto schermo usa la stessa vista senza chip e senza ricerca (le viste possono mancare nel
 * layout) e con [tecnico] = false: niente riga di dettaglio con cervello, millisecondi ed esito grezzo.
 */
class VistaCronologia(
    private val activity: Activity,
    radice: View,
    private val filtroFisso: String? = null,
    private val filtri: List<Pair<String, String>> = FiltroCronologia.FILTRI_HOME,
    private val vuoto: String = activity.getString(R.string.cronologia_vuota),
    /** false = solo parole semplici: il tocco su una risposta la apre intera, senza dettagli tecnici. */
    private val tecnico: Boolean = true,
    /**
     * 09/10 (accordo con Boss): true solo nel filo di JBoss. Ogni delega a Jarvis sulla VPS ha la sua scheda
     * ([com.jarvis.telefono.vps.SchedaDelega]) al suo posto nel filo; le righe «[VPS · …]» che la scheda sostituisce
     * non si ripetono. Le chat degli altri agenti non hanno schede.
     */
    private val schedeDeleghe: Boolean = false,
    /**
     * Boss 2026-10-09: true solo nel filo di JBoss. Niente filtro di sessione: si vedono gli ultimi
     * [FiltroCronologia.STORICO_JBOSS] scambi (domande, risposte, schede delle deleghe), anche delle volte prima;
     * i più vecchi escono quando entrano i nuovi. Le chat degli altri agenti restano «solo la sessione aperta».
     */
    private val storico: Boolean = false,
) {
    private val lista: LinearLayout = radice.findViewById(R.id.lista_cronologia)
    private val scorri: ScrollView = radice.findViewById(R.id.scorri_cronologia)
    private val gruppo: ChipGroup? = radice.findViewById(R.id.filtri)
    private val campoCerca: EditText? = radice.findViewById(R.id.campo_cerca)
    private var filtro = filtroFisso ?: FiltroCronologia.TUTTI
    private var testo = ""
    private var ultimoIdVisto = -1L
    private var smetti: (() -> Unit)? = null
    private val aperti = HashSet<Long>()
    private val chip = LinkedHashMap<String, Chip>()
    /**
     * Boss, 2026-10-09: le chat degli agenti mostrano solo quello che è successo da quando sono state aperte. Il filo di
     * JBoss no ([storico]).
     */
    val inizioSessione = System.currentTimeMillis()

    /**
     * 09/10: vero se fuori dal filo c'è già qualcosa di questa sessione (la riga di stato di un comando appena
     * partito, prima che la sua frase arrivi in cronologia): allora «Ancora niente con …» non si scrive.
     */
    var altroInCorso: () -> Boolean = { false }

    // 0.6.1 (KO 7 dell'audit): un solo thread per le letture, attesa di 300 ms fra le lettere, e solo l'ultimo
    // risultato chiesto arriva sullo schermo ([Turni]): un risultato vecchio non copre più quello nuovo.
    private val principale = android.os.Handler(android.os.Looper.getMainLooper())
    private val turni = com.jarvis.telefono.nucleo.Turni()
    private val rinvio = com.jarvis.telefono.nucleo.Rinvio(
        com.jarvis.telefono.nucleo.Rinvio.RICERCA_MS,
        pianifica = { ms, r -> principale.postDelayed(r, ms) },
        annulla = { r -> principale.removeCallbacks(r) },
    )

    init {
        if (filtroFisso != null || gruppo == null) radice.findViewById<View>(R.id.scorri_filtri)?.visibility = View.GONE
        else filtri.forEachIndexed { i, (id, nome) ->
            gruppo.addView(Chip(activity).apply {
                chip[id] = this
                text = nome
                setEnsureMinTouchTargetSize(true) // 48 dp di tocco anche se il chip si vede più basso
                isCheckable = true
                isCheckedIconVisible = false
                chipBackgroundColor = ContextCompat.getColorStateList(activity, R.color.filtro_fondo)
                setTextColor(ContextCompat.getColorStateList(activity, R.color.filtro_testo))
                chipStrokeWidth = 0f
                this.id = View.generateViewId()
                isChecked = i == 0
                setOnCheckedChangeListener { _, sel -> if (sel) { filtro = id; ultimoIdVisto = -1; ricarica() } }
            })
        }
        radice.findViewById<ImageButton>(R.id.tasto_cerca)?.setOnClickListener {
            val c = campoCerca ?: return@setOnClickListener
            val apri = c.visibility != View.VISIBLE
            transizione(radice as ViewGroup)
            c.visibility = if (apri) View.VISIBLE else View.GONE
            if (apri) c.requestFocus() else { c.setText(""); radice.requestFocus() }
        }
        campoCerca?.doAfterTextChanged {
            testo = it?.toString().orEmpty(); ultimoIdVisto = -1
            rinvio.chiedi { ricarica() }
        }
    }

    fun avvia() {
        smetti = Cronologia.di(activity).osserva { ricarica() }
        ricarica()
    }

    fun ferma() {
        smetti?.invoke(); smetti = null
        rinvio.cancella()
    }

    fun inFondo(ritardoMs: Long = 0) = scorri.postDelayed({ scorri.fullScroll(View.FOCUS_DOWN) }, ritardoMs)

    fun ricarica() {
        val app = activity.applicationContext
        val f = filtro; val q = testo
        val turno = turni.nuovo()
        val conChip = chip.isNotEmpty()
        runCatching {
            LETTORE.execute {
                if (!turni.valido(turno)) return@execute
                val tutte = runCatching { Cronologia.di(app).ultime(if (storico) 500 else 300) }.getOrDefault(emptyList())
                var voci = FiltroCronologia.perFilo(tutte, f, q, inizioSessione, storico)
                val conti = if (conChip) FiltroCronologia.conteggi(tutte, chip.keys.toList()) else emptyMap()
                val schede = if (schedeDeleghe && q.isBlank()) {
                    val lavori = runCatching { com.jarvis.telefono.vps.ModuloVps.registro(app).elenco(60) }.getOrDefault(emptyList())
                    // Con lo storico le schede partono dal primo scambio mostrato (le più vecchie escono con lui).
                    val da = if (storico) FiltroCronologia.inizioStorico(voci) else inizioSessione
                    com.jarvis.telefono.vps.SchedaDelega.perJBoss(lavori, da)
                } else emptyList()
                if (schedeDeleghe) voci = voci.filterNot { rigaDiDelega(it) }
                principale.post {
                    if (activity.isFinishing || !turni.valido(turno)) return@post
                    disegna(voci, schede)
                    contaSuiChip(conti)
                }
            }
        }
    }

    /** «Tutto 42», «Errori 3»: il numero degli scambi per filtro. */
    private fun contaSuiChip(conti: Map<String, Int>) {
        for ((id, c) in chip) {
            val nome = filtri.firstOrNull { it.first == id }?.second ?: continue
            val n = conti[id] ?: continue
            c.text = if (n > 0) "$nome $n" else nome
            c.contentDescription = "$nome, $n"
        }
    }

    private fun disegna(voci: List<Cronologia.Voce>, schede: List<com.jarvis.telefono.vps.SchedaDelega> = emptyList()) {
        lista.removeAllViews()
        if (voci.isEmpty() && schede.isNotEmpty()) {
            val adesso = System.currentTimeMillis()
            for (s in schede) lista.addView(vistaDelega(s, adesso))
            ultimoIdVisto = 0
            scorri.post { scorri.fullScroll(View.FOCUS_DOWN) }
            return
        }
        if (voci.isEmpty()) {
            val senzaFiltri = filtro == (filtroFisso ?: FiltroCronologia.TUTTI) && testo.isBlank()
            // «Ancora niente» solo se il filo è davvero vuoto: niente frase in arrivo per questa chat.
            if (senzaFiltri && altroInCorso()) { ultimoIdVisto = 0; return }
            lista.addView(TextView(activity).apply {
                text = if (filtro == (filtroFisso ?: FiltroCronologia.TUTTI) && testo.isBlank()) vuoto
                else activity.getString(R.string.cronologia_niente_filtro)
                setTextAppearance(R.style.Jarvis_Piccolo)
                setPadding(0, activity.resources.getDimensionPixelSize(R.dimen.spazio_1), 0, 0)
            })
            ultimoIdVisto = 0
            return
        }
        // Si animano solo le righe arrivate con la schermata davanti, e poche: quelle arrivate mentre
        // era dietro (onStart) comparivano trasparenti e restavano così (S24, 07/10).
        val davanti = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState
            ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true
        val nuoveTotali = if (ultimoIdVisto < 0) 0 else voci.count { it.id > ultimoIdVisto }
        val primaVolta = ultimoIdVisto < 0 || !davanti || nuoveTotali > 5
        val adesso = System.currentTimeMillis()
        val inf = LayoutInflater.from(activity)
        val dy = activity.resources.getDimension(R.dimen.spazio_2)
        var nuove = 0
        // Le schede delle deleghe al loro posto nel filo: prima della prima riga venuta dopo la partenza.
        var prossima = 0
        for (v in voci) {
            while (prossima < schede.size && schede[prossima].quando <= v.quando) lista.addView(vistaDelega(schede[prossima++], adesso))
            val riga = if (v.chi == Cronologia.BOSS) rigaBoss(inf, v, adesso) else rigaJarvis(inf, v, adesso)
            lista.addView(riga)
            if (!primaVolta && v.id > ultimoIdVisto) {
                Movimento.compari(riga, dy, ritardoMs = 40L * nuove)
                nuove++
            }
        }
        while (prossima < schede.size) lista.addView(vistaDelega(schede[prossima++], adesso))
        ultimoIdVisto = voci.last().id
        scorri.post { if (nuove > 0 && Movimento.attive()) scorri.smoothScrollTo(0, lista.height) else scorri.fullScroll(View.FOCUS_DOWN) }
    }

    private fun vistaDelega(s: com.jarvis.telefono.vps.SchedaDelega, adesso: Long): View =
        com.jarvis.telefono.vps.ModuloVpsUi.schedaDelega(activity, s, quando(s.quando, adesso))

    private fun rigaBoss(inf: LayoutInflater, v: Cronologia.Voce, adesso: Long): View =
        inf.inflate(R.layout.item_cronologia_boss, lista, false).apply {
            findViewById<TextView>(R.id.testo).text = v.testo
            val come = when {
                v.esito == "voce" -> "a voce"; v.esito == "scritto" -> "scritto"; v.esito == "dettato" -> "dettato"
                v.esito == "prova" -> "prova"
                v.esito.startsWith("chat-") -> "nella chat di " + Competenze.nomeCorto(v.esito.removePrefix("chat-"))
                else -> v.esito
            }
            findViewById<TextView>(R.id.chi_quando).text =
                listOf(activity.getString(R.string.tu), quando(v.quando, adesso), come).filter { it.isNotBlank() }.joinToString(" · ")
        }

    private fun rigaJarvis(inf: LayoutInflater, v: Cronologia.Voce, adesso: Long): View =
        inf.inflate(R.layout.item_cronologia_jarvis, lista, false).apply {
            val agente = v.agenteEsecutore.takeIf { it.isNotBlank() }?.let { CatalogoAgenti.predefinito(it) }
            Avatar.metti(findViewById<ImageView>(R.id.avatar), agente?.id ?: CatalogoAgenti.JARVIS)
            // «Jarvis → Mani · 19:56»: chi ha ricevuto la richiesta e a chi l'ha passata.
            findViewById<TextView>(R.id.chi_quando).text = "${Competenze.delega(agente?.id, vps = v.cervello == "vps")} · ${quando(v.quando, adesso)}"
            val t = findViewById<TextView>(R.id.testo)
            t.text = v.testo
            val (testoEsito, colore) = when (v.esito) {
                "ok" -> (if (agente != null) R.string.esito_eseguito else R.string.esito_risposto) to R.color.spia_verde
                "non capito" -> R.string.esito_non_capito to R.color.spia_giallo
                "annullato" -> R.string.esito_annullato to R.color.jarvis_testo_tenue
                "errore" -> R.string.esito_errore to R.color.spia_rosso
                "in corso" -> R.string.esito_in_corso_vps to R.color.jarvis_accento
                "in coda" -> R.string.esito_in_coda_vps to R.color.spia_giallo
                else -> 0 to R.color.jarvis_testo_tenue
            }
            findViewById<TextView>(R.id.esito).apply {
                if (testoEsito == 0) visibility = View.GONE
                else { setText(testoEsito); setTextColor(ContextCompat.getColor(context, colore)) }
            }
            val det = findViewById<TextView>(R.id.dettaglio)
            det.text = activity.getString(
                R.string.dettaglio_riga,
                v.cervello.ifBlank { "—" },
                if (v.ms > 0) "${secondi(v.ms / 1000.0)} s" else "—",
                v.esito.ifBlank { "—" },
            )
            fun mostra(aperto: Boolean) {
                det.visibility = if (aperto && tecnico) View.VISIBLE else View.GONE
                // 09/10 (Boss: «risposte di JBoss tagliate nel filo»): nella chat (tecnico = false) la risposta è intera.
                t.maxLines = if (aperto || !tecnico) Int.MAX_VALUE else 3
            }
            mostra(v.id in aperti)
            setOnClickListener {
                val apri = v.id !in aperti
                if (apri) aperti += v.id else aperti -= v.id
                transizione(lista)
                mostra(apri)
            }
        }

    private fun transizione(g: ViewGroup) {
        if (Movimento.attive()) TransitionManager.beginDelayedTransition(g, AutoTransition().setDuration(Movimento.breve(activity)))
    }

    companion object {
        /**
         * Le righe che il modulo VPS scrive per un lavoro («[VPS · Ricercatore] In corso sulla VPS…», «… Finito: …»):
         * nel filo di JBoss le sostituisce la scheda della delega.
         */
        fun rigaDiDelega(v: Cronologia.Voce): Boolean =
            v.chi != Cronologia.BOSS && v.cervello == "vps" && v.testo.startsWith("[VPS · ")

        /** Un solo thread per tutte le cronologie (Home e chat): le letture vanno in fila, mai in parallelo. */
        private val LETTORE = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-cronologia").apply { isDaemon = true } }
    }
}

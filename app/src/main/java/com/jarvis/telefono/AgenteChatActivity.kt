package com.jarvis.telefono

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.jarvis.telefono.agenti.Agente
import com.jarvis.telefono.agenti.ArchivioAgenti
import com.jarvis.telefono.agenti.Avatar
import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.agenti.Dove
import com.jarvis.telefono.agenti.Situazione
import com.jarvis.telefono.agenti.StatiAgenti
import com.jarvis.telefono.nucleo.Comandi
import com.jarvis.telefono.nucleo.FiltroCronologia
import com.jarvis.telefono.nucleo.Nucleo
import com.jarvis.telefono.ui.AnimaAvatar
import com.jarvis.telefono.ui.Movimento
import com.jarvis.telefono.ui.Tema
import com.jarvis.telefono.ui.VistaCronologia
import com.jarvis.telefono.voce.Ascolto
import com.jarvis.telefono.voce.Stato
import com.jarvis.telefono.voce.StatoJarvis

/**
 * La chat a tutto schermo di un agente, 0.7.1 (layout approvato da Boss il 09/10), la stessa per tutti: JBoss
 * ([CatalogoAgenti.JARVIS], dall'avatar grande in Home), Ricercatore, Social, Mani, Scrittore. Il Postino ha la sua
 * pagina ([com.jarvis.telefono.postino.PostinoActivity]).
 *
 * In alto indietro, avatar, nome e stato, ⚙ (la scheda dell'agente; per JBoss le Impostazioni). Al centro il filo:
 * una riga onesta su cosa sa fare l'agente, poi domande e risposte ([VistaCronologia] senza filtri, ricerca e
 * dettagli tecnici), e in fondo al filo la riga di stato del lavoro e il box del sì ([VistaComando], stesse regole di
 * prima: cambia solo dove si vede). In basso Parla, il campo e Invia.
 *
 * Quello che Boss scrive a un agente va al nucleo con l'origine «chat-<agente>» ([FiltroCronologia.origineChat]);
 * a JBoss con «scritto», come prima dalla Home. Parla: per JBoss la voce vera del servizio ([JarvisService.ascolta]);
 * per gli altri agenti il dettato ([DettatoNativo]) e la frase parte con l'origine dell'agente, così la risposta
 * resta nel suo filo. La voce del servizio non sa l'agente: per questo qui non si usa per gli altri.
 */
class AgenteChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AGENTE = "agente"

        fun intento(c: Context, agente: String): Intent =
            Intent(c, AgenteChatActivity::class.java).putExtra(EXTRA_AGENTE, agente)
    }

    private lateinit var id: String
    private lateinit var radice: View
    private lateinit var cronologia: VistaCronologia
    private lateinit var vistaComando: com.jarvis.telefono.ui.VistaComando
    private lateinit var anima: AnimaAvatar
    private lateinit var campo: EditText
    private lateinit var barra: com.jarvis.telefono.ui.ControlloreBarra
    private var agente: Agente? = null
    private var smettiStato: (() -> Unit)? = null
    private var smettiCollegamento: (() -> Unit)? = null
    private var smettiComandi: (() -> Unit)? = null
    private var smettiLavori: (() -> Unit)? = null
    private var smettiPassaggio: (() -> Unit)? = null
    private val principale = android.os.Handler(android.os.Looper.getMainLooper())
    /** I passi di un lavoro VPS arrivano fitti: la scheda si ridisegna al massimo ogni mezzo secondo. */
    private val ridisegnaDeleghe = Runnable { cronologia.ricarica() }
    private var inPrimoPiano = false
    private var lavoravaPrima = false

    /** JBoss, il capo: non è nel catalogo dei 5 agenti, ha la sua voce e il suo stato. */
    private val capo: Boolean get() = id == CatalogoAgenti.JARVIS

    /** Per il segno «app in primo piano» ([com.jarvis.telefono.bolla.PrimoPiano]): questa è la chat di JBoss. */
    val chatDiJBoss: Boolean get() = ::id.isInitialized && capo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agente_chat)
        val richiesto = intent.getStringExtra(EXTRA_AGENTE)
        id = richiesto?.takeIf { it == CatalogoAgenti.JARVIS || CatalogoAgenti.predefinito(it) != null } ?: CatalogoAgenti.JARVIS
        val nome = if (capo) getString(R.string.nome_capo) else CatalogoAgenti.predefinito(id)!!.nome
        radice = findViewById(R.id.radice)
        findViewById<ImageButton>(R.id.tasto_indietro).setOnClickListener { finish() }
        val avatar = findViewById<ImageView>(R.id.avatar)
        if (capo) avatar.setImageResource(R.drawable.jboss_grande_256) else Avatar.metti(avatar, id, grande = true)
        anima = AnimaAvatar(avatar, findViewById(R.id.anello))
        findViewById<TextView>(R.id.nome).text = nome
        findViewById<ImageButton>(R.id.tasto_impostazioni_agente).setOnClickListener { apriImpostazioni() }

        cronologia = VistaCronologia(
            this, radice,
            // 09/10: il filo di JBoss mostra anche la posta (letture, cancellazioni, avvisi del Postino).
            filtroFisso = if (capo) FiltroCronologia.FILO_JBOSS else id,
            vuoto = getString(R.string.chat_vuota, nome),
            tecnico = false,
            // 09/10: le schede delle deleghe a Jarvis sulla VPS stanno solo nel filo di JBoss.
            schedeDeleghe = capo,
            // Boss 09/10: il filo di JBoss ha lo storico (ultimi 60 scambi, anche delle sessioni di prima).
            storico = capo,
        )
        // 09/10: la riga di stato mostra solo i comandi di questa chat, partiti da quando è aperta (come il filo).
        val agenteFiltro = if (capo) FiltroCronologia.JARVIS else id
        val sessione = cronologia.inizioSessione
        val diQuestaChat: (com.jarvis.telefono.nucleo.RegistroComandi.Comando) -> Boolean =
            { c -> com.jarvis.telefono.nucleo.RegistroComandi.diChat(c, agenteFiltro, sessione) }
        vistaComando = com.jarvis.telefono.ui.VistaComando(this, radice, diQuestaChat)
        cronologia.altroInCorso = { Comandi.registro.istantanea(diQuestaChat).comando != null }

        // 2026-10-10: la barra degli agenti (campo, Parla, Chiama, Invia), uguale in tutte le chat.
        barra = com.jarvis.telefono.ui.ControlloreBarra(
            this, radice, id,
            invia = { t, daVoce -> manda(t, if (capo && daVoce) "dettato" else origine()) },
            occupato = { Comandi.registro.istantanea(diQuestaChat).comando != null },
            avviso = { t, errore -> avviso(t, errore) },
        )
        campo = barra.campo
        findViewById<TextView>(R.id.missione).text = missione()
        if (!capo) ArchivioAgenti.di(this).carica { l -> agente = l.firstOrNull { it.id == id }; disegna(StatoJarvis.corrente) }
    }

    override fun onStart() {
        super.onStart()
        cronologia.avvia()
        vistaComando.avvia()
        smettiStato = StatoJarvis.osserva { disegna(it) }
        smettiCollegamento = com.jarvis.telefono.vps.ModuloVps.ascoltaCollegamento { disegna(StatoJarvis.corrente) }
        // La riga di stato e il box del sì stanno in fondo al filo: quando cambiano, il filo scende a mostrarli.
        // Il filo si rilegge anche lui: «Ancora niente» sparisce appena parte un comando di questa chat.
        smettiComandi = Comandi.registro.osserva { runOnUiThread { cronologia.ricarica(); cronologia.inFondo(150) } }
        // 09/10 (Boss: «JBoss deve collegarsi SUBITO al Postino»): aperta la chat, il canale unico del Postino si collega.
        if (capo) com.jarvis.telefono.postino.Postino.vistaAperta(this, pagina = false)
        // 09/10: «passa al Postino» / «torna a JBoss»: la riga in alto e il campo dicono con chi si parla.
        if (capo) smettiPassaggio = com.jarvis.telefono.postino.Postino.osservaPassaggio { runOnUiThread { disegna(StatoJarvis.corrente) } }
        // 09/10: le schede delle deleghe seguono il lavoro sulla VPS (stato, esito). Solo JBoss, solo col modulo acceso.
        if (capo && com.jarvis.telefono.vps.ModuloVps.acceso(this)) {
            smettiLavori = com.jarvis.telefono.vps.ModuloVps.nucleo(this).ascolta(object : com.jarvis.telefono.vps.NucleoVps.Ascoltatore {
                override fun cambiato(id: String) {
                    principale.removeCallbacks(ridisegnaDeleghe)
                    principale.postDelayed(ridisegnaDeleghe, 500L)
                }
            })
        }
    }

    override fun onStop() {
        cronologia.ferma()
        vistaComando.ferma()
        smettiStato?.invoke(); smettiStato = null
        smettiCollegamento?.invoke(); smettiCollegamento = null
        smettiComandi?.invoke(); smettiComandi = null
        smettiLavori?.invoke(); smettiLavori = null
        smettiPassaggio?.invoke(); smettiPassaggio = null
        if (capo) com.jarvis.telefono.postino.Postino.vistaChiusa(this, pagina = false)
        principale.removeCallbacks(ridisegnaDeleghe)
        barra.ferma()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        inPrimoPiano = true
        disegna(StatoJarvis.corrente)
        // Boss 09/10: la chat di JBoss è sempre attiva. Con la voce accesa JBoss ascolta senza la parola, e domanda e
        // risposta finiscono nel filo (il popup dentro l'app non c'è).
        if (capo) JarvisService.instance?.chatJBossAperta()
    }

    override fun onPause() {
        inPrimoPiano = false
        anima.ferma()
        super.onPause()
    }

    override fun onDestroy() {
        barra.rilascia()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        Tema.chiudi(this)
    }

    private fun disegna(s: Stato) {
        val lavora: Boolean
        if (capo) {
            Tema.pallino(findViewById(R.id.pallino), Tema.daTinta(this, coloreSpia(s)))
            // 09/10: con la conversazione passata al Postino lo dice la riga in alto (e il campo), finché non si torna.
            val conPostino = com.jarvis.telefono.postino.Postino.passaggio.conPostino
            findViewById<TextView>(R.id.stato).text = if (conPostino) getString(R.string.chat_con_postino) else testoSpia(s)
            findViewById<TextView>(R.id.avviso).setText(if (conPostino) R.string.chat_con_postino_avviso else R.string.chat_jboss_avviso)
            barra.suggerimento(com.jarvis.telefono.ui.TestiBarra.suggerimento(
                if (conPostino) CatalogoAgenti.POSTINO else CatalogoAgenti.JARVIS))
            lavora = s.agenteAlLavoro != null || s.ascolto == Ascolto.PENSO
        } else {
            val a = agente ?: CatalogoAgenti.predefinito(id) ?: return
            // 0.6.1: lo stato del Collegamento Jarvis si passa davvero (prima mancava: «non collegato» sempre).
            val sit = Situazione(
                JarvisAccessibilityService.instance != null, Prefs.isManiFerme(this), s.agenteAlLavoro,
                moduloVps = com.jarvis.telefono.vps.ModuloVps.pronto(this),
                vpsCollegata = com.jarvis.telefono.vps.ModuloVps.collegato(),
            )
            val st = StatiAgenti.stato(a, sit)
            Tema.pallino(findViewById(R.id.pallino), Tema.perStato(this, st))
            findViewById<TextView>(R.id.stato).text = StatiAgenti.testo(a, st, sit)
            findViewById<TextView>(R.id.avviso).text = avviso(a, sit.moduloVps)
            lavora = s.agenteAlLavoro == id
        }
        if (inPrimoPiano) {
            anima.imposta(if (lavora) AnimaAvatar.Modo.LAVORO else AnimaAvatar.Modo.RIPOSO)
            if (lavoravaPrima && !lavora) anima.luccica()
        }
        lavoravaPrima = lavora
    }

    /** La riga in cima al filo, vera adesso: per gli agenti sulla VPS dipende dal Collegamento Jarvis. */
    private fun avviso(a: Agente, vps: Boolean): String = when {
        a.dove == Dove.ESTERNO && vps -> getString(R.string.chat_esterno_collegato, a.nome)
        a.dove == Dove.ESTERNO -> getString(R.string.chat_esterno, a.nome)
        a.id == CatalogoAgenti.SCRITTORE -> getString(R.string.chat_scrittore)
        !a.attivoNelTelefono -> getString(R.string.chat_non_attivo, a.nome)
        else -> getString(R.string.chat_mani)
    }

    /** ⚙: la scheda dell'agente (missione, dove lavora, autonomia); per JBoss le Impostazioni. */
    private fun apriImpostazioni() {
        if (capo) Tema.apri(this, Intent(this, ImpostazioniActivity::class.java))
        else Tema.apri(this, Intent(this, AgentiActivity::class.java).putExtra(AgentiActivity.EXTRA_AGENTE, id))
    }

    private fun origine(): String = if (capo) "scritto" else FiltroCronologia.origineChat(id)

    private fun manda(t: String, origine: String) {
        val s = JarvisService.instance
        if (s != null) s.sendUserText(t, origine) else Nucleo.elabora(this, t, origine)
        cronologia.inFondo(300)
    }

    /** La missione in alto, sotto il nome: una riga onesta. */
    private fun missione(): String = if (capo) getString(R.string.missione_capo)
        else (agente ?: CatalogoAgenti.predefinito(id))?.missione.orEmpty()

    /** Un messaggio della barra (chiamata aperta o chiusa, microfono che non parte): breve, sopra la barra. */
    private fun avviso(testo: String, errore: Boolean) {
        Snackbar.make(radice, testo, if (errore) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
            .setTextMaxLines(4).setAnchorView(R.id.riga_composizione).show()
    }
}

/**
 * L'aggancio della chat del Postino (Boss 07/10): dalla 0.3.0 è la chat a numeri ([com.jarvis.telefono.postino.PostinoActivity]):
 * resoconto numerato letto dalla VPS, comandi per numero, stato «eseguito» letto dalla casella. La Home e il menu
 * Agenti chiamano questa funzione. Senza modulo VPS la chat dice onestamente «collega la VPS».
 */
object PostinoActivity {
    fun intento(c: Context): Intent = com.jarvis.telefono.postino.PostinoActivity.intento(c)
}

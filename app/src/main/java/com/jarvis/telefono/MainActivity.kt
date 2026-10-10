package com.jarvis.telefono

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.jarvis.telefono.agenti.Avatar
import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.ui.AnimaAvatar
import com.jarvis.telefono.ui.ChipLavoro
import com.jarvis.telefono.ui.Movimento
import com.jarvis.telefono.ui.Tema
import com.jarvis.telefono.voce.Ascolto
import com.jarvis.telefono.voce.Stato
import com.jarvis.telefono.voce.StatoJarvis

/**
 * La Home di JBoss, 0.7.1 (layout approvato da Boss il 09/10). In alto «Lavori» con il numero dei lavori aperti
 * sulla VPS e le Impostazioni. Sotto, gli avatar in riga, uno a fianco all'altro: JBoss (grande), gli agenti e
 * Jarvis (webapp). Un tocco su un avatar apre la chat a tutto schermo di quell'agente ([AgenteChatActivity]); il
 * Postino apre la sua pagina, Jarvis la webapp. L'avatar di JBoss non accende più la voce: apre la chat di JBoss
 * (la pressione lunga apre ancora Agenti). Sotto gli avatar lo stato (un tocco porta a Impostazioni → Voce) e il chip
 * dell'agente che lavora; in fondo i due tocchi grandi Agenti e Parla. Cronologia, campo e box del sì stanno nella
 * chat, non più qui. Accendi/Spegni e i dettagli tecnici stanno in Impostazioni → Voce.
 *
 * Avatar animati con [AnimaAvatar] (respiro, ascolto, lavoro, luccichio); tutto si ferma in onPause.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var radice: View
    private lateinit var spia: View
    private lateinit var vTestoSpia: TextView
    private lateinit var chipsJarvis: LinearLayout
    private lateinit var animaJarvis: AnimaAvatar
    private var animaPostino: AnimaAvatar? = null
    /** La cella del Postino nella riga degli avatar e la sua riga breve di stato («12 nuove · 10:42»). */
    private var cellaPostino: View? = null
    private var statoPostino: TextView? = null

    private var smettiDiOsservare: (() -> Unit)? = null
    private var smettiVps: (() -> Unit)? = null
    /** 0.3.0: il lavoro aperto sulla VPS più recente (chip, «in corso» del Postino), letto dal registro. */
    private var lavoroVps: com.jarvis.telefono.vps.LavoroLocale? = null
    private var lavoriAperti = 0
    private var lampeggio: ObjectAnimator? = null
    private var inPrimoPiano = false

    // Il chip dell'agente che lavora adesso (uno alla volta: il nucleo esegue un piano per volta).
    private var chip: View? = null
    private var chipAgente: String? = null
    private var animaChip: AnimaAvatar? = null
    private var ultimoAgente: String? = null

    private val permessoNotifiche = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { passoMicrofono() }

    // Senza microfono «Hey Boss» resta muto e basta, senza nessun avviso. Dopo una
    // reinstallazione il permesso si azzera sempre, per questo si chiede all'apertura.
    private val permessoMicrofono = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concesso ->
        if (concesso) riavviaServizioSeServe()
        else Toast.makeText(this, R.string.serve_microfono, Toast.LENGTH_LONG).show()
        passoPrimoAvvio()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.telefono.collegamento.CollegamentoJarvis.chiediIconaHomeWebapp(this)
        super.onCreate(savedInstanceState)
        // 0.3.1: config-boss.json e token VPS nella cassaforte cifrata, una volta sola, in un thread a parte.
        // 0.4.0: a migrazione finita, la procedura guidata parte da sola se tocca (cassaforte vuota o dati migrati);
        // se parte lei, i permessi li chiede lei e la Home non apre le sue richieste.
        val primaVolta = savedInstanceState == null
        com.jarvis.telefono.cassaforte.AvvioCassaforte.migraUnaVolta(this) {
            if (primaVolta && !isFinishing && !com.jarvis.telefono.configura.StatoConfigurazione.apriSeServe(this)) chiediPermessi()
        }
        setContentView(R.layout.activity_main)

        radice = findViewById(R.id.radice)
        spia = findViewById(R.id.spia)
        vTestoSpia = findViewById(R.id.testo_spia)
        chipsJarvis = findViewById(R.id.chips_jarvis)

        val avatarJarvis = findViewById<ImageView>(R.id.avatar_jarvis)
        // Il grande avatar è JBoss (personaggio grigio); il rapper è Jarvis (webapp), in fondo alla riga.
        avatarJarvis.setImageResource(R.drawable.jboss_grande_256)
        animaJarvis = AnimaAvatar(avatarJarvis, findViewById(R.id.anello_jarvis))
        riempiRigaAvatar()

        findViewById<ImageButton>(R.id.tasto_impostazioni).setOnClickListener {
            Tema.apri(this, Intent(this, ImpostazioniActivity::class.java))
        }
        // «Lavori N» apre i lavori sulla VPS (in corso, finiti, terminale a pieno schermo).
        findViewById<MaterialButton>(R.id.tasto_lavori).setOnClickListener { com.jarvis.telefono.vps.ModuloVpsUi.apri(this) }
        findViewById<MaterialButton>(R.id.tasto_agenti).apply { setOnClickListener { apriAgenti() }; Movimento.pressione(this) }
        findViewById<MaterialButton>(R.id.tasto_parla_grande).apply { setOnClickListener { parla() }; Movimento.pressione(this) }
        // Lo stato porta ai dettagli e ad Accendi/Spegni, che stanno in Impostazioni → Voce.
        findViewById<View>(R.id.riga_stato).setOnClickListener {
            Tema.apri(this, com.jarvis.telefono.configura.SchedaActivity.intento(this, com.jarvis.telefono.configura.SchedaActivity.VOCE))
        }
        // 0.7.1: JBoss non accende più la voce dal suo avatar: il tocco apre la sua chat, la pressione lunga Agenti.
        findViewById<View>(R.id.cornice_jarvis).apply {
            setOnClickListener { Tema.apri(this@MainActivity, AgenteChatActivity.intento(this@MainActivity, CatalogoAgenti.JARVIS)) }
            setOnLongClickListener { apriAgenti(); true }
            Movimento.pressione(this)
        }

        Avatar.preparaPiccoli(this)
    }

    override fun onStart() {
        super.onStart()
        smettiDiOsservare = StatoJarvis.osserva { disegna(it) }
        if (com.jarvis.telefono.vps.ModuloVps.acceso(this)) {
            smettiVps = com.jarvis.telefono.vps.ModuloVps.nucleo(this).ascolta(object : com.jarvis.telefono.vps.NucleoVps.Ascoltatore {
                override fun cambiato(id: String) { runOnUiThread { leggiLavoroVps(); disegna(StatoJarvis.corrente) } }
            })
        }
        leggiLavoroVps()
    }

    override fun onStop() {
        smettiDiOsservare?.invoke()
        smettiDiOsservare = null
        smettiVps?.invoke()
        smettiVps = null
        fermaLampeggio()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        inPrimoPiano = true
        Inventario.rileggiInSfondo(this)
        riavviaServizioSeServe()
        disegna(StatoJarvis.corrente)
    }

    /** Fuori dallo schermo (o schermo spento) niente animazioni: batteria. */
    override fun onPause() {
        inPrimoPiano = false
        fermaLampeggio()
        animaJarvis.ferma(); animaPostino?.ferma(); animaChip?.ferma()
        super.onPause()
    }

    private fun riavviaServizioSeServe() = riavviaServizioSeServe(this)

    private fun chiediPermessi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permessoNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            passoMicrofono()
        }
    }

    private fun passoMicrofono() {
        if (!Permessi.microfono(this)) permessoMicrofono.launch(Manifest.permission.RECORD_AUDIO)
        else passoPrimoAvvio()
    }

    /** Solo alla prima apertura: l'esclusione dall'ottimizzazione batteria (con «Dopo»). */
    private fun passoPrimoAvvio() {
        if (Prefs.isPrimoAvvioFatto(this) || isFinishing) return
        // Jarvis Telefono nasce acceso: alla prima apertura, con il microfono concesso, parte da solo.
        if (Permessi.microfono(this) && !Prefs.isAttivo(this)) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java)) }
                .onSuccess { Prefs.setAttivo(this, true) }
        }
        if (Permessi.batteriaEsclusa(this)) { Prefs.setPrimoAvvioFatto(this, true); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.primo_batteria_titolo)
            .setMessage(R.string.primo_batteria_testo)
            .setPositiveButton(R.string.apri_impostazioni) { _, _ -> Permessi.apriEsclusioneBatteria(this) }
            .setNegativeButton(R.string.dopo, null)
            .setOnDismissListener { Prefs.setPrimoAvvioFatto(this, true) }
            .show()
    }

    private fun disegna(stato: Stato) {
        colora(spia, coloreSpia(stato))
        vTestoSpia.text = testoSpia(stato).replaceFirstChar { it.uppercase() }
        if (spiaLampeggia(stato) && inPrimoPiano) avviaLampeggio() else fermaLampeggio()

        // Postino: «in corso» quando lavora (qui o sulla VPS); il numero delle nuove arriva dal resoconto vero.
        val postinoVps = lavoroVps?.agente == CatalogoAgenti.POSTINO
        val postinoLavora = ChipLavoro.postinoAlLavoro(stato) || postinoVps
        val ultimoConto = com.jarvis.telefono.postino.ConteggioPostino.ultimo(this)
        val vpsPronta = com.jarvis.telefono.vps.ModuloVps.pronto(this)
        val testoPostino = when {
            postinoVps -> getString(R.string.postino_in_corso_vps)
            postinoLavora -> getString(R.string.postino_in_corso)
            !vpsPronta -> getString(R.string.postino_non_collegato)
            ultimoConto != null -> getString(R.string.postino_nuove, ultimoConto.first,
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY).format(java.util.Date(ultimoConto.second)))
            else -> getString(R.string.postino_pronto)
        }
        statoPostino?.apply { text = testoPostino; visibility = View.VISIBLE }
        cellaPostino?.contentDescription = getString(R.string.postino) + ", " + testoPostino
        // «Lavori N»: i lavori aperti sulla VPS (dal registro, letto in leggiLavoroVps).
        findViewById<MaterialButton>(R.id.tasto_lavori).apply {
            text = if (lavoriAperti > 0) getString(R.string.lavori_n, lavoriAperti) else getString(R.string.lavori)
            contentDescription = getString(R.string.vps_lavori) + if (lavoriAperti > 0) ", $lavoriAperti aperti" else ""
            alpha = if (vpsPronta) 1f else 0.6f
        }

        if (inPrimoPiano) {
            animaJarvis.imposta(
                when {
                    (stato.agenteAlLavoro != null && !postinoLavora) || stato.ascolto == Ascolto.PENSO -> AnimaAvatar.Modo.LAVORO
                    stato.ascolto == Ascolto.CATTURA || stato.ascolto == Ascolto.PARLO -> AnimaAvatar.Modo.ASCOLTO
                    else -> AnimaAvatar.Modo.RIPOSO
                },
            )
            animaPostino?.imposta(if (postinoLavora) AnimaAvatar.Modo.LAVORO else AnimaAvatar.Modo.RIPOSO)
            // Finito un lavoro: luccichio sul titolare che l'ha seguito.
            if (ultimoAgente != null && stato.agenteAlLavoro == null) {
                if (ultimoAgente == CatalogoAgenti.POSTINO) animaPostino?.luccica() else animaJarvis.luccica()
            }
        }
        ultimoAgente = stato.agenteAlLavoro
        aggiornaChip(stato)
    }

    /** Il chip a fianco di Jarvis: compare con un rimbalzo mentre l'agente lavora, poi va via. */
    private fun aggiornaChip(stato: Stato) {
        val vps = lavoroVps
        val idVps = ChipLavoro.agenteVps(stato, vps?.agente)
        val id = ChipLavoro.agente(stato) ?: idVps
        val testo = if (idVps != null && vps != null) ChipLavoro.testoVps(idVps, vps.ultimo) else ChipLavoro.testo(stato)
        if (id == null) {
            val vecchio = chip ?: return
            chip = null; chipAgente = null
            animaChip?.ferma(); animaChip = null
            AnimaAvatar.esci(vecchio) { chipsJarvis.removeView(vecchio) }
            return
        }
        val v = chip ?: LayoutInflater.from(this).inflate(R.layout.item_chip_agente, chipsJarvis, false).also {
            chip = it
            chipsJarvis.addView(it)
            AnimaAvatar.rimbalza(it)
        }
        if (chipAgente != id) {
            chipAgente = id
            Avatar.metti(v.findViewById(R.id.avatar), id)
            animaChip?.ferma()
            animaChip = AnimaAvatar(v.findViewById(R.id.avatar), v.findViewById(R.id.anello))
        }
        v.findViewById<TextView>(R.id.testo).text = testo
        v.contentDescription = testo
        // Il chip di un lavoro sulla VPS apre il suo terminale a pieno schermo.
        if (idVps != null && vps != null) v.setOnClickListener { com.jarvis.telefono.vps.ModuloVpsUi.apriLavoro(this, vps.id) }
        else v.setOnClickListener(null)
        if (inPrimoPiano) animaChip?.imposta(AnimaAvatar.Modo.LAVORO)
    }

    private fun apriAgenti() = Tema.apri(this, Intent(this, AgentiActivity::class.java))

    private fun leggiLavoroVps() {
        lavoroVps = com.jarvis.telefono.vps.ModuloVps.primoAperto(this)
        lavoriAperti = if (!com.jarvis.telefono.vps.ModuloVps.acceso(this)) 0
        else runCatching { com.jarvis.telefono.vps.ModuloVps.registro(this).aperti().size }.getOrDefault(0)
    }

    // ------------------------------------------------------------ riga degli avatar, Parla

    /**
     * Gli avatar in riga a fianco di JBoss: solo il Postino (si usa direttamente) e Jarvis (la webapp).
     * Gli altri agenti li comanda JBoss, non si toccano dalla Home (Boss, 2026-10-09). Il tocco apre la chat a
     * tutto schermo; il Postino ha la sua pagina.
     */
    private val agentiInRiga = setOf(CatalogoAgenti.POSTINO)

    private fun riempiRigaAvatar() {
        val riga = findViewById<LinearLayout>(R.id.riga_avatar)
        val inf = LayoutInflater.from(this)
        for (a in CatalogoAgenti.PREDEFINITI.filter { it.id in agentiInRiga }) {
            val cella = inf.inflate(R.layout.item_avatar_home, riga, false)
            val avatar = cella.findViewById<ImageView>(R.id.avatar)
            Avatar.metti(avatar, a.id)
            cella.findViewById<TextView>(R.id.nome).text = a.nome.substringBefore(' ')
            cella.contentDescription = getString(R.string.chat_di, a.nome)
            cella.setOnClickListener {
                Tema.apri(this, if (a.id == CatalogoAgenti.POSTINO) PostinoActivity.intento(this) else AgenteChatActivity.intento(this, a.id))
            }
            Movimento.pressione(cella)
            if (a.id == CatalogoAgenti.POSTINO) {
                cellaPostino = cella
                statoPostino = cella.findViewById(R.id.sotto)
                animaPostino = AnimaAvatar(avatar, cella.findViewById(R.id.anello))
            }
            riga.addView(cella)
        }
        // Jarvis (webapp): il rapper, un tocco apre la webapp (la chat di Jarvis).
        val jarvis = inf.inflate(R.layout.item_avatar_home, riga, false)
        jarvis.findViewById<ImageView>(R.id.avatar).setImageResource(R.drawable.avatar_jarvis_128)
        jarvis.findViewById<TextView>(R.id.nome).setText(R.string.imp_webapp)
        jarvis.contentDescription = getString(R.string.apri_webapp_jarvis)
        jarvis.setOnClickListener { startActivity(Intent(this, com.jarvis.telefono.collegamento.WebJarvisActivity::class.java)) }
        Movimento.pressione(jarvis)
        riga.addView(jarvis)
    }

    /**
     * Parla. Boss 09/10: in Home la voce non va in un popup ma nella chat di JBoss. Con la voce accesa si apre la chat,
     * che ascolta da sola appena è davanti (AgenteChatActivity.onResume → JarvisService.chatJBossAperta): domanda e
     * risposta finiscono nel filo. In pausa o spenta niente ascolto e niente popup: lo dice l'avviso.
     */
    private fun parla() {
        // 09/10: la stessa lettura dell'interruttore «JBoss acceso» della pagina Voce (JarvisService.presenza).
        val p = JarvisService.presenza(this)
        avvisoParla(p)?.let { Snackbar.make(radice, it, Snackbar.LENGTH_SHORT).setAnchorView(R.id.riga_pollice).show() }
        when (p) {
            PresenzaJBoss.ACCESO -> Tema.apri(this, AgenteChatActivity.intento(this, CatalogoAgenti.JARVIS))
            PresenzaJBoss.IN_AVVIO -> riavviaServizioSeServe(this)
            PresenzaJBoss.PAUSA, PresenzaJBoss.VOCE_SPENTA, PresenzaJBoss.SPENTO, PresenzaJBoss.GUASTO -> Unit
        }
    }

    private fun avviaLampeggio() {
        if (lampeggio?.isRunning == true || !Movimento.attive()) return
        lampeggio = ObjectAnimator.ofFloat(spia, View.ALPHA, 1f, 0.2f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun fermaLampeggio() {
        lampeggio?.cancel()
        lampeggio = null
        spia.alpha = 1f
    }

    companion object {
        private const val TAG = "MainActivity"

        fun riavviaServizioSeServe(context: android.content.Context) {
            if (Prefs.isAttivo(context) && Permessi.microfono(context)) {
                runCatching {
                    ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java))
                }.onFailure { Log.w(TAG, "avvio del servizio rifiutato: ${it.javaClass.simpleName}") }
            }
        }

        /** Il pallino è una forma ovale: si cambia solo il colore (tinta tradotta per il modo scuro). */
        fun colora(v: View, colore: Int) {
            (v.background?.mutate() as? GradientDrawable)?.setColor(Tema.daTinta(v.context, colore))
        }
    }
}

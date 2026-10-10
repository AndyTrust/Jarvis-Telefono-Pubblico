package com.jarvis.telefono

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.Sblocco
import com.jarvis.telefono.collegamento.AggiornamentiJBoss
import com.jarvis.telefono.collegamento.CollegamentoJarvis
import com.jarvis.telefono.configura.BaseConfigura
import com.jarvis.telefono.configura.IndiceImpostazioni
import com.jarvis.telefono.configura.IndiceImpostazioni.Gruppo
import com.jarvis.telefono.configura.Mattoni
import com.jarvis.telefono.configura.PannelloVoce
import com.jarvis.telefono.configura.SchedaActivity
import com.jarvis.telefono.configura.StatoConfigurazione
import com.jarvis.telefono.nucleo.ConfigPersonale
import com.jarvis.telefono.ui.Movimento
import com.jarvis.telefono.ui.Tema
import com.jarvis.telefono.voce.EsitoScarico
import com.jarvis.telefono.voce.Modello
import com.jarvis.telefono.voce.StatoJarvis
import com.jarvis.telefono.vps.ModuloVps
import com.jarvis.telefono.vps.ModuloVpsUi

/**
 * L'indice delle Impostazioni a card, 0.6.7 card (layout approvato da Boss il 09/10, res/layout/impostazioni_card.xml):
 *
 *   ←  Impostazioni
 *   [ Cerca un'impostazione… ]
 *   (JB) JBoss · versione            Aggiorna · Licenze          → Informazioni
 *   Voce                  [toggle]   acceso/spento · fine frase   → Voce
 *   Postino e mail             N >                                → Postino e mail
 *   (Jarvis) Collegamento Jarvis ●   Webapp · Lavori · Memoria    → Collegamento Jarvis
 *   Account e accessi >  ·  Sicurezza [blocco schermo]  ·  Aspetto (tema) >
 *   Informazioni · Licenze  ·  Rifai configurazione
 *
 * Ogni card (o riga) apre la sua pagina ([SchedaActivity]); la ricerca resta e porta al gruppo giusto
 * ([IndiceImpostazioni.cerca]). L'interruttore Voce è lo stesso gesto della pagina Voce ([PannelloVoce.accendiJBoss]);
 * quello di Sicurezza dice se il blocco schermo c'è e porta alle impostazioni di Android (l'app non lo può cambiare).
 * Qui resta anche lo scarico dei modelli della voce (nel companion), che usano la pagina Voce e la configurazione
 * guidata: un filo solo, una barra sola. L'elenco di prima e dove è andata ogni voce: impostazioni-inventario.txt.
 */
class ImpostazioniActivity : BaseConfigura() {

    private lateinit var carte: LinearLayout
    private lateinit var risultati: LinearLayout
    private lateinit var cerca: EditText
    private lateinit var voceInterruttore: SwitchMaterial
    private lateinit var sicurezzaInterruttore: SwitchMaterial
    private lateinit var esitoAggiorna: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titoloTesta.text = getString(R.string.impostazioni)
        val v = layoutInflater.inflate(R.layout.impostazioni_card, pagina, false)
        pagina.addView(v)
        carte = v.findViewById(R.id.imp_carte)
        risultati = v.findViewById(R.id.imp_risultati)
        v.findViewById<View>(R.id.imp_agenti)?.setOnClickListener { startActivity(Intent(this, AgentiActivity::class.java)) }
        voceInterruttore = v.findViewById(R.id.imp_voce_interruttore)
        sicurezzaInterruttore = v.findViewById(R.id.imp_sicurezza_interruttore)
        esitoAggiorna = v.findViewById(R.id.imp_profilo_esito)

        // Ogni card o riga apre la sua pagina.
        apre(v, R.id.card_profilo, Gruppo.INFO)
        apre(v, R.id.card_voce, Gruppo.VOCE)
        apre(v, R.id.card_posta, Gruppo.POSTA)
        apre(v, R.id.card_collegamento, Gruppo.COLLEGAMENTO)
        apre(v, R.id.imp_riga_account, Gruppo.ACCOUNT)
        apre(v, R.id.imp_riga_sicurezza, Gruppo.SICUREZZA)
        apre(v, R.id.imp_riga_aspetto, Gruppo.ASPETTO)
        apre(v, R.id.imp_riga_info, Gruppo.INFO)
        v.findViewById<View>(R.id.imp_riga_rifai).setOnClickListener { Tema.apri(this, StatoConfigurazione.intento(this)) }

        // I collegamenti dentro le card.
        v.findViewById<View>(R.id.imp_link_aggiorna).setOnClickListener { cercaAggiornamenti() }
        v.findViewById<View>(R.id.imp_link_licenze).setOnClickListener { Tema.apri(this, Intent(this, LicenzeActivity::class.java)) }
        v.findViewById<View>(R.id.imp_link_webapp).setOnClickListener { CollegamentoJarvis.apriWebapp(this) }
        v.findViewById<View>(R.id.imp_link_lavori).setOnClickListener { ModuloVpsUi.apri(this) }
        v.findViewById<View>(R.id.imp_link_memoria).setOnClickListener { allineaMemoria() }

        // Sicurezza: l'interruttore mostra se il blocco schermo c'è; toccarlo porta alle impostazioni di Android.
        sicurezzaInterruttore.setOnClickListener {
            sicurezzaInterruttore.isChecked = Sblocco.telefonoProtetto(this)
            apri(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    /** Il campo di ricerca sotto il titolo, fisso mentre la pagina scorre. */
    override fun sottoTesta(radice: LinearLayout) {
        val c = this
        cerca = EditText(c).apply {
            hint = "Cerca un'impostazione…"
            background = ContextCompat.getDrawable(c, R.drawable.bg_campo)
            minHeight = Mattoni.dp(c, 48)
            setPadding(Mattoni.dp(c, 16), 0, Mattoni.dp(c, 16), 0)
            setTextColor(Mattoni.col(c, R.color.jarvis_testo))
            setHintTextColor(Mattoni.col(c, R.color.jarvis_testo_tenue))
            textSize = 15f
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_cerca, 0, 0, 0)
            compoundDrawablePadding = Mattoni.dp(c, 8)
            compoundDrawablesRelative[0]?.setTint(Mattoni.col(c, R.color.jarvis_testo_tenue))
            contentDescription = "Cerca un'impostazione"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) { mostraRicerca(s?.toString().orEmpty()) }
            })
        }
        cerca.visibility = View.GONE // Boss, 2026-10-09: niente campo di ricerca nelle impostazioni
        radice.addView(cerca, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Mattoni.dp(c, 16), Mattoni.dp(c, 8), Mattoni.dp(c, 16), Mattoni.dp(c, 4))
        })
    }

    override fun onResume() {
        super.onResume()
        aggiornaCarte()
    }

    private fun apre(radice: View, id: Int, g: Gruppo) {
        radice.findViewById<View>(id).apply {
            setOnClickListener { Tema.apri(this@ImpostazioniActivity, SchedaActivity.intento(this@ImpostazioniActivity, g.chiave)) }
            if (this is com.google.android.material.card.MaterialCardView) Movimento.pressione(this)
        }
    }

    /** In ogni card una riga vera di adesso (dove si legge senza aspettare). */
    private fun aggiornaCarte() {
        val cfg = runCatching { ConfigPersonale.di(this) }.getOrNull()
        val vista = window.decorView

        // Profilo
        vista.findViewById<TextView>(R.id.imp_profilo_titolo).text = getString(R.string.imp_profilo, BuildConfig.VERSION_NAME)
        mostraEsitoAggiorna(AggiornamentiJBoss.ultimoEsito(this))
        vista.findViewById<View>(R.id.card_profilo).contentDescription = "JBoss ${BuildConfig.VERSION_NAME}. Informazioni e licenze"

        // Voce
        val acceso = jarvisAcceso(JarvisService.presenza(this))
        val statoVoce = buildString {
            append(if (acceso) "acceso" else "spento")
            cfg?.let { append(" · fine frase ").append(secondi(it.fineFraseS)).append(" s") }
            append(if (cfg?.filtroImpronta == true) " · solo la mia voce" else "")
        }
        vista.findViewById<TextView>(R.id.imp_voce_stato).text = statoVoce
        vista.findViewById<View>(R.id.card_voce).contentDescription = "Voce. $statoVoce"
        voceInterruttore.setOnCheckedChangeListener(null)
        voceInterruttore.isChecked = acceso
        voceInterruttore.setOnCheckedChangeListener { _, si ->
            PannelloVoce.accendiJBoss(this, si) { aggiornaCarte() }
            dopo(400) { aggiornaCarte() }
        }

        // Collegamento Jarvis
        val statoColl = ModuloVps.statoTesto(this)
        vista.findViewById<TextView>(R.id.imp_coll_stato).text = statoColl
        Tema.pallino(vista.findViewById(R.id.imp_coll_pallino), Mattoni.col(this, when (statoColl) {
            "collegata" -> R.color.spia_verde
            "modulo spento" -> R.color.spia_grigio
            "mi collego…", "a riposo" -> R.color.spia_giallo
            else -> R.color.spia_rosso
        }))
        vista.findViewById<View>(R.id.card_collegamento).contentDescription = "Collegamento Jarvis. $statoColl"

        // Sicurezza
        val protetto = Sblocco.telefonoProtetto(this)
        vista.findViewById<TextView>(R.id.imp_sicurezza_stato).text = "blocco schermo " + if (protetto) "✓" else "da impostare"
        sicurezzaInterruttore.isChecked = protetto
        sicurezzaInterruttore.contentDescription = "Blocco schermo " + if (protetto) "impostato" else "da impostare"

        // Aspetto
        vista.findViewById<TextView>(R.id.imp_aspetto_stato).text = "tema " +
            when (Prefs.getTema(this)) { Prefs.TEMA_CHIARO -> "chiaro"; Prefs.TEMA_SCURO -> "scuro"; else -> "come il telefono" } +
            if (Prefs.isAnimazioniRidotte(this)) " · animazioni ridotte" else ""

        // Informazioni
        vista.findViewById<TextView>(R.id.imp_info_stato).text = "JBoss ${BuildConfig.VERSION_NAME} · licenze"

        // Postino e mail: la cassaforte si legge fuori dal thread principale.
        inSfondo({ runCatching { Cassaforte.di(this).mail().size }.getOrDefault(0) }) { r ->
            val n = r.getOrDefault(0)
            vista.findViewById<TextView>(R.id.imp_posta_numero).text = n.toString()
            vista.findViewById<View>(R.id.card_posta).contentDescription =
                "Postino e mail. " + (if (n == 1) "1 casella" else "$n caselle") + " · Gmail"
        }
    }

    private fun mostraEsitoAggiorna(esito: String?) {
        esitoAggiorna.text = esito.orEmpty()
        esitoAggiorna.visibility = if (esito.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** «Aggiorna» della card profilo: lo stesso controllo di «Cerca aggiornamenti di JBoss» (pagina Collegamento). */
    private fun cercaAggiornamenti() {
        mostraEsitoAggiorna("Controllo…")
        inSfondo({ AggiornamentiJBoss.controllaOra(this) }) { r ->
            mostraEsitoAggiorna(r.getOrDefault("Controllo aggiornamenti non riuscito: riprova."))
        }
    }

    /** «Memoria» della card Collegamento: lo stesso «Allinea la memoria adesso» della pagina Collegamento. */
    private fun allineaMemoria() {
        inSfondo({ CollegamentoJarvis.allineaMemoria(this) }) { r ->
            val e = r.getOrNull()
            avviso(if (e == null) "Non riuscito: collegamento spento o niente rete" else "Memoria allineata: ${e.voci.size} regole, ${e.conflitti.size} da decidere")
            aggiornaCarte()
        }
    }

    /** Una riga dei risultati della ricerca: icona del gruppo, titolo, «in <gruppo>», freccia. */
    private fun riga(g: Gruppo, titolo: String, sotto: String): View {
        val c = this
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Mattoni.dp(c, 64)
            background = ContextCompat.getDrawable(c, R.drawable.bg_riga)
            isClickable = true; isFocusable = true
            setPadding(Mattoni.dp(c, 8), 0, Mattoni.dp(c, 4), 0)
            setOnClickListener { Tema.apri(this@ImpostazioniActivity, SchedaActivity.intento(this@ImpostazioniActivity, g.chiave)) }
            Movimento.pressione(this)
        }
        r.addView(ImageView(c).apply {
            setImageResource(icona(g))
            setColorFilter(Mattoni.col(c, R.color.jarvis_accento))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Mattoni.dp(c, 24), Mattoni.dp(c, 24)))
        val testi = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        testi.addView(TextView(c).apply { text = titolo; setTextColor(Mattoni.col(c, R.color.jarvis_testo)); textSize = 16f })
        testi.addView(TextView(c).apply { text = sotto; setTextColor(Mattoni.col(c, R.color.jarvis_testo_tenue)); textSize = 13f; maxLines = 2 })
        r.addView(testi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Mattoni.dp(c, 16) })
        r.addView(ImageView(c).apply {
            setImageResource(R.drawable.ic_avanti)
            setColorFilter(Mattoni.col(c, R.color.jarvis_testo_tenue))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Mattoni.dp(c, 20), Mattoni.dp(c, 20)))
        r.contentDescription = "$titolo. $sotto"
        return r
    }

    private fun icona(g: Gruppo): Int = when (g) {
        Gruppo.VOCE -> R.drawable.ic_mic
        Gruppo.POSTA -> R.drawable.ic_invia
        Gruppo.COLLEGAMENTO -> R.drawable.ic_monitor
        Gruppo.ACCOUNT -> R.drawable.ic_agenti
        Gruppo.SICUREZZA -> R.drawable.ic_lucchetto
        Gruppo.ASPETTO -> R.drawable.ic_ruota
        Gruppo.INFO -> R.drawable.ic_altro
    }

    private fun mostraRicerca(testo: String) {
        val trovate = IndiceImpostazioni.cerca(testo)
        val cercando = testo.trim().length >= 2
        carte.visibility = if (cercando) View.GONE else View.VISIBLE
        risultati.visibility = if (cercando) View.VISIBLE else View.GONE
        risultati.removeAllViews()
        if (!cercando) return
        if (trovate.isEmpty()) { risultati.addView(Mattoni.nota(this, "Niente con «${testo.trim()}».")); return }
        for (v in trovate) risultati.addView(riga(v.gruppo, v.titolo, "in " + v.gruppo.titolo))
    }

    companion object {
        // Lo scarico dura minuti: vive fuori dalla Activity, così girare il
        // telefono o uscire dalla schermata non lo ferma e non ne parte un secondo.
        @Volatile private var filo: Thread? = null
        @Volatile private var ultimoEsitoModelli: String? = null

        private val DA_SCARICARE = listOf(Modello.WHISPER_SMALL, Modello.ERES2NET)

        fun scaricando(): Boolean = filo?.isAlive == true

        /** Solo una rete che non si paga a consumo (anche per la procedura guidata). */
        fun reteLibera(c: Context): Boolean {
            val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        /**
         * 0.4.0: la procedura guidata (configura/PannelloModelli) avvia lo STESSO scarico di questa schermata:
         * un filo solo, stessa barra (StatoJarvis.scaricoPercento). null = partito o già in corso, altrimenti il motivo.
         */
        fun scaricaDaFuori(context: Context): String? {
            val app = context.applicationContext
            if (scaricando()) return null
            if (DA_SCARICARE.all { Inventario.modelli(app).presente(it) }) return app.getString(R.string.modelli_gia_presenti)
            if (!reteLibera(app)) return app.getString(R.string.serve_wifi)
            ultimoEsitoModelli = null
            avvia(app)
            return null
        }

        /** L'ultimo esito dello scarico in parole (null = niente da dire). */
        fun esitoModelli(): String? = ultimoEsitoModelli

        fun annullaScarico() {
            filo?.interrupt()
        }

        private fun avvia(app: Context) {
            if (scaricando()) return
            val t = Thread({ scarica(app) }, "jarvis-modelli")
            filo = t
            t.start()
        }

        /**
         * Whisper e poi ERes2Net, con una sola barra. Il totale è quello dei due
         * download: l'archivio di Whisper si chiude prima della fine (i file che
         * servono vengono prima), quindi la barra salta avanti quando finisce.
         */
        private fun scarica(app: Context) {
            val modelli = Inventario.modelli(app)
            val totale = DA_SCARICARE.sumOf { it.byteDaScaricare ?: 0L }
            var prima = 0L
            StatoJarvis.aggiorna { it.copy(scaricoPercento = 0) }
            var esito: EsitoScarico = EsitoScarico.Fatto
            for (m in DA_SCARICARE) {
                val base = prima
                esito = modelli.scarica(m) { fatti, _ ->
                    val p = percentuale(base + fatti, totale)
                    if (p != null && p != StatoJarvis.corrente.scaricoPercento) {
                        StatoJarvis.aggiorna { it.copy(scaricoPercento = p) }
                    }
                }
                if (esito != EsitoScarico.Fatto) break
                prima += m.byteDaScaricare ?: 0L
            }
            ultimoEsitoModelli = when (val e = esito) {
                EsitoScarico.Fatto -> null
                EsitoScarico.Annullato -> "annullato: tocca di nuovo per riprendere (i file finiti restano)"
                is EsitoScarico.Errore -> "non riuscito: ${e.motivo}. Se si ripete: «Importa da cartella» qui sotto"
            }
            val whisper = modelli.presente(Modello.WHISPER_SMALL)
            val byte = Inventario.byteWhisper(app)
            StatoJarvis.aggiorna {
                it.copy(scaricoPercento = null, whisperPresente = whisper, whisperByte = byte)
            }
            filo = null
        }
    }
}

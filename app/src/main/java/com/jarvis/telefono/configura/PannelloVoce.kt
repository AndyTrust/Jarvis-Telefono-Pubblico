package com.jarvis.telefono.configura

import android.Manifest
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jarvis.telefono.JarvisService
import com.jarvis.telefono.Permessi
import com.jarvis.telefono.Prefs
import com.jarvis.telefono.R
import com.jarvis.telefono.cassaforte.Altro
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.jarvisAcceso
import com.jarvis.telefono.nucleo.ConfigPersonale
import com.jarvis.telefono.rigaImpronta
import com.jarvis.telefono.rigaWhisper
import com.jarvis.telefono.secondi
import com.jarvis.telefono.testoBatteria
import com.jarvis.telefono.voce.SceltaTrascrittore
import com.jarvis.telefono.voce.StatoJarvis

/**
 * Impostazioni → Voce (0.6.1, Boss 08/10: «voce regolabile dall'app: sì»; layout approvato). Ogni interruttore agisce
 * subito, senza «Salva»: il valore va nella configurazione del telefono ([ConfigPersonale.salva], la stessa che legge
 * la voce) o nelle preferenze, e JarvisService lo usa dal prossimo ascolto ([JarvisService.applicaConfigurazione]),
 * senza riavvio. Il numero WhatsApp sta nella cassaforte e si vede solo nelle ultime 3 cifre.
 */
class PannelloVoce(a: BaseConfigura) : Pannello(a) {
    private lateinit var acceso: SwitchMaterial
    private lateinit var parola: SwitchMaterial
    private lateinit var breve: SwitchMaterial
    private lateinit var impronta: SwitchMaterial
    private lateinit var fine: TextView
    private lateinit var volume: SeekBar
    private lateinit var volumeTesto: TextView
    private lateinit var posta: ChipGroup
    private lateinit var trascrittore: ChipGroup
    private lateinit var whatsapp: Mattoni.Riga
    private lateinit var stato: TextView
    private var smetti: (() -> Unit)? = null
    private var fineAttuale = 1.5
    private val idPosta = HashMap<String, Int>()
    private val idTrascrittore = HashMap<String, Int>()

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        dentro.addView(Mattoni.nota(c, c.getString(R.string.spiega_configurazione)))

        val b1 = Mattoni.scheda(c)
        b1.addView(Mattoni.sezione(c, "ASCOLTO"))
        acceso = interruttore("JBoss acceso", "Spento: niente ascolto e niente risposte a voce.")
        parola = interruttore("Ascolta «Hey Boss» sempre", "«Hey Boss» e «Hey JBoss». Spento: si parla solo col tasto Parla.")
        breve = interruttore("Risposte brevi a voce", "A voce solo l'esito, in due frasi. A schermo resta tutto.")
        impronta = interruttore("Solo la mia voce", "Le frasi di altre voci si scartano. Si impara dalle frasi dette col tasto.")
        listOf(acceso, parola, breve, impronta).forEach { b1.addView(it.parent as View) }

        // Fine frase: [–] 1,5 s [+]
        val rigaFine = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Mattoni.dp(c, 56) }
        val etichetta = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        etichetta.addView(TextView(c).apply { text = "Fine frase"; setTextColor(Mattoni.col(c, R.color.jarvis_testo)); textSize = 15f })
        etichetta.addView(TextView(c).apply { text = "Il silenzio che chiude la frase (da 1 a 3 secondi)."; setTextColor(Mattoni.col(c, R.color.jarvis_testo_tenue)); textSize = 13f })
        rigaFine.addView(etichetta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rigaFine.addView(tastino("–", "Fine frase più corta") { cambiaFine(-1) })
        fine = TextView(c).apply {
            setTextColor(Mattoni.col(c, R.color.jarvis_testo)); textSize = 15f; gravity = Gravity.CENTER
            minWidth = Mattoni.dp(c, 56)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        rigaFine.addView(fine)
        rigaFine.addView(tastino("+", "Fine frase più lunga") { cambiaFine(+1) })
        b1.addView(rigaFine)

        // Volume dei segnali
        val rigaVol = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Mattoni.dp(c, 56) }
        rigaVol.addView(TextView(c).apply { text = "Volume dei segnali"; setTextColor(Mattoni.col(c, R.color.jarvis_testo)); textSize = 15f })
        volume = SeekBar(c).apply {
            max = 10
            contentDescription = "Volume dei segnali"
            minimumHeight = Mattoni.dp(c, 48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, utente: Boolean) { volumeTesto.text = "${p * 10}%" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { salva("volume_segnali" to RegoleVoce.volumeDaPercento(s.progress * 10)) }
            })
        }
        rigaVol.addView(volume, LinearLayout.LayoutParams(0, Mattoni.dp(c, 48), 1f).apply { marginStart = Mattoni.dp(c, 8) })
        volumeTesto = TextView(c).apply { setTextColor(Mattoni.col(c, R.color.jarvis_testo)); textSize = 15f; minWidth = Mattoni.dp(c, 48); gravity = Gravity.END }
        rigaVol.addView(volumeTesto)
        b1.addView(rigaVol)
        stato = Mattoni.nota(c, "", sopra = 8)
        b1.addView(stato)
        dentro.addView(b1)

        val b2 = Mattoni.scheda(c)
        b2.addView(Mattoni.sezione(c, "POSTA E WHATSAPP"))
        b2.addView(Mattoni.testo(c, "App della posta"))
        posta = gruppo(RegoleVoce.APP_POSTA, idPosta) { k -> salva("app_mail" to k) }
        b2.addView(posta)
        b2.addView(Mattoni.nota(c, "Dove JBoss prepara le mail. Gmail si usa comunque se lo nomini nella frase."))
        whatsapp = Mattoni.Riga(c, "WhatsApp a me stesso", "", "Cambia") { cambiaNumero() }
        b2.addView(whatsapp.vista)
        dentro.addView(b2)

        val b3 = Mattoni.scheda(c)
        b3.addView(Mattoni.sezione(c, "CHI SCRIVE QUELLO CHE DICO"))
        trascrittore = gruppo(RegoleVoce.TRASCRITTORI, idTrascrittore) { k ->
            SceltaTrascrittore.imposta(a, k)
            android.util.Log.i("JarvisConfig", "trascrittore scelto dall'app: $k")
        }
        b3.addView(trascrittore)
        b3.addView(Mattoni.nota(c, "Google è più preciso; se non c'è o non sente parole, scrive il telefono da solo, anche senza rete. Vale dalla prossima frase."))
        dentro.addView(b3)

        smetti = StatoJarvis.osserva { scriviStato() }
        aggiorna()
    }

    override fun chiudi() {
        smetti?.invoke(); smetti = null
    }

    /** Rilegge tutto (anche al ritorno sulla pagina): gli interruttori dicono sempre com'è adesso. */
    override fun aggiorna() {
        val cfg = ConfigPersonale.ricarica(a)
        metti(acceso, jarvisAcceso(JarvisService.presenza(a)))
        metti(parola, Prefs.isParolaAttiva(a))
        metti(breve, Prefs.isVoceSintetica(a))
        metti(impronta, cfg.filtroImpronta)
        fineAttuale = cfg.fineFraseS.coerceIn(RegoleVoce.FINE_MIN, RegoleVoce.FINE_MAX)
        fine.text = "${secondi(fineAttuale)} s"
        fine.contentDescription = "Fine frase ${secondi(fineAttuale)} secondi"
        val vp = RegoleVoce.volumePercento(cfg.volumeSegnali)
        volume.progress = vp / 10
        volumeTesto.text = "$vp%"
        aggiornando = true
        idPosta[cfg.appMail.ifEmpty { "predefinita" }]?.let { if (posta.checkedChipId != it) posta.check(it) }
        idTrascrittore[SceltaTrascrittore.di(a)]?.let { if (trascrittore.checkedChipId != it) trascrittore.check(it) }
        aggiornando = false
        whatsapp.aggiorna(
            if (cfg.whatsappMe.isNotEmpty()) StatoPasso.FATTO else StatoPasso.DA_FARE,
            spiega = RegoleVoce.numeroNascosto(cfg.whatsappMe) + ". Sta nella cassaforte cifrata.",
        )
        collega()
        scriviStato()
    }

    private fun scriviStato() {
        // 09/10: l'interruttore segue lo stato vero anche mentre la pagina è aperta (pausa o spegnimento dalla notifica).
        if (::acceso.isInitialized) { metti(acceso, jarvisAcceso(JarvisService.presenza(a))); acceso.setOnCheckedChangeListener { _, si -> accendi(si) } }
        val s = StatoJarvis.corrente
        stato.text = "Impronta: ${rigaImpronta(s).testo}\nVoce senza rete: ${rigaWhisper(s).testo}\nBatteria: ${testoBatteria(s)}"
    }

    // ------------------------------------------------------------ azioni

    private fun collega() {
        acceso.setOnCheckedChangeListener { _, si -> accendi(si) }
        parola.setOnCheckedChangeListener { _, si ->
            Prefs.setParolaAttiva(a, si)
            salva("ascolto_sempre_acceso" to si)
        }
        breve.setOnCheckedChangeListener { _, si ->
            Prefs.setVoceSintetica(a, si)
            android.util.Log.i("JarvisConfig", "risposte brevi a voce: ${if (si) "sì" else "no"}")
        }
        impronta.setOnCheckedChangeListener { _, si -> salva("filtro_impronta" to si) }
    }

    private fun accendi(si: Boolean) = accendiJBoss(a, si) { aggiorna() }

    companion object {
        /**
         * Accende o spegne JBoss (servizio della voce). Lo stesso gesto della pagina Voce e dell'interruttore della
         * card Voce nell'indice delle Impostazioni: un posto solo. [dopoRifiuto] ridisegna chi ha chiamato se il
         * microfono viene negato.
         */
        fun accendiJBoss(a: BaseConfigura, si: Boolean, dopoRifiuto: () -> Unit) {
            if (si) {
                // 09/10: «JBoss acceso» vuol dire anche voce accesa: se era in pausa o spenta dalla notifica, riprende.
                JarvisService.instance?.azioneVoce(com.jarvis.telefono.voce.AzioneVoce.RIPRENDI, "impostazioni")
                    ?: Prefs.setModoVoce(a, com.jarvis.telefono.voce.ModoVoce.ACCESO)
                if (!Permessi.microfono(a)) {
                    a.chiediPermesso(Manifest.permission.RECORD_AUDIO) { ok ->
                        if (ok) accendiJBoss(a, true, dopoRifiuto) else { a.avviso("Senza microfono JBoss non sente."); dopoRifiuto() }
                    }
                    return
                }
                runCatching { ContextCompat.startForegroundService(a, Intent(a, JarvisService::class.java)) }
                Prefs.setAttivo(a, true)
            } else {
                a.startService(Intent(a, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
                Prefs.setAttivo(a, false)
            }
        }
    }

    private fun cambiaFine(passi: Int) {
        val nuovo = RegoleVoce.fineFrase(fineAttuale, passi)
        if (nuovo == fineAttuale) return
        fineAttuale = nuovo
        fine.text = "${secondi(nuovo)} s"
        fine.contentDescription = "Fine frase ${secondi(nuovo)} secondi"
        salva("fine_frase_s" to nuovo)
    }

    /** Scrive fuori dal thread principale, poi la voce la usa dal prossimo ascolto. */
    private fun salva(vararg valori: Pair<String, Any?>) {
        val m = valori.toMap()
        a.inSfondo({ ConfigPersonale.salva(a, m) }) { r ->
            if (r.isFailure) { a.avviso("Non salvato: riprova."); return@inSfondo }
            JarvisService.instance?.applicaConfigurazione()
            aggiorna()
        }
    }

    private fun cambiaNumero() {
        val campo = EditText(a).apply {
            hint = "Numero con il prefisso, per esempio 39…"
            inputType = InputType.TYPE_CLASS_PHONE
            minHeight = Mattoni.dp(a, 48)
        }
        val cornice = LinearLayout(a).apply { setPadding(Mattoni.dp(a, 20), Mattoni.dp(a, 8), Mattoni.dp(a, 20), 0); addView(campo, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(a)
            .setTitle("WhatsApp a me stesso")
            .setMessage("Il numero va nella cassaforte cifrata del telefono. A schermo si vedranno solo le ultime 3 cifre.")
            .setView(cornice)
            .setPositiveButton("Salva") { _, _ ->
                val n = ConfigPersonale.soloNumero(campo.text.toString())
                if (n.isEmpty()) { a.avviso("Non sembra un numero di telefono: niente salvato."); return@setPositiveButton }
                a.inSfondo({
                    Cassaforte.di(a).salva(Altro(Altro.WHATSAPP_ME, "Il mio numero WhatsApp", n, segreto = true))
                    RegistroAccessi.di(a).segna("salvato", "numero WhatsApp personale")
                    // Il numero non resta in chiaro nel file della configurazione.
                    ConfigPersonale.salva(a, mapOf("whatsapp_me" to null))
                }) { r ->
                    if (r.isFailure) a.avviso("Non salvato: riprova.") else a.avviso("Numero salvato nella cassaforte.")
                    aggiorna()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ------------------------------------------------------------ mattoni della pagina

    /** Un interruttore da 48 dp con la spiegazione sotto; restituisce lo switch (il contenitore è il suo parent). */
    private fun interruttore(titolo: String, spiega: String): SwitchMaterial {
        val c = a
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Mattoni.dp(c, 4) }
        }
        val sw = SwitchMaterial(c).apply {
            text = titolo
            textSize = 15f
            minHeight = Mattoni.dp(c, 48)
            setTextColor(Mattoni.col(c, R.color.jarvis_testo))
        }
        box.addView(sw, LinearLayout.LayoutParams(-1, -2))
        box.addView(TextView(c).apply { text = spiega; setTextColor(Mattoni.col(c, R.color.jarvis_testo_tenue)); textSize = 13f })
        return sw
    }

    private fun metti(sw: SwitchMaterial, v: Boolean) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = v
    }

    private fun tastino(t: String, descr: String, f: () -> Unit) = MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = t
        textSize = 18f
        contentDescription = descr
        minWidth = Mattoni.dp(a, 48); minimumWidth = Mattoni.dp(a, 48); minHeight = Mattoni.dp(a, 48)
        insetTop = 0; insetBottom = 0
        setPadding(0, 0, 0, 0)
        cornerRadius = Mattoni.dp(a, 24)
        strokeColor = ContextCompat.getColorStateList(a, R.color.jarvis_accento)
        setTextColor(Mattoni.col(a, R.color.jarvis_accento))
        setOnClickListener { f() }
    }

    /** Scelta singola a chip; [ids] si riempie chiave → id del chip. */
    private fun gruppo(scelte: List<Pair<String, String>>, ids: HashMap<String, Int>, scelto: (String) -> Unit): ChipGroup =
        ChipGroup(a).apply {
            isSingleSelection = true
            isSelectionRequired = true
            for ((k, nome) in scelte) {
                val chip = Mattoni.chip(a, nome).apply { id = View.generateViewId(); setEnsureMinTouchTargetSize(true) }
                ids[k] = chip.id
                addView(chip)
            }
            setOnCheckedStateChangeListener { _, sel ->
                val id = sel.firstOrNull() ?: return@setOnCheckedStateChangeListener
                val k = ids.entries.firstOrNull { it.value == id }?.key ?: return@setOnCheckedStateChangeListener
                if (!aggiornando) scelto(k)
            }
        }

    private var aggiornando = false
}

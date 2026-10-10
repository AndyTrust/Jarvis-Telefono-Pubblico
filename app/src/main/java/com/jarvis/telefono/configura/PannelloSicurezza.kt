package com.jarvis.telefono.configura

import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.jarvis.telefono.R
import com.jarvis.telefono.cassaforte.AccessoSito
import com.jarvis.telefono.cassaforte.AccountMail
import com.jarvis.telefono.cassaforte.Altro
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.Cervello
import com.jarvis.telefono.cassaforte.Google
import com.jarvis.telefono.cassaforte.Sblocco
import com.jarvis.telefono.cassaforte.TestiGuida
import com.jarvis.telefono.cassaforte.Voce
import com.jarvis.telefono.cassaforte.Vps
import com.jarvis.telefono.vps.ConfigVps

/**
 * Impostazioni → Sicurezza: l'elenco dei segreti (nome e stato, mai il valore), «Mostra» solo con impronta o PIN
 * (30 secondi), cancella, esporta cifrato con una frase, importa, svuota, registro degli accessi.
 */
class PannelloSicurezza(a: BaseConfigura, private val parte: Parte = Parte.SICUREZZA) : Pannello(a) {
    /**
     * 0.6.1 (Impostazioni a gruppi): [Parte.ACCESSI] in «Account e accessi» (solo gli accessi dei siti),
     * [Parte.SICUREZZA] in «Sicurezza» (blocco schermo, gli altri segreti, copia cifrata, registro).
     */
    enum class Parte { SICUREZZA, ACCESSI }

    override val conSegreti = true
    private var blocco: Mattoni.Riga? = null
    private lateinit var elenco: LinearLayout
    private var registro: LinearLayout? = null
    private lateinit var esito: TextView
    private lateinit var esitoSito: TextView

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        if (parte == Parte.ACCESSI) {
            costruisciAccessi(dentro)
            aggiorna()
            return
        }
        dentro.addView(Mattoni.nota(c, TestiGuida.SICUREZZA))
        val b0 = Mattoni.scheda(c)
        blocco = Mattoni.Riga(c, "Blocco schermo", "", "Imposta") { a.apri(Intent(Settings.ACTION_SECURITY_SETTINGS)) }.also { b0.addView(it.vista) }
        dentro.addView(b0)

        val b1 = Mattoni.scheda(c)
        b1.addView(Mattoni.sezione(c, "SEGRETI NEL TELEFONO"))
        elenco = Mattoni.colonna(c)
        b1.addView(elenco)
        b1.addView(Mattoni.nota(c, TestiConfigura.SICUREZZA_MOSTRA))
        dentro.addView(b1)


        val b2 = Mattoni.scheda(c)
        b2.addView(Mattoni.sezione(c, "COPIA CIFRATA"))
        b2.addView(Mattoni.nota(c, "Per cambiare telefono: «Esporta» crea un file cifrato con una frase che scegli tu. Sul telefono nuovo «Importa» con la stessa frase. Senza la frase il file non si apre."))
        esito = Mattoni.esito(c)
        b2.addView(Mattoni.fila(c, Mattoni.bottone(c, "Esporta cifrato", pieno = false) { esporta() }, Mattoni.bottone(c, "Importa", pieno = false) { importa() }))
        b2.addView(esito)
        b2.addView(Mattoni.bottone(c, "Svuota la cassaforte", pieno = false) { svuota() }.apply {
            setTextColor(Mattoni.col(c, R.color.spia_rosso))
            strokeColor = androidx.core.content.ContextCompat.getColorStateList(c, R.color.spia_rosso)
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = Mattoni.dp(c, 12)
        })
        dentro.addView(b2)

        val b3 = Mattoni.scheda(c)
        b3.addView(Mattoni.sezione(c, "REGISTRO DEGLI ACCESSI"))
        registro = Mattoni.colonna(c).also { b3.addView(it) }
        dentro.addView(b3)
        aggiorna()
    }

    /** 0.5.0: «Accessi siti» (link, utente, password, note), solo email e password. Dalla 0.6.1 in Account e accessi. */
    private fun costruisciAccessi(dentro: LinearLayout) {
        val c = a
        val bs = Mattoni.scheda(c)
        bs.addView(Mattoni.sezione(c, "ACCESSI SITI"))
        bs.addView(Mattoni.nota(c, "Link, utente, password e note dei siti. Solo accessi con email e password: niente «Accedi con Google» o altri social. La password si vede solo con impronta o PIN."))
        elenco = Mattoni.colonna(c)
        bs.addView(elenco)
        esitoSito = Mattoni.esito(c)
        bs.addView(Mattoni.bottone(c, "+ Aggiungi accesso", pieno = false) { aggiungiSito() })
        bs.addView(esitoSito)
        dentro.addView(bs)
    }

    override fun aggiorna() {
        val c = a
        val bl = Sblocco.telefonoProtetto(c)
        blocco?.aggiorna(if (bl) StatoPasso.FATTO else StatoPasso.DA_FARE, if (bl) "Fatto" else "Imposta", !bl,
            if (bl) "c'è: i segreti si mostrano con impronta o PIN" else "manca: senza, i segreti non si possono mostrare")
        elenco.removeAllViews()
        val tutte = runCatching { Cassaforte.di(c).elenco() }.getOrDefault(emptyList())
        // Ogni voce in un posto solo: gli accessi dei siti in «Account e accessi», il resto qui.
        val voci = if (parte == Parte.ACCESSI) tutte.filterIsInstance<AccessoSito>() else tutte.filter { it !is AccessoSito }
        if (voci.isEmpty()) elenco.addView(Mattoni.nota(c, if (parte == Parte.ACCESSI) "Nessun accesso salvato." else "La cassaforte è vuota."))
        voci.forEach { elenco.addView(riga(it)) }
        val reg = registro ?: return
        reg.removeAllViews()
        val righe = RegistroAccessi.di(c).ultime(15)
        if (righe.isEmpty()) reg.addView(Mattoni.nota(c, "Nessun accesso registrato."))
        righe.forEach { reg.addView(Mattoni.nota(c, RegistroAccessi.testo(it), sopra = 2)) }
    }

    /** Nome e genere sopra, «Mostra» e «Cancella» sotto: su uno schermo stretto il nome non si taglia. */
    private fun riga(v: Voce): LinearLayout {
        val c = a
        val r = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Mattoni.dp(c, 8), 0, Mattoni.dp(c, 8)) }
        val segreto = segretoDi(v)
        r.addView(TextView(c).apply { text = nome(v); textSize = 15f; setTextColor(Mattoni.col(c, R.color.jarvis_testo)); maxLines = 2 })
        r.addView(TextView(c).apply {
            text = genere(v) + " · " + if (segreto.isNullOrEmpty()) "nessun segreto" else "segreto presente"
            textSize = 13f; setTextColor(Mattoni.col(c, R.color.jarvis_testo_tenue))
        })
        val cancella = Mattoni.bottone(c, "Cancella", pieno = false) { cancella(v) }
        if (!segreto.isNullOrEmpty()) {
            val mostra = Mattoni.bottone(c, "Mostra", pieno = false) {
                a.chiediSblocco("Mostra «${nome(v)}»") {
                    RegistroAccessi.di(a).segna("mostrato", nome(v))
                    mostraValore(a, nome(v), segreto)
                    aggiorna()
                }
            }.apply { contentDescription = "Mostra il segreto di ${nome(v)}" }
            r.addView(Mattoni.fila(c, mostra, cancella, sopra = 6))
        } else r.addView(Mattoni.fila(c, cancella, android.view.View(c), sopra = 6))
        cancella.contentDescription = "Cancella ${nome(v)}"
        return r
    }

    private fun cancella(v: Voce) {
        AlertDialog.Builder(a)
            .setTitle("Cancellare «${nome(v)}»?")
            .setMessage("Si cancella dal telefono. Se era stato mandato alla VPS, lì resta: per toglierlo dalla VPS usa Account mail → Elimina.")
            .setPositiveButton("Cancella") { _, _ ->
                a.chiediSblocco("Conferma la cancellazione") {
                    Cassaforte.di(a).elimina(v.id)
                    if (v is Vps) ConfigVps.invalida()
                    RegistroAccessi.di(a).segna("cancellato", nome(v))
                    aggiorna()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun campoTesto(suggerimento: String, tipo: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(a).apply {
        hint = suggerimento
        inputType = tipo
        minHeight = Mattoni.dp(a, 48)
    }

    /** Un accesso nuovo: si salva solo dopo l'impronta o il PIN, e solo se è email/utente + password. */
    private fun aggiungiSito() {
        val c = a
        val link = campoTesto("Link (es. sito.it/login)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val utente = campoTesto("Email o utente", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pw = campoFrase("Password")
        val note = campoTesto("Note (facoltative)")
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(Mattoni.dp(c, 20), Mattoni.dp(c, 8), Mattoni.dp(c, 20), 0)
            addView(link); addView(utente); addView(pw); addView(note)
        }
        AlertDialog.Builder(c)
            .setTitle("Nuovo accesso sito")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                val l = link.text.toString().trim(); val u = utente.text.toString().trim()
                val p = pw.text.toString(); val n = note.text.toString().trim()
                pw.setText("")
                AccessoSito.valida(l, u, p, n)?.let { Mattoni.mostraEsito(esitoSito, false, it); return@setPositiveButton }
                a.chiediSblocco("Salva l'accesso") {
                    val v = AccessoSito(AccessoSito.idPer(l), l.removePrefix("https://").removePrefix("http://").substringBefore('/'), l, u, p, n)
                    Cassaforte.di(a).salva(v)
                    RegistroAccessi.di(a).segna("salvato accesso", v.etichetta)
                    aggiorna()
                    Mattoni.mostraEsito(esitoSito, true, "Salvato ${v.etichetta}.")
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun campoFrase(suggerimento: String): EditText = EditText(a).apply {
        hint = suggerimento
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        typeface = android.graphics.Typeface.DEFAULT
        minHeight = Mattoni.dp(a, 48)
        if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun esporta() {
        val c = a
        val f1 = campoFrase("Frase (almeno 8 caratteri)")
        val f2 = campoFrase("Ripeti la frase")
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Mattoni.dp(c, 20), Mattoni.dp(c, 8), Mattoni.dp(c, 20), 0); addView(f1); addView(f2) }
        AlertDialog.Builder(c)
            .setTitle("Esporta la cassaforte")
            .setMessage("Scegli una frase che ricordi: senza, il file non si apre. JBoss non la salva.")
            .setView(box)
            .setPositiveButton("Esporta") { _, _ ->
                val a1 = f1.text.toString().toCharArray(); val a2 = f2.text.toString().toCharArray()
                f1.setText(""); f2.setText("")
                Campi.frase(a1, a2)?.let { Mattoni.mostraEsito(esito, false, it); a1.fill(' '); a2.fill(' '); return@setPositiveButton }
                a2.fill(' ')
                a.chiediSblocco("Conferma l'esportazione") {
                    val nome = "jboss-cassaforte-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ITALY).format(java.util.Date()) + ".txt"
                    a.creaFile(nome) { uri ->
                        if (uri == null) { a1.fill(' '); Mattoni.mostraEsito(esito, null, "Esportazione annullata."); return@creaFile }
                        Mattoni.mostraEsito(esito, null, "Cifro (qualche secondo)…")
                        a.inSfondo({
                            val t = try { Cassaforte.di(a).esporta(a1) } finally { a1.fill(' ') }
                            a.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(t.toByteArray()) }
                        }) { r ->
                            if (r.isSuccess) { RegistroAccessi.di(a).segna("esportata", "cassaforte"); aggiorna(); Mattoni.mostraEsito(esito, true, "Esportata. Tieni il file e la frase in due posti diversi.") }
                            else Mattoni.mostraEsito(esito, false, "Esportazione non riuscita.")
                        }
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun importa() {
        a.scegliFile { uri ->
            if (uri == null) return@scegliFile
            val f = campoFrase("Frase dell'esportazione")
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Mattoni.dp(a, 20), Mattoni.dp(a, 8), Mattoni.dp(a, 20), 0); addView(f) }
            AlertDialog.Builder(a)
                .setTitle("Importa nella cassaforte")
                .setMessage("Le voci con lo stesso nome si sostituiscono.")
                .setView(box)
                .setPositiveButton("Importa") { _, _ ->
                    val frase = f.text.toString().toCharArray(); f.setText("")
                    Mattoni.mostraEsito(esito, null, "Apro il file (qualche secondo)…")
                    a.inSfondo({
                        val t = a.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                        try { Cassaforte.di(a).importa(t, frase) } finally { frase.fill(' ') }
                    }) { r ->
                        ConfigVps.invalida()
                        r.onSuccess { n -> RegistroAccessi.di(a).segna("importata", "$n voci"); aggiorna(); Mattoni.mostraEsito(esito, true, "Importate $n voci.") }
                            .onFailure { Mattoni.mostraEsito(esito, false, it.message?.takeIf { m -> "frase" in m || "JBoss" in m || "rovinat" in m } ?: "Importazione non riuscita.") }
                    }
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
    }

    private fun svuota() {
        AlertDialog.Builder(a)
            .setTitle("Svuotare la cassaforte?")
            .setMessage("Si cancellano dal telefono tutte le password, i token e la VPS. Non si torna indietro (salvo un'esportazione).")
            .setPositiveButton("Svuota") { _, _ ->
                a.chiediSblocco("Conferma: svuota la cassaforte") {
                    Cassaforte.di(a).svuota()
                    ConfigVps.invalida()
                    RegistroAccessi.di(a).segna("svuotata", "cassaforte")
                    aggiorna()
                    Mattoni.mostraEsito(esito, true, "Cassaforte vuota.")
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    companion object {
        fun nome(v: Voce): String = when (v) {
            is AccountMail -> v.indirizzo
            is Vps -> "VPS " + v.url.removePrefix("wss://").substringBefore('/')
            is Google -> "Google " + v.account
            is AccessoSito -> v.etichetta.ifBlank { v.link } + " · " + v.utente
            else -> v.etichetta
        }

        fun genere(v: Voce): String = when (v) {
            is AccountMail -> "casella di posta"
            is Cervello -> "cervello"
            is Vps -> "collegamento VPS"
            is Google -> "account Google (nessun segreto)"
            is Altro -> if (v.segreto) "valore segreto" else "valore"
            is AccessoSito -> "accesso sito"
        }

        fun segretoDi(v: Voce): String? = when (v) {
            is AccountMail -> v.password
            is Cervello -> v.token
            is Vps -> v.token
            is Altro -> v.valore
            is AccessoSito -> v.password
            is Google -> null
        }

        /** Il valore per 30 secondi in una finestra (la schermata è già FLAG_SECURE), poi si chiude da sola. */
        fun mostraValore(a: BaseConfigura, titolo: String, valore: String) {
            val tv = TextView(a).apply {
                text = valore
                setTextIsSelectable(true)
                textSize = 16f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Mattoni.col(a, R.color.jarvis_testo))
                setPadding(Mattoni.dp(a, 24), Mattoni.dp(a, 12), Mattoni.dp(a, 24), 0)
            }
            val d = AlertDialog.Builder(a).setTitle(titolo).setView(tv).setPositiveButton("Nascondi", null).show()
            a.dopo(30_000) { if (d.isShowing) d.dismiss() }
        }
    }
}

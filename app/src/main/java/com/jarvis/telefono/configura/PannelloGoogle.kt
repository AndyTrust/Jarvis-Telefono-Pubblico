package com.jarvis.telefono.configura

import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.Google
import com.jarvis.telefono.cassaforte.TestiGuida
import com.jarvis.telefono.ui.Tema

/**
 * Google, detto onestamente (docs/CONFIGURAZIONE.md §5): niente OAuth nell'app, nessuna password di Google. JBoss usa
 * l'account già sul telefono aprendo le app Google; Gmail per il Postino passa dalla password per app.
 *
 * 0.6.1 (default sicuro, Boss non ha ancora risposto): il selettore dell'account è tolto perché l'account scelto non lo
 * leggeva nessuno. Restano due parti, ognuna in un posto solo:
 * - [Parte.ACCOUNT] in Impostazioni → Account e accessi: l'account come informazione (quello scelto in passato, se c'è)
 *   e il link agli account del telefono;
 * - [Parte.APP] in Impostazioni → Postino e mail: le app Google e «Aggiungi Gmail» per il Postino.
 */
class PannelloGoogle(a: BaseConfigura, private val parte: Parte) : Pannello(a) {
    enum class Parte { ACCOUNT, APP }

    private var account: Mattoni.Riga? = null

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        if (parte == Parte.ACCOUNT) {
            val b1 = Mattoni.scheda(c)
            b1.addView(Mattoni.sezione(c, "ACCOUNT GOOGLE"))
            account = Mattoni.Riga(c, "Account Google", "", null, null).also { b1.addView(it.vista) }
            b1.addView(Mattoni.nota(c, TestiConfigura.GOOGLE_ONESTO))
            b1.addView(Mattoni.bottone(c, "Vedi gli account del telefono", pieno = false) { a.apri(Intent(Settings.ACTION_SYNC_SETTINGS)) })
            dentro.addView(b1)
            aggiorna()
            return
        }
        val b2 = Mattoni.scheda(c)
        b2.addView(Mattoni.sezione(c, "APP GOOGLE"))
        b2.addView(Mattoni.nota(c, "Posta, calendario e file stanno nelle app Google del telefono: JBoss le apre per te («apri Calendar», «cerca in Gmail…»)."))
        val app = listOf("Gmail" to "com.google.android.gm", "Calendar" to "com.google.android.calendar", "Drive" to "com.google.android.apps.docs", "Google" to "com.google.android.googlequicksearchbox")
        app.chunked(2).forEach { coppia ->
            b2.addView(Mattoni.fila(c, *coppia.map { (n, p) -> Mattoni.bottone(c, n, pieno = false) { apriApp(n, p) } }.toTypedArray()))
        }
        dentro.addView(b2)

        val b3 = Mattoni.scheda(c)
        b3.addView(Mattoni.sezione(c, "GMAIL PER IL POSTINO"))
        b3.addView(Mattoni.nota(c, TestiGuida.GOOGLE))
        b3.addView(Mattoni.bottone(c, "Aggiungi Gmail →") { Tema.apri(a, CasellaActivity.conPreset(a, "gmail")) })
        dentro.addView(b3)
    }

    /** Solo informazione: l'account che JBoss usa è quello del telefono; se in passato ne è stato scelto uno, si mostra. */
    override fun aggiorna() {
        val r = account ?: return
        val g = runCatching { Cassaforte.di(a).leggi(Google.ID) as? Google }.getOrNull()
        r.aggiorna(StatoPasso.FATTO, spiega = if (g != null && g.account.isNotBlank()) "quello del telefono · ${g.account}" else "quello già sul telefono")
    }

    private fun apriApp(nome: String, pacchetto: String) {
        val i = a.packageManager.getLaunchIntentForPackage(pacchetto)
        if (i == null) a.avviso("$nome non è installata su questo telefono.") else a.apri(i)
    }
}

package com.jarvis.telefono.configura

import android.content.ClipboardManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.Vps
import com.jarvis.telefono.vps.ConfigVps
import com.jarvis.telefono.vps.ModuloVps

/**
 * «Scansiona QR» e «Incolla» per abbinare la VPS (Cervello e pagina VPS). Il lettore è quello di Google Play
 * Services: JBoss non chiede il permesso della fotocamera e non vede le immagini, riceve solo il testo del QR.
 * Lo scambio codice → token è in [ClienteAbbinamento]; il token va nella Cassaforte e non si mostra.
 */
class Abbinatore(private val a: BaseConfigura, private val esito: TextView, private val fatto: () -> Unit) {

    fun scansiona() {
        // Telefoni senza Google Play Services (Huawei recenti, ROM senza Google): il lettore non esiste.
        // Mai un crash: si dice di usare «Incolla», che fa la stessa cosa a mano.
        runCatching { avviaLettore() }.onFailure { e ->
            android.util.Log.i("JBossConfigura", "lettore QR assente: ${e.javaClass.simpleName}")
            Mattoni.mostraEsito(esito, false, "Su questo telefono manca il lettore di codici di Google: usa «Incolla» con indirizzo e codice.")
        }
    }

    private fun avviaLettore() {
        val opz = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        val lettore = GmsBarcodeScanning.getClient(a, opz)
        lettore.startScan()
            .addOnSuccessListener { b -> esegui(Abbinamento.leggi(b.rawValue.orEmpty())) }
            .addOnCanceledListener { Mattoni.mostraEsito(esito, null, "Scansione annullata. Puoi anche usare «Incolla».") }
            .addOnFailureListener { e ->
                val codice = (e as? MlKitException)?.errorCode
                android.util.Log.i("JBossConfigura", "lettore QR non riuscito: ${e.javaClass.simpleName} codice=$codice")
                if (codice == MlKitException.CANCELLED) {
                    Mattoni.mostraEsito(esito, null, "Scansione annullata. Puoi anche usare «Incolla».")
                } else if (codice == MlKitException.UNAVAILABLE) {
                    runCatching { ModuleInstall.getClient(a).installModules(ModuleInstallRequest.newBuilder().addApi(lettore).build()) }
                    Mattoni.mostraEsito(esito, false, "Il lettore di codici si sta scaricando da Google Play Services: riprova tra un minuto, oppure usa «Incolla».")
                } else {
                    Mattoni.mostraEsito(esito, false, "Il lettore di codici non è partito su questo telefono: usa «Incolla» con indirizzo e codice.")
                }
            }
    }

    fun incolla() {
        val c = a
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Mattoni.dp(c, 20), Mattoni.dp(c, 8), Mattoni.dp(c, 20), 0) }
        val url = Mattoni.campo(c, "Indirizzo (wss://…/phone)", Mattoni.Tipo.INDIRIZZO_WEB)
        val codice = Mattoni.campo(c, "Codice (8 caratteri, es. ABCD-2345)", Mattoni.Tipo.CODICE)
        // Se negli appunti c'è il testo del QR o l'indirizzo, si compila da solo.
        val appunti = runCatching { c.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(c)?.toString() }.getOrNull().orEmpty()
        val gia = ConfigVps.dati(c).url
        url.editText?.setText(when {
            appunti.contains("wss://") || appunti.startsWith(Abbinamento.SCHEMA) -> appunti.trim()
            gia.isNotBlank() -> gia
            else -> "wss://"
        })
        box.addView(Mattoni.nota(c, "Li vedi sul computer, sotto il codice QR di Jarvis."))
        box.addView(url); box.addView(codice)
        AlertDialog.Builder(c)
            .setTitle("Abbina con indirizzo e codice")
            .setView(box)
            .setPositiveButton("Abbina") { _, _ -> esegui(Abbinamento.leggi(Mattoni.valore(url), Mattoni.valore(codice))) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun esegui(r: Result<DatiAbbinamento>) {
        val d = r.getOrElse { Mattoni.mostraEsito(esito, false, it.message ?: "Testo non valido."); return }
        Mattoni.mostraEsito(esito, null, "Mi collego a ${d.host} con il codice ${d.codiceLeggibile}…")
        a.inSfondo({ ClienteAbbinamento().scambia(d) }) { res ->
            when (val e = res.getOrNull()) {
                is EsitoAbbinamento.Fatto -> {
                    val salvato = runCatching { Cassaforte.di(a).salva(Vps(d.url, e.token)) }.isSuccess
                    ConfigVps.invalida()
                    if (salvato) {
                        com.jarvis.telefono.collegamento.CollegamentoJarvis.accendi(a, true)
                        RegistroAccessi.di(a).segna("abbinata la VPS", d.host)
                        Mattoni.mostraEsito(esito, true, "Abbinata a ${d.host}. Il token è nella cassaforte del telefono.")
                        fatto()
                    } else Mattoni.mostraEsito(esito, false, "Abbinata, ma la cassaforte non ha salvato il token: riprova.")
                }
                is EsitoAbbinamento.NonFatto -> Mattoni.mostraEsito(esito, false, e.messaggio)
                null -> Mattoni.mostraEsito(esito, false, "Abbinamento non riuscito.")
            }
        }
    }
}

package com.jarvis.telefono.mani

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.telefono.PhoneActionExecutor
import org.json.JSONObject

/**
 * Banco di prova da ADB (1.2.0): manda all'esecutore lo stesso comando JSON che manda il
 * cervello del telefono, e la risposta finisce nel logcat (tag JarvisProva).
 * Protetto da android.permission.DUMP nel Manifest: lo ha solo la shell di ADB, nessuna app.
 *
 *   adb shell am broadcast -n com.jarvis.telefono/.mani.ProvaAdbReceiver --es comando '{"action":"stato_tecnico"}'
 */
class ProvaAdbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val testo = intent.getStringExtra("comando") ?: return
        val comando = runCatching { JSONObject(testo) }.getOrElse {
            Log.w(PhoneActionExecutor.TAG_PROVA, "comando non JSON: ${it.message}")
            return
        }
        // «risposta»: simula Boss che dice una frase mentre una bozza aspetta.
        if (comando.optString("action") == "risposta_di_boss") {
            Log.i(PhoneActionExecutor.TAG_PROVA, "risposta_di_boss gestita=" + PhoneActionExecutor.rispostaDiBoss(comando.optString("testo")))
            return
        }
        // 1.2.2: «Jarvis» + frase come dalla voce vera (segnale, bolla, poi la stessa strada di
        // dopo Whisper: risposta alla bozza in attesa o user_message alla VPS). Salta solo
        // microfono e trascrizione.
        if (comando.optString("action") == "voce_prova") {
            val s = com.jarvis.telefono.JarvisService.instance
            Log.i(PhoneActionExecutor.TAG_PROVA, "voce_prova: servizio ${if (s != null) "acceso" else "spento"}")
            s?.provaFraseVocale(comando.optString("testo"))
            return
        }
        // 1.2.2: apre una cattura vera del microfono, come il tasto «Ascolta» (per provare il
        // «non ho sentito niente» dopo 10 s di silenzio).
        if (comando.optString("action") == "ascolta_prova") {
            com.jarvis.telefono.JarvisService.instance?.ascolta("prova adb")
            return
        }
        // Jarvis Telefono: la configurazione personale di Boss (scripts/configura-boss.sh). Arriva in
        // base64 (niente virgolette da proteggere nella shell) e si scrive in filesDir/config-boss.json.
        if (comando.optString("action") == "configura") {
            val esito = runCatching {
                val testo = String(android.util.Base64.decode(comando.optString("base64"), android.util.Base64.DEFAULT), Charsets.UTF_8)
                org.json.JSONObject(testo) // deve essere JSON valido
                com.jarvis.telefono.nucleo.ConfigPersonale.file(context).writeText(testo)
                val c = com.jarvis.telefono.nucleo.ConfigPersonale.ricarica(context)
                "configurazione salvata: fine frase ${c.fineFraseS} s, filtro impronta ${c.filtroImpronta}, app mail ${c.appMail.ifEmpty { "predefinita" }}, whatsapp a me ${if (c.whatsappMe.isEmpty()) "no" else "sì"}"
            }.getOrElse { "configurazione NON salvata: ${it.javaClass.simpleName}" }
            Log.i(PhoneActionExecutor.TAG_PROVA, esito)
            com.jarvis.telefono.JarvisService.instance?.let { Log.i(PhoneActionExecutor.TAG_PROVA, "riaccendi Jarvis (Spegni, Accendi) per applicare fine frase e filtro alla voce") }
            return
        }
        // 0.3.1: la cassaforte da ADB, senza valori: solo generi e conteggi; «cassaforte_migra» rifà la migrazione.
        if (comando.optString("action") == "cassaforte_stato") {
            val r = runCatching { com.jarvis.telefono.cassaforte.Cassaforte.di(context).riassunto() }.getOrElse { "non disponibile: ${it.javaClass.simpleName}" }
            Log.i(PhoneActionExecutor.TAG_PROVA, "cassaforte: $r")
            return
        }
        if (comando.optString("action") == "cassaforte_migra") {
            val r = runCatching { com.jarvis.telefono.cassaforte.AvvioCassaforte.migra(context) }.getOrElse { listOf("migrazione non riuscita: ${it.javaClass.simpleName}") }
            r.forEach { Log.i(PhoneActionExecutor.TAG_PROVA, "cassaforte: $it") }
            return
        }
        // 0.5.0: allinea le caselle del Postino sulla VPS nella cassaforte (senza password), come Account mail.
        if (comando.optString("action") == "caselle_allinea") {
            val ca = com.jarvis.telefono.configura.CanaleAccount(context.applicationContext)
            val no = ca.motivoNo()
            if (no != null) { Log.i(PhoneActionExecutor.TAG_PROVA, "caselle_allinea: $no"); return }
            ca.lista { l ->
                val r = runCatching {
                    if (l == null) "nessuna risposta dalla VPS" else {
                        val cf = com.jarvis.telefono.cassaforte.Cassaforte.di(context)
                        val nuove = com.jarvis.telefono.cassaforte.AllineaCaselle.daSalvare(cf.mail(), l.caselle).onEach { cf.salva(it) }
                        "VPS ${l.caselle.size} caselle (con password ${l.caselle.count { it.password }}), salvate ${nuove.size}, nel telefono ${cf.mail().size}"
                    }
                }.getOrElse { "errore ${it.javaClass.simpleName}" }
                Log.i(PhoneActionExecutor.TAG_PROVA, "caselle_allinea: $r")
                ca.chiudi()
            }
            return
        }
        // 0.6.0: Collegamento Jarvis da ADB. «cosa»: stato, giro (notifiche), allinea (memoria), coda_prova (due notifiche
        // uguali da due fonti: deve uscirne una), accendi, spegni. Nel log solo conteggi, mai testi personali.
        if (comando.optString("action") == "collegamento") {
            val app = context.applicationContext
            val CJ = com.jarvis.telefono.collegamento.CollegamentoJarvis
            val cosa = comando.optString("cosa", "stato")
            val r = goAsync()
            Thread {
                val esito = runCatching {
                    when (cosa) {
                        "giro" -> "giro notifiche: ${CJ.giroNotifiche(app)}"
                        "allinea" -> CJ.allineaMemoria(app)?.let { "memoria: ${it.voci.size} voci, ${it.fattiVps.size} fatti su Jarvis, ${it.conflitti.size} conflitti, contesti ${it.contesti.map { c -> c.id + (if (c.indirizzo.startsWith("https://")) "+url" else "") }}" } ?: "memoria: non partita"
                        "coda_prova" -> {
                            val ora = System.currentTimeMillis()
                            val testo = "Prova della coda unica " + ora
                            val a1 = com.jarvis.telefono.collegamento.CodaNotifiche.Notifica("prova:lavoro:$ora", "lavoro", "Postino · Prova coda", testo, ora, "postino", true)
                            val a2 = com.jarvis.telefono.collegamento.CodaNotifiche.Notifica("prova:ponte:$ora", "report", "Postino · Prova coda", testo, ora, "postino", true)
                            val m1 = CJ.passaDallaCoda(app, a1).also { if (it) com.jarvis.telefono.collegamento.NotificheJarvis.mostra(app, a1) }
                            val m2 = CJ.passaDallaCoda(app, a2).also { if (it) com.jarvis.telefono.collegamento.NotificheJarvis.mostra(app, a2) }
                            "coda_prova: prima ${if (m1) "mostrata" else "fermata"}, seconda ${if (m2) "mostrata" else "fermata"}"
                        }
                        // Come i pulsanti di Impostazioni: «decidi_telefono» o «decidi_jarvis» su tutti i conflitti aperti.
                        "decidi_telefono", "decidi_jarvis" -> {
                            val conf = CJ.ultimaMemoria(app)?.conflitti.orEmpty()
                            val ok = conf.count { CJ.decidi(app, it, cosa == "decidi_telefono") }
                            "decisi $ok conflitti su ${conf.size} (vince ${if (cosa == "decidi_telefono") "telefono" else "Jarvis"}): ${conf.map { it.chiave }}"
                        }
                        // integra-jarvis: le funzioni portate dall'app 1.2.4.
                        "aggiornamenti" -> "aggiornamenti: " + com.jarvis.telefono.collegamento.AggiornamentiJBoss.controllaOra(app)
                        "notifica_ultima" -> "notifica: " + CJ.rimostraUltima(app)
                        "scorciatoia" -> { CJ.scorciatoiaWebapp(app); "scorciatoia webapp: " + androidx.core.content.pm.ShortcutManagerCompat.getDynamicShortcuts(app).map { it.id } }
                        "webapp" -> { CJ.apriWebapp(app, comando.optString("filo").ifBlank { null }); "webapp aperta" }
                        "accesso_salvato" -> CJ.baseWeb(app)?.let { "accesso salvato: " + com.jarvis.telefono.collegamento.AccessoSalvato(app, it).haCredenziali() } ?: "accesso salvato: manca l'indirizzo"
                        "accendi" -> { CJ.accendi(app, true); "collegamento acceso" }
                        "spegni" -> { CJ.accendi(app, false); "collegamento spento" }
                        else -> "stato: " + CJ.statoTesto(app).replace("\n", " | ")
                    }
                }.getOrElse { "collegamento $cosa: errore ${it.javaClass.simpleName}" }
                Log.i(PhoneActionExecutor.TAG_PROVA, esito)
                r.finish()
            }.start()
            return
        }
        if (comando.optString("action") == "configura_togli") {
            com.jarvis.telefono.nucleo.ConfigPersonale.file(context).delete()
            com.jarvis.telefono.nucleo.ConfigPersonale.ricarica(context)
            Log.i(PhoneActionExecutor.TAG_PROVA, "configurazione tolta: valori di fabbrica")
            return
        }
        // 0.3.3: lo strumento di misura della voce. SPENTO di default; «on» vale 24 ore, «off» cancella le frasi.
        //   --es comando '{"action":"debug_audio","stato":"on"}'
        if (comando.optString("action") == "debug_audio") {
            val on = comando.optString("stato").lowercase() in setOf("on", "acceso", "si", "sì", "true", "1")
            com.jarvis.telefono.voce.FrasiDebug.imposta(context, on)
            Log.i(PhoneActionExecutor.TAG_PROVA, "debug_audio ${if (on) "acceso per 24 ore: ${com.jarvis.telefono.voce.FrasiDebug.cartella(context)}" else "spento, frasi cancellate"}")
            return
        }
        // 0.3.3: chi trascrive dopo la parola: «google», «whisper» o «predefinito».
        if (comando.optString("action") == "trascrittore") {
            val v = comando.optString("scelta")
            if (v == "predefinito") com.jarvis.telefono.voce.SceltaTrascrittore.azzera(context) else com.jarvis.telefono.voce.SceltaTrascrittore.imposta(context, v)
            Log.i(PhoneActionExecutor.TAG_PROVA, "trascrittore: ${com.jarvis.telefono.voce.SceltaTrascrittore.di(context)} (google sul telefono disponibile: ${com.jarvis.telefono.voce.TrascrittoreGoogle.disponibile(context)})")
            return
        }
        // 0.3.3: quante frasi non capite ci sono nell'archivio (il testo resta sul telefono).
        if (comando.optString("action") == "non_capite") {
            Thread {
                val n = runCatching { com.jarvis.telefono.nucleo.FrasiNonCapite.di(context).conta() }.getOrDefault(-1)
                Log.i(PhoneActionExecutor.TAG_PROVA, "non capite in archivio: $n")
            }.start()
            return
        }
        // 0.3.3: il banco sul telefono. Trascrive con Google e con Whisper ogni WAV (16 kHz mono 16 bit)
        // della cartella esterna files/<cartella>/ e scrive risultati.tsv accanto (adb pull).
        if (comando.optString("action") == "banco_trascrivi") {
            val s = com.jarvis.telefono.JarvisService.instance
            if (comando.has("ritmo")) com.jarvis.telefono.voce.TrascrittoreGoogle.ritmo = comando.optDouble("ritmo").toFloat()
            if (s == null) Log.i(PhoneActionExecutor.TAG_PROVA, "banco_trascrivi: servizio spento")
            else s.bancoTrascrivi(comando.optString("cartella").ifEmpty { "banco" })
            return
        }
        // Una frase scritta al nucleo, senza microfono né bolla d'ascolto (come il campo di testo).
        if (comando.optString("action") == "frase") {
            com.jarvis.telefono.nucleo.Nucleo.elabora(context, comando.optString("testo"), "prova")
            Log.i(PhoneActionExecutor.TAG_PROVA, "frase: al nucleo")
            return
        }
        // Spegne Jarvis Telefono come il tasto «Spegni» (prove finite, due Jarvis sullo stesso telefono).
        if (comando.optString("action") == "spegni") {
            com.jarvis.telefono.Prefs.setAttivo(context, false)
            runCatching {
                context.startService(Intent(context, com.jarvis.telefono.JarvisService::class.java).setAction(com.jarvis.telefono.JarvisService.ACTION_STOP))
            }
            Log.i(PhoneActionExecutor.TAG_PROVA, "spento da ADB")
            return
        }
        // L'ultima misura «frase → azione» del nucleo.
        if (comando.optString("action") == "misura") {
            Log.i(PhoneActionExecutor.TAG_PROVA, "misura: " + com.jarvis.telefono.nucleo.Nucleo.ultimaMisura)
            return
        }
        // Solo da ADB (permesso DUMP): rimette in moto le mani come il riquadro della tendina.
        if (comando.optString("action") == "sblocca_mani") {
            PhoneActionExecutor.impostaEmergenza(context, false, "ADB")
            Log.i(PhoneActionExecutor.TAG_PROVA, "mani riattivate da ADB")
            return
        }
        PhoneActionExecutor.attachSeServe(context)
        val id = "adb-" + System.currentTimeMillis()
        PhoneActionExecutor.handle(JSONObject().put("type", "tool_call").put("id", id).put("command", comando))
    }
}

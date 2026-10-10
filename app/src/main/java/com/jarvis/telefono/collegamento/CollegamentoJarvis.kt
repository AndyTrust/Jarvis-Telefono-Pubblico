package com.jarvis.telefono.collegamento

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.jarvis.telefono.cassaforte.Altro
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.nucleo.ConfigPersonale
import com.jarvis.telefono.vps.ConfigVps
import com.jarvis.telefono.vps.ManiVps
import com.jarvis.telefono.vps.ModuloVps
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Il modulo «Collegamento Jarvis» di JBoss (0.6.0, Boss 08/10: «il collegamento con la webapp collegata a Mac e VPS
 * deve essere un collegamento integrato; un'app unica»). Prima erano due app sullo stesso telefono (JBoss e
 * `com.jarvis.app` 1.2.4) con due collegamenti, due code di notifiche e due voci: ora è un modulo di JBoss.
 *
 * UN interruttore (Impostazioni → Collegamento Jarvis, lo stesso del modulo VPS: `ConfigVps.acceso`), UN indirizzo e
 * UN token (cassaforte), UN protocollo (jarvis-agent: ruolo «lavori» per i compiti lunghi, ruolo «mani» per il
 * cervello, HTTP per notifiche e memoria). Dentro:
 *   - lavori sulla VPS                    ModuloVps (già in 0.3.0)
 *   - cervello della VPS e mani           ManiVps / CanaleMani; chi risponde lo decide [Arbitro]
 *   - notifiche di Jarvis e del Postino   [giroNotifiche] → [CodaNotifiche] → [NotificheJarvis] (una sola coda)
 *   - webapp Jarvis (Mac, Windows, VPS)   [WebJarvisActivity]
 *   - memoria condivisa                   [allineaMemoria] → [MemoriaCondivisa]
 *   - contesti seguiti                    [Contesti] (CRM di lavoro, Patrimonio, sola lettura)
 * Spento: nessun collegamento, nessun giro, nessuna notifica di Jarvis. Nei log mai token, testi o valori personali.
 */
object CollegamentoJarvis {
    const val TAG = "JarvisCollegamento"
    const val PACCHETTO_VECCHIO = "com.jarvis.app"
    private const val PREFS = "collegamento_jarvis"
    private const val DOPO = "notifiche_dopo_ms"
    private const val CODA = "coda_notifiche"
    private const val ULTIMO_GIRO = "ultimo_giro_ms"
    private const val ULTIMA_MEMORIA = "ultima_memoria_ms"
    private const val ESITO_MEMORIA = "esito_memoria"
    const val VOCE_MEMORIA = "memoria-condivisa"
    private const val GIRO_MS = 15 * 60_000L

    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    private val JSON = "application/json; charset=utf-8".toMediaType()
    @Volatile private var coda: CodaNotifiche? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun acceso(c: Context): Boolean = ConfigVps.acceso(c)
    fun pronto(c: Context): Boolean = ModuloVps.pronto(c)

    /** Accende o spegne TUTTO il collegamento (lavori, mani, notifiche, memoria). */
    fun accendi(c: Context, si: Boolean) {
        val app = c.applicationContext
        ModuloVps.accendi(app, si)
        if (si) {
            programma(app)
            Thread({ runCatching { giroNotifiche(app) }; runCatching { allineaMemoria(app) } }, "jboss-collegamento").start()
        } else {
            togliProgramma(app)
        }
        Log.i(TAG, "collegamento Jarvis ${if (si) "acceso" else "spento"}")
    }

    /** All'avvio dell'app o del servizio: rimette il giro e, se è passato abbastanza, riallinea la memoria. */
    fun avvio(c: Context) {
        val app = c.applicationContext
        scorciatoiaWebapp(app)
        if (!pronto(app)) return
        programma(app)
        val ultima = prefs(app).getLong(ULTIMA_MEMORIA, 0L)
        if (System.currentTimeMillis() - ultima > MemoriaCondivisa.OGNI_MS) {
            Thread({ runCatching { allineaMemoria(app) } }, "jboss-memoria").start()
        }
    }

    // ─── la webapp ──────────────────────────────────────────────────────────────
    fun baseWeb(c: Context): String? {
        val conf = ConfigPersonale.di(c).webUrl
        if (AccessoWeb.indirizzoBaseValido(conf)) return if (conf.endsWith("/")) conf else "$conf/"
        return AccessoWeb.baseDalPonte(ConfigVps.dati(c).url)
    }

    /**
     * La webapp è un modulo di JBoss (Boss 08/10: «deve essere dentro l'app nostra, non devo switchare da un'app
     * all'altra»): stesso task di JBoss, «indietro» torna a JBoss. Da una schermata si apre sopra; da servizio o voce
     * porta davanti il task di JBoss (niente taskAffinity a parte, niente CLEAR_TASK).
     */
    fun apriWebapp(c: Context, filo: String? = null, pagina: String? = null, fid: String? = null) {
        val i = intentWebapp(c, filo, pagina, fid)
        if (c !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        c.startActivity(i)
    }

    fun intentWebapp(c: Context, filo: String? = null, pagina: String? = null, fid: String? = null): Intent =
        Intent(c, WebJarvisActivity::class.java)
            .putExtra(WebJarvisActivity.EXTRA_FILO, filo).putExtra(WebJarvisActivity.EXTRA_PAGINA, pagina)
            .putExtra(WebJarvisActivity.EXTRA_FID, fid)

    /**
     * integra-jarvis: l'icona dell'app 1.2.4 apriva la webapp. In JBoss l'icona apre JBoss; la webapp ha la sua
     * scorciatoia (tocco lungo sull'icona → «Webapp Jarvis», trascinabile sulla Home). Dinamica: niente manifest.
     */
    fun scorciatoiaWebapp(c: Context) {
        if (baseWeb(c) == null) return
        runCatching {
            val i = Intent(c, WebJarvisActivity::class.java).setAction(Intent.ACTION_VIEW)
            val s = androidx.core.content.pm.ShortcutInfoCompat.Builder(c, "webapp-jarvis")
                .setShortLabel("Webapp Jarvis")
                .setLongLabel("Webapp Jarvis (Command Center)")
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(c, com.jarvis.telefono.R.mipmap.ic_launcher))
                .setIntent(i)
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(c, s)
        }.onFailure { Log.i(TAG, "scorciatoia webapp non messa (${it.javaClass.simpleName})") }
    }

    /**
     * Chiede al launcher un'icona vera sulla Home, con l'avatar di Jarvis, che apre la webapp.
     * Android mostra un solo dialogo «Aggiungi»: lo tocca Boss una volta. Chiesto una sola volta
     * (flag in Prefs), mai dalle impostazioni.
     */
    fun chiediIconaHomeWebapp(c: Context) {
        if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(c)) return
        if (c.getSharedPreferences("jboss_pin", Context.MODE_PRIVATE).getBoolean("pin_webapp_chiesto", false)) return
        runCatching {
            val bmp = android.graphics.BitmapFactory.decodeResource(c.resources, com.jarvis.telefono.R.drawable.avatar_jarvis_256)
            val icona = androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)
            val i = Intent(c, WebJarvisActivity::class.java).setAction(Intent.ACTION_VIEW)
            val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(c, "webapp-jarvis-home")
                .setShortLabel("Jarvis")
                .setLongLabel("Webapp Jarvis")
                .setIcon(icona)
                .setIntent(i)
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(c, info, null)
            c.getSharedPreferences("jboss_pin", Context.MODE_PRIVATE).edit().putBoolean("pin_webapp_chiesto", true).apply()
        }.onFailure { Log.i(TAG, "icona home webapp non chiesta (${it.javaClass.simpleName})") }
    }

    // ─── l'app vecchia ──────────────────────────────────────────────────────────
    /** `com.jarvis.app` è ancora sul telefono? (Manifest: <queries> col suo nome.) */
    fun appVecchiaInstallata(c: Context): Boolean =
        runCatching { c.packageManager.getPackageInfo(PACCHETTO_VECCHIO, 0); true }.getOrDefault(false)

    /** Apre la finestra di sistema per disinstallarla: decide Boss con il suo tocco. */
    fun disinstallaAppVecchia(c: Context) {
        c.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$PACCHETTO_VECCHIO")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ─── la coda unica delle notifiche ──────────────────────────────────────────
    private fun coda(c: Context): CodaNotifiche = coda ?: synchronized(this) {
        coda ?: CodaNotifiche().also { it.carica(prefs(c).getString(CODA, null)); coda = it }
    }

    /**
     * Ogni notifica di Jarvis passa di qui (report, lavori finiti, avvisi fuori turno). true = mostrala
     * (chi chiama la mostra col suo canale); false = doppione o contesto in silenzio.
     */
    fun passaDallaCoda(c: Context, n: CodaNotifiche.Notifica): Boolean {
        val q = coda(c)
        val e = q.entra(n)
        prefs(c).edit().putString(CODA, q.salva()).apply()
        if (e != CodaNotifiche.Esito.MOSTRA) Log.i(TAG, "notifica ${n.fonte}: ${e.name.lowercase()}")
        return e == CodaNotifiche.Esito.MOSTRA
    }

    /** Un avviso fuori turno della VPS (Arbitro → NOTIFICA). */
    fun avvisoSottofondo(c: Context, testo: String) {
        val n = CodaNotifiche.Notifica("", "sottofondo", "Jarvis", testo, System.currentTimeMillis())
        if (passaDallaCoda(c, n)) NotificheJarvis.mostra(c, n)
    }

    private fun base(c: Context): String? = MemoriaCondivisa.base(ConfigVps.dati(c).url)

    private fun richiesta(c: Context, percorso: String): Request.Builder? {
        val b = base(c) ?: return null
        val t = ConfigVps.dati(c).token.takeIf { it.isNotBlank() } ?: return null
        return Request.Builder().url(b + percorso).header("Authorization", "Bearer $t")
    }

    /**
     * Un giro delle notifiche del Command Center (GET /notifiche?dopo=). Fuori dal thread principale.
     * @return quante ne ha mostrate, -1 se non è partito (spento, senza configurazione, errore).
     */
    fun giroNotifiche(c: Context): Int {
        if (!pronto(c) || !ModuloVps.haRete(c)) return -1
        val p = prefs(c)
        val dopo = p.getLong(DOPO, 0L)
        if (dopo == 0L) {
            // Primo giro: si parte da adesso, niente valanga di vecchie.
            p.edit().putLong(DOPO, System.currentTimeMillis()).putLong(ULTIMO_GIRO, System.currentTimeMillis()).apply()
            Log.i(TAG, "notifiche: primo giro, parto da adesso")
            return 0
        }
        val req = richiesta(c, "/notifiche?dopo=$dopo")?.build() ?: return -1
        return runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.i(TAG, "notifiche: ponte ${r.code}"); return -1 }
                val lista = JSONObject(r.body?.string() ?: "{}").optJSONArray("notifiche")
                var ultimo = dopo
                var mostrate = 0
                if (lista != null) for (i in 0 until lista.length()) {
                    val o = lista.optJSONObject(i) ?: continue
                    val n = CodaNotifiche.daPonte(o)
                    ultimo = maxOf(ultimo, n.ts)
                    if (passaDallaCoda(c, n)) {
                        // 09/10 (Boss): un avviso del Postino con l'app davanti va nel filo di JBoss come riga, non a comparsa.
                        if (com.jarvis.telefono.postino.PostaCondivisa.avvisoComeRiga(n.filo, com.jarvis.telefono.bolla.PrimoPiano.app)) {
                            com.jarvis.telefono.postino.Postino.riga(c, listOf(n.titolo, n.testo).filter { it.isNotBlank() }.joinToString(": "))
                        } else NotificheJarvis.mostra(c, n)
                        mostrate++
                    }
                }
                p.edit().putLong(DOPO, ultimo).putLong(ULTIMO_GIRO, System.currentTimeMillis()).apply()
                Log.i(TAG, "notifiche: ${lista?.length() ?: 0} nuove dal ponte, $mostrate mostrate")
                mostrate
            }
        }.getOrElse { Log.i(TAG, "notifiche: giro non riuscito (${it.javaClass.simpleName})"); -1 }
    }

    /** Banco ADB: rimostra l'ultima notifica del ponte (ultimi 3 giorni) senza passare dalla coda, per provare il tocco. */
    fun rimostraUltima(c: Context): String {
        val req = richiesta(c, "/notifiche?dopo=" + (System.currentTimeMillis() - 3 * 24 * 3600_000L))?.build() ?: return "manca la configurazione"
        return runCatching {
            http.newCall(req).execute().use { r ->
                val lista = JSONObject(r.body?.string() ?: "{}").optJSONArray("notifiche") ?: return "nessuna notifica (${r.code})"
                if (lista.length() == 0) return "nessuna notifica negli ultimi 3 giorni"
                val n = CodaNotifiche.daPonte(lista.getJSONObject(lista.length() - 1))
                NotificheJarvis.mostra(c, n)
                "rimostrata: filo ${n.filo}, chiave ${n.chiave.take(40)}"
            }
        }.getOrElse { "non riuscita (${it.javaClass.simpleName})" }
    }

    // ─── la memoria condivisa ───────────────────────────────────────────────────
    private fun configJson(c: Context): JSONObject =
        runCatching { JSONObject(ConfigPersonale.file(c).readText()) }.getOrElse { JSONObject() }

    private fun extra(c: Context): Map<String, String> = mapOf(
        "parole_attivazione" to com.jarvis.telefono.voce.Opzioni().parole.joinToString(", "),
        "trascrittore" to runCatching { com.jarvis.telefono.voce.SceltaTrascrittore.di(c) }.getOrDefault(""),
    )

    fun ultimaMemoria(c: Context): MemoriaCondivisa.Esito? =
        MemoriaCondivisa.carica(runCatching { (Cassaforte.di(c).leggi(VOCE_MEMORIA) as? Altro)?.valore }.getOrNull())

    /**
     * Manda i fatti del telefono, prende il riassunto, salva nella cassaforte, adotta i fatti che al telefono mancano.
     * Fuori dal thread principale. @return l'esito, null se non è partito.
     */
    fun allineaMemoria(c: Context): MemoriaCondivisa.Esito? {
        if (!pronto(c) || !ModuloVps.haRete(c)) return null
        val f = ConfigPersonale.file(c)
        val ts = if (f.isFile) f.lastModified() else 0L
        val fatti = MemoriaCondivisa.fattiDelTelefono(configJson(c), extra(c), ts)
        val versione = AggiornamentiJBoss.versioneAttuale()
        val r1 = richiesta(c, "/memoria/allinea")?.post(MemoriaCondivisa.corpoAllinea(fatti, versione).toRequestBody(JSON))?.build() ?: return null
        val r2 = richiesta(c, "/memoria/profilo")?.build() ?: return null
        return runCatching {
            val allinea = http.newCall(r1).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}") else { Log.i(TAG, "memoria: allinea ${r.code}"); null } }
            val profilo = http.newCall(r2).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}") else { Log.i(TAG, "memoria: profilo ${r.code}"); null } }
            if (allinea == null && profilo == null) return null
            val e = MemoriaCondivisa.leggi(allinea, profilo)
            runCatching { Cassaforte.di(c).salva(Altro(VOCE_MEMORIA, "Memoria condivisa con Jarvis", MemoriaCondivisa.salva(e), segreto = false)) }
            val adottati = MemoriaCondivisa.daAdottare(fatti, e.fattiVps)
            if (adottati.isNotEmpty()) scriviInConfig(c, adottati.associate { it.chiave to it.valore })
            prefs(c).edit().putLong(ULTIMA_MEMORIA, System.currentTimeMillis())
                .putString(ESITO_MEMORIA, "${fatti.size} fatti mandati, ${e.voci.size} regole nel riassunto, ${e.conflitti.size} da decidere, ${adottati.size} presi da Jarvis").apply()
            Log.i(TAG, "memoria: ${fatti.size} fatti mandati, ${e.voci.size} voci, ${e.fattiVps.size} fatti su Jarvis, ${e.conflitti.size} conflitti, ${adottati.size} adottati")
            e
        }.getOrElse { Log.i(TAG, "memoria: non riuscita (${it.javaClass.simpleName})"); null }
    }

    /** Boss decide un conflitto. Se vince Jarvis, il valore entra nella configurazione del telefono. Fuori dal principale. */
    fun decidi(c: Context, conflitto: MemoriaCondivisa.Conflitto, vinceTelefono: Boolean): Boolean {
        val req = richiesta(c, "/memoria/decidi")?.post(MemoriaCondivisa.corpoDecidi(conflitto.chiave, vinceTelefono).toRequestBody(JSON))?.build() ?: return false
        val ok = runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
        if (ok && !vinceTelefono) scriviInConfig(c, mapOf(conflitto.chiave to conflitto.jarvis))
        Log.i(TAG, "memoria: decisione su ${conflitto.chiave} (vince ${if (vinceTelefono) "telefono" else "Jarvis"}) ${if (ok) "salvata" else "NON salvata"}")
        if (ok) allineaMemoria(c)
        return ok
    }

    private fun scriviInConfig(c: Context, valori: Map<String, String>) {
        val o = configJson(c)
        for ((k, v) in valori) {
            if (k !in MemoriaCondivisa.CHIAVI || k == "parole_attivazione" || k == "trascrittore") continue
            when {
                v == "true" || v == "false" -> o.put(k, v.toBoolean())
                v.toDoubleOrNull() != null && k in setOf("fine_frase_s", "volume_segnali") -> o.put(k, v.toDouble())
                else -> o.put(k, v)
            }
        }
        runCatching { ConfigPersonale.file(c).writeText(o.toString()) ; ConfigPersonale.ricarica(c) }
    }

    // ─── i comandi a voce del collegamento ──────────────────────────────────────
    /** Esegue un comando di [ComandiCollegamento]. Fuori dal thread principale. @return testo da dire, errore. */
    fun esegui(c: Context, cmd: ComandiCollegamento.Comando): Pair<String, Boolean> {
        val spento = "Il Collegamento Jarvis è spento: accendilo in Impostazioni, Collegamento Jarvis."
        return when (cmd) {
            is ComandiCollegamento.Comando.Webapp -> {
                if (baseWeb(c) == null) return "Manca l'indirizzo della webapp Jarvis nella configurazione." to true
                apriWebapp(c, cmd.filo)
                (if (cmd.filo == "postino") "Ti apro i report del Postino nella webapp." else "Ti apro la webapp di Jarvis.") to false
            }
            is ComandiCollegamento.Comando.ApriContesto -> {
                if (!pronto(c)) return spento to true
                val nome = Contesti.TUTTI.firstOrNull { it.id == cmd.id }?.nome ?: cmd.id
                val url = (ultimaMemoria(c) ?: allineaMemoria(c))?.indirizzo(cmd.id)
                    ?: return "Non ho l'indirizzo di $nome: Jarvis non me l'ha ancora dato. Riprova dopo «allinea la memoria»." to true
                runCatching {
                    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.fold({ "Ti apro $nome, in sola lettura." to false }, { "Non riesco ad aprire $nome: nessun browser." to true })
            }
            ComandiCollegamento.Comando.AllineaMemoria -> {
                if (!pronto(c)) return spento to true
                val e = allineaMemoria(c) ?: return "Non sono riuscito ad allineare la memoria con Jarvis: controlla la rete e riprova." to true
                val conf = if (e.conflitti.isEmpty()) "Nessun disaccordo." else "Su ${e.conflitti.size} cose il telefono e Jarvis non sono d'accordo: decidi tu in Impostazioni, Collegamento Jarvis."
                "Memoria allineata con Jarvis: ${e.voci.size} regole nel riassunto, ${e.fattiVps.size} fatti. $conf" to false
            }
            ComandiCollegamento.Comando.CosaSaiDiMe -> {
                val e = ultimaMemoria(c) ?: (if (pronto(c)) allineaMemoria(c) else null)
                MemoriaCondivisa.riassuntoVoce(e ?: MemoriaCondivisa.Esito(emptyList(), emptyList(), emptyList(), emptyList(), 0L)) to (e == null)
            }
            ComandiCollegamento.Comando.Stato -> statoTesto(c).replace("\n", " ") to false
            ComandiCollegamento.Comando.GiroNotifiche -> {
                if (!pronto(c)) return spento to true
                when (val n = giroNotifiche(c)) {
                    -1 -> "Non sono riuscito a chiedere i report a Jarvis: controlla la rete." to true
                    0 -> "Nessun report nuovo da Jarvis." to false
                    1 -> "C'è un report nuovo di Jarvis: è nelle notifiche." to false
                    else -> "Ci sono $n report nuovi di Jarvis: sono nelle notifiche." to false
                }
            }
            ComandiCollegamento.Comando.CercaAggiornamenti -> AggiornamentiJBoss.controllaOra(c).let { it to it.startsWith("Controllo aggiornamenti non") }
        }
    }

    // ─── stato in parole ────────────────────────────────────────────────────────
    fun statoTesto(c: Context): String = buildString {
        append("Collegamento: ").append(ModuloVps.statoTesto(c)).append(". ")
        append(ManiVps.stato().replaceFirstChar { it.uppercase() }).append(". ")
        val p = prefs(c)
        val giro = p.getLong(ULTIMO_GIRO, 0L)
        append("Notifiche: ").append(if (giro == 0L) "nessun giro ancora" else "ultimo giro ${quando(giro)}").append(". ")
        append("Memoria: ").append(p.getString(ESITO_MEMORIA, null)?.let { "$it (${quando(p.getLong(ULTIMA_MEMORIA, 0L))})" } ?: "non ancora allineata").append(".")
        if (appVecchiaInstallata(c)) append("\nAttenzione: c'è ancora l'app Jarvis vecchia (com.jarvis.app). Fa notifiche doppie e si contende il collegamento: disinstallala.")
    }

    private fun quando(ms: Long): String {
        val min = (System.currentTimeMillis() - ms) / 60_000
        return when {
            min < 1 -> "adesso"
            min < 60 -> "$min minuti fa"
            else -> "${min / 60} ore fa"
        }
    }

    // ─── il giro ogni 15 minuti (AlarmManager inesatto: niente Handler nel servizio, niente sveglie esatte) ─────
    private fun intentGiro(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 7301, Intent(c, GiroCollegamentoReceiver::class.java).setAction(GiroCollegamentoReceiver.AZIONE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun programma(c: Context) {
        runCatching {
            c.getSystemService(AlarmManager::class.java).setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + GIRO_MS, GIRO_MS, intentGiro(c),
            )
        }.onFailure { Log.w(TAG, "giro non programmato: ${it.javaClass.simpleName}") }
    }

    private fun togliProgramma(c: Context) {
        runCatching { c.getSystemService(AlarmManager::class.java).cancel(intentGiro(c)) }
    }

    /** Banco ADB: riparte da zero (coda, ultimo giro, memoria). La cassaforte non si tocca. */
    fun azzera(c: Context) { prefs(c).edit().clear().apply(); coda = null }
}

/** Il giro ogni 15 minuti (non esportato: lo chiama solo l'AlarmManager dell'app). */
class GiroCollegamentoReceiver : BroadcastReceiver() {
    companion object { const val AZIONE = "com.jarvis.telefono.collegamento.GIRO" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AZIONE) return
        val r = goAsync()
        Thread({
            runCatching { CollegamentoJarvis.giroNotifiche(context.applicationContext) }
            // integra-jarvis: gli aggiornamenti di JBoss, una volta ogni 12 ore dentro questo giro (niente lavori in più).
            runCatching { AggiornamentiJBoss.seServe(context.applicationContext) }
            r.finish()
        }, "jboss-giro").start()
    }
}

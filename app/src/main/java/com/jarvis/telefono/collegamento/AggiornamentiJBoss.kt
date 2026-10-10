package com.jarvis.telefono.collegamento

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.telefono.vps.ConfigVps
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gli aggiornamenti di JBoss dal ponte (integra-jarvis, 2026-10-08), al posto di UpdateChecker + AggiornamentoWorker
 * dell'app `com.jarvis.app` 1.2.4. Stesso ponte, stessa cartella pubblica `/update/apk/` di jarvis-agent (express.static
 * su `/app/releases`), file suoi: `jboss-version.json` e l'APK che nomina. Il `version.json` dell'app vecchia non si tocca.
 *
 * Non installa da solo (Android non lo permette): una notifica «Aggiornamento JBoss disponibile», il tocco apre l'APK nel
 * browser, Boss conferma l'installazione. Controllo ogni 12 ore nel giro del Collegamento e a richiesta («cerca
 * aggiornamenti», pulsante in Impostazioni, banco ADB). Solo verso l'alto, solo con un numero remoto sensato.
 *
 * Nota per l'unione: l'installazione dentro l'app (FileProvider + REQUEST_INSTALL_PACKAGES, come la 1.2.4) chiede due
 * righe nel manifest, che oggi è in lavorazione da jboss-sveglia e jboss-ui-impl: per ora si passa dal browser.
 */
object AggiornamentiJBoss {
    private const val TAG = "JBossAggiornamenti"
    private const val CANALE = "jboss_aggiornamenti"
    private const val ID_NOTIFICA = 7401
    private const val PREFS = "jboss_aggiornamenti"
    private const val ULTIMO = "ultimo_controllo_ms"
    private const val ESITO = "ultimo_esito"
    private const val ESITO_VERSIONE = "ultimo_esito_versione"
    const val FILE_VERSIONE = "jboss-version.json"
    const val OGNI_MS = 12 * 60 * 60_000L

    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }

    data class Remota(val codice: Int, val nome: String, val apk: String, val sha256: String)

    /** Solo verso l'alto e solo se il numero remoto ha senso (un version.json rotto dà -1). */
    fun serveAggiornare(remoto: Int, locale: Long): Boolean = remoto > 0 && remoto > locale

    /** Il JSON del ponte; null se non è leggibile. Il nome del file APK si accetta solo semplice (niente percorsi). */
    fun leggi(json: String?): Remota? = runCatching {
        val o = JSONObject(json ?: return null)
        val apk = o.optString("apk", "jboss-latest.apk")
        if (!Regex("^[A-Za-z0-9._-]{1,80}\\.apk$").matches(apk) || apk.contains("..")) return null
        Remota(o.optInt("versionCode", -1), o.optString("versionName", "?"), apk, o.optString("sha256", ""))
    }.getOrNull()

    /** La cartella pubblica degli aggiornamenti sul ponte (`wss://host/phone` → `https://host/update/apk/`). */
    fun cartella(urlVps: String): String? = MemoriaCondivisa.base(urlVps)?.let { "$it/update/apk/" }

    private fun codiceLocale(c: Context): Long = runCatching {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }.getOrDefault(0L)

    /**
     * 09/10 (Boss: «sei alla» con il nome vecchio di un APK di prova): il nome della versione è quello compilato nell'app
     * ([com.jarvis.telefono.BuildConfig.VERSION_NAME]), mai un numero scritto a mano o il nome di un APK di prova.
     */
    fun versioneAttuale(): String = com.jarvis.telefono.BuildConfig.VERSION_NAME

    private fun nomeLocale(@Suppress("UNUSED_PARAMETER") c: Context): String = versioneAttuale()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * L'esito dell'ultimo controllo, solo se è stato scritto da QUESTA versione: un esito salvato da un'app più
     * vecchia (il nome di un APK di prova) dopo l'aggiornamento non si mostra più.
     */
    fun ultimoEsito(c: Context): String? {
        val p = prefs(c)
        return p.getString(ESITO, null)?.takeIf { esitoDiQuestaVersione(p.getString(ESITO_VERSIONE, null), versioneAttuale()) }
    }

    /** Puro: un esito vale solo per la versione che l'ha scritto (null = salvato prima della 0.7.1, quindi vecchio). */
    fun esitoDiQuestaVersione(versioneSalvata: String?, attuale: String): Boolean = versioneSalvata == attuale

    /** Nel giro ogni 15 minuti: controlla solo se sono passate 12 ore. Fuori dal thread principale. */
    fun seServe(c: Context) {
        if (System.currentTimeMillis() - prefs(c).getLong(ULTIMO, 0L) < OGNI_MS) return
        controllaOra(c)
    }

    /** Il controllo vero, sincrono (mai sul thread principale). @return la frase con l'esito. */
    fun controllaOra(c: Context): String {
        val dir = cartella(ConfigVps.dati(c).url) ?: return salva(c, "Manca l'indirizzo del ponte: abbina la VPS.")
        val esito = runCatching {
            http.newCall(Request.Builder().url(dir + FILE_VERSIONE).build()).execute().use { r ->
                when {
                    r.code == 404 -> "Sul ponte non c'è ancora nessuna versione di JBoss pubblicata (sei alla ${nomeLocale(c)})."
                    !r.isSuccessful -> "Il ponte degli aggiornamenti non risponde (${r.code})."
                    else -> {
                        val rem = leggi(r.body?.string()) ?: return@use "La versione pubblicata sul ponte non si legge."
                        if (serveAggiornare(rem.codice, codiceLocale(c))) {
                            notifica(c, dir + rem.apk, rem.nome)
                            "C'è JBoss ${rem.nome}: guarda la notifica e toccala per scaricarla."
                        } else "JBoss è già all'ultima versione (${nomeLocale(c)})."
                    }
                }
            }
        }.getOrElse { "Controllo aggiornamenti non riuscito: niente rete o ponte spento." }
        Log.i(TAG, esito)
        return salva(c, esito)
    }

    private fun salva(c: Context, esito: String): String {
        prefs(c).edit().putLong(ULTIMO, System.currentTimeMillis()).putString(ESITO, esito)
            .putString(ESITO_VERSIONE, versioneAttuale()).apply()
        return esito
    }

    private fun notifica(c: Context, urlApk: String, nome: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CANALE, "JBoss: aggiornamenti", NotificationManager.IMPORTANCE_DEFAULT))
        val apri = Intent(Intent.ACTION_VIEW, Uri.parse(urlApk)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(c, ID_NOTIFICA, apri, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CANALE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Aggiornamento JBoss disponibile")
            .setContentText("Versione $nome: tocca per scaricarla, poi conferma l'installazione")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID_NOTIFICA, n) }.onFailure { Log.i(TAG, "notifica non mostrata (${it.javaClass.simpleName})") }
    }
}

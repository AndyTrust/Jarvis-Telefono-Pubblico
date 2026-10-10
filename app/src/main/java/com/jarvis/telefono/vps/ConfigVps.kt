package com.jarvis.telefono.vps

import android.content.Context
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.Vps
import org.json.JSONObject
import java.io.File

/**
 * La configurazione del modulo VPS: indirizzo del ponte e token. Dalla 0.3.1 nella Cassaforte cifrata
 * (cassaforte/Cassaforte.kt); prima in `filesDir/modulo-vps.json` (cartella privata, allowBackup=false), che si legge
 * ancora se la cassaforte non ha la VPS. NON sta nel repo né nell'APK: la porta sul telefono
 * `scripts/configura-vps.sh`, che legge il token da `~/.env.jarvis` (JARVIS_AGENT__PHONE_TOKEN) sul Mac.
 * Il token non si stampa mai: fuori di qui si sa solo se c'è.
 *
 * Interruttore e scelte per agente stanno nelle preferenze `modulo_vps` (spento di fabbrica).
 */
object ConfigVps {
    const val NOME_FILE = "modulo-vps.json"
    private const val PREFS = "modulo_vps"
    private const val ACCESO = "acceso"

    data class Dati(val url: String, val token: String) {
        val completa: Boolean get() = (url.startsWith("wss://") || url.startsWith("ws://")) && token.isNotBlank()
    }

    fun file(context: Context): File = File(context.filesDir, NOME_FILE)

    @Volatile private var cache: Dati? = null

    fun dati(context: Context): Dati = cache ?: leggi(context).also { cache = it }

    /**
     * 0.3.1 (cassaforte): prima la Cassaforte cifrata; il vecchio file in chiaro solo se la cassaforte non ha la VPS
     * (telefono configurato con la 0.3.0). Il file vecchio si sposta nella cassaforte con [portaInCassaforte].
     */
    fun leggi(context: Context): Dati {
        runCatching { Cassaforte.di(context).vps() }.getOrNull()?.let { if (it.completa) return Dati(it.url, it.token) }
        return leggiFile(context)
    }

    private fun leggiFile(context: Context): Dati {
        val f = file(context)
        if (!f.isFile) return Dati("", "")
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return Dati("", "")
        return Dati(o.optString("url").trim(), o.optString("token").trim())
    }

    /** Sposta indirizzo e token dal file in chiaro alla cassaforte e cancella il file. true se fatto. */
    fun portaInCassaforte(context: Context): Boolean {
        val d = leggiFile(context)
        if (!d.completa) return false
        val ok = runCatching { Cassaforte.di(context).salva(Vps(d.url, d.token)) }.isSuccess
        if (ok) { file(context).delete(); cache = null }
        return ok
    }

    /** Dal banco ADB: valida e scrive. Restituisce una riga di esito SENZA il token. */
    fun scrivi(context: Context, testo: String): String {
        val o = JSONObject(testo)
        val url = o.optString("url").trim()
        val token = o.optString("token").trim()
        require(url.startsWith("wss://") || url.startsWith("ws://")) { "indirizzo non valido" }
        require(token.length >= 16) { "token mancante o troppo corto" }
        // 0.3.1: nella cassaforte cifrata; il file in chiaro solo se il Keystore non funziona.
        val inCassaforte = runCatching { Cassaforte.di(context).salva(Vps(url, token)) }.isSuccess
        if (inCassaforte) file(context).delete()
        else file(context).writeText(JSONObject().put("url", url).put("token", token).toString())
        cache = null
        return "modulo VPS configurato: ${url.substringBefore("?")}, token di ${token.length} caratteri" +
            if (inCassaforte) " (cassaforte)" else " (file: cassaforte non disponibile)"
    }

    /** 0.4.0: dopo un abbinamento o una modifica dalla cassaforte, si rilegge. */
    fun invalida() { cache = null }

    fun togli(context: Context) {
        file(context).delete()
        runCatching { Cassaforte.di(context).elimina(Vps.ID) }
        cache = null
    }

    fun acceso(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ACCESO, false)

    fun setAcceso(context: Context, si: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ACCESO, si).apply()
    }

    /** La scelta di Boss per un agente (null = predefinito di [Instradamento.PREDEFINITI]). */
    fun preferenza(context: Context, agente: String): Instradamento.Dove? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dove_$agente", null)
            ?.let { runCatching { Instradamento.Dove.valueOf(it) }.getOrNull() }

    fun setPreferenza(context: Context, agente: String, dove: Instradamento.Dove?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (dove == null) remove("dove_$agente") else putString("dove_$agente", dove.name)
        }.apply()
    }
}

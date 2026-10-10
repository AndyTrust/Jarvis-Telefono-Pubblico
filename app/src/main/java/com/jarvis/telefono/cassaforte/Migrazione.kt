package com.jarvis.telefono.cassaforte

import org.json.JSONObject

/**
 * Migrazione dal file personale `config-boss.json` (e dal token del modulo VPS in `modulo-vps.json`) alla Cassaforte.
 * Non cancella né cambia i file di prima: le preferenze (fine frase, impronta, volume…) restano in config-boss.json,
 * nella cassaforte entrano solo gli account e i recapiti. Si può rifare: le voci già presenti non si sovrascrivono.
 */
object Migrazione {
    data class Esito(val voci: List<Voce>, val righe: List<String>)

    /**
     * [configBoss] = testo di config-boss.json (o vuoto); [vpsUrl]/[vpsToken] = dal modulo VPS attuale (o vuoti).
     * Funzione pura: restituisce le voci da aggiungere, senza scrivere niente.
     */
    fun daConfigBoss(configBoss: String, vpsUrl: String = "", vpsToken: String = "", giaPresenti: Set<String> = emptySet()): Esito {
        val o = runCatching { JSONObject(configBoss) }.getOrNull() ?: JSONObject()
        val voci = ArrayList<Voce>()
        val righe = ArrayList<String>()
        fun aggiungi(v: Voce, cosa: String) {
            if (v.id in giaPresenti) righe += "$cosa: c'è già, lasciato com'era" else { voci += v; righe += "$cosa: aggiunto" }
        }

        val account = o.optString("account_mail").trim()
        if (account.contains('@')) {
            val app = o.optString("app_mail").trim().lowercase()
            val preset = PresetPosta.perIndirizzo(account)
            val tipo = when {
                app == "samsung" -> TipoMail.SAMSUNG
                app == "gmail" -> TipoMail.GMAIL
                else -> preset?.tipo ?: TipoMail.GENERICO
            }
            // Samsung Email = l'app del telefono: niente server, niente password. La casella vera per il Postino si
            // aggiunge a parte (preset + password + prova).
            val p = if (tipo == TipoMail.SAMSUNG) null else preset
            aggiungi(
                AccountMail(
                    id = "mail-" + account.substringBefore('@').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).ifEmpty { "principale" },
                    etichetta = "Posta del telefono",
                    indirizzo = account,
                    imap = p?.imap, smtp = p?.smtp, tipo = tipo,
                ),
                "account di posta $account",
            )
        }

        val wa = o.optString("whatsapp_me").filter { it.isDigit() }
        if (wa.length in 8..15) aggiungi(Altro(Altro.WHATSAPP_ME, "Il mio numero WhatsApp", wa), "numero WhatsApp personale")

        val url = vpsUrl.ifBlank { o.optString("vps_url").trim() }
        if (url.startsWith("wss://") || url.startsWith("ws://")) {
            aggiungi(Vps(url, vpsToken.trim()), if (vpsToken.isBlank()) "VPS (indirizzo senza token)" else "VPS (indirizzo e token)")
        }

        // Le chiavi segrete NON sono mai in config-boss.json (lo script le rifiuta): se ci fossero, si segnala e basta.
        val vietate = o.keys().asSequence().filter { Regex("password|token|segreto|secret|api_key", RegexOption.IGNORE_CASE).containsMatchIn(it) }.toList()
        if (vietate.isNotEmpty()) righe += "chiavi segrete trovate nel file e ignorate: ${vietate.size} (toglile dal file)"
        if (voci.isEmpty() && righe.isEmpty()) righe += "niente da portare nella cassaforte"
        return Esito(voci, righe)
    }
}

/** La migrazione sul telefono: una volta sola (preferenza «cassaforte/migrata_v1»), in un thread a parte. */
object AvvioCassaforte {
    private const val PREFS = "cassaforte"
    private const val MIGRATA = "migrata_v1"

    /** 0.4.0: quante voci sono entrate dalla migrazione (la procedura guidata parte «ripresa» se > 0). */
    private const val VOCI_MIGRATE = "voci_migrate"

    fun vociMigrate(context: android.content.Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getInt(VOCI_MIGRATE, 0)

    /** [poi] sul thread principale a migrazione finita (anche se era già fatta): la procedura guidata decide dopo. */
    fun migraUnaVolta(context: android.content.Context, poi: (() -> Unit)? = null) {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val principale = android.os.Handler(android.os.Looper.getMainLooper())
        if (p.getBoolean(MIGRATA, false)) { poi?.let { principale.post(it) }; return }
        Thread({
            val righe = runCatching { migra(app) }.getOrElse { listOf("migrazione non riuscita: ${it.javaClass.simpleName}") }
            if (righe.none { it.startsWith("migrazione non riuscita") }) p.edit().putBoolean(MIGRATA, true).apply()
            righe.forEach { android.util.Log.i("JarvisCassaforte", it) }
            poi?.let { principale.post(it) }
        }, "cassaforte-migrazione").start()
    }

    /** Porta config-boss.json e modulo-vps.json nella cassaforte. I file di prima restano (tranne il token VPS in chiaro). */
    fun migra(context: android.content.Context): List<String> {
        val c = Cassaforte.di(context)
        val righe = ArrayList<String>()
        var n = 0
        if (com.jarvis.telefono.vps.ConfigVps.portaInCassaforte(context)) { righe += "VPS: indirizzo e token spostati nella cassaforte, file in chiaro tolto"; n++ }
        val f = com.jarvis.telefono.nucleo.ConfigPersonale.file(context)
        val testo = if (f.isFile) runCatching { f.readText() }.getOrDefault("") else ""
        val e = Migrazione.daConfigBoss(testo, giaPresenti = c.elenco().map { it.id }.toSet())
        e.voci.forEach { c.salva(it) }
        n += e.voci.size
        if (n > 0) context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putInt(VOCI_MIGRATE, vociMigrate(context) + n).apply()
        righe += e.righe
        righe += "cassaforte: ${c.riassunto()}"
        return righe
    }
}

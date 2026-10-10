package com.jarvis.telefono.nucleo

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Le preferenze personali di chi usa l'app (Boss, 2026-10-07: «deve nascere già configurata per me»).
 *
 * Il file `config-boss.json` NON sta nel repo né nell'APK: sta sul Mac in
 * una cartella privata del computer (fuori da git) e arriva sul telefono con
 * `scripts/configura-boss.sh` (ADB, banco di prova protetto da DUMP). Senza file l'app usa i
 * valori di fabbrica qui sotto, neutri: nessun nome, nessun indirizzo.
 *
 * Esempio (chiavi tutte facoltative):
 * ```
 * {"filtro_impronta": false, "fine_frase_s": 1.5, "ascolto_sempre_acceso": true,
 *  "volume_segnali": 0.8, "app_mail": "samsung", "account_mail": "nome@example.com",
 *  "chat_se_stesso": "NOME (Tu)", "whatsapp_me": "39XXXXXXXXXX", "lingua": "it"}
 * ```
 * Nessuna password e nessun token: se una chiave «password», «token» o «segreto» compare, si ignora.
 */
data class ConfigPersonale(
    /** false = filtro dell'impronta vocale spento (soglia -1, come la 1.2.1). */
    val filtroImpronta: Boolean = false,
    /** Soglia dell'impronta, usata solo se [filtroImpronta]. */
    val sogliaImpronta: Float = 0.35f,
    /** Secondi di silenzio che chiudono la frase. */
    val fineFraseS: Double = 1.5,
    /** L'ascolto di «Jarvis» resta acceso: il tasto flottante non lo spegne per sempre. */
    val ascoltoSempreAcceso: Boolean = true,
    /** Volume dei segnali 0..1 (null = come il volume multimediale). */
    val volumeSegnali: Float? = null,
    /**
     * «predefinita», «gmail», «samsung» o un pacchetto. Vuoto = l'app di posta predefinita del telefono
     * (qualunque marca). «samsung» vale solo se Samsung Email è installata, altrimenti si usa la predefinita.
     */
    val appMail: String = "",
    /** L'indirizzo per «scrivi una mail a me stesso» (solo il nome dell'account, mai la password). */
    val accountMail: String = "",
    /** Il nome della chat con se stessi su WhatsApp (solo da mostrare: la chat si apre col numero). */
    val chatSeStesso: String = "",
    /**
     * 0.1.1: il numero WhatsApp di chi usa l'app, solo cifre col prefisso («39…»), per «manda un
     * WhatsApp a me stesso». Si apre wa.me/<numero> con la bozza: niente ricerca nella lista chat
     * (0.1.0: il campo di ricerca di WhatsApp non si trovava e non partiva niente). Vuoto = non noto.
     */
    val whatsappMe: String = "",
    val lingua: String = "it",
    /** 0.6.0: l'indirizzo della webapp Jarvis (il sito del Command Center), https. Vuoto = dal ponte (jarvis-agent.X → jarvis.X). */
    val webUrl: String = "",
    /** Da dove viene: «fabbrica» o il percorso del file. */
    val origine: String = "fabbrica",
) {
    /** La soglia da dare a Opzioni.sogliaImpronta (-1 = spento). */
    val sogliaEffettiva: Float get() = if (filtroImpronta) sogliaImpronta else -1f

    /** 0.4.2: chi è Boss per il cervello della VPS («mandami», «a me»). Vuota se la configurazione non lo dice. */
    fun notaPerVps(): String {
        val pezzi = mutableListOf<String>()
        if (whatsappMe.isNotBlank()) pezzi += "Boss stesso su WhatsApp è il numero +${whatsappMe.trimStart('+')}" +
            (if (chatSeStesso.isNotBlank()) " (chat «$chatSeStesso»)" else "")
        if (accountMail.isNotBlank()) pezzi += "la mail di Boss è $accountMail"
        if (appMail.isNotBlank() && appMail != "predefinita") pezzi += "l'app di posta è ${if (appMail == "samsung") "Samsung Email" else appMail}"
        return pezzi.joinToString("; ")
    }

    companion object {
        const val NOME_FILE = "config-boss.json"
        private const val TAG = "JarvisConfig"
        private val VIETATE = Regex("password|token|segreto|secret|chiave_api|api_key", RegexOption.IGNORE_CASE)

        /** Il numero in forma internazionale senza «+» (39…), o vuoto se non è un numero plausibile. */
        fun soloNumero(testo: String): String {
            if (testo.isBlank()) return ""
            val n = com.jarvis.telefono.mani.ComponiIntent.numeroInternazionale(testo) ?: return ""
            return if (n.length in 8..15) n else ""
        }

        /** Dal testo del file; un file rotto vale come assente (si torna ai valori di fabbrica). */
        fun daJson(testo: String, origine: String): ConfigPersonale {
            val o = runCatching { JSONObject(testo) }.getOrNull() ?: return ConfigPersonale(origine = "fabbrica (file non valido)")
            val f = ConfigPersonale()
            fun s(k: String, d: String) = if (o.has(k) && !VIETATE.containsMatchIn(k)) o.optString(k, d).trim() else d
            return ConfigPersonale(
                filtroImpronta = o.optBoolean("filtro_impronta", f.filtroImpronta),
                sogliaImpronta = o.optDouble("soglia_impronta", f.sogliaImpronta.toDouble()).toFloat().coerceIn(0f, 1f),
                fineFraseS = o.optDouble("fine_frase_s", f.fineFraseS).coerceIn(0.6, 7.0),
                ascoltoSempreAcceso = o.optBoolean("ascolto_sempre_acceso", f.ascoltoSempreAcceso),
                volumeSegnali = if (o.has("volume_segnali")) o.optDouble("volume_segnali", 1.0).toFloat().coerceIn(0f, 1f) else null,
                appMail = s("app_mail", f.appMail),
                accountMail = s("account_mail", f.accountMail).takeIf { it.isEmpty() || it.contains('@') }.orEmpty(),
                chatSeStesso = s("chat_se_stesso", f.chatSeStesso),
                whatsappMe = s("whatsapp_me", f.whatsappMe).let { soloNumero(it) },
                lingua = s("lingua", f.lingua).ifEmpty { "it" },
                webUrl = s("web_url", f.webUrl).takeIf { it.startsWith("https://") }.orEmpty(),
                origine = origine,
            )
        }

        /**
         * 0.6.1 (Boss 08/10: «voce regolabile dall'app»): scrive le [valori] nel file della configurazione del telefono,
         * la stessa fonte che leggono la voce ([com.jarvis.telefono.voce.Opzioni] via JarvisService) e la memoria
         * condivisa con Jarvis. null toglie la chiave (torna il valore di fabbrica). Niente segreti ([unisci]).
         * Scrittura atomica (file temporaneo e poi rinomina).
         */
        fun salva(context: Context, valori: Map<String, Any?>): ConfigPersonale {
            val f = file(context)
            // Si parte dal file che vale adesso (anche quello messo nella cartella esterna), così niente si perde.
            val sorgente = listOfNotNull(f, context.getExternalFilesDir(null)?.let { File(it, NOME_FILE) }).firstOrNull { it.isFile }
            val testo = unisci(runCatching { sorgente?.readText() }.getOrNull(), valori)
            synchronized(this) {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(testo)
                if (!tmp.renameTo(f)) { f.writeText(testo); tmp.delete() }
            }
            return ricarica(context)
        }

        /**
         * Il JSON di [attuale] con [valori] sopra (null = togli la chiave). Si ignorano le chiavi dei segreti; «whatsapp_me»
         * si può solo togliere (il numero sta nella cassaforte). Pura: provata in VoceImpostazioniTest.
         */
        fun unisci(attuale: String?, valori: Map<String, Any?>): String {
            val o = runCatching { JSONObject(attuale ?: "") }.getOrElse { JSONObject() }
            for ((k, v) in valori) {
                if (VIETATE.containsMatchIn(k)) continue
                if (v == null) o.remove(k)
                else if (k != "whatsapp_me") o.put(k, v)
            }
            return o.toString()
        }

        /** Il file che scrive il banco ADB (azione «configura»). */
        fun file(context: Context): File = File(context.filesDir, NOME_FILE)

        @Volatile
        private var corrente: ConfigPersonale? = null

        /** Letta una volta, poi in memoria; [ricarica] dopo che lo script l'ha cambiata. */
        fun di(context: Context): ConfigPersonale = corrente ?: ricarica(context)

        fun ricarica(context: Context): ConfigPersonale {
            val candidati = listOfNotNull(file(context), context.getExternalFilesDir(null)?.let { File(it, NOME_FILE) })
            val f = candidati.firstOrNull { it.isFile }
            val dalFile = if (f == null) ConfigPersonale() else daJson(runCatching { f.readText() }.getOrDefault(""), f.path)
            // 0.6.1: il numero WhatsApp di Boss sta nella cassaforte cifrata (si cambia da Impostazioni → Voce);
            // il file vale solo se la cassaforte non ce l'ha.
            val wa = runCatching {
                (com.jarvis.telefono.cassaforte.Cassaforte.di(context).leggi(com.jarvis.telefono.cassaforte.Altro.WHATSAPP_ME)
                    as? com.jarvis.telefono.cassaforte.Altro)?.valore
            }.getOrNull()?.let { soloNumero(it) }.orEmpty()
            val c = if (wa.isNotEmpty()) dalFile.copy(whatsappMe = wa) else dalFile
            corrente = c
            Log.i(TAG, "configurazione: ${c.origine}, fine frase ${c.fineFraseS} s, filtro impronta ${c.filtroImpronta}, whatsapp a me ${if (c.whatsappMe.isEmpty()) "no" else "sì"}")
            return c
        }
    }
}

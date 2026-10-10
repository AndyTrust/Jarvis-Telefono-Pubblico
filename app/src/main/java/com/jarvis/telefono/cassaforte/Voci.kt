package com.jarvis.telefono.cassaforte

import org.json.JSONObject

/**
 * Le voci della Cassaforte (JBoss 0.3.1, 2026-10-07): tutto quello che serve per far lavorare l'app
 * senza file personali. I segreti (password, token, chiavi) stanno solo qui dentro, cifrati.
 *
 * Regola: nessun segreto in [toString] (finisce in log, crash e debugger). Si vede solo «•••• (N)».
 */
sealed class Voce {
    abstract val id: String
    abstract val etichetta: String
    abstract val genere: Genere

    /** Il JSON completo, segreti compresi: si usa SOLO dentro la cassaforte (cifrato) o verso la VPS (WSS). */
    abstract fun json(): JSONObject

    enum class Genere(val chiave: String) { MAIL("mail"), CERVELLO("cervello"), VPS("vps"), GOOGLE("google"), ALTRO("altro"), SITO("sito") }

    companion object {
        fun mascherato(s: String?): String = if (s.isNullOrEmpty()) "(vuoto)" else "•••• (${s.length})"

        fun daJson(o: JSONObject): Voce? = runCatching {
            when (o.optString("genere")) {
                "mail" -> AccountMail.daJson(o)
                "cervello" -> Cervello.daJson(o)
                "vps" -> Vps.daJson(o)
                "google" -> Google.daJson(o)
                "altro" -> Altro.daJson(o)
                "sito" -> AccessoSito.daJson(o)
                else -> null
            }
        }.getOrNull()
    }
}

enum class TipoMail(val chiave: String) {
    GMAIL("gmail"), OUTLOOK("outlook"), ICLOUD("icloud"), SAMSUNG("samsung"), PEC("pec"),
    HOSTINGER("hostinger"), LIBERO("libero"), ARUBA("aruba"), GENERICO("generico");

    companion object { fun da(s: String?): TipoMail = values().firstOrNull { it.chiave == s } ?: GENERICO }
}

/** ssl = TLS subito (993/465); starttls = in chiaro e poi TLS (143/587); nessuna = solo per le prove locali. */
enum class Sicurezza(val chiave: String) {
    SSL("ssl"), STARTTLS("starttls"), NESSUNA("nessuna");

    companion object { fun da(s: String?): Sicurezza = values().firstOrNull { it.chiave == s } ?: SSL }
}

data class ServerPosta(val host: String, val porta: Int, val sicurezza: Sicurezza = Sicurezza.SSL) {
    fun json(): JSONObject = JSONObject().put("host", host).put("porta", porta).put("sicurezza", sicurezza.chiave)
    val valido: Boolean get() = host.matches(Regex("[A-Za-z0-9.-]{3,253}")) && host.contains('.') && porta in 1..65535

    companion object {
        fun daJson(o: JSONObject?): ServerPosta? = o?.let { ServerPosta(it.optString("host"), it.optInt("porta"), Sicurezza.da(it.optString("sicurezza"))) }
    }
}

/** Le cartelle se hanno nomi strani (il Postino le trova da solo con i flag \Sent, \Trash, \Drafts). */
data class Cartelle(val inviata: String = "", val cestino: String = "", val bozze: String = "") {
    val vuote: Boolean get() = inviata.isBlank() && cestino.isBlank() && bozze.isBlank()
    fun json(): JSONObject = JSONObject().put("inviata", inviata).put("cestino", cestino).put("bozze", bozze)

    companion object {
        fun daJson(o: JSONObject?): Cartelle = if (o == null) Cartelle() else Cartelle(o.optString("inviata"), o.optString("cestino"), o.optString("bozze"))
    }
}

data class AccountMail(
    override val id: String,
    override val etichetta: String,
    val indirizzo: String,
    /** Vuoto = l'indirizzo. */
    val utente: String = "",
    /** La password o la password per app. Vuota per Samsung Email (app del telefono, nessun server). */
    val password: String = "",
    val imap: ServerPosta? = null,
    val smtp: ServerPosta? = null,
    val cartelle: Cartelle = Cartelle(),
    val tipo: TipoMail = TipoMail.GENERICO,
    /** Sulla VPS c'è (account_esito ok): il Postino la legge. */
    val suVps: Boolean = false,
) : Voce() {
    override val genere = Genere.MAIL
    val utenteEffettivo: String get() = utente.ifBlank { indirizzo }

    override fun json(): JSONObject = JSONObject().put("genere", genere.chiave).put("id", id).put("etichetta", etichetta)
        .put("indirizzo", indirizzo).put("utente", utente).put("password", password)
        .put("imap", imap?.json()).put("smtp", smtp?.json()).put("cartelle", cartelle.json())
        .put("tipo", tipo.chiave).put("su_vps", suVps)

    override fun toString(): String =
        "AccountMail(id=$id, etichetta=$etichetta, indirizzo=$indirizzo, tipo=${tipo.chiave}, password=${mascherato(password)}, imap=$imap, smtp=$smtp, suVps=$suVps)"

    companion object {
        fun daJson(o: JSONObject) = AccountMail(
            id = o.getString("id"), etichetta = o.optString("etichetta"), indirizzo = o.optString("indirizzo"),
            utente = o.optString("utente"), password = o.optString("password"),
            imap = ServerPosta.daJson(o.optJSONObject("imap")), smtp = ServerPosta.daJson(o.optJSONObject("smtp")),
            cartelle = Cartelle.daJson(o.optJSONObject("cartelle")), tipo = TipoMail.da(o.optString("tipo")),
            suVps = o.optBoolean("su_vps"),
        )
    }
}

enum class ProviderCervello(val chiave: String, val nome: String) {
    CLAUDE_CODE_VPS("claude-code-vps", "Claude Code sulla VPS (abbonamento)"),
    API_ANTHROPIC("api-anthropic", "Chiave API Anthropic"),
    API_OPENAI("api-openai", "Chiave API OpenAI"),
    CODEX("codex", "Codex sulla VPS");

    companion object { fun da(s: String?): ProviderCervello? = values().firstOrNull { it.chiave == s } }
}

data class Cervello(
    val provider: ProviderCervello,
    val token: String,
    val modello: String = "",
    override val etichetta: String = provider.nome,
) : Voce() {
    override val id: String get() = "cervello-" + provider.chiave
    override val genere = Genere.CERVELLO
    override fun json(): JSONObject = JSONObject().put("genere", genere.chiave).put("id", id).put("etichetta", etichetta)
        .put("provider", provider.chiave).put("token", token).put("modello", modello)
    override fun toString(): String = "Cervello(provider=${provider.chiave}, modello=$modello, token=${mascherato(token)})"

    companion object {
        fun daJson(o: JSONObject): Cervello? {
            val p = ProviderCervello.da(o.optString("provider")) ?: return null
            return Cervello(p, o.optString("token"), o.optString("modello"), o.optString("etichetta").ifBlank { p.nome })
        }
    }
}

data class Vps(val url: String, val token: String, override val etichetta: String = "VPS") : Voce() {
    override val id: String get() = ID
    override val genere = Genere.VPS
    val completa: Boolean get() = (url.startsWith("wss://") || url.startsWith("ws://")) && token.length >= 16
    override fun json(): JSONObject = JSONObject().put("genere", genere.chiave).put("id", id).put("etichetta", etichetta).put("url", url).put("token", token)
    override fun toString(): String = "Vps(url=${url.substringBefore('?')}, token=${mascherato(token)})"

    companion object {
        const val ID = "vps"
        fun daJson(o: JSONObject) = Vps(o.optString("url"), o.optString("token"), o.optString("etichetta").ifBlank { "VPS" })
    }
}

/** Google: nessun segreto. Lo stato dice come JBoss arriva ai dati Google (vedi docs/CONFIGURAZIONE.md, «Google»). */
data class Google(
    val account: String,
    /** «telefono» = account già sul telefono, usato dalle app Google tramite l'accessibilità; «imap» = Gmail con password per app. */
    val stato: String = "telefono",
    override val etichetta: String = "Google",
) : Voce() {
    override val id: String get() = ID
    override val genere = Genere.GOOGLE
    override fun json(): JSONObject = JSONObject().put("genere", genere.chiave).put("id", id).put("etichetta", etichetta).put("account", account).put("stato", stato)

    companion object {
        const val ID = "google"
        fun daJson(o: JSONObject) = Google(o.optString("account"), o.optString("stato", "telefono"), o.optString("etichetta").ifBlank { "Google" })
    }
}

/** Un valore libero (numero WhatsApp personale, ecc.). [segreto] = true lo tratta come una password. */
data class Altro(override val id: String, override val etichetta: String, val valore: String, val segreto: Boolean = false) : Voce() {
    override val genere = Genere.ALTRO
    override fun json(): JSONObject = JSONObject().put("genere", genere.chiave).put("id", id).put("etichetta", etichetta).put("valore", valore).put("segreto", segreto)
    override fun toString(): String = "Altro(id=$id, etichetta=$etichetta, valore=${if (segreto) mascherato(valore) else valore})"

    companion object {
        const val WHATSAPP_ME = "whatsapp-me"
        fun daJson(o: JSONObject) = Altro(o.getString("id"), o.optString("etichetta"), o.optString("valore"), o.optBoolean("segreto"))
    }
}

/**
 * 0.5.0, categoria «Accessi siti» (Boss 08/10): link, utente, password, note. Regola di Boss: i siti si usano SOLO
 * con email (o utente) e password, mai con «Accedi con Google» o altri social. Stesso JSON della cassaforte della
 * webapp Mac/Windows: {"genere":"sito","id","etichetta","link","utente","password","note"}.
 * La password si mostra solo con impronta o PIN (PannelloSicurezza) e non va mai nei log ([toString] la maschera).
 */
data class AccessoSito(
    override val id: String,
    override val etichetta: String,
    val link: String,
    val utente: String,
    val password: String,
    val note: String = "",
) : Voce() {
    override val genere = Genere.SITO
    override fun json(): JSONObject = JSONObject().put("genere", genere.chiave).put("id", id).put("etichetta", etichetta)
        .put("link", link).put("utente", utente).put("password", password).put("note", note)
    override fun toString(): String = "AccessoSito(id=$id, etichetta=$etichetta, link=${link.substringBefore('?')}, utente=$utente, password=${mascherato(password)})"

    companion object {
        /** Gli accessi «con Google / Facebook / Apple» non si salvano: la regola è email e password. */
        private val SOCIAL = Regex("\\b(accedi con|login with|sign in with|continua con|continue with)\\s+(google|facebook|apple|microsoft|linkedin|x|twitter)\\b", RegexOption.IGNORE_CASE)

        /** null = valido; altrimenti il motivo in italiano. */
        fun valida(link: String, utente: String, password: String, note: String = ""): String? = when {
            !Regex("^(https?://)?[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/.*)?$").matches(link.trim()) -> "Il link non sembra un indirizzo di sito."
            utente.isBlank() -> "Manca l'utente (di solito l'email)."
            password.isBlank() -> "Manca la password: si salvano solo accessi con email e password, non «Accedi con Google»."
            SOCIAL.containsMatchIn(note) || SOCIAL.containsMatchIn(utente) -> "Niente «Accedi con Google» o altri social: serve un accesso con email e password."
            else -> null
        }

        fun idPer(link: String): String = "sito-" + link.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore('/').replace(Regex("[^a-z0-9.-]"), "-")

        fun daJson(o: JSONObject) = AccessoSito(
            o.getString("id"), o.optString("etichetta"), o.optString("link"), o.optString("utente"), o.optString("password"), o.optString("note"),
        )
    }
}

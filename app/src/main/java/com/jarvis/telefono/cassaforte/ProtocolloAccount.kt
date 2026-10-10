package com.jarvis.telefono.cassaforte

import org.json.JSONArray
import org.json.JSONObject

/**
 * I messaggi account_* sul canale «lavori» della VPS (docs/PROTOCOLLO-VPS.md §8, 2026-10-07).
 * Giro di una casella: 1) [anteprima] senza password → la VPS dice cosa cambierà; 2) Boss conferma sul telefono
 * (impronta o PIN); 3) [salvaCasella] con la password e «conferma: si» → la VPS scrive .env.jarvis (600) e
 * posta-caselle.json. La password viaggia una volta sola, solo sul WSS autenticato.
 */
object ProtocolloAccount {

    /** Un messaggio con un segreto parte solo su wss:// (o ws:// verso il telefono stesso, per le prove). */
    fun puoPartire(url: String, messaggio: String): Boolean {
        val segreto = runCatching { JSONObject(messaggio).optJSONObject("dati")?.let { it.has("password") || it.has("token") } == true }.getOrDefault(true)
        if (!segreto) return true
        return url.startsWith("wss://") || Regex("^ws://(127\\.0\\.0\\.1|localhost)(:|/)").containsMatchIn(url)
    }

    fun lista(): String = JSONObject().put("type", "account_lista").toString()

    /** I dati di una casella per la VPS. Con [conPassword] = false la password non parte (anteprima). */
    fun datiCasella(a: AccountMail, conPassword: Boolean): JSONObject {
        val d = JSONObject()
            .put("indirizzo", a.indirizzo)
            .put("tipo", a.tipo.chiave)
            .put("azienda", a.etichetta.take(60))
            .put("imap", a.imap?.json())
            .put("smtp", a.smtp?.json())
        if (a.utente.isNotBlank() && a.utente != a.indirizzo) d.put("utente", a.utente)
        if (!a.cartelle.vuote) d.put("cartelle", a.cartelle.json())
        if (conPassword && a.password.isNotEmpty()) d.put("password", a.password)
        return d
    }

    /** L'id della casella sulla VPS: minuscole, cifre, trattini (2-30). Dall'id della cassaforte senza «mail-». */
    fun idVps(a: AccountMail): String =
        a.id.removePrefix("mail-").lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(30).let { if (it.length < 2) "jb-$it" else it }

    fun anteprimaCasella(richiestaId: String, a: AccountMail, sostituisci: Boolean = false): String =
        JSONObject().put("type", "account_set").put("richiesta_id", richiestaId).put("tipo", "casella").put("id", idVps(a))
            .put("dati", datiCasella(a, conPassword = false)).put("anteprima", true).put("sostituisci", sostituisci).toString()

    /** Il messaggio vero: parte SOLO dopo il sì di Boss sul telefono. */
    fun salvaCasella(richiestaId: String, a: AccountMail, sostituisci: Boolean = false): String =
        JSONObject().put("type", "account_set").put("richiesta_id", richiestaId).put("tipo", "casella").put("id", idVps(a))
            .put("dati", datiCasella(a, conPassword = true)).put("conferma", "si").put("sostituisci", sostituisci).toString()

    fun eliminaCasella(richiestaId: String, idVps: String): String =
        JSONObject().put("type", "account_elimina").put("richiesta_id", richiestaId).put("tipo", "casella").put("id", idVps).put("conferma", "si").toString()

    fun anteprimaCervello(richiestaId: String, c: Cervello): String =
        JSONObject().put("type", "account_set").put("richiesta_id", richiestaId).put("tipo", "cervello").put("id", c.provider.chiave)
            .put("dati", JSONObject()).put("anteprima", true).toString()

    fun salvaCervello(richiestaId: String, c: Cervello, sostituisci: Boolean = false): String =
        JSONObject().put("type", "account_set").put("richiesta_id", richiestaId).put("tipo", "cervello").put("id", c.provider.chiave)
            .put("dati", JSONObject().put("token", c.token)).put("conferma", "si").put("sostituisci", sostituisci).toString()

    /** Le risposte della VPS. null se non è un messaggio account. */
    fun leggi(testo: String): RispostaAccount? = runCatching { leggi(JSONObject(testo)) }.getOrNull()

    fun leggi(o: JSONObject): RispostaAccount? = when (o.optString("type")) {
        "account_anteprima" -> RispostaAccount.Anteprima(
            o.optString("richiesta_id"), o.optString("tipo"), o.optString("id"), o.optString("azione"),
            stringhe(o.optJSONArray("cosa")), stringhe(o.optJSONArray("avvisi")),
        )
        "account_esito" -> RispostaAccount.Esito(o.optString("richiesta_id"), o.optString("tipo"), o.optString("id"), o.optBoolean("ok"),
            o.optString("testo"), stringhe(o.optJSONArray("avvisi")))
        "account_errore" -> RispostaAccount.Errore(o.optString("richiesta_id"), o.optString("motivo"))
        "account_lista" -> {
            val a = o.optJSONArray("caselle") ?: JSONArray()
            val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                CasellaVps(
                    it.optString("id"), it.optString("indirizzo"), it.optString("tipo"), it.optBoolean("password"), it.optBoolean("da_app"),
                    azienda = it.optString("azienda"), imap = server(it.opt("imap")), smtp = server(it.opt("smtp")),
                )
            }
            val c = o.optJSONObject("cervello") ?: JSONObject()
            RispostaAccount.Lista(l, c.keys().asSequence().associateWith { c.optBoolean(it) })
        }
        else -> null
    }

    /** La VPS scrive i server come ["host", porta] (posta-caselle.json) o {host, porta}. */
    private fun server(v: Any?): ServerPosta? = when (v) {
        is JSONArray -> v.optString(0).takeIf { it.isNotBlank() }?.let { ServerPosta(it, v.optInt(1, 993)) }
        is JSONObject -> v.optString("host").takeIf { it.isNotBlank() }?.let { ServerPosta(it, v.optInt("porta", v.optInt("port", 993))) }
        else -> null
    }

    private fun stringhe(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
}

data class CasellaVps(
    val id: String, val indirizzo: String, val tipo: String, val password: Boolean, val daApp: Boolean,
    val azienda: String = "", val imap: ServerPosta? = null, val smtp: ServerPosta? = null,
)

/**
 * 0.5.0 (Boss 08/10: «tutte le caselle devono essere configurate e usabili dal Postino; nell'app c'è solo Samsung
 * Email»). Le caselle che il Postino legge sulla VPS entrano nella cassaforte del telefono come schede SENZA
 * password: la password resta solo in /root/.env.jarvis (fonte unica dei segreti, regola di Boss). Il telefono le
 * mostra in Account mail con «la legge il Postino». Kotlin puro: AllineaCaselleTest.
 */
object AllineaCaselle {
    fun idTelefono(c: CasellaVps) = "vps-" + c.id.lowercase().replace(Regex("[^a-z0-9_-]"), "-")

    fun tipoDa(c: CasellaVps): TipoMail {
        val host = (c.imap?.host ?: "").lowercase()
        TipoMail.values().firstOrNull { it.chiave == c.tipo.lowercase() }?.let { return it }
        return when {
            c.indirizzo.endsWith("@gmail.com") || host.contains("gmail") -> TipoMail.GMAIL
            host.contains("hostinger") -> TipoMail.HOSTINGER
            host.contains("sicurezzapostale") || host.contains("pec") || host.contains("legalmail") -> TipoMail.PEC
            host.contains("aruba") -> TipoMail.ARUBA
            else -> TipoMail.GENERICO
        }
    }

    /** Le schede da salvare: nuove per gli indirizzi che il telefono non ha, «suVps» acceso per quelle che ha già. */
    fun daSalvare(locali: List<AccountMail>, vps: List<CasellaVps>): List<AccountMail> {
        val fuori = mutableListOf<AccountMail>()
        for (c in vps) {
            if (c.indirizzo.isBlank() || !c.indirizzo.contains('@')) continue
            val gia = locali.firstOrNull { it.indirizzo.equals(c.indirizzo, ignoreCase = true) && it.tipo != TipoMail.SAMSUNG }
            if (gia != null) {
                if (!gia.suVps) fuori += gia.copy(suVps = true)
                continue
            }
            fuori += AccountMail(
                id = idTelefono(c),
                etichetta = c.azienda.ifBlank { c.id },
                indirizzo = c.indirizzo,
                password = "",
                imap = c.imap,
                smtp = c.smtp,
                tipo = tipoDa(c),
                suVps = true,
            )
        }
        return fuori
    }
}

sealed class RispostaAccount {
    abstract val richiestaId: String
    data class Anteprima(override val richiestaId: String, val tipo: String, val id: String, val azione: String, val cosa: List<String>, val avvisi: List<String>) : RispostaAccount()
    data class Esito(override val richiestaId: String, val tipo: String, val id: String, val ok: Boolean, val testo: String, val avvisi: List<String>) : RispostaAccount()
    data class Errore(override val richiestaId: String, val motivo: String) : RispostaAccount()
    data class Lista(val caselle: List<CasellaVps>, val cervello: Map<String, Boolean>) : RispostaAccount() { override val richiestaId = "" }
}

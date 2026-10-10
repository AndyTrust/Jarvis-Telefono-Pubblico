package com.jarvis.telefono.collegamento

import org.json.JSONArray
import org.json.JSONObject

/**
 * La memoria condivisa fra JBoss e Jarvis (0.6.0, Boss 08/10: «JBoss verifica e parla con l'integrazione per
 * allineare memorie, usi e costumi personali di Boss»). Kotlin puro, provato in MemoriaCondivisaTest.
 * Protocollo completo: docs/COLLEGAMENTO-JARVIS.md; lato VPS: jarvis-agent/server/memoriaTelefono.js.
 *
 * - La memoria vera sta sulla VPS: grafo delle regole di Boss (memoria_cerca/apri/vicini) e i «fatti».
 *   Il telefono riceve un RIASSUNTO (due nodi per tema: scrittura, conferme, voce, posta, segreti, date, patrimonio),
 *   i contesti seguiti e i fatti; lo tiene nella cassaforte cifrata (voce «memoria-condivisa»).
 * - Il telefono manda i SUOI fatti: le preferenze della sua configurazione (app di posta, fine frase, parole…).
 *   Mai segreti: le chiavi con password, token, segreto, api_key non partono (e la VPS le rifiuta comunque).
 * - Conflitto (Jarvis ha un valore diverso, o Boss aveva già deciso): nessuno sovrascrive. JBoss lo mostra in
 *   Impostazioni e decide Boss (POST /memoria/decidi). Se vince Jarvis, il telefono scrive il valore nella sua
 *   configurazione. Un fatto che il telefono non ha (vuoto) e Jarvis sì si adotta senza chiedere: riempie un buco.
 * - Quando: all'accensione del collegamento, all'apertura dell'app se l'ultimo giro ha più di [OGNI_MS], dopo
 *   «configura», e a richiesta («allinea la memoria», pulsante in Impostazioni).
 */
object MemoriaCondivisa {

    const val OGNI_MS = 6 * 3600_000L
    private val VIETATE = Regex("password|token|segreto|secret|api_?key|chiave_api", RegexOption.IGNORE_CASE)

    /** Le chiavi della configurazione del telefono che sono «usi e costumi» di Boss. */
    val CHIAVI = listOf(
        "app_mail", "account_mail", "whatsapp_me", "chat_se_stesso", "lingua", "fine_frase_s",
        "ascolto_sempre_acceso", "filtro_impronta", "volume_segnali", "parole_attivazione", "trascrittore",
    )

    data class Fatto(val chiave: String, val valore: String, val ts: Long, val fonte: String = "telefono", val decisoDaBoss: Long? = null)
    data class Conflitto(val chiave: String, val telefono: String, val jarvis: String)
    data class Voce(val tema: String, val titolo: String, val testo: String, val data: String)
    /** Un contesto seguito come lo descrive la VPS (id «crm» o «patrimonio», nome, indirizzo da aprire). */
    data class ContestoVps(val id: String, val nome: String, val indirizzo: String)
    data class Esito(
        val voci: List<Voce>,
        val fattiVps: List<Fatto>,
        val conflitti: List<Conflitto>,
        val contesti: List<ContestoVps>,
        val ora: Long,
    ) {
        fun indirizzo(id: String): String? = contesti.firstOrNull { it.id == id }?.indirizzo?.takeIf { it.startsWith("https://") }
    }

    /** I fatti da mandare, dalla configurazione del telefono (JSON di config-boss.json + scelte della voce). */
    fun fattiDelTelefono(config: JSONObject, extra: Map<String, String>, ts: Long): List<Fatto> {
        val out = mutableListOf<Fatto>()
        for (k in CHIAVI) {
            if (VIETATE.containsMatchIn(k)) continue
            val v = when {
                extra.containsKey(k) -> extra[k]
                config.has(k) && !config.isNull(k) -> config.opt(k)?.toString()
                else -> null
            } ?: continue
            if (v.isBlank() || v.length > 200) continue
            out += Fatto(k, v, ts)
        }
        return out
    }

    fun corpoAllinea(fatti: List<Fatto>, versione: String): String = JSONObject()
        .put("app", "jboss").put("versione", versione)
        .put("fatti", JSONArray().apply { fatti.forEach { put(JSONObject().put("chiave", it.chiave).put("valore", it.valore).put("ts", it.ts)) } })
        .toString()

    fun corpoDecidi(chiave: String, vinceTelefono: Boolean): String =
        JSONObject().put("chiave", chiave).put("vince", if (vinceTelefono) "telefono" else "vps").toString()

    private fun fatti(a: JSONArray?): List<Fatto> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val o = a!!.optJSONObject(i) ?: return@mapNotNull null
        Fatto(o.optString("chiave"), o.optString("valore"), o.optLong("ts"), o.optString("fonte", "vps"),
            if (o.isNull("decisoDaBoss") || !o.has("decisoDaBoss")) null else o.optLong("decisoDaBoss"))
    }

    private fun conflitti(a: JSONArray?): List<Conflitto> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val o = a!!.optJSONObject(i) ?: return@mapNotNull null
        Conflitto(o.optString("chiave"), o.optJSONObject("telefono")?.optString("valore").orEmpty(), o.optJSONObject("vps")?.optString("valore").orEmpty())
    }

    /** La risposta di /memoria/allinea unita a quella di /memoria/profilo (ognuna può mancare). */
    fun leggi(allinea: JSONObject?, profilo: JSONObject?): Esito {
        val voci = profilo?.optJSONArray("voci")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Voce(it.optString("tema"), it.optString("titolo"), it.optString("testo"), it.optString("data")) }
        }.orEmpty()
        val contesti = profilo?.optJSONArray("contesti")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { ContestoVps(it.optString("id"), it.optString("nome"), it.optString("indirizzo")) }
        }.orEmpty()
        val fattiVps = fatti(allinea?.optJSONArray("fatti") ?: profilo?.optJSONArray("fatti"))
        val conf = conflitti(allinea?.optJSONArray("conflitti") ?: profilo?.optJSONArray("conflitti"))
        return Esito(voci, fattiVps, conf, contesti, allinea?.optLong("ora") ?: profilo?.optLong("ora") ?: 0L)
    }

    /** I fatti di Jarvis che il telefono prende senza chiedere: chiave nota, vuota sul telefono, valore presente su Jarvis. */
    fun daAdottare(telefono: List<Fatto>, vps: List<Fatto>): List<Fatto> {
        val miei = telefono.associateBy { it.chiave }
        return vps.filter { it.chiave in CHIAVI && it.fonte != "telefono" && it.valore.isNotBlank() && miei[it.chiave] == null }
    }

    /** Il riassunto da dire a voce («cosa sai di me»): temi e prime regole, corto. */
    fun riassuntoVoce(e: Esito, max: Int = 5): String {
        if (e.voci.isEmpty() && e.fattiVps.isEmpty()) return "Non ho ancora allineato la memoria con Jarvis: accendi il Collegamento Jarvis e dimmi «allinea la memoria»."
        val temi = e.voci.groupBy { it.tema }.entries.take(max).joinToString(". ") { (tema, v) -> "${tema.replaceFirstChar { it.uppercase() }}: ${v.first().titolo.trimEnd('.')}" }
        val conf = if (e.conflitti.isEmpty()) "" else " Ci sono ${e.conflitti.size} cose su cui il telefono e Jarvis non sono d'accordo: decidi tu in Impostazioni."
        val seguo = if (e.contesti.isEmpty()) "" else " Seguo in sola lettura: ${e.contesti.joinToString(" e ") { it.nome }}."
        return "So queste cose di te. $temi.$seguo$conf"
    }

    fun salva(e: Esito): String = JSONObject()
        .put("ora", e.ora)
        .put("voci", JSONArray().apply { e.voci.forEach { put(JSONObject().put("tema", it.tema).put("titolo", it.titolo).put("testo", it.testo).put("data", it.data)) } })
        .put("fatti", JSONArray().apply { e.fattiVps.forEach { put(JSONObject().put("chiave", it.chiave).put("valore", it.valore).put("ts", it.ts).put("fonte", it.fonte).put("decisoDaBoss", it.decisoDaBoss ?: JSONObject.NULL)) } })
        .put("conflitti", JSONArray().apply { e.conflitti.forEach { put(JSONObject().put("chiave", it.chiave).put("telefono", JSONObject().put("valore", it.telefono)).put("vps", JSONObject().put("valore", it.jarvis))) } })
        .put("contesti", JSONArray().apply { e.contesti.forEach { put(JSONObject().put("id", it.id).put("nome", it.nome).put("indirizzo", it.indirizzo)) } })
        .toString()

    fun carica(testo: String?): Esito? {
        if (testo.isNullOrBlank()) return null
        val o = runCatching { JSONObject(testo) }.getOrNull() ?: return null
        return leggi(JSONObject().put("ora", o.optLong("ora")).put("fatti", o.optJSONArray("fatti")).put("conflitti", o.optJSONArray("conflitti")), o)
    }

    /** `wss://host/phone` → `https://host` (la base delle rotte HTTP del ponte). */
    fun base(urlVps: String): String? {
        val u = urlVps.trim()
        val schema = when {
            u.startsWith("wss://") -> "https://"
            u.startsWith("ws://") -> "http://"
            else -> return null
        }
        val host = u.substringAfter("://").substringBefore('/').substringBefore('?')
        return if (host.isBlank()) null else schema + host
    }
}

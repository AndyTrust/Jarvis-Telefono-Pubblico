package com.jarvis.telefono.collegamento

import org.json.JSONArray
import org.json.JSONObject

/**
 * La coda UNICA delle notifiche di JBoss (0.6.0, Boss 08/10: «un'app unica, sennò rischiamo conflitti anche con
 * comunicazioni e notifiche»). Kotlin puro, provato in CodaNotificheTest.
 *
 * Tutte le fonti passano di qui prima di diventare una notifica Android:
 *   «report»     i report di Jarvis e del Postino dal Command Center (GET /notifiche del ponte, prima nell'app vecchia)
 *   «lavoro»     un lavoro sulla VPS finito (NotificheVps.fine)
 *   «sottofondo» un avviso di Jarvis fuori turno sul canale mani (Arbitro.Chi.NOTIFICA)
 *   «contesto»   un evento dei contesti seguiti (CRM di lavoro, Patrimonio): solo se importante
 *
 * Regole:
 * - Doppione per id: la stessa chiave (id del ponte, id del lavoro) passa una volta sola, anche dopo un riavvio.
 * - Doppione per contenuto: stesso titolo e stesso inizio del testo entro [finestraMs] (30 minuti), da qualunque fonte:
 *   il report del Postino che arriva sia come lavoro finito sia dal Command Center diventa UNA notifica.
 * - Contesti seguiti (k = «crm», «patrimonio»): solo con importanza alta; gli altri restano in silenzio (su richiesta).
 * - Le risposte della chat del sito non entrano mai (le ferma prima l'Arbitro).
 * Lo stato (ultime [memoria] chiavi e impronte) si salva come JSON nelle preferenze dell'app.
 */
class CodaNotifiche(
    private val finestraMs: Long = 30 * 60_000L,
    private val memoria: Int = 300,
) {
    data class Notifica(
        val chiave: String,
        val fonte: String,
        val titolo: String,
        val testo: String,
        val ts: Long,
        /** «postino», «notifiche-jarvis», «crm», «patrimonio»… (il filo della webapp che la notifica apre). */
        val filo: String = "notifiche-jarvis",
        val importante: Boolean = false,
    )

    enum class Esito { MOSTRA, DOPPIONE, SILENZIO }

    private val chiavi = LinkedHashMap<String, Long>()
    private val impronte = LinkedHashMap<String, Long>()

    @Synchronized
    fun entra(n: Notifica, ora: Long = System.currentTimeMillis()): Esito {
        if (n.chiave.isNotBlank() && chiavi.containsKey(n.chiave)) return Esito.DOPPIONE
        val imp = impronta(n)
        val vista = impronte[imp]
        if (vista != null && ora - vista < finestraMs) {
            ricorda(n.chiave, ora)
            return Esito.DOPPIONE
        }
        ricorda(n.chiave, ora)
        impronte[imp] = ora
        while (impronte.size > memoria) impronte.remove(impronte.keys.first())
        if (n.filo in Contesti.SEGUITI && !n.importante) return Esito.SILENZIO
        return Esito.MOSTRA
    }

    private fun ricorda(chiave: String, ora: Long) {
        if (chiave.isBlank()) return
        chiavi[chiave] = ora
        while (chiavi.size > memoria) chiavi.remove(chiavi.keys.first())
    }

    @Synchronized
    fun salva(): String = JSONObject()
        .put("chiavi", JSONArray().apply { chiavi.forEach { (k, t) -> put(JSONArray().put(k).put(t)) } })
        .put("impronte", JSONArray().apply { impronte.forEach { (k, t) -> put(JSONArray().put(k).put(t)) } })
        .toString()

    @Synchronized
    fun carica(testo: String?) {
        if (testo.isNullOrBlank()) return
        val o = runCatching { JSONObject(testo) }.getOrNull() ?: return
        fun leggi(a: JSONArray?, m: MutableMap<String, Long>) {
            if (a == null) return
            for (i in 0 until a.length()) a.optJSONArray(i)?.let { m[it.optString(0)] = it.optLong(1) }
        }
        leggi(o.optJSONArray("chiavi"), chiavi)
        leggi(o.optJSONArray("impronte"), impronte)
    }

    companion object {
        /** Titolo + primi 80 caratteri del testo, senza maiuscole, spazi e punteggiatura. */
        fun impronta(n: Notifica): String {
            fun pulisci(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            val titolo = pulisci(n.titolo).removePrefix("postino ").removePrefix("jarvis ")
            return titolo + "|" + pulisci(n.testo).take(80)
        }

        /** Una notifica di GET /notifiche ({id, ts, k, mittente, titolo, testo, priorita?}). */
        fun daPonte(o: JSONObject): Notifica {
            val k = o.optString("k")
            val filo = when (k) {
                "postino" -> "postino"
                in Contesti.SEGUITI -> k
                else -> "notifiche-jarvis"
            }
            val chi = o.optString("mittente").ifBlank { if (filo == "postino") "Postino" else "Jarvis" }
            val titolo = o.optString("titolo").ifBlank { chi }
            val importante = o.optString("priorita").lowercase() in setOf("alta", "importante", "urgente") || o.optBoolean("importante")
            return Notifica("ponte:" + o.optString("id"), "report", "$chi · $titolo", o.optString("testo"),
                (o.optDouble("ts", 0.0) * 1000).toLong(), filo, importante)
        }
    }
}

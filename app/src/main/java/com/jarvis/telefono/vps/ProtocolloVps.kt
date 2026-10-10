package com.jarvis.telefono.vps

import org.json.JSONObject

/**
 * Il protocollo del modulo VPS (docs/PROTOCOLLO-VPS.md, versione 1, 2026-10-07): i messaggi JSON
 * sul WebSocket del ponte, in Kotlin puro (provati sulla JVM in ProtocolloVpsTest).
 *
 * Il socket si presenta con `ruolo: "lavori"`: per la VPS non è il telefono delle mani, quindi
 * non sostituisce il collegamento dell'app com.jarvis.app né riceve `tool_call`.
 */
object ProtocolloVps {
    const val VERSIONE = 1

    fun auth(token: String): String =
        JSONObject().put("type", "auth").put("token", token).put("ruolo", "lavori").put("versione", VERSIONE).toString()

    fun jobStart(id: String, agente: String, testo: String, modello: String = "sonnet", maxMin: Int = 30, titolo: String? = null): String {
        val op = JSONObject().put("modello", if (modello == "opus") "opus" else "sonnet").put("max_min", maxMin.coerceIn(1, 60))
        if (!titolo.isNullOrBlank()) op.put("titolo", titolo.take(120))
        return JSONObject().put("type", "job_start").put("id", id).put("agente", agente).put("testo", testo).put("opzioni", op).toString()
    }

    fun jobCancel(id: String): String = JSONObject().put("type", "job_cancel").put("id", id).toString()

    /** [scelta] è sempre «invia» o «annulla»: qualunque altra cosa diventa «annulla». */
    fun conferma(id: String, azioneId: String, scelta: String): String =
        JSONObject().put("type", "conferma").put("id", id).put("azione_id", azioneId)
            .put("scelta", if (scelta == INVIA) INVIA else ANNULLA).toString()

    fun jobLista(limite: Int = 30): String = JSONObject().put("type", "job_lista").put("limite", limite.coerceIn(1, 100)).toString()

    fun jobSegui(id: String, ultimoEvento: Int): String =
        JSONObject().put("type", "job_segui").put("id", id).put("ultimo_evento", ultimoEvento.coerceAtLeast(0)).toString()

    const val INVIA = "invia"
    const val ANNULLA = "annulla"

    /** Un id nuovo valido per la VPS (8-64 caratteri A-Z a-z 0-9 _ -). */
    fun nuovoId(adessoMs: Long, caso: Long): String =
        "tel-" + java.lang.Long.toString(adessoMs, 36) + "-" + java.lang.Long.toString(caso and 0xFFFFFFL, 36)

    /** Un messaggio della VPS; null se non è JSON o non ha `type`. */
    fun leggi(testo: String): MessaggioVps? {
        val o = runCatching { JSONObject(testo) }.getOrNull() ?: return null
        return when (o.optString("type")) {
            "connected" -> MessaggioVps.Collegato(o.optString("ruolo"), o.optInt("versione", 0))
            "job_event" -> MessaggioVps.Evento(eventoDa(o))
            "job_done" -> MessaggioVps.Fine(
                o.optString("id"), o.optInt("n"), o.optString("esito"), o.optString("riassunto"), o.optLong("ts"),
            )
            "job_stato" -> MessaggioVps.Stato(o.optString("id"), o.optString("stato"), o.optStringONull("esito"), o.optInt("ultimo_evento"))
            "job_errore" -> MessaggioVps.Errore(o.optString("id"), o.optString("motivo"))
            "job_lista" -> {
                val a = o.optJSONArray("lavori")
                val l = ArrayList<LavoroRemoto>()
                if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { l += lavoroDa(it) }
                MessaggioVps.Lista(l, o.optInt("in_corso"), o.optInt("max_paralleli"))
            }
            "" -> null
            else -> MessaggioVps.Altro(o.optString("type"))
        }
    }

    private fun JSONObject.optStringONull(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    fun eventoDa(o: JSONObject): EventoLavoro {
        val dati = o.optJSONObject("dati") ?: JSONObject()
        return EventoLavoro(
            id = o.optString("id"),
            n = o.optInt("n"),
            kind = o.optString("kind"),
            ts = o.optLong("ts"),
            testo = o.optString("testo"),
            dati = dati.toString(),
        )
    }

    private fun lavoroDa(o: JSONObject): LavoroRemoto = LavoroRemoto(
        id = o.optString("id"),
        agente = o.optString("agente"),
        titolo = o.optString("titolo"),
        testo = o.optString("testo"),
        stato = o.optString("stato"),
        esito = o.optStringONull("esito"),
        riassunto = o.optStringONull("riassunto"),
        creato = o.optLong("creato"),
        finito = if (o.isNull("finito")) 0L else o.optLong("finito"),
        ultimoEvento = o.optInt("ultimo_evento"),
        ultimo = o.optString("ultimo"),
        conferma = o.optJSONObject("conferma")?.let { ConfermaVps.da(o.optString("id"), it) },
    )
}

sealed class MessaggioVps {
    data class Collegato(val ruolo: String, val versione: Int) : MessaggioVps()
    data class Evento(val evento: EventoLavoro) : MessaggioVps()
    data class Fine(val id: String, val n: Int, val esito: String, val riassunto: String, val ts: Long) : MessaggioVps()
    data class Stato(val id: String, val stato: String, val esito: String?, val ultimoEvento: Int) : MessaggioVps()
    data class Errore(val id: String, val motivo: String) : MessaggioVps()
    data class Lista(val lavori: List<LavoroRemoto>, val inCorso: Int, val maxParalleli: Int) : MessaggioVps()
    data class Altro(val tipo: String) : MessaggioVps()
}

/** Un evento di un lavoro (job_event). [dati] è il JSON grezzo dei dati. */
data class EventoLavoro(
    val id: String,
    val n: Int,
    /** stato, comando, log, testo, conferma, risultato, errore, fine (job_done salvato come evento). */
    val kind: String,
    val ts: Long,
    val testo: String,
    val dati: String = "{}",
) {
    fun datiJson(): JSONObject = runCatching { JSONObject(dati) }.getOrElse { JSONObject() }

    /** Per un evento `conferma`: la richiesta strutturata. */
    fun conferma(): ConfermaVps? = if (kind == "conferma") ConfermaVps.da(id, datiJson(), testo) else null

    /** Per un evento `stato` che chiude una conferma: l'azione e la scelta. */
    fun confermaChiusa(): Pair<String, String>? {
        val d = datiJson()
        return if (kind == "stato" && d.has("azione_id") && d.has("scelta")) d.optString("azione_id") to d.optString("scelta") else null
    }
}

/** Una conferma in attesa: il lavoro sulla VPS è fermo finché Boss non tocca Invia o Annulla. */
data class ConfermaVps(
    val lavoroId: String,
    val azioneId: String,
    /** invio, scrittura, cancellazione, servizio, remoto, rete, installazione, altro. */
    val azione: String,
    val destinatario: String,
    val anteprima: String,
    val motivo: String,
    val scadeTs: Long,
    val domanda: String = "",
) {
    companion object {
        fun da(lavoroId: String, d: JSONObject, domanda: String = ""): ConfermaVps? {
            val aid = d.optString("azione_id")
            if (aid.isEmpty()) return null
            return ConfermaVps(
                lavoroId = lavoroId,
                azioneId = aid,
                azione = d.optString("azione", "altro"),
                destinatario = d.optString("destinatario"),
                anteprima = d.optString("anteprima"),
                motivo = d.optString("motivo"),
                scadeTs = d.optLong("scade_ts"),
                domanda = domanda,
            )
        }
    }

    /** Il titolo del pannello: in parole, secondo il tipo di azione. */
    fun titolo(): String = when (azione) {
        "invio" -> "La VPS vuole inviare"
        "cancellazione" -> "La VPS vuole cancellare"
        "scrittura" -> "La VPS vuole scrivere"
        "servizio" -> "La VPS vuole toccare un servizio"
        "remoto" -> "La VPS vuole collegarsi a un'altra macchina"
        "rete" -> "La VPS vuole mandare dati in rete"
        "installazione" -> "La VPS vuole installare"
        else -> "La VPS chiede il tuo sì"
    }
}

/** Una riga di job_lista. */
data class LavoroRemoto(
    val id: String,
    val agente: String,
    val titolo: String,
    val testo: String,
    /** coda, lavoro, attesa, finito. */
    val stato: String,
    val esito: String?,
    val riassunto: String?,
    val creato: Long,
    val finito: Long,
    val ultimoEvento: Int,
    val ultimo: String,
    val conferma: ConfermaVps?,
)

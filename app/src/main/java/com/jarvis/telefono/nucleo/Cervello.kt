package com.jarvis.telefono.nucleo

import com.jarvis.telefono.mani.CercaContatti
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chi capisce la frase di Boss e decide cosa fare (passo A di Jarvis Telefono, 2026-10-07).
 *
 * Il contratto con le mani NON cambia: un [Piano] è una lista di comandi JSON
 * (`{"action":"componi",…}`), gli stessi che la VPS mandava all'app 1.2.x e che
 * `PhoneActionExecutor.handle(JSONObject)` esegue. Così un cervello nuovo (regole oggi,
 * cloud con la chiave di Boss nel passo B) si aggiunge senza toccare le mani né il cancello d'invio.
 */
interface Cervello {
    /** Nome breve, finisce nella cronologia («regole», «cloud»…). */
    val nome: String

    /** La frase (già trascritta e corretta dal glossario) → cosa fare e cosa dire. */
    suspend fun capisci(frase: String, contesto: Contesto): Piano
}

/** Quello che il cervello può sapere del telefono, senza toccarlo. */
data class Contesto(
    /** La rubrica; null = permesso non concesso (il cervello lo deve dire, mai inventare numeri). */
    val contatti: List<CercaContatti.Contatto>?,
    /** Le preferenze personali (config-boss.json) o quelle di fabbrica. */
    val config: ConfigPersonale = ConfigPersonale(),
    /** Ora e minuti per «che ore sono» (passati da fuori: le prove non dipendono dall'orologio). */
    val ora: Int = 0,
    val minuti: Int = 0,
    /** 0.3.3: per «che giorno è». Giorno del mese, mese 1-12, anno, giorno della settimana 1 = lunedì … 7 = domenica. */
    val giorno: Int = 0,
    val mese: Int = 0,
    val anno: Int = 0,
    val giornoSettimana: Int = 0,
)

/** Un comando per PhoneActionExecutor: `{"action": …, campi…}`. */
data class Azione(val action: String, val campi: Map<String, Any?> = emptyMap()) {
    operator fun get(chiave: String): Any? = campi[chiave]

    fun json(): JSONObject {
        val o = JSONObject().put("action", action)
        for ((k, v) in campi) {
            when (v) {
                null -> {}
                is List<*> -> o.put(k, JSONArray(v))
                else -> o.put(k, v)
            }
        }
        return o
    }
}

/**
 * La decisione del cervello.
 * - [azioni]: da eseguire in fila; ci si ferma al primo errore.
 * - [dire]: la frase finale se tutte le azioni vanno a segno (o subito, se non ce ne sono).
 * - [capito]: false = «non ho capito» (oggi: frase fuori dalle regole; domani: passa al cloud).
 * - [serveRubrica]: il piano non è eseguibile senza il permesso della rubrica: va chiesto.
 * - [cosa]: due o tre parole per la bolla e la cronologia («WhatsApp a Marco»).
 */
data class Piano(
    val azioni: List<Azione>,
    val dire: String,
    val capito: Boolean = true,
    val serveRubrica: Boolean = false,
    val cosa: String = "",
    /**
     * 0.3.4 (cervello della VPS): le azioni sono GIÀ state fatte dalle mani durante [Cervello.capisci]
     * (la VPS le chiede una alla volta con tool_call). Il nucleo non le ripete: le mostra in cronologia.
     */
    val giaEseguito: Boolean = false,
    /** 0.3.4: le azioni fatte dalla VPS sul telefono, in ordine (per l'agente in bolla e cronologia). */
    val eseguite: List<Azione> = emptyList(),
    /** 0.3.4: la risposta è un errore (VPS che non risponde dopo aver già fatto qualcosa, errore del ponte). */
    val errore: Boolean = false,
    /** 0.3.4: Boss ha cambiato idea mentre la VPS lavorava: niente da dire. */
    val annullato: Boolean = false,
    /** 0.3.4: chi ha deciso davvero («regole», «vps»); vuoto = il nome del cervello. */
    val cervello: String = "",
    /** 0.3.4: ms dalla frase alla prima azione già fatta (-1 = nessuna), misurati dal cervello della VPS. */
    val primaAzioneMs: Long = -1,
) {
    companion object {
        const val NON_HO_CAPITO = "Non ho capito."

        /**
         * 0.3.3 (Boss 07/10: «Non ho capito» e basta non aiuta): si dice cosa si è sentito e cosa si può
         * chiedere. Senza testo sentito resta «Non ho capito.».
         */
        fun nonCapito(sentito: String = ""): Piano {
            val s = sentito.trim().trimEnd('.', '!', '?', ',', ';', ' ')
            if (s.isEmpty()) return Piano(emptyList(), NON_HO_CAPITO, capito = false)
            val corto = if (s.length > 80) s.take(80).substringBeforeLast(' ') + "…" else s
            return Piano(
                emptyList(),
                "Ho sentito «$corto», ma non so cosa fare. Prova con: apri WhatsApp, scrivi a Marco, che ore sono, cerca su Google.",
                capito = false,
            )
        }
        fun solo(dire: String, cosa: String = "") = Piano(emptyList(), dire, cosa = cosa)
    }
}

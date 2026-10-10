package com.jarvis.telefono.nucleo

import com.jarvis.telefono.vps.CanaleMani
import org.json.JSONObject

/**
 * Il cervello della VPS (JBoss 0.3.4, 2026-10-07; Boss: «funziona malissimo», le regole non bastano).
 *
 * La frase va a jarvis-agent sul ruolo «mani» (Sonnet sempre vivo, contesto a grafo, 27 strumenti del telefono):
 * la VPS decide e chiede le azioni una alla volta (`tool_call`), il telefono le fa con le sue mani
 * (PhoneActionExecutor.handle, identico a prima, conferma Invia/Annulla imposta dal telefono) e risponde
 * (`tool_result`); alla fine la VPS dice cosa ha fatto (`assistant_message`). Le azioni sono già fatte quando
 * [capisci] torna: il piano è `giaEseguito`.
 *
 * Kotlin puro (il canale è OkHttp): provato in CervelloVpsTest con un finto ponte.
 */
class CervelloVps(
    private val canale: CanaleMani,
    /** Modulo VPS acceso, configurato e con la rete? */
    private val disponibilita: () -> Disponibilita,
    /** Un comando alle mani → il loro payload (`{result}` / `{error}`, null = scaduto). */
    private val esegui: suspend (JSONObject) -> JSONObject?,
    private val osservatore: Osservatore = Osservatore.NIENTE,
    private val primoSegnoMs: Long = PRIMO_SEGNO_MS,
    private val silenzioMs: Long = 60_000L,
    /**
     * 0.4.2: la nota personale per la VPS quando Boss parla di sé («mandami», «a me»): numero WhatsApp e mail
     * di Boss dalla configurazione personale (fuori dal repo). Vuota = niente nota.
     */
    private val notaPersonale: () -> String = { "" },
) : Cervello {
    override val nome = NOME

    enum class Disponibilita { PRONTO, SPENTO, NON_CONFIGURATO, SENZA_RETE }

    /** La bolla e i tempi (Android lo collega a JarvisService). */
    interface Osservatore {
        fun inizio(frase: String) {}
        fun strumento(azione: Azione) {}
        /** 0.4.2: la VPS lavora con i suoi strumenti (segno di vita «in_lavoro»). */
        fun lavoro(testo: String) {}
        fun misura(riga: String) {}

        companion object { val NIENTE = object : Osservatore {} }
    }

    fun disponibile(): Disponibilita = disponibilita()

    val occupato: Boolean get() = canale.occupato

    fun annulla(): Boolean = canale.annulla()

    /** La conversazione con la VPS, senza decidere cosa fare se fallisce (lo decide [CervelloCatena]). */
    suspend fun chiedi(frase: String): CanaleMani.Esito {
        osservatore.inizio(frase)
        val e = canale.conversa(
            conNota(frase, runCatching { notaPersonale() }.getOrDefault("")), primoSegnoMs = primoSegnoMs, silenzioMs = silenzioMs,
            suStrumento = { osservatore.strumento(azione(it)) },
            suLavoro = { osservatore.lavoro(it) },
            esegui = esegui,
        )
        osservatore.misura(
            when (e) {
                is CanaleMani.Esito.Risposta -> "vps esito=risposta collegamento_ms=${e.collegamentoMs} primo_strumento_ms=${e.primoStrumentoMs} risposta_ms=${e.rispostaMs} strumenti=${e.strumenti.joinToString("+") { it.optString("action") }.ifEmpty { "-" }}"
                is CanaleMani.Esito.Fallito -> "vps esito=fallito tipo=${e.tipo} strumenti=${e.strumenti.size}"
                is CanaleMani.Esito.Annullato -> "vps esito=annullato strumenti=${e.strumenti.size}"
            },
        )
        return e
    }

    override suspend fun capisci(frase: String, contesto: Contesto): Piano = piano(chiedi(frase))

    fun piano(e: CanaleMani.Esito): Piano = when (e) {
        is CanaleMani.Esito.Risposta -> Piano(
            emptyList(), e.testo.trim().ifEmpty { "Fatto." },
            giaEseguito = true, eseguite = e.strumenti.map { azione(it) }, errore = e.errore,
            cervello = NOME, primaAzioneMs = e.primoStrumentoMs,
            cosa = e.strumenti.firstOrNull()?.optString("action").orEmpty(),
        )
        is CanaleMani.Esito.Annullato -> Piano(
            emptyList(), "", giaEseguito = true, eseguite = e.strumenti.map { azione(it) }, annullato = true, cervello = NOME,
        )
        is CanaleMani.Esito.Fallito -> Piano(
            emptyList(), e.motivo, capito = e.strumenti.isNotEmpty(), giaEseguito = e.strumenti.isNotEmpty(),
            eseguite = e.strumenti.map { azione(it) }, errore = true, cervello = NOME,
        )
    }

    companion object {
        const val NOME = "vps"
        /** Il primo segno di vita della VPS dopo la frase (misurati 1,6-3,9 s per le frasi semplici). */
        const val PRIMO_SEGNO_MS = 15_000L

        private val PARLA_DI_SE = Regex(
            """\b(a me|me stesso|mandami|scrivimi|inviami|girami|inoltrami|mandamelo|mandamela|a me stesso|al mio numero|alla mia mail|il mio numero|la mia mail)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** 0.4.2: la frase per la VPS, con la nota personale solo se Boss parla di sé. Kotlin puro (CervelloVpsTest). */
        fun conNota(frase: String, nota: String): String =
            if (nota.isBlank() || !PARLA_DI_SE.containsMatchIn(frase)) frase else "$frase\n\n[Nota del telefono: $nota]"

        /** Un comando della VPS come [Azione] (per bolla, agente e cronologia; i testi non vanno nei log). */
        fun azione(c: JSONObject): Azione {
            val campi = linkedMapOf<String, Any?>()
            for (k in c.keys()) if (k != "action") campi[k] = c.opt(k)?.takeUnless { it == JSONObject.NULL }
            return Azione(c.optString("action"), campi)
        }
    }
}

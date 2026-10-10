package com.jarvis.telefono.postino

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lo stato della chat Postino, costruito SOLO dagli eventi della VPS (job_event kind «postino»
 * e «conferma» del PROTOCOLLO-VPS.md, dati in PROTOCOLLO-POSTINO.md). Codice puro: niente
 * Android, provato sulla JVM.
 *
 * Regola di Boss (07/10): «in ordine» ed «eseguito» sono diversi. Una mail è FATTA solo quando
 * arriva il suo evento «stato» con esito ok, e quell'esito la VPS lo scrive solo dopo aver riletto
 * la casella (la prova è nel campo [StatoVoce.prova]). Il telefono non dichiara mai niente da solo.
 */
class StatoPostino {

    data class Proposta(val azione: String, val cartella: String?, val perche: String)

    data class StatoVoce(
        val esito: String,          // ok | errore | annullato
        val stato: String,          // «inviata», «nel Cestino», «bozza creata»… oppure l'esito
        val verbo: String,
        val prova: String?,
        val motivo: String?,
        val testoBozza: String?,
        val destinatario: String? = null,
    ) {
        val fatto get() = esito == "ok"
        val errore get() = esito == "errore"
    }

    data class Voce(
        val numero: Int,
        val casella: String,
        val da: String,
        val indirizzo: String,
        val oggetto: String,
        val data: String,
        val categoria: String,
        val livello: Int,
        val urgenza: String,
        val proposta: Proposta,
        val anteprima: String,
        val allegati: Int,
        val stato: StatoVoce?,
        val inCorso: String? = null,      // il verbo in esecuzione adesso (dal «piano»)
    )

    data class Conferma(
        val idLavoro: String,
        val azioneId: String,
        val azione: String,
        val domanda: String,
        val destinatario: String,
        val anteprima: String,
        val numero: Int?,
        val oggetto: String?,
        val scadeTs: Long,
    )

    /** 0.5.0: un allegato scaricato e letto sulla VPS (al telefono solo nome, misura ed estratto corto). */
    data class Allegato(val n: Int, val nome: String, val tipo: String, val kb: Int, val estratto: String, val nota: String?, val letto: Boolean)

    /** 0.5.0: la pagina di una mail aperta (evento «scheda»). */
    data class Scheda(
        val numero: Int,
        val casella: String,
        val da: String,
        val rispondiA: String,
        val a: String,
        val data: String,
        val oggetto: String,
        val testo: String,
        val allegati: List<Allegato>,
        val riassunto: String,
        val riassuntoDalModello: Boolean,
        val riassuntoInArrivo: Boolean,
        val cartella: String?,
        val perche: String,
        val scelte: List<String>,
        val secondi: Double,
    )

    /** 0.5.0: la bozza di una mail: salvata nelle Bozze (verificata) o da completare (manca il destinatario). */
    data class Bozza(
        val numero: Int,
        val tipo: String,              // risposta | inoltro
        val a: String,
        val testo: String,
        val salvata: Boolean,
        val domanda: String? = null,
        val scelte: List<Pair<String, String>> = emptyList(),   // (nome, indirizzo)
        val prova: String? = null,
        val motivo: String? = null,
        val inviata: Boolean = false,
        val versione: Long = System.nanoTime(),
    )

    enum class Filtro(val etichetta: String) { DA_FARE("Da fare"), FATTO("Fatto"), ERRORI("Errori"), TUTTE("Tutte") }

    var reportId: String? = null
        private set
    var quando: String = ""
        private set
    var totale = 0
        private set
    var pagine = 0
        private set
    val pagineArrivate = sortedSetOf<Int>()
    var caselle: List<Triple<String, Int, String?>> = emptyList()   // (casella, nuove, errore)
        private set
    /** 0.5.0: le cartelle vere di ogni casella (destinazioni dell'archivio), dal resoconto. */
    var cartelle: Map<String, List<String>> = emptyMap()
        private set
    val schede = sortedMapOf<Int, Scheda>()
    val bozze = sortedMapOf<Int, Bozza>()
    private val voci = sortedMapOf<Int, Voce>()
    var conferma: Conferma? = null
        private set
    /** L'ultima frase della VPS: un passo («leggo negozio») o la riga finale («eseguiti 9 su 10…»). */
    var ultimaFrase: String = ""
        private set(v) { field = italiano(v) }
    var ultimoRiassunto: String? = null
        private set
    var ultimoErrore: String? = null
        private set
    var testoMail: Pair<Int, String>? = null
        private set
    /** Dettagli della conferma che sta per arrivare (attesa_conferma precede l'evento «conferma»). */
    private var prossimaConferma: JSONObject? = null

    fun voci(): List<Voce> = voci.values.toList()
    fun voce(n: Int): Voce? = voci[n]

    fun filtra(f: Filtro): List<Voce> = voci.values.filter {
        when (f) {
            Filtro.TUTTE -> true
            Filtro.FATTO -> it.stato?.fatto == true
            Filtro.ERRORI -> it.stato?.errore == true
            Filtro.DA_FARE -> it.stato == null || it.stato.esito == "annullato"
        }
    }

    fun conta(f: Filtro) = filtra(f).size

    /**
     * 09/10 (Boss: «Home dice 24 nuove, la pagina 6 mail, JBoss 7 e poi 8»): IL numero della posta, uno solo per Home,
     * pagina del Postino e chat di JBoss: le mail del resoconto ancora da fare. Prima la Home sommava le «nuove» delle
     * caselle (un altro conto della VPS, su tutta la casella) e JBoss le aggiungeva alla sua frase. null = nessun resoconto.
     */
    fun numeroUnico(): Int? = if (reportId == null) null else conta(Filtro.DA_FARE)

    /** «6 mail da fare», «1 mail da fare», «nessuna mail da fare». */
    fun rigaNumero(): String = when (val n = numeroUnico()) {
        null -> "nessun resoconto"
        0 -> "nessuna mail da fare"
        else -> "$n mail da fare"
    }

    /** Per casella, le mail da fare: «negozio 4, jarvis 2». */
    fun daFarePerCasella(): String = filtra(Filtro.DA_FARE).groupBy { it.casella }
        .filterKeys { it.isNotEmpty() }.entries.joinToString(", ") { "${it.key} ${it.value.size}" }

    /** «200 mail · 12 fatte · 1 errore · 187 da fare»: la riga sotto il titolo. */
    fun rigaRiassunto(): String {
        if (reportId == null) return "Nessun resoconto: chiedi «controlla la posta»."
        val fatte = conta(Filtro.FATTO)
        val errori = conta(Filtro.ERRORI)
        val parti = mutableListOf("$totale mail")
        if (fatte > 0) parti.add("$fatte ${if (fatte == 1) "fatta" else "fatte"}")
        if (errori > 0) parti.add("$errori ${if (errori == 1) "errore" else "errori"}")
        parti.add("${conta(Filtro.DA_FARE)} da fare")
        if (pagineArrivate.size < pagine) parti.add("arrivo ${pagineArrivate.size}/$pagine")
        return parti.joinToString(" · ")
    }

    /** Un messaggio del modulo VPS (job_event / job_done / job_errore), così com'è arrivato. */
    fun applicaMessaggio(m: JSONObject): Boolean {
        val tipo = m.optString("type")
        val id = m.optString("id")
        return when (tipo) {
            "job_event" -> {
                val dati = m.optJSONObject("dati") ?: JSONObject()
                when (m.optString("kind")) {
                    "postino" -> applicaDati(dati)
                    "conferma" -> { applicaConferma(id, m.optString("testo"), dati); true }
                    "testo" -> { ultimaFrase = m.optString("testo"); true }
                    "errore" -> { ultimoErrore = m.optString("testo"); ultimaFrase = ultimoErrore!!; true }
                    "stato" -> {
                        val az = dati.optString("azione_id")
                        if (az.isNotEmpty() && dati.has("scelta") && conferma?.azioneId == az) conferma = null
                        true
                    }
                    else -> false
                }
            }
            "job_done" -> {
                conferma = conferma?.takeIf { it.idLavoro != id }
                if (m.optString("esito") != "ok" && ultimoErrore == null) ultimoErrore = m.optString("riassunto")
                ultimaFrase = m.optString("riassunto").ifEmpty { ultimaFrase }
                voci.replaceAll { _, v -> v.copy(inCorso = null) }
                true
            }
            "job_errore" -> { ultimoErrore = m.optString("motivo"); ultimaFrase = ultimoErrore!!; true }
            else -> false
        }
    }

    /** I dati di un evento «postino» (campo «tipo»: totali, voci, riassunti, piano, stato, fine…). */
    fun applicaDati(d: JSONObject): Boolean {
        when (d.optString("tipo")) {
            "passo" -> ultimaFrase = d.optString("testo")
            "totali" -> {
                reportId = d.optString("report_id")
                quando = d.optString("quando")
                totale = d.optInt("totale")
                pagine = d.optInt("pagine")
                pagineArrivate.clear()
                voci.clear()
                ultimoErrore = null
                ultimoRiassunto = null
                caselle = d.optJSONArray("caselle").oggetti().map {
                    Triple(it.optString("casella"), it.optInt("nuove"), it.optString("errore").ifEmpty { null }.takeIf { e -> e != "null" })
                }
                schede.clear()
                bozze.clear()
                val c = d.optJSONObject("cartelle")
                cartelle = if (c == null) emptyMap() else c.keys().asSequence().associateWith { k -> c.optJSONArray(k).stringhe() }
                ComandiPostino.cartelleVere = cartelle.values.flatten().distinct()
                ultimaFrase = "Ho letto ${caselle.size} caselle: $totale mail numerate da 1 a $totale."
            }
            "voci" -> {
                if (reportId != null && d.optString("report_id") != reportId) return false
                pagineArrivate.add(d.optInt("pagina"))
                for (v in d.optJSONArray("voci").oggetti()) {
                    val n = v.optInt("numero")
                    val p = v.optJSONObject("proposta") ?: JSONObject()
                    voci[n] = Voce(
                        numero = n, casella = v.optString("casella"), da = v.optString("da"),
                        indirizzo = v.optString("indirizzo").pulito(), oggetto = v.optString("oggetto"),
                        data = v.optString("data"), categoria = v.optString("categoria"), livello = v.optInt("livello"),
                        urgenza = v.optString("urgenza"),
                        proposta = Proposta(p.optString("azione"), p.optString("cartella").pulito().ifEmpty { null }, p.optString("perche")),
                        anteprima = v.optString("anteprima"), allegati = v.optInt("allegati"),
                        stato = v.optJSONObject("stato")?.let { statoDa(it) } ?: voci[n]?.stato,
                    )
                }
            }
            "riassunti" -> {
                if (d.optString("report_id") != reportId) return false
                val r = d.optJSONObject("riassunti") ?: JSONObject()
                for (k in r.keys()) {
                    val n = k.toIntOrNull() ?: continue
                    voci[n]?.let { voci[n] = it.copy(anteprima = r.optString(k)) }
                }
            }
            "piano" -> for (a in d.optJSONArray("azioni").oggetti()) {
                val n = a.optInt("numero")
                voci[n]?.let { voci[n] = it.copy(inCorso = a.optString("verbo")) }
            }
            "stato" -> {
                if (d.optString("report_id") != reportId) return false
                val n = d.optInt("numero")
                val st = statoDa(d)
                voci[n]?.let { voci[n] = it.copy(stato = st, inCorso = null) }
                if (st.fatto && (st.verbo == "rispondi" || st.verbo == "inoltra")) {
                    bozze[n] = Bozza(n, if (st.verbo == "inoltra") "inoltro" else "risposta", st.destinatario.orEmpty(),
                        st.testoBozza.orEmpty(), salvata = true, prova = st.prova)
                } else if (st.fatto && st.verbo == "invia") {
                    bozze[n]?.let { bozze[n] = it.copy(inviata = true, prova = st.prova, versione = System.nanoTime()) }
                } else if (st.errore && (st.verbo == "rispondi" || st.verbo == "inoltra")) {
                    bozze[n]?.let { bozze[n] = it.copy(motivo = st.motivo, versione = System.nanoTime()) }
                }
            }
            "scheda" -> {
                if (reportId != null && d.optString("report_id") != reportId) return false
                val sc = schedaDa(d)
                schede[sc.numero] = sc
                val b = d.optJSONObject("bozza")
                if (b != null && b.optString("a").pulito().isNotEmpty() && bozze[sc.numero]?.salvata != true) {
                    bozze[sc.numero] = Bozza(sc.numero, "risposta", b.optString("a").pulito(), b.optString("testo").pulito(), salvata = true)
                }
            }
            "bozza" -> {
                if (reportId != null && d.optString("report_id") != reportId) return false
                val n = d.optInt("numero")
                bozze[n] = Bozza(
                    numero = n, tipo = d.optString("tipo_bozza").ifEmpty { "risposta" }, a = d.optString("a").pulito(),
                    testo = d.optString("testo").pulito(), salvata = d.optBoolean("salvata"),
                    domanda = d.optString("domanda").pulito().ifEmpty { null },
                    scelte = d.optJSONArray("scelte").oggetti().map { it.optString("nome") to it.optString("indirizzo") },
                    prova = d.optString("prova").pulito().ifEmpty { null }, motivo = d.optString("motivo").pulito().ifEmpty { null },
                )
                ultimaFrase = bozze[n]!!.domanda ?: if (bozze[n]!!.salvata) "Bozza pronta per la mail $n: leggila e conferma." else ultimaFrase
            }
            "fine" -> {
                ultimoRiassunto = d.optString("riassunto")
                ultimaFrase = ultimoRiassunto!!.replaceFirstChar { it.uppercase() }
            }
            "attesa_conferma" -> prossimaConferma = d
            // dalla 0.6.1 la mail aperta arriva come «scheda»; «mail» resta per le app vecchie e qui si ignora
            "mail" -> if (d.optInt("numero") !in schede) testoMail = d.optInt("numero") to d.optString("testo")
            else -> return false
        }
        return true
    }

    private fun applicaConferma(idLavoro: String, domanda: String, dati: JSONObject) {
        val dett = prossimaConferma
        prossimaConferma = null
        conferma = Conferma(
            idLavoro = idLavoro,
            azioneId = dati.optString("azione_id"),
            azione = dati.optString("azione"),
            domanda = dett?.optString("testo")?.ifEmpty { null } ?: domanda,
            destinatario = dati.optString("destinatario"),
            anteprima = dati.optString("anteprima"),
            numero = dett?.optInt("numero")?.takeIf { it > 0 },
            oggetto = dett?.optString("oggetto")?.ifEmpty { null },
            scadeTs = dati.optLong("scade_ts"),
        )
    }

    fun confermaRisolta() { conferma = null }

    fun dimenticaMail() { testoMail = null }

    private fun statoDa(d: JSONObject) = StatoVoce(
        esito = d.optString("esito"), stato = d.optString("stato"), verbo = d.optString("verbo"),
        prova = d.optString("prova").pulito().ifEmpty { null }, motivo = d.optString("motivo").pulito().ifEmpty { null },
        testoBozza = d.optString("testo_bozza").pulito().ifEmpty { null },
        destinatario = d.optString("destinatario").pulito().ifEmpty { null },
    )

    private fun schedaDa(d: JSONObject): Scheda {
        val dest = d.optJSONObject("destinazione") ?: JSONObject()
        return Scheda(
            numero = d.optInt("numero"), casella = d.optString("casella"), da = d.optString("da").pulito(),
            rispondiA = d.optString("rispondi_a").pulito(), a = d.optString("a").pulito(), data = d.optString("data").pulito(),
            oggetto = d.optString("oggetto").pulito(), testo = d.optString("testo").pulito(),
            allegati = d.optJSONArray("allegati").oggetti().map {
                Allegato(it.optInt("n"), it.optString("nome"), it.optString("tipo"), it.optInt("kb"),
                    it.optString("estratto").pulito(), it.optString("nota").pulito().ifEmpty { null }, it.optBoolean("letto"))
            },
            riassunto = d.optString("riassunto").pulito(), riassuntoDalModello = d.optString("riassunto_da") == "modello",
            riassuntoInArrivo = d.optBoolean("riassunto_in_arrivo"),
            cartella = dest.optString("cartella").pulito().ifEmpty { null }, perche = dest.optString("perche").pulito(),
            scelte = dest.optJSONArray("scelte").stringhe().ifEmpty { cartelle[d.optString("casella")].orEmpty() },
            secondi = d.optDouble("secondi", 0.0),
        )
    }

    /** Le cartelle dove può andare la mail n: quelle vere della sua casella, o le fisse se non arrivate. */
    fun cartellePer(n: Int): List<String> {
        val v = voci[n] ?: return ComandiPostino.CARTELLE
        return cartelle[v.casella]?.takeIf { it.isNotEmpty() } ?: ComandiPostino.CARTELLE
    }

    companion object {
        /**
         * 09/10 (Boss: «"riassumo 6 mail in 1 blocchi" è italiano sbagliato»): le frasi della VPS con «1» davanti a un
         * plurale. La VPS scrive «{n} blocchi» senza guardare n; qui si corregge prima di mostrarle o dirle.
         */
        fun italiano(t: String): String = t
            .replace(Regex("\\b1 blocchi\\b"), "1 blocco")
            .replace(Regex("\\b1 caselle\\b"), "1 casella")
            .replace(Regex("\\b1 pagine\\b"), "1 pagina")
            .replace(Regex("\\b1 azioni\\b"), "1 azione")
            .replace(Regex("\\b1 riassunti\\b"), "1 riassunto")
            .replace(Regex("\\b1 mail numerate da 1 a 1\\b"), "1 mail, la numero 1")
            .replace(Regex("\\ble 1 mail siano\\b"), "la mail sia")
            .replace(Regex("\\b1 fatte\\b"), "1 fatta")

        private fun JSONArray?.oggetti(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONArray?.stringhe(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() && it != "null" }

        /** org.json scrive «null» come stringa con optString: qui torna vuota. */
        private fun String.pulito() = if (this == "null") "" else this

        /** La riga di stato sotto una scheda: «✓ inviata · trovata in Inviata (uid 812)». */
        fun rigaStato(s: StatoVoce): String = when (s.esito) {
            "ok" -> "✓ ${s.stato}" + (s.prova?.let { " · $it" } ?: "")
            "annullato" -> "– annullato" + (s.motivo?.let { ": $it" } ?: "")
            else -> "! errore" + (s.motivo?.let { ": $it" } ?: "")
        }

        /** «→ rispondi», «→ archivia in Fatture-Fornitori», «→ da leggere». */
        fun rigaProposta(p: Proposta): String = when (p.azione) {
            "archivia" -> "→ archivia" + (p.cartella?.let { " in $it" } ?: "")
            "leggi" -> "→ da leggere"
            "" -> ""
            else -> "→ ${p.azione}"
        }
    }
}

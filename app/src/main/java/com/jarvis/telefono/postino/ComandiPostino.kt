package com.jarvis.telefono.postino

import java.text.Normalizer

/**
 * Il parser dei comandi per numero della chat Postino (Boss, 2026-10-07): «rispondi 3 e 7»,
 * «cestina da 10 a 15», «invia tutte le bozze», «sposta 6 in fatture fornitori».
 *
 * È lo specchio di `capisci()` in strumenti/postino_numeri.py (sulla VPS): le prove dei due lati
 * usano gli stessi esempi. Sul telefono serve a mostrare SUBITO cosa si farà («Farò: cestina 10, 11…»)
 * e a fermare un comando storto prima di mandarlo; quello che decide resta il parser della VPS.
 */
object ComandiPostino {

    data class Richiesta(
        val verbo: String,
        val numeri: List<Int>,
        val selettore: String? = null,
        val testo: String? = null,
        val cartella: String? = null,
        val invia: Boolean = false,
        /** 0.5.0: «rispondi 4 a nome@dominio.it: …», «inoltra 4 a …: …». */
        val destinatario: String? = null,
        /** 0.5.0: «controlla la posta di jarvis». */
        val casella: String? = null,
    )

    class Errore(msg: String) : IllegalArgumentException(msg)

    const val MASSIMO = 500

    /** Le cartelle fisse di posta.py (PRATICHE + Rumore). */
    val CARTELLE = listOf(
        "Legale-PEC", "Fisco-e-Utenze", "Veicolo", "Fornitori-e-Offerte", "Fatture-Fornitori",
        "Trading-e-Finanza", "Software-e-Fatture", "Sicurezza", "GitHub", "Rumore",
    )

    /** 0.5.0: le cartelle VERE delle caselle, arrivate col resoconto (evento «totali», campo «cartelle»). */
    @Volatile var cartelleVere: List<String> = emptyList()

    fun cartelleNote(): List<String> = CARTELLE + cartelleVere.filter { it !in CARTELLE }

    private val VERBI = listOf(
        "rispondi e invia|rispondi e manda" to "rispondi+invia",
        "inoltra e invia|inoltra e manda" to "inoltra+invia",
        "inoltra|inoltrala|inoltrale|gira|girala" to "inoltra",
        "istruisci|jboss|di a jboss|dì a jboss" to "istruisci",
        "segna come lett[ae]|segna lett[ae]|segnal[ae] come lett[ae]" to "letta",
        "invia|inviale|inviala|manda|mandale|spedisci" to "invia",
        "rispondi|rispondere|rispondigli|rispondile|bozza|prepara (la |una )?bozza" to "rispondi",
        "cestina|cestinale|cestino|cancella|cancellale|elimina|eliminale|butta|buttale" to "cestina",
        "archivia|archiviale|archiviala" to "archivia",
        "sposta|spostale|spostala|metti" to "sposta",
        "lett[ae]" to "letta",
        "spam|indesiderat[ae]" to "spam",
        "tieni|tienil[ae]|lascia|lasciale" to "tieni",
        "apri|leggimi|fammi vedere|mostra|mostrami" to "apri",
        "pagina" to "pagina",
        "controlla( la)? posta|resoconto|aggiorna|rifai il giro|nuovo giro|leggi( la)? posta|posta" to "resoconto",
    )
    private val RE_VERBI = VERBI.map { (r, v) -> Regex("^\\s*(?:$r)\\b", RegexOption.IGNORE_CASE) to v }

    private val NUMERI_PAROLE = mapOf(
        "uno" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5, "sei" to 6, "sette" to 7,
        "otto" to 8, "nove" to 9, "dieci" to 10, "undici" to 11, "dodici" to 12, "tredici" to 13,
        "quattordici" to 14, "quindici" to 15, "sedici" to 16, "diciassette" to 17, "diciotto" to 18,
        "diciannove" to 19, "cento" to 100,
    )
    private val DECINE = mapOf(
        "venti" to 20, "trenta" to 30, "quaranta" to 40, "cinquanta" to 50, "sessanta" to 60,
        "settanta" to 70, "ottanta" to 80, "novanta" to 90,
    )
    private val SELETTORI = listOf(
        "tutte le bozze|le bozze|tutte bozze" to "bozze",
        "(tutte )?le promozioni|(tutta )?la pubblicit[aà]|(tutto )?lo spam|(tutte )?le pubblicit[aà]" to "cat:5,6",
        "(tutte )?le notifiche|(tutte )?le ricevute" to "cat:4",
        "(tutte )?le fatture" to "cat:7,3",
        "tutte|tutto|tutti" to "tutte",
    ).map { (r, s) -> Regex("\\b(?:$r)\\b", RegexOption.IGNORE_CASE) to s }

    private val RE_CARTELLA = Regex("\\b(?:in|nella cartella|nella|nel|su|dentro)\\b\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val RE_DESTINATARIO = Regex("\\s+(?:a|per)\\s+([^@\\s<>,;:]+@[^@\\s<>,;:]+\\.[^@\\s<>,;:]+)\\s*$", RegexOption.IGNORE_CASE)
    private val RE_CASELLA = Regex("\\b(?:di|della casella|nella casella|in|su)\\s+([a-z0-9-]+)\\s*$", RegexOption.IGNORE_CASE)

    fun senzaAccenti(t: String): String =
        Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** «tre» → 3, «ventitré» → 23, «trentotto» → 38; «un/una» no (sono articoli). */
    fun parolaNumero(p: String): Int? {
        val w = senzaAccenti(p.lowercase())
        NUMERI_PAROLE[w]?.let { return it }
        for ((parola, v) in DECINE) {
            if (w == parola) return v
            for (radice in listOf(parola, parola.dropLast(1))) {
                if (w.startsWith(radice)) {
                    val u = NUMERI_PAROLE[w.substring(radice.length)]
                    if (u != null && u < 10) return v + u
                }
            }
        }
        return null
    }

    private fun normCartella(t: String) = senzaAccenti(t.lowercase()).replace(Regex("[^a-z0-9]"), "")

    fun trovaCartella(t: String): String {
        val tutte = cartelleNote()
        val q = normCartella(t)
        if (q.isEmpty()) throw Errore("manca la cartella. Ci sono: ${tutte.joinToString()}")
        tutte.firstOrNull { normCartella(it) == q }?.let { return it }
        val simili = tutte.filter { normCartella(it).contains(q) }
        if (simili.size == 1) return simili[0]
        if (simili.size > 1) throw Errore("«$t» può essere ${simili.joinToString()}: dimmi quale.")
        throw Errore("la cartella «$t» non c'è. Ci sono: ${tutte.joinToString()}")
    }

    /** «3 e 7», «3, 5-9 e 12», «da 10 a 15», «dal tre al sette» → lista ordinata senza doppioni. */
    fun estraiNumeri(t: String, massimo: Int = MASSIMO): List<Int> {
        val parole = Regex("\\d+|[a-zàèéìòù]+|[-–—]").findAll(t.lowercase()).map { it.value }.toList()
        val gettoni = mutableListOf<Any>()
        for (p in parole) {
            val n = p.toIntOrNull() ?: parolaNumero(p)
            if (n != null) gettoni.add(n)
            else if (p in setOf("-", "–", "—", "a", "al", "alla", "fino", "all")) gettoni.add("a")
        }
        val numeri = mutableListOf<Int>()
        var i = 0
        while (i < gettoni.size) {
            val g = gettoni[i]
            if (g is Int) {
                var j = i + 1
                while (j < gettoni.size && gettoni[j] == "a") j++
                if (j > i + 1 && j < gettoni.size && gettoni[j] is Int) {
                    val da = g
                    val a = gettoni[j] as Int
                    if (a < da) throw Errore("«$da-$a» è alla rovescia: scrivilo «$a-$da».")
                    if (a - da + 1 > massimo) {
                        throw Errore("«$da-$a» sono ${a - da + 1} mail: troppe in un colpo solo (il tetto è $massimo). Spezza l'elenco.")
                    }
                    numeri.addAll(da..a)
                    i = j + 1
                    continue
                }
                numeri.add(g)
            }
            i++
        }
        if (numeri.any { it == 0 }) throw Errore("i numeri del resoconto partono da 1, lo zero non c'è.")
        return numeri.toSortedSet().toList()
    }

    /** Il testo di Boss → richieste. Un pezzo che non si capisce ferma tutto ([Errore]). */
    /**
     * Il testo di Boss → richieste. Un pezzo che non si capisce ferma tutto ([Errore]).
     * 0.5.0: il testo dopo i PRIMI due punti è di Boss (bozza, frase per JBoss) e resta intero:
     * «;», a capo e «poi» lì dentro non spezzano il comando (come capisci() sulla VPS).
     */
    fun capisci(testo: String): List<Richiesta> {
        val due = testo.indexOf(':')
        val testa = if (due >= 0) testo.substring(0, due) else testo
        val coda = if (due >= 0) testo.substring(due + 1).trim().ifEmpty { null } else null
        val pezzi = testa.split(Regex("\\s*(?:;|\\n|\\bpoi\\b)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        if (pezzi.isEmpty()) {
            throw Errore("comando vuoto. Per esempio «cestina 3, 5-9», «rispondi 4: grazie, confermo», «invia 4».")
        }
        return pezzi.mapIndexed { i, p -> capisciUno(p, if (i == pezzi.lastIndex) coda else null) }
    }

    private fun capisciUno(pezzoIntero: String, testoLibero: String?): Richiesta {
        val pezzo = pezzoIntero
        val trovato = RE_VERBI.firstNotNullOfOrNull { (re, v) -> re.find(pezzo)?.let { it to v } }
            ?: throw Errore("non capisco «${pezzo.trim()}». I comandi sono: rispondi, inoltra, invia, cestina, archivia, sposta, letta, spam, tieni, apri, pagina, resoconto.")
        var verbo = trovato.second
        var resto = pezzo.substring(trovato.first.range.last + 1)
        var invia = false
        if (verbo == "rispondi+invia" || verbo == "inoltra+invia") { verbo = verbo.substringBefore('+'); invia = true }
        if (verbo == "resoconto") {
            val c = RE_CASELLA.find(resto)?.groupValues?.get(1)?.lowercase()?.takeIf { it !in setOf("posta", "oggi") }
            return Richiesta(verbo, emptyList(), testo = testoLibero, casella = c)
        }
        var destinatario: String? = null
        if (verbo == "rispondi" || verbo == "inoltra") {
            val d = RE_DESTINATARIO.find(resto)
            if (d != null) { destinatario = d.groupValues[1]; resto = resto.substring(0, d.range.first) }
            else if (verbo == "inoltra") throw Errore("«inoltra» vuole l'indirizzo: «inoltra 5 a nome@dominio.it: testo».")
        }
        var cartella: String? = null
        if (verbo == "sposta" || verbo == "archivia") {
            val c = RE_CARTELLA.find(resto)
            if (c != null) {
                cartella = trovaCartella(c.groupValues[1])
                resto = resto.substring(0, c.range.first)
            } else if (verbo == "sposta") {
                throw Errore("«sposta» vuole la cartella: «sposta 5 in Fatture-Fornitori». Ci sono: ${CARTELLE.joinToString()}")
            }
        }
        var selettore: String? = null
        for ((re, s) in SELETTORI) {
            val m = re.find(resto) ?: continue
            selettore = s
            resto = resto.substring(0, m.range.first) + " " + resto.substring(m.range.last + 1)
            break
        }
        val numeri = estraiNumeri(resto)
        if (numeri.isEmpty() && selettore == null) {
            throw Errore("«${pezzo.trim()}»: quali numeri? Per esempio «$verbo 3», «$verbo 3 e 7», «$verbo da 10 a 15».")
        }
        if (verbo in setOf("apri", "pagina", "istruisci", "inoltra") && numeri.size != 1) throw Errore("«$verbo» vuole un numero solo.")
        if (selettore == "tutte" && verbo in setOf("rispondi", "invia", "inoltra", "istruisci")) {
            throw Errore("«$verbo tutte» no: dimmi i numeri (o «invia tutte le bozze»).")
        }
        if (selettore == "bozze" && verbo != "invia") throw Errore("«le bozze» vale solo con «invia»: «invia tutte le bozze».")
        if (testoLibero != null && verbo !in setOf("rispondi", "inoltra", "istruisci")) {
            throw Errore("il testo dopo i due punti vale solo con «rispondi», «inoltra» o «istruisci», non con «$verbo».")
        }
        if (verbo == "istruisci" && testoLibero == null) throw Errore("«istruisci» vuole il testo dopo i due punti: «istruisci ${numeri[0]}: …».")
        return Richiesta(verbo, numeri, selettore, testoLibero, cartella, invia, destinatario)
    }

    /** «3, 5, 6, 7» → «3, 5-7»: per dire in poco spazio cosa si farà. */
    fun elencoCorto(numeri: List<Int>): String {
        if (numeri.isEmpty()) return ""
        val s = numeri.sorted()
        val pezzi = mutableListOf<String>()
        var inizio = s[0]
        var prima = s[0]
        for (n in s.drop(1) + listOf(Int.MIN_VALUE)) {
            if (n == prima + 1) { prima = n; continue }
            pezzi.add(if (inizio == prima) "$inizio" else if (prima == inizio + 1) "$inizio, $prima" else "$inizio-$prima")
            inizio = n
            prima = n
        }
        return pezzi.joinToString(", ")
    }

    private val NOMI_SELETTORI = mapOf(
        "bozze" to "tutte le bozze", "cat:5,6" to "le promozioni", "cat:4" to "le notifiche",
        "cat:7,3" to "le fatture", "tutte" to "tutte",
    )

    /** «Farò: cestina 10-15 (6 mail)»: la riga che il telefono mostra prima di mandare il comando. */
    fun anteprima(richieste: List<Richiesta>): String = "Farò: " + richieste.joinToString("; ") { r ->
        val chi = buildList {
            if (r.numeri.isNotEmpty()) add(elencoCorto(r.numeri) + if (r.numeri.size > 1) " (${r.numeri.size} mail)" else "")
            r.selettore?.let { add(NOMI_SELETTORI[it] ?: it) }
        }.joinToString(" e ")
        val verbo = when {
            r.invia -> "${r.verbo} e invia"
            r.verbo == "resoconto" -> "controlla la posta"
            else -> r.verbo
        }
        buildString {
            append(verbo)
            if (chi.isNotEmpty()) append(" ").append(chi)
            r.cartella?.let { append(" in ").append(it) }
            r.destinatario?.let { append(" a ").append(it) }
            if (r.verbo == "rispondi") append(if (r.testo != null) " col tuo testo" else " con una bozza scritta dal Postino")
            if (r.verbo == "istruisci") append(": JBoss prepara la bozza, non parte niente")
            if (r.verbo == "resoconto" && r.casella != null) append(" di ").append(r.casella)
        }
    }

    /** I comandi che il telefono manda da solo, dalla selezione: «cestina 3, 5-7». */
    fun daSelezione(verbo: String, numeri: Collection<Int>): String = "$verbo ${elencoCorto(numeri.toList())}"
}

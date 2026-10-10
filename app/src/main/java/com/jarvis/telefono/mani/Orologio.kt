package com.jarvis.telefono.mani

/**
 * Sveglie e timer dalla frase (0.5.0, Boss 08/10: «chiama X, imposta sveglia, timer: oggi si apre solo
 * l'Orologio, completa l'azione o di' chiaramente cosa manca»). Kotlin puro, provato in OrologioTest.
 *
 * Lavora sulla «chiave» delle regole: minuscole, senza accenti né punteggiatura («6:45» arriva «6 45»).
 * Null = la frase non dice abbastanza (niente ora, niente durata): le regole non inventano, la frase passa
 * al cervello della VPS o JBoss chiede l'ora.
 */
object Orologio {

    data class Sveglia(val ora: Int, val minuti: Int)

    private val UNITA = listOf(
        "zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci",
        "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove",
    )
    private val DECINE = mapOf("venti" to 20, "trenta" to 30, "quaranta" to 40, "cinquanta" to 50)

    /** «sette» 7, «ventitre» 23, «trentotto» 38, «un»/«una» 1, «45» 45. Null se non è un numero da 0 a 59. */
    fun numero(p: String): Int? {
        val w = p.trim()
        w.toIntOrNull()?.let { return it }
        if (w == "un" || w == "una") return 1
        UNITA.indexOf(w).takeIf { it >= 0 }?.let { return it }
        for ((nome, v) in DECINE) {
            val radice = nome.dropLast(1) // «vent», «trent»…: «ventuno», «ventotto»
            if (w == nome) return v
            if (w.startsWith(nome) || w.startsWith(radice)) {
                val resto = if (w.startsWith(nome)) w.removePrefix(nome) else w.removePrefix(radice)
                val u = when (resto) { "uno" -> 1; "otto" -> 8; else -> UNITA.indexOf(resto).takeIf { it in 2..9 } }
                if (u != null) return v + u
            }
        }
        return null
    }

    private const val N = "(\\d{1,2}|[a-z]+)"
    private val E_MINUTI = Regex("^$N(?:\\s+(?:e\\s+)?(?:(mezza|mezzo)|(un quarto)|(tre quarti)|$N(?:\\s+minuti)?))?(?:\\s+(del mattino|di mattina|di sera|del pomeriggio|di notte|meno un quarto))?\\b")

    /** «metti una sveglia alle 6 e 45», «svegliami alle sette e mezza», «sveglia alle 18 30», «sveglia per le 7». */
    fun sveglia(k: String): Sveglia? {
        if (!Regex("\\b(sveglia|svegliami)\\b").containsMatchIn(k)) return null
        val m = Regex("\\b(?:alle|per le|a le|all|alla|per l|verso le|ore)\\s+(.+)$").find(k) ?: return null
        val dopo = m.groupValues[1].replace(Regex("^ore\\s+"), "").trim()
        if (dopo.startsWith("una ") || dopo == "una") return componi(1, dopo.removePrefix("una").trim())
        val e = E_MINUTI.find(dopo) ?: return null
        val ora = numero(e.groupValues[1]) ?: return null
        return componi(ora, dopo.removePrefix(e.groupValues[1]).trim())
    }

    private fun componi(oraDetta: Int, resto: String): Sveglia? {
        var ora = oraDetta
        if (ora !in 0..23) return null
        var minuti = 0
        val r = resto.removePrefix("e ").trim()
        when {
            r.startsWith("mezza") || r.startsWith("mezzo") -> minuti = 30
            r.startsWith("un quarto") -> minuti = 15
            r.startsWith("tre quarti") -> minuti = 45
            r.startsWith("meno un quarto") -> { minuti = 45; ora = (ora + 23) % 24 }
            else -> {
                val primo = r.split(' ').firstOrNull().orEmpty()
                numero(primo)?.let { if (it in 0..59) minuti = it else return null }
            }
        }
        if (Regex("\\b(di sera|del pomeriggio)\\b").containsMatchIn(resto) && ora in 1..11) ora += 12
        return Sveglia(ora, minuti)
    }

    /** «timer di 5 minuti», «un timer di un'ora e mezza», «conto alla rovescia di 90 secondi», «timer di mezz'ora». */
    fun timerSecondi(k: String): Int? {
        if (!Regex("\\b(timer|conto alla rovescia|countdown)\\b").containsMatchIn(k)) return null
        val t = k.replace("'", " ").replace(Regex("\\s+"), " ")
        var tot = 0
        var trovato = false
        if (Regex("\\bmezz\\s?ora\\b").containsMatchIn(t)) { tot += 1800; trovato = true }
        if (Regex("\\bun quarto d\\s?ora\\b").containsMatchIn(t)) { tot += 900; trovato = true }
        Regex("\\b$N\\s+(ore|ora|minuti|minuto|secondi|secondo)\\b").findAll(t).forEach {
            val n = numero(it.groupValues[1]) ?: return@forEach
            val per = when (it.groupValues[2]) { "ore", "ora" -> 3600; "minuti", "minuto" -> 60; else -> 1 }
            tot += n * per
            trovato = true
        }
        // «un'ora e mezza», «due minuti e mezzo»
        if (Regex("\\b(ore|ora)\\s+e\\s+mezza\\b").containsMatchIn(t)) tot += 1800
        if (Regex("\\b(minuti|minuto)\\s+e\\s+mezzo\\b").containsMatchIn(t)) tot += 30
        return if (trovato && tot in 1..86_399) tot else null
    }

    /** «6 e 45», «7 e 5»: per la risposta detta. */
    fun inParole(s: Sveglia): String = if (s.minuti == 0) "${s.ora}" else "${s.ora} e ${s.minuti}"

    /** «5 minuti», «1 ora e 30 minuti», «90 secondi» → testo breve per la risposta. */
    fun durataInParole(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        val pezzi = buildList {
            if (h > 0) add(if (h == 1) "un'ora" else "$h ore")
            if (m > 0) add(if (m == 1) "un minuto" else "$m minuti")
            if (s > 0) add(if (s == 1) "un secondo" else "$s secondi")
        }
        return pezzi.joinToString(" e ")
    }
}

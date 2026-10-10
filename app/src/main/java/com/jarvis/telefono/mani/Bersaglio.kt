package com.jarvis.telefono.mani

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Come si trova un elemento dello schermo da una chiave: «:id/send» cerca l'id di risorsa,
 * qualunque altra cosa cerca nel testo e nella descrizione (uguale prima, poi contenuto).
 * Niente Android: lavora su [NodoInfo].
 */
object Bersaglio {
    /**
     * 0 = non corrisponde; più alto = più sicuro. Un testo non cerca mai dentro gli id:
     * «Search» trovava un id nascosto nella barra di WhatsApp e apriva la scheda del contatto.
     */
    fun punteggio(chiave: String, n: NodoInfo): Int {
        val k = chiave.trim()
        if (k.isEmpty()) return 0
        if (k.startsWith(":id/") || k.contains(":id/")) {
            val voluto = ParoleInvio.nomeId(k) ?: return 0
            return if (ParoleInvio.nomeId(n.id) == voluto) 100 else 0
        }
        val q = k.lowercase()
        val testi = listOfNotNull(n.descrizione, n.testo).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return when {
            testi.any { it == q } -> 90
            testi.any { it.startsWith(q) } -> 70
            testi.any { it.contains(q) } -> 50
            else -> 0
        }
    }

    /** L'indice del miglior elemento per la prima chiave che trova qualcosa; -1 se nessuna. */
    fun trova(chiavi: List<String>, nodi: List<NodoInfo>): Int {
        for (k in chiavi) {
            var migliore = -1
            var punti = 0
            nodi.forEachIndexed { i, n ->
                val p = punteggio(k, n) + if (n.cliccabile) 1 else 0
                if (p > punti && p > 1) { punti = p; migliore = i }
            }
            if (migliore >= 0) return migliore
        }
        return -1
    }
}

/**
 * Rubrica: quale contatto è «Marco» o «Marco Rossi». Niente Android: riceve i nomi.
 */
object CercaContatti {
    data class Contatto(val nome: String, val numeri: List<String>, val mail: List<String>)

    fun punteggio(cercato: String, nome: String): Int {
        val q = CatalogoApp.normalizza(cercato)
        val n = CatalogoApp.normalizza(nome)
        if (q.isEmpty() || n.isEmpty()) return 0
        val parole = n.split(' ')
        val pq = q.split(' ')
        return when {
            n == q -> 100
            pq.all { t -> parole.any { it == t } } -> 85
            pq.all { t -> parole.any { it.startsWith(t) } } -> 70
            n.contains(q) -> 50
            else -> 0
        }
    }

    fun cerca(cercato: String, contatti: List<Contatto>): List<Contatto> =
        contatti.map { it to punteggio(cercato, it.nome) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
}

/**
 * Il registro di ogni azione sul telefono (Boss 2026-10-07). Una riga per azione: ora, app in
 * primo piano, azione, bersaglio, esito. Mai il testo scritto né le password: solo quanti
 * caratteri. Tiene le ultime [max] righe.
 */
class RegistroAzioni(private val file: File, private val max: Int = 400) {
    private val formato = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY)

    @Synchronized
    fun scrivi(app: String?, azione: String, dettaglio: String, esito: String, adesso: Date = Date()) {
        val riga = listOf(formato.format(adesso), app ?: "-", azione, dettaglio.replace('\n', ' ').take(160), esito.replace('\n', ' ').take(160))
            .joinToString(" | ")
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(riga + "\n")
            val righe = file.readLines()
            if (righe.size > max + 50) file.writeText(righe.takeLast(max).joinToString("\n", postfix = "\n"))
        }
    }

    @Synchronized
    fun ultime(n: Int): List<String> = runCatching { file.readLines().takeLast(n.coerceIn(1, max)) }.getOrDefault(emptyList())
}

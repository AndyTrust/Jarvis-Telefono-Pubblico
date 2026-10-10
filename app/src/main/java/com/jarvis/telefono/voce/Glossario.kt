package com.jarvis.telefono.voce

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Il post-correttore della trascrizione: porta uno a uno di
 * il correttore di riferimento (`post_correggi`, `_norm`, `distanza`, `glossario`,
 * `parole_comuni`) del computer, 26/09/2026.
 *
 * Legge da [cartella] tre file che il computer spinge sul ponte e l'app scarica
 * con [aggiorna]:
 *
 *   glossario.txt       una riga per termine: `termine<TAB>fonte<TAB>peso`
 *                       (le colonne 2 e 3 sono facoltative, peso 1 se manca);
 *                       righe vuote e righe che iniziano con `#` si saltano.
 *                       Se il file manca valgono i termini [FISSI] con peso 5.
 *   correzioni.jsonl    una riga JSON per coppia: {"sbagliata": ..., "giusta": ...}
 *                       (gli altri campi, ts e frase, si ignorano).
 *   parole-comuni.txt   una parola per riga, le 20.000 italiane più comuni.
 *                       Senza questa lista si applicano solo le correzioni
 *                       esplicite, come sul computer.
 *
 * I file si rileggono da soli quando cambiano (data o dimensione).
 * [registro] riceve righe di diagnostica senza segreti: il token non ci passa mai.
 */
class Glossario(
    private val cartella: File,
    private val registro: (String) -> Unit = {},
) {

    private val fileGlossario = File(cartella, GLOSSARIO)
    private val fileCorrezioni = File(cartella, CORREZIONI)
    private val fileComuni = File(cartella, COMUNI)

    private class Termine(val testo: String, val peso: Double)
    private class Correzione(val sbagliata: String, val giusta: String)

    private var firmaGlossario: Pair<Long, Long>? = null
    private var firmaCorrezioni: Pair<Long, Long>? = null
    private var firmaComuni: Pair<Long, Long>? = null
    private var termini: List<Termine> = FISSI.map { Termine(it, 5.0) }
    private var correzioni: List<Correzione> = emptyList()
    private var comuni: Set<String> = emptySet()

    /** Glossario e parole comuni ci sono: il correttore lavora per intero. */
    val pronto: Boolean
        get() = fileGlossario.isFile && fileComuni.isFile && fileComuni.length() > 0

    /** I termini del glossario (o i fissi, se il file manca), nell'ordine del file. */
    @Synchronized
    fun termini(): List<String> {
        ricarica()
        return termini.map { it.testo }
    }

    // ------------------------------------------------------------ lettura

    private fun firma(f: File): Pair<Long, Long>? =
        if (f.isFile) f.lastModified() to f.length() else null

    @Synchronized
    private fun ricarica() {
        val fg = firma(fileGlossario)
        if (fg != firmaGlossario) {
            termini = if (fg == null) FISSI.map { Termine(it, 5.0) } else leggiGlossario()
            firmaGlossario = fg
        }
        val fc = firma(fileCorrezioni)
        if (fc != firmaCorrezioni) {
            correzioni = if (fc == null) emptyList() else leggiCorrezioni()
            firmaCorrezioni = fc
        }
        val fx = firma(fileComuni)
        if (fx != firmaComuni) {
            comuni = if (fx == null) emptySet() else leggiComuni()
            firmaComuni = fx
        }
    }

    private fun leggiRighe(f: File): List<String> = try {
        f.readText(Charsets.UTF_8).lines()
    } catch (e: IOException) {
        emptyList()
    }

    // Come glossario() del computer: termine, fonte, peso separati da TAB.
    private fun leggiGlossario(): List<Termine> {
        val out = ArrayList<Termine>()
        for (r in leggiRighe(fileGlossario)) {
            if (r.isBlank() || r.startsWith("#")) continue
            val c = r.split("\t")
            val peso = if (c.size > 2) c[2].trim().toDoubleOrNull() ?: 1.0 else 1.0
            out.add(Termine(c[0].trim(), peso))
        }
        return out
    }

    private fun leggiCorrezioni(): List<Correzione> {
        val out = ArrayList<Correzione>()
        for (r in leggiRighe(fileCorrezioni)) {
            if (r.isBlank()) continue
            val o = try {
                Json(r).oggetto()
            } catch (e: IllegalArgumentException) {
                null
            } ?: continue
            val sb = o["sbagliata"] as? String ?: ""
            val gi = o["giusta"] as? String ?: ""
            out.add(Correzione(sb, gi))
        }
        return out
    }

    private fun leggiComuni(): Set<String> =
        leggiRighe(fileComuni).map { it.trim() }.filter { it.isNotEmpty() }
            .mapTo(HashSet()) { norm(it) }

    // ------------------------------------------------------ post-correttore

    /**
     * 1) le correzioni esplicite dell'utente (parola intera, senza badare alle
     * maiuscole); 2) le parole rare (non fra le comuni) a distanza di edit
     * ≤ 1 (≤ 5 lettere) o ≤ 2 da un termine singolo del glossario; 3) due
     * parole attaccate che danno un termine («I cassa» → «iCassa»);
     * 4) i termini di più parole, con l'«ancora». Ritorna il testo e le
     * sostituzioni (prima, dopo).
     */
    @Synchronized
    fun postCorreggi(testo: String): Pair<String, List<Pair<String, String>>> {
        if (testo.isEmpty()) return testo to emptyList()
        ricarica()
        var t = testo
        val fatte = ArrayList<Pair<String, String>>()
        for (c in correzioni) {
            val sb = c.sbagliata
            val gi = c.giusta
            if (sb.isEmpty() || gi.isEmpty() || norm(sb) == norm(gi)) continue
            // Android non supporta Pattern.UNICODE_CHARACTER_CLASS (IllegalArgumentException alla creazione della classe,
            // 04/10/2026: il servizio andava in crash all'avvio): la classe «parola» è scritta a mano e vale uguale su JVM e Android.
            val rx = Pattern.compile(
                "(?<![\\p{L}\\p{M}\\p{N}_])" + Pattern.quote(sb) + "(?![\\p{L}\\p{M}\\p{N}_])",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )
            val m = rx.matcher(t)
            if (m.find()) {
                t = m.replaceAll(Matcher.quoteReplacement(gi))
                fatte.add(sb to gi)
            }
        }
        val comuni = this.comuni
        if (comuni.isEmpty()) return t to fatte

        val singoli = LinkedHashMap<String, Termine>()
        val multi = ArrayList<Pair<Termine, String>>()
        val attaccati = LinkedHashMap<String, String>()
        for (term in termini) {
            val tn = norm(term.testo)
            if (term.testo.trim().contains(' ')) {
                multi.add(term to tn)
            } else if (tn.length >= 4) {
                singoli.putIfAbsent(tn, term)
            }
            if (tn.replace(" ", "").length >= 6) {
                attaccati.putIfAbsent(tn.replace(" ", "").replace(".", ""), term.testo)
            }
        }

        class Tok(val w: String, val a: Int, val b: Int)
        val tok = ArrayList<Tok>()
        val mp = PAROLA.matcher(t)
        while (mp.find()) tok.add(Tok(mp.group(), mp.start(), mp.end()))

        fun raro(w: String) = norm(w).trim('.') !in comuni

        val sost = ArrayList<Triple<Int, Int, String>>()
        val usati = HashSet<Int>()

        // 3) due parole attaccate, prima delle parole singole
        for (i in 0 until tok.size - 1) {
            val w1 = tok[i].w
            val w2 = tok[i + 1].w
            if (t.substring(tok[i].b, tok[i + 1].a).isNotBlankPy()) continue
            val k = norm(w1 + w2).replace(".", "")
            val att = attaccati[k] ?: continue
            if (norm("$w1 $w2") != norm(att)) {
                sost.add(Triple(tok[i].a, tok[i + 1].b, att))
                usati.add(i); usati.add(i + 1)
            }
        }
        // 4) i termini di più parole
        for ((term, tn) in multi) {
            val parti = splitPy(tn)
            val n = parti.size
            for (i in 0..tok.size - n) {
                if ((i until i + n).any { it in usati }) continue
                val fin = tok.subList(i, i + n)
                if ((0 until n - 1).any { j -> t.substring(fin[j].b, fin[j + 1].a).isNotBlankPy() }) continue
                val finestra = norm(fin.joinToString(" ") { it.w })
                if (finestra == tn) continue
                // Basta una parola rara, oppure un'«ancora»: una parola del
                // termine scritta giusta e le altre lunghe almeno 4 lettere
                // («l'utente Bryan» → «Note», dove «bryan» è comune).
                val ancora = fin.indices.any { j -> norm(fin[j].w) == parti[j] } &&
                    fin.indices.all { j -> norm(fin[j].w) == parti[j] || fin[j].w.codePointCount(0, fin[j].w.length) >= 4 }
                if (!(fin.any { raro(it.w) } || ancora)) continue
                val lim = maxOf(1, tn.codePointCount(0, tn.length) / 6)
                if (distanza(finestra, tn, lim) <= lim) {
                    sost.add(Triple(fin[0].a, fin[n - 1].b, term.testo))
                    for (x in i until i + n) usati.add(x)
                }
            }
        }
        // 2) le parole singole rare
        for ((i, tk) in tok.withIndex()) {
            if (i in usati) continue
            val wn = norm(tk.w).trim('.')
            if (wn.length < 4 || !raro(tk.w) || wn in singoli) continue
            val lim = if (wn.length <= 5) 1 else 2
            var bestD = 0
            var bestPeso = 0.0
            var best: String? = null
            for ((tn, term) in singoli) {
                if (Math.abs(tn.length - wn.length) > lim) continue
                val d = distanza(wn, tn, lim)
                if (d <= lim && (best == null || d < bestD || (d == bestD && -term.peso < -bestPeso))) {
                    bestD = d; bestPeso = term.peso; best = term.testo
                }
            }
            if (best != null) sost.add(Triple(tk.a, tk.b, best))
        }
        // Dalla fine all'inizio, così le posizioni restano valide
        // (stesso ordine di sorted(sost, reverse=True) del computer).
        val ordinate = sost.sortedWith(
            compareByDescending<Triple<Int, Int, String>> { it.first }
                .thenByDescending { it.second }
                .thenByDescending { it.third },
        )
        for ((a, b, nuovo) in ordinate) {
            fatte.add(t.substring(a, b) to nuovo)
            t = t.substring(0, a) + nuovo + t.substring(b)
        }
        return t to fatte
    }

    // --------------------------------------------------------- aggiornamento

    /**
     * Scarica i tre file dal ponte (`$baseUrl/voce/<file>`), ognuno su un
     * file temporaneo rinominato sopra il vecchio solo a download finito.
     * Un 404 (o un errore) lascia il file com'era. Ritorna true se almeno
     * un file è cambiato. Il token va solo nell'header, mai nel registro.
     */
    fun aggiorna(client: OkHttpClient, baseUrl: String, token: String): Boolean {
        val base = baseUrl.trimEnd('/')
        var cambiato = false
        cartella.mkdirs()
        for (nome in listOf(GLOSSARIO, CORREZIONI, COMUNI)) {
            val url = "$base/voce/$nome"
            val req = try {
                Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            } catch (e: IllegalArgumentException) {
                registro("[glossario] indirizzo non valido per $nome")
                return false
            }
            try {
                client.newCall(req).execute().use { r ->
                    when {
                        r.code == 404 -> registro("[glossario] $nome non c'è sul ponte (404), resta il vecchio")
                        !r.isSuccessful -> registro("[glossario] $nome: HTTP ${r.code}, resta il vecchio")
                        else -> {
                            val corpo = r.body?.bytes() ?: ByteArray(0)
                            if (corpo.size > MAX_BYTE) {
                                registro("[glossario] $nome troppo grande (${corpo.size} byte), scartato")
                            } else if (scriviSeDiverso(File(cartella, nome), corpo)) {
                                registro("[glossario] $nome aggiornato (${corpo.size} byte)")
                                cambiato = true
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                registro("[glossario] $nome non scaricato: ${e.javaClass.simpleName}")
            }
        }
        return cambiato
    }

    private fun scriviSeDiverso(dest: File, corpo: ByteArray): Boolean {
        if (dest.isFile && dest.length() == corpo.size.toLong() && dest.readBytes().contentEquals(corpo)) {
            return false
        }
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(corpo)
        if (!tmp.renameTo(dest)) {
            // renameTo sopra un file esistente di solito va; se no, Files.move.
            java.nio.file.Files.move(
                tmp.toPath(), dest.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }
        return true
    }

    companion object {
        const val GLOSSARIO = "glossario.txt"
        const val CORREZIONI = "correzioni.jsonl"
        const val COMUNI = "parole-comuni.txt"
        private const val MAX_BYTE = 16 * 1024 * 1024

        /** I termini fissi: nomi di prodotti che il riconoscimento vocale sbaglia. Il proprio elenco si aggiunge dal glossario. */
        val FISSI = listOf(
            "Jarvis", "JBoss", "Claude", "Claude Code", "Codex", "Cursor", "Gemini", "Kokoro", "Whisper",
            "Supabase", "Vercel", "GitHub", "Telegram", "LinkedIn", "Command Center",
        )

        // Una parola, anche con punti DENTRO (sportello.cloud), mai il punto finale.
        private val PAROLA: Pattern = Pattern.compile("[\\p{L}\\p{M}\\p{N}_]+(?:\\.[\\p{L}\\p{M}\\p{N}_]+)*")
        private val SEGNI = Regex("\\p{Mn}+")

        /** Minuscole e niente accenti, come `_norm` del computer (NFKD). */
        fun norm(s: String): String =
            SEGNI.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD), "")

        /** Levenshtein con uscita anticipata oltre [limite], come `distanza` del computer. */
        fun distanza(a: String, b: String, limite: Int = 99): Int {
            if (Math.abs(a.length - b.length) > limite) return limite + 1
            var prec = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                var minimo = i
                for (j in 1..b.length) {
                    val costo = if (a[i - 1] != b[j - 1]) 1 else 0
                    cur[j] = minOf(prec[j] + 1, cur[j - 1] + 1, prec[j - 1] + costo)
                    if (cur[j] < minimo) minimo = cur[j]
                }
                if (minimo > limite) return limite + 1
                prec = cur
            }
            return prec[b.length]
        }

        // str.strip() di Python: vero se resta qualcosa dopo aver tolto gli spazi.
        private fun String.isNotBlankPy(): Boolean = any { !it.isWhitespace() && !Character.isSpaceChar(it) }

        // str.split() di Python senza argomenti.
        private fun splitPy(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
}

/**
 * Un lettore JSON minimo, solo per le righe di correzioni.jsonl: sulla JVM
 * delle prove `org.json` di Android è uno stub vuoto. Legge un valore intero
 * (oggetto, lista, stringa, numero, true/false/null) e lancia
 * IllegalArgumentException se la riga non è JSON valido.
 */
internal class Json(private val s: String) {
    private var i = 0

    fun oggetto(): Map<String, Any?>? {
        val v = valore()
        spazi()
        if (i != s.length) errore()
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?>
    }

    private fun errore(): Nothing = throw IllegalArgumentException("JSON non valido alla posizione $i")

    private fun spazi() {
        while (i < s.length && s[i] in " \t\r\n") i++
    }

    private fun prendi(c: Char): Boolean {
        spazi()
        if (i < s.length && s[i] == c) {
            i++
            return true
        }
        return false
    }

    private fun valore(): Any? {
        spazi()
        if (i >= s.length) errore()
        return when (s[i]) {
            '{' -> { i++; mappa() }
            '[' -> { i++; lista() }
            '"' -> stringa()
            't' -> parola("true", true)
            'f' -> parola("false", false)
            'n' -> parola("null", null)
            else -> numero()
        }
    }

    private fun mappa(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        if (prendi('}')) return m
        do {
            spazi()
            if (i >= s.length || s[i] != '"') errore()
            val k = stringa()
            if (!prendi(':')) errore()
            m[k] = valore()
        } while (prendi(','))
        if (!prendi('}')) errore()
        return m
    }

    private fun lista(): List<Any?> {
        val l = ArrayList<Any?>()
        if (prendi(']')) return l
        do {
            l.add(valore())
        } while (prendi(','))
        if (!prendi(']')) errore()
        return l
    }

    private fun parola(p: String, v: Any?): Any? {
        if (!s.startsWith(p, i)) errore()
        i += p.length
        return v
    }

    private fun numero(): Double {
        val inizio = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(inizio, i).toDoubleOrNull() ?: errore()
    }

    private fun stringa(): String {
        i++ // la virgoletta d'apertura
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) errore()
            val c = s[i++]
            if (c == '"') return sb.toString()
            if (c != '\\') {
                sb.append(c)
                continue
            }
            if (i >= s.length) errore()
            when (s[i++]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 4 > s.length) errore()
                    sb.append((s.substring(i, i + 4).toIntOrNull(16) ?: errore()).toChar())
                    i += 4
                }
                else -> errore()
            }
        }
    }
}

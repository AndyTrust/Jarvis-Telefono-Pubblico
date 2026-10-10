package com.jarvis.telefono.voce

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * La voce sintetica di Jarvis sul telefono (l'utente, 2026-10-04).
 *
 * «Quando usiamo l'audio di Jarvis deve essere sintetico: non deve dire le
 * stringhe di comando che sta facendo per eseguire l'operatività.»
 *
 * [perLaVoce] riceve la risposta intera e restituisce SOLO quello che si dice
 * ad alta voce; il testo a schermo resta intero. È la copia fedele di
 * `per_la_voce` in ~/Jarvis/backtalk/backtalk/sintesi_voce.py (e di
 * command-center/static/voce-sintetica.js): stessi filtri, stessi limiti
 * (2 frasi o circa 220 caratteri), stesse frasi finali. Le prove
 * (PerLaVoceTest) hanno le stesse uscite attese della versione Python.
 *
 * Espressioni regolari e Android. Il motore di Android (ICU) non è quello
 * della JVM: niente flag che ICU rifiuta (quello delle classi Unicode ha già
 * fatto cadere l'app), niente `\w`, `\s`, `\d`, `\b` nudi, perché su JVM sono
 * ASCII e su ICU sono Unicode e darebbero risultati diversi. I modelli qui
 * sotto sono scritti con la sintassi di Python e [py] li traduce in classi
 * esplicite (lettere `\p{L}\p{M}\p{N}_`, spazi elencati uno per uno), uguali
 * sui due motori. Solo lookbehind di un carattere, niente quantificatori
 * possessivi né gruppi atomici. Flag usati: CASE_INSENSITIVE, UNICODE_CASE,
 * UNIX_LINES, DOTALL, tutti accettati da Android.
 */
object PerLaVoce {

    const val MAX_CARATTERI = 220
    const val MAX_FRASI = 2
    const val CODA_RESTO = "Il resto è nella chat."
    const val NIENTE_DA_DIRE = "Fatto, i dettagli sono nella chat."

    // ------------------------------------------------------------ traduzione dei modelli
    /** Le lettere di Python 3 (`\w`). */
    private const val W = "\\p{L}\\p{M}\\p{N}_"

    /** Gli spazi di Python 3 (`\s`, cioè str.isspace). */
    private const val S = "\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\u0085\\u00A0\\u1680\\u2000-\\u200A" +
        "\\u2028\\u2029\\u202F\\u205F\\u3000"
    private const val D = "\\p{Nd}"
    private const val BORDO = "(?:(?<=[$W])(?![$W])|(?<![$W])(?=[$W]))"

    private const val SPAZI = "\t\n\u000B\u000C\r\u001C\u001D\u001E\u001F \u0085\u00A0\u1680" +
        "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000"

    /** Traduce un modello scritto come in Python in uno che dà lo stesso risultato su JVM e su ICU. */
    internal fun py(p: String): String {
        val sb = StringBuilder()
        var inClasse = false
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '\\' && i + 1 < p.length) {
                val n = p[i + 1]
                when (n) {
                    'w' -> sb.append(if (inClasse) W else "[$W]")
                    's' -> sb.append(if (inClasse) S else "[$S]")
                    'S' -> {
                        require(!inClasse) { "\\S dentro una classe" }
                        sb.append("[^$S]")
                    }
                    'd' -> sb.append(D)
                    'b' -> {
                        require(!inClasse) { "\\b dentro una classe" }
                        sb.append(BORDO)
                    }
                    'Z' -> sb.append("\\z")
                    'x' -> if (i + 2 < p.length && p[i + 2] == '{') {
                        val fine = p.indexOf('}', i + 2)
                        sb.append(p, i, fine + 1)
                        i = fine + 1
                        continue
                    } else {
                        sb.append(c).append(n)
                    }
                    else -> sb.append(c).append(n)
                }
                i += 2
                continue
            }
            if (inClasse) {
                when (c) {
                    ']' -> { inClasse = false; sb.append(c) }
                    '[', '{', '}', '&' -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
            } else {
                if (c == '[') {
                    inClasse = true
                    sb.append(c)
                    if (i + 1 < p.length && p[i + 1] == '^') {
                        sb.append('^')
                        i++
                    }
                } else {
                    sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    private const val BASE = Pattern.UNIX_LINES
    private const val I = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    private fun re(p: String, flag: Int = 0): Pattern = Pattern.compile(py(p), BASE or flag)

    // ------------------------------------------------------------ i modelli (come in Python)
    private val COMANDI = setOf(
        "$", "#!", "ssh", "scp", "rsync", "git", "gh", "python", "python3", "pip",
        "pip3", "uv", "curl", "wget", "docker", "docker-compose", "systemctl",
        "journalctl", "sudo", "cd", "ls", "cat", "head", "tail", "grep", "rg",
        "find", "sed", "awk", "echo", "export", "source", "chmod", "chown", "mkdir",
        "rm", "mv", "cp", "touch", "kill", "pkill", "killall", "ps", "top", "brew",
        "npm", "npx", "node", "yarn", "pnpm", "launchctl", "rclone", "adb",
        "fastboot", "vercel", "open", "osascript", "defaults", "crontab", "tmux",
        "screen", "make", "cargo", "go", "java", "gradle", "./gradlew", "bash",
        "sh", "zsh", "env", "lsof", "netstat", "ping", "dig", "nslookup", "tar",
        "unzip", "zip", "ffmpeg", "say", "pm2", "caddy", "nginx", "psql", "mysql",
        "sqlite3", "kubectl", "terraform", "jq", "xargs", "nohup", "which",
        "whoami", "df", "du", "uname", "date", "diff", "patch", "less", "more",
        "vim", "nano", "code", "codegraph", "graphify", "notebooklm",
    )
    private val AMBIGUI = setOf(
        "date", "open", "make", "code", "more", "less", "top", "say",
        "go", "find", "which", "source", "patch", "diff", "env",
        "screen", "kill", "touch", "head", "tail", "export", "java",
        "node", "ping", "zip",
    )

    private val NARRAZIONE = re(
        """^\s*(?:(?:ok|bene|allora|ora|adesso|intanto|poi|prima|quindi|perfetto)""" +
            """[,!.]?\s+)*(?:""" +
            """(?:lancio|rilancio|eseguo|avvio|faccio\s+partire|apro|leggo|rileggo|""" +
            """controllo|ricontrollo|verifico|cerco|guardo|provo|riprovo|scrivo|""" +
            """modifico|aggiorno|installo|scarico|copio|sposto|riavvio|passo\s+a|""" +
            """vado\s+a|mi\s+collego|uso|interrogo|chiedo|confronto|analizzo|esamino|""" +
            """preparo|creo|aggiungo|sistemo|correggo|ricarico|compilo|testo|""" +
            """do\s+un'?occhiata|dò\s+un'?occhiata|faccio\s+un\s+(?:controllo|giro|""" +
            """tentativo|test))\b""" +
            """|(?:sto|stiamo)\s+\w+(?:ando|endo)\b""" +
            """|(?:ora|adesso)\s+(?:provo|vedo|guardo|controllo)\b""" +
            """|un\s+(?:momento|attimo|secondo)\b""" +
            """|let\s+me\b|let's\b|i'll\s+(?:run|check|look|read|open|try|grab|""" +
            """search|start|use|fetch|write|edit|update)\b""" +
            """|i'm\s+(?:going\s+to|now\s+)?\w+ing\b""" +
            """|(?:now\s+)?(?:running|checking|reading|looking|opening|searching|""" +
            """fetching|writing|editing|trying|grabbing|loading)\b""" +
            """|one\s+(?:moment|sec(?:ond)?)\b""" +
            """)""",
        I,
    )
    private val ESITO = re(
        """\b(?:fatto|fatta|fatti|ok|okay|pronto|pronta|pronti|finito|finita|""" +
            """completat[oaie]|riuscit[oaie]|funziona(?:no)?|risolt[oaie]|""" +
            """sistemat[oaie]|salvat[oaie]|aggiornat[oaie]|attiv[oaie]|accès[oa]|""" +
            """acces[oaie]|spent[oaie]|partit[oaie]|chius[oaie]|a\s+posto|tutto\s+bene|""" +
            """verde|ross[oaie]|errore|errori|fallit[oaie]|fallisce|non\s+riesco|""" +
            """non\s+(?:funziona|va|parte)|bloccat[oaie]|manca|mancano|trovat[oaie]|""" +
            """esito|risultato|sì|no|done|ready|fixed|finished|failed|error|works|""" +
            """working|passed|success(?:ful)?|all\s+set|broken)\b""",
        I,
    )
    private val PROSSIMO = re(
        """\?|\b(?:serve|servono|ti\s+serve|devi|dovresti|vuoi|preferisci|posso|""" +
            """procedo|conferm\w*|dimmi|decidi|scegli|prossim[oa]|domani|poi|""" +
            """resta|restano|da\s+fare|aspetto|attendo|""" +
            """should|want\s+me|do\s+you|next|need|confirm|let\s+me\s+know)\b""",
        I,
    )

    private val FENCE = re("""```.*?(?:```|\Z)|~~~.*?(?:~~~|\Z)""", Pattern.DOTALL)
    private val INLINE_CODE = re("""`[^`\n]*`""")
    private val MD_IMG = re("""!\[[^\]]*\]\([^)]*\)""")
    private val MD_LINK = re("""\[([^\]]+)\]\([^)]+\)""")
    private val URL = re("""\b(?:(?:https?|ftp|ssh|file|wss?)://|www\.)\S+?(?=[.,;:!?)»"']*(?:\s|$))""", I)
    private val EMAIL = re("""\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b""")
    private val IP = re(
        """\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b|\[?\b[0-9a-f]{0,4}::[0-9a-f:]*[0-9a-f]\b\]?""", I,
    )
    private val DOMINIO = re(
        """\b[\w-]+(?:\.[\w-]+)*\.(?:com|it|net|org|io|dev|app|cloud|ai|co|eu|me|""" +
            """info|xyz|sh|local|lan)\b(?::\d+)?(?:/\S*)?""",
        I,
    )
    private val HOST_LUNGO = re("""\b[a-z][\w-]*(?:\.[\w-]+)+\.[a-z]{2,}\b(?::\d+)?""", I)
    // In Python «(?:(?<=^)|(?<=[...]))»: qui «^» al posto del lookbehind su «^», stesso significato.
    private val PERCORSO_ASSOLUTO = re("""(?:^|(?<=[\s("'«:=]))(?:~|\.{1,2})?/[^\s,;)"'»]*""")
    private val PERCORSO_WIN = re("""\b[A-Za-z]:\\[^\s,;)"'»]*""")
    private val PERCORSO_RELATIVO = re("""(?<![\w/])[\w.@-]*[\w@](?:/[\w.@-]*[\w@])+/?""")
    private val UUID = re("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", I)
    private val HEX = re("""\b(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])[0-9a-f]{8,}\b""", I)
    private val ID_LUNGO = re("""\b(?=[\w-]*\d)(?=[\w-]*[A-Za-z])[\w-]{16,}\b""")
    private val OPZIONE = re("""(?<!\w)--?[a-zA-Z][\w-]*(?:=\S+)?""")
    private val EMOJI = re(
        """[\x{1F000}-\x{1FAFF}\u2600-\u27BF\x{1F1E6}-\x{1F1FF}""" +
            """\u2190-\u21FF\u2300-\u23FF\u2B00-\u2BFF\uFE0F\u200D\u20E3]""",
    )

    /** Segno messo al posto di quello che si toglie (in Python "\x00"). */
    private const val TOLTO = "\u0000"
    private const val TOLTO_RE = """\x00"""

    private val APPESA_PRIMA = re(
        """(?:\b(?:in|su|a|da|di|con|per|nel|nella|nello|nei|nelle|sul|sulla|sui|""" +
            """al|alla|ai|dal|dalla|dai|del|della|dei|tra|fra|come|tipo|cioè|ovvero|""" +
            """at|on|to|from|into|of|with|via|the|a|an|il|lo|la|l'|i|gli|le|un|una|""" +
            """uno|un'|file|cartella|comando|indirizzo|percorso|link)\s*)+""" + TOLTO_RE,
        I,
    )
    private val FINE_FRASE = re("""(?<=[.!?…])\s+""")
    private val RIGA_TABELLA = re("""^\s*\|.*\|?\s*$|^\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$""")
    private val RIGA_JSON = re("""^\s*(?:[{}\[\]],?\s*$|"[^"]*"\s*:|[{\[]\s*")""")
    private val RIGA_LOG = re(
        """^\s*(?:\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(?::\d{2})?\S*\s+\[|""" +
            """(?:Traceback|File "|\s+at\s+\S+\(|[A-Z]\w*(?:Error|Exception):))""",
    )
    private val PUNTO_ELENCO = re("""^\s*(?:[-*+•·▪►]|\d{1,2}[.)])\s+""")
    private val NUMERO = re("""(?<![A-Za-zÀ-ÿ])\d""")

    // modelli usati in linea nelle funzioni di Python
    private val SEGNO_AMBIGUO = re("""\s-{1,2}\w|[|>&;$/~=]|\.\w{1,4}\b""")
    private val SEGNO_COMANDO = re("""\s-{1,2}\w|[|&;$/~=\\'"]|\.\w{1,4}\b|@""")
    private val ESTENSIONE = re("""\.\w{1,5}$""")
    private val SOLO_CIFRE_PUNTI = re("""[\d.]+""")
    private val GRASSETTO = re("""\*\*|__|~~""")
    private val CORSIVO_STELLA = re("""(?<![\w*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?![\w*])""")
    private val CORSIVO_TRATTINO = re("""(?<![\w_])_(?=\S)([^_\n]+?)(?<=\S)_(?![\w_])""")
    private val SPAZI_RE = re("""\s+""")
    private val TOLTI_DI_FILA = re(TOLTO_RE + """(?:\s*""" + TOLTO_RE + """)+""")
    private val TOLTI_CON_E = re(TOLTO_RE + """\s*(?:e|o|ed|and|or)\s*""" + TOLTO_RE)
    private val PARENTESI_TOLTA = re("""\(\s*""" + TOLTO_RE + """?\s*\)""")
    private val BARRA = re("""(?<!\d)/|/(?!\d)""")
    private val TRATTINO_LUNGO = re("""\s*(?:—|–)\s*""")
    private val FRECCE = re("""->|=>|<-|<=|>=|\|""")
    private val SIMBOLI = re("""[_\\#*~^{}\[\]<>=]""")
    private val PARENTESI_VUOTE = re("""\(\s*[,.;:]*\s*\)""")
    private val SPAZIO_PRIMA_PUNTO = re("""\s+([.,;:!?…])""")
    private val PUNTEGGIATURA_DOPPIA = re("""([,;:])(?:\s*[,;:])+""")
    private val INIZIO_SPORCO = re("""^[\s,;:.\-]+""")
    private val PRIMA_DEL_PUNTO = re("""[\s,;:]+([.!?…])""")
    private val FINE_SPORCA = re("""[\s,;]+$""")
    private val SCENA = re("""<<[^<>]{1,80}>>""")
    private val SEGNAPOSTO = re("""\((?:codice|link)\)""", I)
    private val TITOLO = re("""^\s{0,3}#{1,6}\s""")
    private val CITAZIONE = re("""^\s*>\s?""")
    private val RIENTRATO = re("""^\s{4,}\S""")
    private val PAROLA = re("""[A-Za-zÀ-ÿ]{2,}|\d""")
    private val FINE_CON_SEGNO = re("""[.!?…:;]$""")
    private val ESITO_NARR = re(
        """\b(?:fatto|finito|completat\w|riuscit\w|ok|pronto|errore|""" +
            """done|ready|failed|error|works)\b""",
        I,
    )

    // ------------------------------------------------------------ aiuti alla Python
    private fun spazio(c: Char) = SPAZI.indexOf(c) >= 0
    private fun strip(s: String) = s.trim { spazio(it) }
    private fun rstrip(s: String) = s.trimEnd { spazio(it) }
    private fun lstrip(s: String) = s.trimStart { spazio(it) }
    private fun strip(s: String, car: String) = s.trim { car.indexOf(it) >= 0 }
    private fun rstrip(s: String, car: String) = s.trimEnd { car.indexOf(it) >= 0 }

    /** str.split() senza argomenti. */
    private fun parole(s: String): List<String> = SPAZI_RE.split(strip(s), -1).filter { it.isNotEmpty() }

    /** len() di Python: conta i caratteri veri, non le metà delle emoji. */
    private fun lung(s: String) = s.codePointCount(0, s.length)

    private fun cerca(p: Pattern, s: String) = p.matcher(s).find()
    private fun inizia(p: Pattern, s: String) = p.matcher(s).lookingAt()

    private inline fun sub(p: Pattern, s: String, f: (Matcher) -> String): String {
        val m = p.matcher(s)
        if (!m.find()) return s
        val sb = StringBuilder()
        var ultimo = 0
        do {
            sb.append(s, ultimo, m.start())
            sb.append(f(m))
            ultimo = m.end()
        } while (m.find())
        sb.append(s, ultimo, s.length)
        return sb.toString()
    }

    private fun sub(p: Pattern, s: String, con: String): String = sub(p, s) { con }
    private fun subGruppo1(p: Pattern, s: String): String = sub(p, s) { it.group(1) ?: "" }

    // ------------------------------------------------------------ la logica (come in Python)
    private fun eComando(riga: String): Boolean {
        var r = strip(riga)
        if (r.isEmpty()) return false
        if ((r.startsWith("$ ") || r.startsWith("% ") || r.startsWith("# ") || r.startsWith("> ")) &&
            r.length > 2 && strip(r.substring(2, 3)).isNotEmpty()
        ) {
            r = strip(r.substring(2))
            val l = lstrip(riga)
            if (parole(r)[0].lowercase() in COMANDI || l.startsWith("$") || l.startsWith("%")) return true
        }
        val primo = parole(r)[0]
        if (primo.lowercase() !in COMANDI && primo !in COMANDI) return false
        if (primo.lowercase() in AMBIGUI) return cerca(SEGNO_AMBIGUO, r)
        if (cerca(SEGNO_COMANDO, r)) return true
        return parole(r).size <= 4 && !(r.endsWith(".") || r.endsWith("!") || r.endsWith("?"))
    }

    private fun isdigit(p: String): Boolean = p.isNotEmpty() && p.codePoints().allMatch { cp ->
        Character.isDigit(cp) ||
            (Character.getType(cp) == Character.OTHER_NUMBER.toInt() && Character.getNumericValue(cp) in 0..9)
    }

    private fun isalpha(p: String): Boolean = p.isNotEmpty() && p.codePoints().allMatch { Character.isLetter(it) }

    private fun togliPercorsoRelativo(s: String): String {
        val pezzi = s.split("/").filter { it.isNotEmpty() }
        if (pezzi.all { isdigit(it) }) return s                           // date e frazioni: 04/10/2026, 24/7
        if (pezzi.size == 2 && pezzi.all { lung(it) <= 3 && isalpha(it) }) return s   // e/o, km/h, I/O
        if (pezzi.size >= 3 || cerca(ESTENSIONE, s) || s.endsWith("/")) return " $TOLTO "
        if (pezzi.size == 2 && ("-" in s || "_" in s || "." in s)) return " $TOLTO "
        return s                                                          // «computer/server», «sì/no»: restano
    }

    private fun pulisciRiga(riga: String): String {
        val x = " $TOLTO "
        var r = riga
        r = sub(MD_IMG, r, x)
        r = subGruppo1(MD_LINK, r)
        r = sub(URL, r, x)
        r = sub(EMAIL, r, x)
        r = sub(UUID, r, x)
        r = sub(IP, r, x)
        r = sub(PERCORSO_WIN, r, x)
        r = sub(PERCORSO_ASSOLUTO, r, x)
        r = sub(PERCORSO_RELATIVO, r) { togliPercorsoRelativo(it.group()) }
        r = sub(DOMINIO, r, x)
        r = sub(HOST_LUNGO, r) { if (SOLO_CIFRE_PUNTI.matcher(it.group()).matches()) it.group() else x }
        r = sub(HEX, r, x)
        r = sub(ID_LUNGO, r, x)
        r = sub(OPZIONE, r, x)
        r = sub(EMOJI, r, " ")
        // markdown in linea
        r = sub(GRASSETTO, r, "")
        r = subGruppo1(CORSIVO_STELLA, r)
        r = subGruppo1(CORSIVO_TRATTINO, r)
        r = r.replace("`", " ")
        // via le parole rimaste appese a quello che si è tolto
        r = sub(SPAZI_RE, r, " ")
        r = sub(TOLTI_DI_FILA, r, TOLTO)
        r = sub(APPESA_PRIMA, r, TOLTO)
        r = sub(TOLTI_CON_E, r, TOLTO)
        r = sub(PARENTESI_TOLTA, r, " ")
        r = r.replace(TOLTO, " ")
        // simboli che il sintetizzatore pronuncerebbe male
        r = sub(BARRA, r, " ")
        r = sub(TRATTINO_LUNGO, r, ", ")
        r = sub(FRECCE, r, " ")
        r = sub(SIMBOLI, r, " ")
        r = sub(PARENTESI_VUOTE, r, " ")
        r = strip(sub(SPAZI_RE, r, " "))
        r = subGruppo1(SPAZIO_PRIMA_PUNTO, r)
        r = subGruppo1(PUNTEGGIATURA_DOPPIA, r)
        r = sub(INIZIO_SPORCO, r, "")
        r = subGruppo1(PRIMA_DEL_PUNTO, r)
        r = sub(FINE_SPORCA, r, "")
        return strip(r)
    }

    private fun togliPresentazione(righe: MutableList<String>) {
        if (righe.isNotEmpty() && righe.last().endsWith(":")) righe.removeAt(righe.size - 1)
    }

    private class Filtrato(val frasi: List<String>, val tolto: Boolean)

    private fun filtra(testo: String): Filtrato {
        var tolto = false
        var t = testo.replace("\r\n", "\n").replace("\r", "\n")
        t = sub(SCENA, t, " ")                                            // indicazioni di scena
        // i segnaposto della voce del browser (ponte.js) valgono come cose tolte
        if (cerca(SEGNAPOSTO, t)) {
            tolto = true
            t = sub(SEGNAPOSTO, t, " ")
        }
        if (cerca(FENCE, t)) {
            tolto = true
            t = sub(FENCE, t, "\n\u0001\n")
        }
        val buone = mutableListOf<String>()
        for (riga in t.split("\n")) {
            var r = rstrip(riga)
            if (strip(r).isEmpty()) continue
            if (strip(r) == "\u0001" || inizia(RIGA_TABELLA, r) || inizia(RIGA_JSON, r) || inizia(RIGA_LOG, r)) {
                tolto = true
                togliPresentazione(buone)
                continue
            }
            if (inizia(TITOLO, r)) continue                               // titoli: si saltano
            r = sub(CITAZIONE, r, "")                                     // citazioni
            val eElenco = inizia(PUNTO_ELENCO, r)
            r = sub(PUNTO_ELENCO, r, "")
            if ((inizia(RIENTRATO, riga) && !eElenco) || eComando(r)) {
                tolto = true                                              // codice rientrato o comando
                togliPresentazione(buone)
                continue
            }
            // una riga che resta solo codice inline («`ls -la`») è un comando
            val senzaInline = strip(sub(INLINE_CODE, r, ""), " \t:.,;-")
            if (senzaInline.isEmpty()) {
                tolto = true
                togliPresentazione(buone)
                continue
            }
            r = sub(INLINE_CODE, r, " $TOLTO ")
            r = pulisciRiga(r)
            if (!cerca(PAROLA, r)) continue
            if (!cerca(FINE_CON_SEGNO, r)) r += "."
            buone.add(r)
        }
        val frasi = mutableListOf<String>()
        for (b in buone) {
            var blocco = b
            if (blocco.endsWith(":") || blocco.endsWith(";")) blocco = blocco.substring(0, blocco.length - 1) + "."
            for (pezzo in FINE_FRASE.split(blocco, -1)) {
                val f = strip(pezzo)
                if (f.isEmpty() || !cerca(PAROLA, f)) continue
                if (eNarrazione(f)) continue
                frasi.add(f)
            }
        }
        return Filtrato(frasi, tolto)
    }

    private fun eNarrazione(frase: String): Boolean {
        if (!inizia(NARRAZIONE, frase)) return false
        // «Controllo finito: 3 errori» è un esito, non una narrazione.
        if (cerca(NUMERO, frase)) return false
        if (cerca(ESITO_NARR, frase)) return false
        return true
    }

    /** Taglia una frase troppo lunga a una virgola o a uno spazio (in caratteri veri, come Python). */
    private fun accorcia(frase: String, maxCar: Int): String {
        if (lung(frase) <= maxCar) return frase
        val pezzo = frase.substring(0, frase.offsetByCodePoints(0, maxCar))
        fun cp(i: Int) = if (i < 0) -1 else pezzo.codePointCount(0, i)
        var taglio = maxOf(cp(pezzo.lastIndexOf(", ")), cp(pezzo.lastIndexOf("; ")), cp(pezzo.lastIndexOf(": ")))
        if (taglio < maxCar * 0.5) taglio = cp(pezzo.lastIndexOf(" "))
        if (taglio <= 0) taglio = maxCar
        return rstrip(pezzo.substring(0, pezzo.offsetByCodePoints(0, taglio)), " ,;:") + "."
    }

    private fun valeLaCoda(testo: String): Boolean {
        if (cerca(FENCE, testo) || cerca(SEGNAPOSTO, testo)) return true
        val righe = testo.split("\n").filter { strip(it).isNotEmpty() }
        val pesanti = righe.count {
            inizia(RIGA_TABELLA, it) || inizia(RIGA_JSON, it) || eComando(sub(PUNTO_ELENCO, it, ""))
        }
        return pesanti >= 2
    }

    /** Il testo da dire ad alta voce per una risposta di Jarvis. */
    @JvmStatic
    @JvmOverloads
    fun perLaVoce(testo: String?, maxCar: Int = MAX_CARATTERI, maxFrasi: Int = MAX_FRASI): String {
        if (testo == null || strip(testo).isEmpty()) return ""
        var t: String = testo
        // Già passato di qui (o da chiamata.js): la coda si toglie e si rimette.
        var giaCoda = false
        for (coda in listOf(CODA_RESTO, "Il resto è in chat.")) {
            val s = rstrip(t)
            if (s.endsWith(coda)) {
                t = s.substring(0, s.length - coda.length)
                giaCoda = true
            }
        }
        val filtrato = filtra(t)
        val frasi = filtrato.frasi
        val tolto = filtrato.tolto
        if (frasi.isEmpty()) return NIENTE_DA_DIRE
        val tutto = frasi.joinToString(" ")
        // Già breve: si dice così com'è dopo il filtro.
        if (frasi.size <= maxFrasi && lung(tutto) <= maxCar + 20) {
            return if (giaCoda || (tolto && valeLaCoda(t))) "$tutto $CODA_RESTO" else tutto
        }

        var scelte = mutableListOf<Int>()
        val esiti = frasi.indices.filter { cerca(ESITO, frasi[it]) || cerca(NUMERO, frasi[it]) }
        if (esiti.isNotEmpty()) {
            scelte.add(esiti[0])
        } else {
            scelte = (0 until minOf(maxFrasi, frasi.size)).toMutableList()   // nessun esito: in ordine
        }
        if (esiti.isNotEmpty() && maxFrasi >= 2) {
            val dopo = frasi.indices.filter { it !in scelte && cerca(PROSSIMO, frasi[it]) }
            val altriEsiti = esiti.filter { it !in scelte }
            val resto = frasi.indices.filter { it !in scelte }
            for (candidati in listOf(dopo, altriEsiti, resto)) {
                if (candidati.isNotEmpty()) {
                    scelte.add(candidati[0])
                    break
                }
            }
        }
        var detto = ""
        var usate = 0
        for ((k, i) in scelte.withIndex()) {
            val f = frasi[i]
            if (k == 0) {
                detto = accorcia(f, maxCar)
                usate = 1
                continue
            }
            if (lung(detto) + 1 + lung(f) <= maxCar) {
                detto += " $f"
                usate += 1
            }
        }
        val intero = scelte.take(usate).joinToString(" ") { frasi[it] }
        val restaAltro = usate < frasi.size || giaCoda || (tolto && valeLaCoda(t)) || detto != intero
        if (restaAltro) detto += " $CODA_RESTO"
        return strip(detto)
    }
}

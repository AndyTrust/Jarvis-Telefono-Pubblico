package com.jarvis.telefono.configura

import com.jarvis.telefono.cassaforte.ServerPosta
import com.jarvis.telefono.cassaforte.Sicurezza

/** I controlli dei campi delle schermate di configurazione. null = va bene, altrimenti la frase da mostrare. Pure. */
object Campi {

    fun indirizzo(s: String): String? {
        val t = s.trim()
        return when {
            t.isEmpty() -> "Scrivi l'indirizzo della casella."
            t.any { it.isWhitespace() } -> "L'indirizzo non può avere spazi."
            !Regex("^[^@]+@[^@]+\\.[A-Za-z]{2,}$").matches(t) -> "Non sembra un indirizzo di posta: nome@dominio.it"
            else -> null
        }
    }

    fun password(s: String, perApp: Boolean): String? = when {
        s.isEmpty() -> if (perApp) "Incolla la password per app (16 lettere)." else "Scrivi la password della casella."
        else -> null
    }

    /** Le password per app di Google si mostrano a gruppi di 4 con gli spazi: gli spazi non contano. */
    fun pulisciPasswordPerApp(s: String): String = if (Regex("^([a-z]{4}\\s){3}[a-z]{4}$").matches(s.trim())) s.replace(" ", "").trim() else s

    fun host(s: String): String? {
        val t = s.trim()
        return when {
            t.isEmpty() -> "Manca il server (per esempio imap.gmail.com)."
            !Regex("[A-Za-z0-9.-]{3,253}").matches(t) || !t.contains('.') -> "Il server non sembra giusto: imap.nomegestore.it"
            else -> null
        }
    }

    fun porta(s: String): String? {
        val n = s.trim().toIntOrNull() ?: return "La porta è un numero (di solito 993 per IMAP)."
        return if (n in 1..65535) null else "La porta va da 1 a 65535."
    }

    fun server(host: String, porta: String, sicurezza: Sicurezza): Result<ServerPosta> {
        host(host)?.let { return Result.failure(IllegalArgumentException(it)) }
        porta(porta)?.let { return Result.failure(IllegalArgumentException(it)) }
        return Result.success(ServerPosta(host.trim(), porta.trim().toInt(), sicurezza))
    }

    /** La frase dell'esportazione cifrata: almeno 8 caratteri e scritta due volte uguale. */
    fun frase(a: CharArray, b: CharArray): String? = when {
        a.size < 8 -> "La frase deve avere almeno 8 caratteri (meglio una frase intera)."
        !a.contentEquals(b) -> "Le due frasi non sono uguali."
        else -> null
    }

    /** Un id di cassaforte per una casella: «mail-» + la parte prima della @, solo minuscole, cifre e trattini. */
    fun idCasella(indirizzo: String, esistenti: Set<String>): String {
        val base = "mail-" + indirizzo.substringBefore('@').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).ifEmpty { "casella" }
        if (base !in esistenti) return base
        var i = 2
        while ("$base-$i" in esistenti) i++
        return "$base-$i"
    }

    /** «gmail…@example.com»: l'indirizzo accorciato per una riga di elenco. */
    fun corto(indirizzo: String, max: Int = 22): String {
        if (indirizzo.length <= max) return indirizzo
        val nome = indirizzo.substringBefore('@')
        val dom = indirizzo.substringAfter('@', "")
        val spazio = (max - dom.length - 2).coerceAtLeast(3)
        return nome.take(spazio) + "…@" + dom
    }
}

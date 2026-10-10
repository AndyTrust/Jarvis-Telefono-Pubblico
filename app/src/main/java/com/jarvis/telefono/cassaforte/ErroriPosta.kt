package com.jarvis.telefono.cassaforte

/** Le risposte dei server di posta tradotte in parole semplici (funzioni pure, provate in CassaforteTest). */
object ErroriPosta {

    /** Dalla riga finale di un LOGIN rifiutato (es. «NO [AUTHENTICATIONFAILED] Invalid credentials»). */
    fun causaDaRisposta(riga: String, tipo: TipoMail): CausaProva {
        val r = riga.lowercase()
        return when {
            "application-specific password" in r || "app password" in r || "apppassword" in r -> CausaProva.PASSWORD_PER_APP
            "not enabled for imap" in r || "imap access is disabled" in r || "imap is disabled" in r ||
                "imap non abilitato" in r || "imap disabilitato" in r -> CausaProva.IMAP_SPENTO
            "web browser" in r || "webloginrequired" in r || "weblogin" in r -> CausaProva.ACCESSO_WEB
            "basicauthblocked" in r || "basic auth" in r || "oauth" in r -> CausaProva.OAUTH_RICHIESTO
            tipo == TipoMail.OUTLOOK && ("authenticate failed" in r || "login failed" in r) -> CausaProva.OAUTH_RICHIESTO
            "authenticationfailed" in r || "invalid credentials" in r || "authentication failed" in r ||
                "login failed" in r || "incorrect" in r || "credenziali" in r || "password errata" in r ||
                "authentication unsuccessful" in r || "[auth]" in r -> {
                if (tipo == TipoMail.GMAIL || tipo == TipoMail.ICLOUD) CausaProva.PASSWORD_PER_APP else CausaProva.PASSWORD_SBAGLIATA
            }
            else -> CausaProva.PASSWORD_SBAGLIATA
        }
    }

    fun testo(c: CausaProva, tipo: TipoMail, srv: ServerPosta? = null): String = when (c) {
        CausaProva.OK -> "Accesso riuscito."
        CausaProva.DATI_MANCANTI -> "Mancano indirizzo, password o server IMAP."
        CausaProva.SERVER_SCONOSCIUTO -> "Il server ${srv?.host ?: ""} non esiste: controlla di averlo scritto giusto.".replace("  ", " ")
        CausaProva.RETE -> "Non riesco a raggiungere ${srv?.let { "${it.host}:${it.porta}" } ?: "il server"}: niente rete, porta sbagliata o server spento."
        CausaProva.TLS -> "La connessione sicura non parte: su questa porta il server non parla SSL, o il certificato non è del server giusto. Di solito IMAP con SSL è la porta 993."
        CausaProva.LOGIN_DISATTIVATO -> "Il server non accetta l'accesso con password su questa connessione."
        CausaProva.PASSWORD_SBAGLIATA -> "Indirizzo o password sbagliati."
        CausaProva.PASSWORD_PER_APP -> when (tipo) {
            TipoMail.GMAIL -> "Google ha rifiutato la password: serve la password per app, non quella normale. " + TestiGuida.GMAIL_PASSWORD_PER_APP_BREVE
            TipoMail.ICLOUD -> "Apple ha rifiutato la password: serve una password per app creata su account.apple.com."
            else -> "Il gestore vuole una password per app (account con verifica in due passaggi)."
        }
        CausaProva.IMAP_SPENTO -> if (tipo == TipoMail.GMAIL) "IMAP è spento su questa casella Gmail: accendilo da Gmail, Impostazioni, Inoltro e POP/IMAP."
            else "L'accesso IMAP è spento: va acceso dalle impostazioni della webmail del gestore."
        CausaProva.ACCESSO_WEB -> "Il gestore ha bloccato l'accesso per sicurezza: entra una volta dalla webmail sul browser, conferma che sei tu e riprova."
        CausaProva.OAUTH_RICHIESTO -> "Questo gestore non accetta più la password per IMAP (vuole l'accesso OAuth): usa la sua app o Samsung Email sul telefono."
        CausaProva.SOLO_APP -> "Samsung Email è l'app di posta del telefono: non c'è nessun server da provare. JBoss la apre con la bozza pronta."
        CausaProva.PROTOCOLLO -> "Il server ha risposto in un modo che non capisco: forse non è un server IMAP o la porta è quella dello SMTP."
        CausaProva.ALTRO -> "Prova non riuscita per un errore imprevisto."
    }

    /** Toglie la password da un testo, nel caso un server la ripeta (non dovrebbe mai). */
    fun pulisci(testo: String, password: String): String =
        if (password.length >= 3 && testo.contains(password)) testo.replace(password, "••••") else testo

    /** Da una riga «* LIST (\HasNoChildren \Sent) "/" "Posta inviata"»: nome e flag speciale. */
    fun cartellaDaList(riga: String): Pair<String, String>? {
        val m = Regex("""^\* LIST \(([^)]*)\) (?:"[^"]*"|NIL) (.+)$""", RegexOption.IGNORE_CASE).find(riga.trim()) ?: return null
        val flag = m.groupValues[1].split(' ').firstOrNull { it.lowercase() in SPECIALI }.orEmpty()
        var nome = m.groupValues[2].trim()
        if (nome.startsWith("\"") && nome.endsWith("\"") && nome.length >= 2) nome = nome.substring(1, nome.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        return nome to flag
    }

    private val SPECIALI = setOf("\\sent", "\\trash", "\\drafts", "\\junk", "\\archive", "\\all")
}

package com.jarvis.telefono.cassaforte

/**
 * I server noti delle caselle più comuni (2026-10-07). [verificato] = parametri controllati su una fonte
 * (sito del gestore, guida ufficiale o le caselle già in uso da Boss); gli altri si confermano con la prova.
 * La prova di accesso ([ProvaImap]) dice comunque se il server risponde.
 */
data class PresetPosta(
    val chiave: String,
    val nome: String,
    val tipo: TipoMail,
    val imap: ServerPosta?,
    val smtp: ServerPosta?,
    /** Serve una password per app (account con verifica in due passaggi). */
    val passwordPerApp: Boolean = false,
    /** La spiegazione breve per chi configura. */
    val nota: String = "",
    val verificato: Boolean = false,
    val domini: List<String> = emptyList(),
) {
    /** Il Postino della VPS spedisce solo con SSL diretto: avviso se lo SMTP è STARTTLS. */
    val vpsInviaAnche: Boolean get() = smtp?.sicurezza == Sicurezza.SSL

    companion object {
        private fun s(h: String, p: Int, sic: Sicurezza = Sicurezza.SSL) = ServerPosta(h, p, sic)

        val TUTTI: List<PresetPosta> = listOf(
            PresetPosta(
                "gmail", "Gmail", TipoMail.GMAIL, s("imap.gmail.com", 993), s("smtp.gmail.com", 465),
                passwordPerApp = true, verificato = true, domini = listOf("gmail.com", "googlemail.com"),
                nota = "Serve la verifica in due passaggi e una password per app (16 lettere). IMAP è acceso di base.",
            ),
            PresetPosta(
                "outlook", "Outlook, Hotmail, Live", TipoMail.OUTLOOK, s("outlook.office365.com", 993), s("smtp-mail.outlook.com", 587, Sicurezza.STARTTLS),
                verificato = true, domini = listOf("outlook.com", "outlook.it", "hotmail.com", "hotmail.it", "live.com", "live.it", "msn.com"),
                nota = "Dal 16 settembre 2024 Microsoft non accetta più la password (nemmeno quella per app) per IMAP: " +
                    "serve l'accesso OAuth, che JBoss non ha. Sul telefono usa Samsung Email o Outlook.",
            ),
            PresetPosta(
                "office365", "Microsoft 365 aziendale", TipoMail.OUTLOOK, s("outlook.office365.com", 993), s("smtp.office365.com", 587, Sicurezza.STARTTLS),
                verificato = true,
                nota = "Exchange Online ha spento l'accesso con password: funziona solo se l'amministratore l'ha lasciato acceso.",
            ),
            PresetPosta(
                "icloud", "iCloud", TipoMail.ICLOUD, s("imap.mail.me.com", 993), s("smtp.mail.me.com", 587, Sicurezza.STARTTLS),
                passwordPerApp = true, verificato = true, domini = listOf("icloud.com", "me.com", "mac.com"),
                nota = "Serve una password per app da account.apple.com. Nome utente: l'indirizzo o la parte prima della @. " +
                    "La VPS può leggere ma non spedire (SMTP solo 587).",
            ),
            PresetPosta(
                "hostinger", "Hostinger (dominio proprio)", TipoMail.HOSTINGER, s("imap.hostinger.com", 993), s("smtp.hostinger.com", 465),
                verificato = true, nota = "Per le caselle con dominio proprio ospitate da Hostinger.",
            ),
            PresetPosta("libero", "Libero", TipoMail.LIBERO, s("imapmail.libero.it", 993), s("smtp.libero.it", 465),
                domini = listOf("libero.it", "inwind.it", "iol.it", "blu.it"), nota = "IMAP va attivato dalle impostazioni della webmail Libero."),
            PresetPosta("virgilio", "Virgilio", TipoMail.LIBERO, s("in.virgilio.it", 993), s("out.virgilio.it", 465), domini = listOf("virgilio.it")),
            PresetPosta("aruba", "Aruba (dominio proprio)", TipoMail.ARUBA, s("imaps.aruba.it", 993), s("smtps.aruba.it", 465)),
            PresetPosta("pec-aruba", "PEC Aruba", TipoMail.PEC, s("imaps.pec.aruba.it", 993), s("smtps.pec.aruba.it", 465),
                verificato = true, domini = listOf("pec.it")),
            PresetPosta("pec-legalmail", "PEC Legalmail (InfoCert)", TipoMail.PEC, s("mbox.cert.legalmail.it", 993), s("sendm.cert.legalmail.it", 465),
                verificato = true, domini = listOf("legalmail.it"),
                nota = "Il nome utente è lo USERID dato da InfoCert, non l'indirizzo: il Postino della VPS oggi entra con l'indirizzo."),
            PresetPosta("pec-sicurezzapostale", "PEC Sicurezza Postale", TipoMail.PEC, s("imaps.sicurezzapostale.it", 993), s("smtps.sicurezzapostale.it", 465),
                verificato = true, domini = listOf("sicurezzapostale.it")),
            PresetPosta("pec-register", "PEC Register.it", TipoMail.PEC, null, null,
                nota = "I server si leggono nel pannello Register.it della casella e si scrivono a mano."),
            PresetPosta("samsung", "Samsung Email (app del telefono)", TipoMail.SAMSUNG, null, null,
                verificato = true,
                nota = "È l'app di posta del telefono: JBoss la apre con la bozza pronta, non serve nessuna password. " +
                    "Per far leggere la casella al Postino della VPS aggiungila anche con il suo gestore (Gmail, Hostinger…)."),
            PresetPosta("generico", "Altro gestore", TipoMail.GENERICO, null, null, nota = "Server e porte si copiano dalla guida del gestore."),
        )

        fun di(chiave: String): PresetPosta? = TUTTI.firstOrNull { it.chiave == chiave }

        /** Il preset giusto dall'indirizzo (dominio), o null. */
        fun perIndirizzo(indirizzo: String): PresetPosta? {
            val dom = indirizzo.substringAfterLast('@', "").lowercase().trim()
            if (dom.isEmpty()) return null
            return TUTTI.firstOrNull { p -> p.domini.any { dom == it } }
        }
    }
}

package com.jarvis.telefono.cassaforte

/** I testi guida delle schermate di configurazione (le schermate si disegnano a parte; qui solo le parole). */
object TestiGuida {

    const val GMAIL_PASSWORD_PER_APP_BREVE =
        "Vai su myaccount.google.com/apppasswords, scrivi «JBoss» e tocca Crea: copia le 16 lettere qui."

    val GMAIL_PASSWORD_PER_APP = """
        Gmail non accetta la password normale nelle app di posta. Serve una «password per app»:
        1. La verifica in due passaggi deve essere accesa (myaccount.google.com, Sicurezza).
        2. Apri myaccount.google.com/apppasswords (se non c'è, l'account è di lavoro e l'amministratore l'ha spenta).
        3. Scrivi un nome, per esempio «JBoss», e tocca Crea.
        4. Copia le 16 lettere nel campo Password (gli spazi non contano).
        La password per app si toglie dalla stessa pagina: JBoss perde l'accesso subito, la tua password vera non cambia.
    """.trimIndent()

    val CERVELLO_CLAUDE_CODE = """
        Claude Code sulla tua VPS, con il TUO abbonamento Claude (Pro o Max):
        1. Sul tuo computer, con Claude Code installato, scrivi nel terminale il comando «claude setup-token».
        2. Accedi con il tuo account Claude: il comando ti dà un codice lungo, il token.
        3. Incollalo qui. JBoss lo tiene cifrato nel telefono e, quando confermi, lo manda alla tua VPS
           su un collegamento cifrato, dove resta fra i segreti di Jarvis.
        Regole di Anthropic: l'abbonamento è personale. Non si presta ad altre persone e non si usa dentro app di terzi.
        Ognuno usa il proprio abbonamento sulla propria VPS, oppure una chiave API a consumo.
    """.trimIndent()

    val CERVELLO_API = """
        Chiave API (Anthropic da console.anthropic.com, oppure OpenAI da platform.openai.com):
        si paga a consumo, separata dall'abbonamento. La chiave resta solo nella cassaforte del telefono e serve al
        cervello in cloud dell'app. «Prova la chiave» chiede l'elenco dei modelli: non consuma crediti.
    """.trimIndent()

    val CERVELLO_CODEX = """
        Codex (OpenAI) sulla VPS: la strada semplice è accedere una volta sulla VPS con «codex login» (abbonamento ChatGPT).
        In alternativa una chiave API OpenAI: JBoss la manda alla VPS come OPENAI_API_KEY, dopo la tua conferma.
    """.trimIndent()

    val GOOGLE = """
        Per cercare e usare le app Google (Ricerca, Gmail, Maps, Calendar, Drive) JBoss usa l'account Google che hai già
        sul telefono: apre le app e legge lo schermo con l'accessibilità. Non serve nessun accesso in più.
        Per far leggere Gmail al Postino della VPS si aggiunge la casella con la password per app.
    """.trimIndent()

    val SICUREZZA = """
        Le password e i token stanno cifrati nel telefono (chiave nel chip sicuro, non esportabile).
        Per vederli sullo schermo serve l'impronta o il PIN. Non finiscono nei backup né nelle schermate recenti.
        Verso la VPS viaggiano solo sul collegamento cifrato e solo dopo il tuo sì.
    """.trimIndent()
}

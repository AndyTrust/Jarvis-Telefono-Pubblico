package com.jarvis.telefono.nucleo

import com.jarvis.telefono.mani.CercaContatti

/**
 * Il cervello a regole, in italiano, senza rete e senza modelli (passo A, 2026-10-07).
 *
 * Copre i comandi di tutti i giorni: apri un'app, cerca su Google o su YouTube, chiedi a Gemini,
 * WhatsApp, mail, SMS, chiama, naviga, indietro, home, scorri, che ore sono. Tutto il resto:
 * «Non ho capito» (il cervello in cloud arriverà nel passo B e prenderà le frasi non capite).
 *
 * Regola ferrea: numeri e indirizzi NON si inventano. Vengono dalla frase (cifre dette, un indirizzo
 * con la chiocciola) o dalla rubrica; se la rubrica non risponde in modo univoco il piano non fa
 * niente e lo dice. Ogni invio passa da `invia_bozza`: il telefono mostra la bozza e aspetta
 * «Invia» o «Annulla» di Boss (CancelloInvio).
 *
 * Kotlin puro: si prova sulla JVM (CervelloRegoleTest). Niente classi di caratteri Unicode
 * nelle regex: su Android fanno cadere l'app.
 */
class CervelloRegole : Cervello {
    override val nome = "regole"

    override suspend fun capisci(frase: String, contesto: Contesto): Piano = interpreta(frase, contesto)

    fun interpreta(frase: String, ctx: Contesto): Piano {
        val f = pulisci(frase)
        if (f.isBlank()) return Piano.nonCapito()
        // 0.3.3: la «chiave» è la frase per riconoscere i comandi (minuscole, senza accenti né
        // punteggiatura, senza «adesso»/«grazie» in fondo). Il testo dei messaggi usa sempre [f].
        val k = chiave(f)
        oraEsatta(k, ctx)?.let { return it }
        data(k, ctx)?.let { return it }
        soloConferma(k)?.let { return it }
        tasti(k)?.let { return it }
        impostazioni(k)?.let { return it }
        messaggio(f, ctx)?.let { return it }
        chiama(f, ctx)?.let { return it }
        gemini(f)?.let { return it }
        cercaInApp(f)?.let { return it }
        cercaGoogle(f)?.let { return it }
        naviga(f)?.let { return it }
        apri(f)?.let { return it }
        somiglia(k, ctx)?.let { return it }
        return Piano.nonCapito(f)
    }

    // ================================================================ pulizia

    /**
     * Toglie «Jarvis», i saluti e le formule di cortesia, ricuce le parole che Whisper spezza
     * («whats app», «you tube»), toglie la punteggiatura finale. Le maiuscole restano: il testo di
     * un messaggio va scritto come Boss l'ha detto.
     */
    internal fun pulisci(frase: String): String {
        var s = " " + frase.replace('’', '\'').replace('‘', '\'').replace('«', ' ').replace('»', ' ')
            .replace('"', ' ').replace('\n', ' ') + " "
        s = s.replace(Regex("\\s+"), " ")
        for ((da, a) in RICUCI) s = s.replace(da, a)
        // Davanti: «Jarvis,», «hey Jarvis», «ok», «senti», «puoi», «potresti», «mi»…
        var prima: String
        do {
            prima = s
            s = s.replace(INIZIO, " ")
        } while (s != prima)
        s = s.replace(CORTESIA, " ")
        s = s.trim().trimStart('.', '…', ',', ' ').trimEnd('.', '!', '?', ',', ';', '…', ' ').trim()
        return s.replace(Regex("\\s+"), " ")
    }

    // ================================================================ regole semplici

    /**
     * «che ore sono», «che ora è», «che ora sono adesso» (Whisper scrive «ora» per «ore»), «ore sono»
     * (Whisper perde il «che»), «mi dici l'ora», «sai che ore sono», «ora esatta». [k] è la chiave.
     */
    private fun oraEsatta(k: String, ctx: Contesto): Piano? {
        if (!ORA.any { it.matches(k) }) return null
        return Piano.solo(direOra(ctx), "ora")
    }

    private fun direOra(ctx: Contesto): String {
        val m = if (ctx.minuti == 0) "" else " e ${ctx.minuti}"
        return when (ctx.ora) {
            1 -> "È l'una$m."
            0 -> "È mezzanotte$m."
            12 -> "È mezzogiorno$m."
            else -> "Sono le ${ctx.ora}$m."
        }
    }

    /** «che giorno è oggi», «che data è», «quanti ne abbiamo», «oggi che giorno è». */
    private fun data(k: String, ctx: Contesto): Piano? {
        if (!DATA.any { it.matches(k) }) return null
        return Piano.solo(direData(ctx), "data")
    }

    private fun direData(ctx: Contesto): String {
        if (ctx.giorno <= 0 || ctx.mese !in 1..12) return "Non riesco a leggere la data del telefono."
        val nome = GIORNI.getOrNull(ctx.giornoSettimana - 1)?.let { "$it " }.orEmpty()
        val primo = if (ctx.giorno == 1) "primo" else ctx.giorno.toString()
        return "Oggi è $nome$primo ${MESI[ctx.mese - 1]}${if (ctx.anno > 0) " ${ctx.anno}" else ""}."
    }

    /** «invia» o «annulla» da soli senza una bozza in attesa (con la bozza li prende il Cancello prima). */
    private fun soloConferma(k: String): Piano? {
        if (!Regex("^(?:invia|inviala|invialo|inviare|conferma|confermo|annulla|annullala|annullalo|lascia stare|lascia perdere)$").matches(k)) return null
        return Piano.solo("Non c'è nessun messaggio che aspetta la conferma.", "niente da confermare")
    }

    private fun tasti(t: String): Piano? {
        return when {
            Regex("^(?:torna |vai |tornare |torniamo |)(?:in ?dietro|indietro)$").matches(t) -> Piano(listOf(Azione("key", mapOf("name" to "BACK"))), "", cosa = "indietro")
            Regex("^(?:torna |vai |tornare |portami |)(?:alla |sulla |nella |a |in )?(?:home|schermata (?:principale|home|iniziale))$").matches(t) ->
                Piano(listOf(Azione("key", mapOf("name" to "HOME"))), "", cosa = "home")
            Regex("^(?:mostra |mostrami |apri |fammi vedere )?(?:le )?(?:app|applicazioni) (?:recenti|aperte)$").matches(t) -> Piano(listOf(Azione("key", mapOf("name" to "RECENTS"))), "", cosa = "app recenti")
            Regex("^(?:scorri|scorrere|vai|scendi)(?: in| verso il| verso)? ?(?:giu|sotto|basso)$|^scendi$").matches(t) -> Piano(listOf(Azione("scorri", mapOf("direzione" to "giu"))), "", cosa = "scorro giù")
            Regex("^(?:scorri|scorrere|vai|sali)(?: in| verso l'| verso l| verso)? ?(?:su|sopra|alto)$|^sali$").matches(t) -> Piano(listOf(Azione("scorri", mapOf("direzione" to "su"))), "", cosa = "scorro su")
            Regex("^(?:apri |mostra |mostrami |fammi vedere |leggi |leggimi |abbassa |tira giu )?(?:le )?(?:mie )?(?:ultime )?notifiche$").matches(t) ->
                Piano(listOf(Azione("key", mapOf("name" to "NOTIFICHE"))), "Ti ho aperto le notifiche.", cosa = "notifiche")
            else -> null
        }
    }

    /**
     * Torcia, Wi-Fi, Bluetooth…: le mani non li comandano ancora, ma possono aprire le impostazioni
     * rapide (un tocco e Boss fa da sé). Volume: lo si dice onesto. Sveglia e timer: si apre l'Orologio.
     */
    private fun impostazioni(k: String): Piano? {
        Regex("^(?:accendi|accendere|spegni|spegnere|attiva|attivare|disattiva|disattivare|togli|metti|apri)\\s+(?:la |il |lo |l' ?|i )?" +
            "(torcia|luce|flash|wi ?fi|wireless|bluetooth|dati(?: mobili)?|modalita aereo|aereo|non disturbare|rotazione(?: automatica)?|hotspot|gps|posizione|nfc)$").find(k)?.let {
            val cosa = it.groupValues[1].let { c -> if (c == "luce" || c == "flash") "torcia" else c.replace("wi fi", "wifi") }
            val bello = when (cosa) { "wifi", "wireless" -> "Wi-Fi"; "bluetooth" -> "Bluetooth"; "torcia" -> "Torcia"; else -> maiuscola(cosa) }
            return Piano(
                listOf(Azione("key", mapOf("name" to "IMPOSTAZIONI_RAPIDE"))),
                "Ti ho aperto le impostazioni rapide: tocca $bello.", cosa = "impostazioni rapide",
            )
        }
        if (Regex("^(?:alza|alzare|abbassa|abbassare|aumenta|diminuisci|metti|togli|regola)\\s+(?:il |l' ?)?(?:volume|audio|suoneria)\\b.*$|^volume\\b.*$|^(?:metti )?(?:in |il )?(?:silenzioso|muto|vibrazione)$").matches(k)) {
            return Piano.solo("Il volume non lo so ancora regolare: usa i tasti laterali del telefono.", "volume")
        }
        if (Regex("\\b(?:sveglia|sveglie|timer|cronometro|conto alla rovescia)\\b").containsMatchIn(k) &&
            Regex("^(?:imposta|impostami|metti|mettimi|punta|puntami|programma|fissa|avvia|fai partire|crea|attiva|aggiungi|una |un |il |la )|^svegliami\\b").containsMatchIn(k) ||
            Regex("^svegliami\\b").containsMatchIn(k)
        ) {
            val timer = Regex("\\b(?:timer|cronometro|conto alla rovescia)\\b").containsMatchIn(k)
            // 0.5.0 (Boss 08/10): con l'ora o la durata nella frase la sveglia e il timer si mettono davvero.
            if (timer) com.jarvis.telefono.mani.Orologio.timerSecondi(k)?.let { sec ->
                return Piano(listOf(Azione("componi", mapOf("tipo" to "timer", "secondi" to sec))),
                    "Timer di ${com.jarvis.telefono.mani.Orologio.durataInParole(sec)} partito.", cosa = "timer")
            } else com.jarvis.telefono.mani.Orologio.sveglia(k)?.let { sv ->
                return Piano(listOf(Azione("componi", mapOf("tipo" to "sveglia", "ora" to sv.ora, "minuti" to sv.minuti))),
                    "Sveglia messa alle ${com.jarvis.telefono.mani.Orologio.inParole(sv)}.", cosa = "sveglia")
            }
            // Senza ora né durata: si chiede, non si inventa.
            if (!Regex("\\b(?:elimina|togli|cancella|disattiva|spegni)\\b").containsMatchIn(k)) {
                return Piano.solo(if (timer) "Di quanto metto il timer? Dimmi per esempio: timer di cinque minuti."
                    else "A che ora metto la sveglia? Dimmi per esempio: sveglia alle sei e quarantacinque.", if (timer) "timer senza durata" else "sveglia senza ora")
            }
            return Piano(
                listOf(Azione("apri_app", mapOf("nome" to "Orologio"))),
                if (timer) "Ho aperto l'Orologio: il timer impostalo tu, io non lo so ancora fare."
                else "Ho aperto l'Orologio: la sveglia impostala tu, io non la so ancora mettere.",
                cosa = "apro l'Orologio",
            )
        }
        return null
    }

    // ================================================================ apri

    private fun apri(f: String): Piano? {
        if (Regex("^(?:apri|aprimi|apra|apre|aprì|avvia|lancia)$", IC).matches(f)) return Piano.solo("Cosa apro? Dimmi il nome dell'app.", "apri senza nome")
        val m = Regex(
            "^(?:apri|aprimi|aprire|apra|apre|aprì|appri|riapri|avvia|avviami|lancia|lanciami|fammi aprire|vai su|vai in|vai nell'app|entra in|apriamo|apriti)\\s+" +
                "(?:l'app(?:licazione)?\\s*|la app\\s+|app\\s+|l'applicazione\\s*|applicazione\\s+)?(.+)$", IC,
        ).find(f) ?: return null
        var nome = m.groupValues[1].trim().removePrefix("di ").trim()
        // 0.3.3: «apri la fotocamera» → «fotocamera»; «apri WhatsApp adesso» → «WhatsApp».
        nome = nome.replace(Regex("^(?:(?:il|lo|la|i|gli|le|un|una)\\s+|l'\\s*)", IC), "")
            .replace(Regex("[,\\s]+(?:adesso|subito|ora|grazie|per favore)$", IC), "").trim()
        if (nome.isEmpty()) return null
        // 0.5.0 (KO del 08/10: «apri il sito ilsole24ore.com» cercava un'app): un indirizzo si apre nel browser.
        val sito = nome.replace(Regex("^(?:(?:il\\s+)?sito(?:\\s+web)?|la\\s+pagina|pagina)\\s+(?:di\\s+|del\\s+|della\\s+)?", IC), "").trim()
        if (com.jarvis.telefono.mani.ComponiIntent.sembraSito(sito)) {
            return Piano(listOf(Azione("componi", mapOf("tipo" to "link", "url" to sito.lowercase()))), "Ho aperto $sito.", cosa = "apro $sito")
        }
        val bello = maiuscola(nome)
        return Piano(listOf(Azione("apri_app", mapOf("nome" to nome))), "Ho aperto $bello.", cosa = "apro $bello")
    }

    // ================================================================ ricerche

    private fun cercaGoogle(f: String): Piano? {
        val domanda = listOf(
            Regex("^(?:cerca|cercami|cercare|ricerca)\\s+(?:su|con|in)\\s+google[,:]?\\s+(.+)$", IC),
            Regex("^(?:cerca|cercami|cercare|ricerca)\\s+(.+?)\\s+(?:su|con|in)\\s+google$", IC),
            Regex("^(?:googla|googlami)\\s+(.+)$", IC),
            Regex("^(?:cerca|cercami|cerca in rete|cerca su internet)\\s+(.+)$", IC),
        ).firstNotNullOfOrNull { it.find(f)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotEmpty() } ?: return null
        return Piano(listOf(Azione("cerca_google", mapOf("domanda" to domanda))), "Ecco cosa trova Google.", cosa = "cerco su Google")
    }

    /** «cerca X su YouTube», «cerca su Spotify X», «metti X su YouTube». */
    private fun cercaInApp(f: String): Piano? {
        val app = "(youtube|spotify|maps|google maps|amazon|netflix|instagram|tiktok|play store|gmail|whatsapp|telegram|x|twitter)"
        val (testo, quale) = listOf(
            Regex("^(?:cerca|cercami|cercare|metti|mettimi|riproduci|fammi sentire|fammi vedere|trova)\\s+(.+?)\\s+(?:su|in|con)\\s+$app$", IC)
                .find(f)?.let { it.groupValues[1] to it.groupValues[2] },
            Regex("^(?:cerca|cercami|cercare|trova)\\s+(?:su|in|con)\\s+$app\\s+(.+)$", IC)
                .find(f)?.let { it.groupValues[2] to it.groupValues[1] },
        ).firstOrNull { it != null } ?: return null
        val t = testo.trim()
        if (t.isEmpty()) return null
        val nomeApp = NOMI_APP[quale.lowercase()] ?: maiuscola(quale)
        if (quale.lowercase() == "youtube") {
            return Piano(listOf(Azione("componi", mapOf("tipo" to "youtube", "domanda" to t))), "Ecco i video su YouTube.", cosa = "cerco su YouTube")
        }
        return Piano(
            listOf(Azione("cerca_in_app", mapOf("app" to nomeApp, "testo" to t))),
            "Ho cercato su $nomeApp.", cosa = "cerco su $nomeApp",
        )
    }

    private fun gemini(f: String): Piano? {
        val d = Regex("^(?:chiedi|domanda|chiedere)\\s+(?:a|ad)\\s+gemini\\s+(?:se\\s+|di\\s+|:\\s*)?(.+)$", IC).find(f)?.groupValues?.get(1)?.trim()
            ?: return null
        if (d.isEmpty()) return null
        return Piano(listOf(Azione("gemini_chiedi", mapOf("domanda" to d))), "Ho chiesto a Gemini: la risposta è sullo schermo.", cosa = "chiedo a Gemini")
    }

    private fun naviga(f: String): Piano? {
        Regex(
            "^(?:portami|naviga|navigazione|indicazioni|andiamo|guidami|accompagnami|percorso|strada|come arrivo|come si arriva|come faccio ad arrivare|come ci arrivo|voglio andare|devo andare|vorrei andare)" +
                "\\s+(?:(?:fino\\s+)?(?:a|ad|al|alla|allo|ai|alle|agli|all)\\s+|(?:fino\\s+)?all'\\s*|verso\\s+(?:l'\\s*|il\\s+|la\\s+)?|per\\s+|in\\s+|da\\s+|dal\\s+|dalla\\s+|dall'\\s*)(.+)$", IC,
        ).find(f)?.let {
            val dove = it.groupValues[1].trim()
            if (dove.isEmpty()) return null
            return Piano(listOf(Azione("componi", mapOf("tipo" to "naviga", "dove" to dove))), "Navigazione verso $dove.", cosa = "navigo")
        }
        Regex("^(?:dove si trova|dove sta|dov'è|dove è|mostrami sulla mappa|fammi vedere sulla mappa|cerca sulla mappa|cerca su maps)\\s+(.+)$", IC).find(f)?.let {
            val dove = it.groupValues[1].trim().replace(Regex("^(?:(?:il|lo|la|i|gli|le)\\s+|l'\\s*)", IC), "")
            return Piano(listOf(Azione("componi", mapOf("tipo" to "mappe", "dove" to dove))), "Ecco $dove sulla mappa.", cosa = "mappa")
        }
        return null
    }

    // ================================================================ chiama

    private fun chiama(f: String, ctx: Contesto): Piano? {
        if (Regex("^(?:chiama|telefona|fai una (?:telefonata|chiamata)|chiamata)$", IC).matches(f)) {
            return Piano.solo("Chi chiamo? Dimmi il nome o il numero.", "chiamata senza destinatario")
        }
        val m = Regex("^(?:chiama|chiamami|chiamare|chiamo|telefona|telefonare|fai una telefonata|fai una chiamata|fai uno squillo|componi il numero|componi)\\s+(?:a\\s+|ad\\s+|al\\s+|alla\\s+|il\\s+|la\\s+|di\\s+)?(.+)$", IC)
            .find(f) ?: return null
        val chi = m.groupValues[1].trim()
        // 0.5.0 (Boss 08/10: «chiama X» fatto fino in fondo): tastierino col numero, poi il pannello Invia/Annulla;
        // la chiamata parte solo dopo il sì di Boss, con il tocco del telefono sul tasto verde.
        fun piano(nome: String, numero: String) = Piano(
            listOf(
                Azione("componi", mapOf("tipo" to "chiama", "numero" to numero)),
                Azione("invia_bozza", mapOf("app" to "Telefono", "destinatario" to nome, "bozza" to if (nome == numero) "Chiamata al $numero" else "Chiamata a $nome ($numero)")),
            ),
            "Chiamata a $nome avviata.", cosa = "chiamo $nome",
        )
        cifre(chi)?.let { n -> return piano(n, n) }
        val c = trovaContatto(chi, ctx, mail = false)
        if (c is Trovato.Errore) return c.piano
        c as Trovato.Si
        return piano(c.nome, c.valore)
    }

    // ================================================================ messaggi

    private enum class Tipo { WHATSAPP, SMS, MAIL }

    /**
     * «manda un WhatsApp a Marco: arrivo», «scrivi a Marco su WhatsApp che arrivo», «messaggio a
     * Marco dicendo arrivo», «manda un SMS al 333 1234567: ok», «scrivi una mail a Marco con
     * oggetto riunione: ci vediamo alle 5».
     */
    private fun messaggio(f: String, ctx: Contesto): Piano? {
        val basso = f.lowercase()
        val verbo = Regex("^(manda|mandami|mandare|invia|inviare|scrivi|scrivimi|scrivere|spedisci|messaggio|messaggia|whatsapp|mail|email|sms|un messaggio|una mail|un whatsapp|un sms|rispondi)\\b").containsMatchIn(basso)
        if (!verbo) return null
        // L'app detta per prima vince («una mail a Marco: ti mando il WhatsApp» è una mail).
        val tipo = listOf(
            Tipo.WHATSAPP to Regex("\\bwhatsapp\\b"),
            Tipo.MAIL to Regex("\\b(e-?mail|mail|posta elettronica|gmail)\\b"),
            Tipo.SMS to Regex("\\b(sms|messaggino)\\b"),
            Tipo.WHATSAPP to Regex("\\bmessaggio\\b"),
            // «scrivi a Marco che arrivo»: senza app, è un messaggio WhatsApp.
            Tipo.WHATSAPP to Regex("^(scrivi|scrivere|manda|invia)\\s+(a|ad|al)\\s+"),
        ).mapNotNull { (t, r) -> r.find(basso)?.let { t to it.range.first } }.minByOrNull { it.second }?.first ?: return null
        // Via il verbo e l'app: resta «a Marco: testo» (in qualunque ordine li abbia detti Boss).
        var resto = f
        // «mandami / scrivimi un WhatsApp: …» senza altro destinatario = a me stesso.
        val versoMe = Regex("^(?:mandami|scrivimi|inviami)\\b", IC).containsMatchIn(resto)
        resto = resto.replace(Regex("^(?:manda|mandami|mandare|invia|inviami|inviare|scrivi|scrivimi|scrivere|spedisci|rispondi|messaggia)\\b\\s*", IC), "")
        resto = resto.replace(
            Regex("\\b(?:(?:un|una|il|la|lo|uno)\\s+)?(?:(?:messaggio|messaggino|mail|email|e-mail|sms|whatsapp)\\s+)?(?:(?:su|con|via|tramite|per|in)\\s+)?(?:whatsapp|posta elettronica|gmail)\\b", IC), " ",
        )
        resto = resto.replace(Regex("^\\s*(?:(?:un|una|il|la|lo)\\s+)?(?:messaggio|messaggino|mail|email|e-mail|sms)\\b", IC), " ")
        resto = resto.replace(Regex("\\s+"), " ").trim()
        // «sulla mia chat», «nella chat con me stesso», «a me»: il destinatario sono io.
        resto = resto.replace(
            Regex("^(?:sulla|nella|alla|in|su|sul)\\s+(?:mia\\s+chat|chat\\s+(?:con\\s+)?(?:me\\s+stess[oa]|me|mia))\\b", IC), "a me",
        )
        if (versoMe && (resto.isEmpty() || resto.startsWith(":") || resto.startsWith(","))) resto = "a me" + (if (resto.isEmpty()) "" else resto)
        // Il destinatario si apre con «a/ad/al/per»; senza, la frase non è un messaggio da regole.
        val ma = Regex("^(?:a|ad|al|alla|allo|per)\\s+(.+)$", IC).find(resto) ?: return Piano.solo(
            "A chi lo mando? Ripeti con il nome, per esempio: manda un WhatsApp a Marco, arrivo tardi.", "messaggio senza destinatario",
        )
        val dopoA = ma.groupValues[1].trim()

        // Oggetto della mail: «con oggetto X: testo» o «oggetto X testo Y».
        var oggetto = ""
        var corpo: String
        var chi: String
        val separatore = SEPARATORI.mapNotNull { r -> r.find(dopoA)?.let { it.range.first to it } }.minByOrNull { it.first }?.second
        if (separatore != null) {
            chi = dopoA.substring(0, separatore.range.first).trim()
            corpo = dopoA.substring(separatore.range.last + 1).trim()
        } else {
            // «a me stesso prova bozza» (Whisper senza due punti): «me stesso» non è in rubrica.
            val io = Regex("^((?:la\\s+)?mia chat|me stess[oa]|me medesimo|me)\\s+(.+)$", IC).find(dopoA)
            if (io != null) {
                chi = io.groupValues[1]; corpo = io.groupValues[2]
            } else {
                val (c, t) = dividiConRubrica(dopoA, ctx, tipo == Tipo.MAIL)
                chi = c; corpo = t
            }
        }
        if (tipo == Tipo.MAIL) {
            Regex("^(?:con\\s+)?(?:l'|l |come\\s+)?oggetto\\s+(.+?)\\s*(?:[:,]|\\s+e\\s+testo\\s+|\\s+testo\\s+|\\s+e\\s+scrivi\\s+)\\s*(.*)$", IC).find(corpo)?.let {
                oggetto = it.groupValues[1].trim(); corpo = it.groupValues[2].trim()
            }
            Regex("^(.+?)\\s+con\\s+(?:l'|l )?oggetto\\s+(.+?)$", IC).find(chi)?.let {
                chi = it.groupValues[1].trim(); oggetto = it.groupValues[2].trim()
            }
        }
        chi = chi.trim().trimEnd(',', ':').trim()
        corpo = maiuscola(corpo.trim().trimStart(',', ':').trim())
        if (chi.isEmpty()) return Piano.solo("A chi lo mando? Ripeti con il nome.", "messaggio senza destinatario")

        val aMe = A_ME.matches(chi)
        // La posta è quella scelta nelle impostazioni; vuoto = l'app predefinita del telefono. Gmail se nominato nella frase.
        val appMail = if (Regex("\\bgmail\\b", IC).containsMatchIn(f)) "gmail" else ctx.config.appMail
        val app = when (tipo) { Tipo.WHATSAPP -> "WhatsApp"; Tipo.SMS -> "SMS"; Tipo.MAIL -> nomeAppMail(appMail) }
        if (corpo.isEmpty()) {
            return Piano.solo("Cosa scrivo${if (aMe) "" else " a $chi"}? Ripeti con il messaggio.", "$app senza testo")
        }

        return when (tipo) {
            Tipo.WHATSAPP -> whatsapp(chi, corpo, aMe, ctx)
            Tipo.SMS -> sms(chi, corpo, aMe, ctx)
            Tipo.MAIL -> mail(chi, oggetto, corpo, aMe, ctx, app, appMail)
        }
    }

    private fun whatsapp(chi: String, testo: String, aMe: Boolean, ctx: Contesto): Piano {
        if (aMe) {
            // 0.1.1: la chat con me stesso si apre col MIO numero (configurazione personale, mai nel
            // codice): wa.me/<numero>?text=… porta la bozza nel campo. La ricerca per nome nella lista
            // delle chat (0.1.0) falliva: il campo di ricerca di WhatsApp non si trovava.
            val mio = ctx.config.whatsappMe
            if (mio.isEmpty()) {
                return Piano.solo("Non so il tuo numero WhatsApp: va messo nella configurazione personale, alla voce whatsapp_me.", "WhatsApp a me")
            }
            return Piano(
                listOf(
                    Azione("componi", mapOf("tipo" to "whatsapp", "numero" to "+$mio", "testo" to testo)),
                    Azione("invia_bozza", mapOf("app" to "WhatsApp", "destinatario" to "te stesso", "bozza" to testo)),
                ),
                "Inviato a te stesso.", cosa = "WhatsApp a te stesso",
            )
        }
        val numero = cifre(chi)
        val (nome, valore) = if (numero != null) numero to numero else {
            val c = trovaContatto(chi, ctx, mail = false)
            if (c is Trovato.Errore) return c.piano
            c as Trovato.Si
            c.nome to c.valore
        }
        return Piano(
            listOf(
                Azione("componi", mapOf("tipo" to "whatsapp", "numero" to valore, "testo" to testo)),
                Azione("invia_bozza", mapOf("app" to "WhatsApp", "destinatario" to nome, "bozza" to testo)),
            ),
            "Inviato a $nome.", cosa = "WhatsApp a $nome",
        )
    }

    private fun sms(chi: String, testo: String, aMe: Boolean, ctx: Contesto): Piano {
        if (aMe) return Piano.solo("Per un SMS a te stesso mi serve il tuo numero: dimmelo nella frase.", "SMS a me")
        val numero = cifre(chi)
        val (nome, valore) = if (numero != null) numero to numero else {
            val c = trovaContatto(chi, ctx, mail = false)
            if (c is Trovato.Errore) return c.piano
            c as Trovato.Si
            c.nome to c.valore
        }
        return Piano(
            listOf(
                Azione("componi", mapOf("tipo" to "sms", "numero" to valore, "testo" to testo)),
                Azione("invia_bozza", mapOf("app" to "Messaggi", "destinatario" to nome, "bozza" to testo)),
            ),
            "SMS inviato a $nome.", cosa = "SMS a $nome",
        )
    }

    private fun mail(chi: String, oggetto: String, corpo: String, aMe: Boolean, ctx: Contesto, app: String, appMail: String): Piano {
        val (nome, indirizzo) = when {
            aMe -> {
                val a = ctx.config.accountMail
                if (a.isEmpty()) return Piano.solo("Non so il tuo indirizzo: va messo nella configurazione.", "mail a me")
                "te stesso" to a
            }
            indirizzo(chi) != null -> indirizzo(chi)!!.let { it to it }
            else -> {
                val c = trovaContatto(chi, ctx, mail = true)
                if (c is Trovato.Errore) return c.piano
                c as Trovato.Si
                c.nome to c.valore
            }
        }
        val campi = mutableMapOf<String, Any?>("tipo" to "mail", "a" to listOf(indirizzo), "oggetto" to maiuscola(oggetto), "corpo" to corpo)
        campi["app"] = appMail
        return Piano(
            listOf(
                Azione("componi", campi),
                Azione("invia_bozza", mapOf("app" to app, "destinatario" to nome, "bozza" to corpo)),
            ),
            "Mail inviata a $nome.", cosa = "mail a $nome",
        )
    }

    // ================================================================ rubrica

    private sealed class Trovato {
        class Si(val nome: String, val valore: String) : Trovato()
        class Errore(val piano: Piano) : Trovato()
    }

    /** Il contatto [chi] con un numero (o una mail): univoco, o un errore detto chiaro. Mai inventato. */
    private fun trovaContatto(chi: String, ctx: Contesto, mail: Boolean): Trovato {
        val tutti = ctx.contatti ?: return Trovato.Errore(
            Piano(emptyList(), "Mi serve il permesso della rubrica per trovare $chi: te l'ho chiesto sullo schermo.", serveRubrica = true, cosa = "rubrica"),
        )
        val trovati = CercaContatti.cerca(chi, tutti).filter { if (mail) it.mail.isNotEmpty() else it.numeri.isNotEmpty() }
        if (trovati.isEmpty()) {
            return Trovato.Errore(Piano.solo("Non trovo ${maiuscola(chi)} in rubrica${if (mail) " con una mail" else ""}.", "contatto non trovato"))
        }
        val punteggi = trovati.map { it to CercaContatti.punteggio(chi, it.nome) }
        val migliore = punteggi.maxOf { it.second }
        val primi = punteggi.filter { it.second == migliore }.map { it.first }
        val unico = primi.singleOrNull() ?: return Trovato.Errore(
            Piano.solo("Ho più contatti per ${maiuscola(chi)}: ${primi.take(3).joinToString(", ") { it.nome }}. Ripeti con nome e cognome.", "contatto ambiguo"),
        )
        val voci = (if (mail) unico.mail else unico.numeri).distinctBy { if (mail) it.lowercase() else soloCifre(it).takeLast(9) }
        val scelta = when {
            voci.size == 1 -> voci[0]
            mail -> null
            // Per WhatsApp, SMS e chiamate: se c'è un solo cellulare italiano si prende quello.
            else -> voci.filter { soloCifre(it).removePrefix("0039").removePrefix("39").startsWith("3") }.singleOrNull()
        } ?: return Trovato.Errore(
            Piano.solo("${unico.nome} ha più ${if (mail) "indirizzi" else "numeri"}: dimmi quale, oppure dillo nella frase.", "più numeri"),
        )
        return Trovato.Si(unico.nome, scelta)
    }

    /**
     * Senza separatore («manda un WhatsApp a Marco Rossi arrivo tardi»): il nome è il pezzo più
     * lungo, da tre parole a una, che in rubrica dà un nome con quelle parole intere.
     */
    private fun dividiConRubrica(s: String, ctx: Contesto, mail: Boolean): Pair<String, String> {
        val parole = s.split(' ').filter { it.isNotEmpty() }
        if (parole.size <= 1) return s to ""
        // Un numero detto: le parole con cifre all'inizio sono il numero, il resto è il testo.
        val conCifre = parole.takeWhile { w -> w.any { it.isDigit() } || w == "+" }.size
        if (conCifre > 0 && cifre(parole.take(conCifre).joinToString(" ")) != null) {
            return parole.take(conCifre).joinToString(" ") to parole.drop(conCifre).joinToString(" ")
        }
        if (mail) parole.indexOfFirst { it.contains('@') }.takeIf { it >= 0 }?.let { i ->
            return parole.take(i + 1).joinToString(" ") to parole.drop(i + 1).joinToString(" ")
        }
        val tutti = ctx.contatti.orEmpty()
        for (k in minOf(3, parole.size - 1) downTo 1) {
            val nome = parole.take(k).joinToString(" ")
            if (tutti.any { CercaContatti.punteggio(nome, it.nome) >= 85 }) return nome to parole.drop(k).joinToString(" ")
        }
        return parole[0] to parole.drop(1).joinToString(" ")
    }

    // ================================================================ tolleranza (0.3.3)

    /**
     * Ultima spiaggia prima di «non ho capito»: la frase somiglia, a suono, a un comando innocuo?
     * Solo comandi che non mandano niente a nessuno (ora, data, indietro, home, scorri): un errore qui
     * costa una risposta sbagliata, mai un messaggio partito. Si dice cosa si è capito.
     *
     * Per l'ora la soglia è più bassa (Whisper storpia proprio «che ore»: «Pior sono», «Chiaro sono
     * adesso», log del 07/10) ma serve il suono «or»/«ro» prima di «sono», così «come sono» o «dove
     * sono» non diventano l'ora.
     */
    private fun somiglia(k: String, ctx: Contesto): Piano? {
        val f = fonetica(k)
        if (f.length < 4) return null
        var migliore: Pair<String, Double>? = null
        for (t in MODELLI_INNOCUI.keys) {
            val s = somiglianza(f, fonetica(t))
            if (migliore == null || s > migliore.second) migliore = t to s
        }
        val (modello, s) = migliore ?: return null
        val tipo = MODELLI_INNOCUI.getValue(modello)
        val basta = if (tipo == "ora") {
            val primaDiSono = Regex("^(.+?) son[oa]?\\b").find(k)?.groupValues?.get(1).orEmpty()
            s >= 0.6 && primaDiSono.isNotEmpty() && Regex("or|ro").containsMatchIn(fonetica(primaDiSono))
        } else s >= 0.8
        if (!basta) return null
        val capito = "Ho capito «$modello». "
        return when (tipo) {
            "ora" -> Piano.solo(capito + direOra(ctx), "ora (somiglianza)")
            "data" -> Piano.solo(capito + direData(ctx), "data (somiglianza)")
            "BACK", "HOME", "RECENTS" -> Piano(listOf(Azione("key", mapOf("name" to tipo))), "", cosa = modello)
            "giu", "su" -> Piano(listOf(Azione("scorri", mapOf("direzione" to tipo))), "", cosa = modello)
            else -> null
        }
    }

    companion object {
        private val IC = RegexOption.IGNORE_CASE

        private val ORA: List<Regex> = listOf(
            Regex("^(?:(?:mi )?(?:sai dirmi|puoi dirmi|mi sai dire|sai|dici|dimmi|vorrei sapere) )?(?:che |ke |chi |a che |qu?ale )?or[ae] (?:sono|e|e'|fa|si e fatta|abbiamo)(?: adesso| ora| di preciso| esattamente| qui)?$"),
            Regex("^(?:(?:mi )?(?:sai dirmi|puoi dirmi|mi sai dire|dici|dimmi|dammi|vorrei sapere) )?l' ?ora(?: esatta| di adesso)?$"),
            Regex("^(?:che )?ora esatta$"),
            Regex("^(?:sai |dimmi |mi dici )?(?:che )?or[ae] (?:sono|e)(?: adesso)?(?: per favore)?$"),
        )
        private val DATA: List<Regex> = listOf(
            Regex("^(?:(?:mi )?(?:sai dirmi|puoi dirmi|mi sai dire|sai|dici|dimmi|vorrei sapere) )?(?:oggi )?(?:che|ke|quale|qual e|qual e il|quale e il) ?(?:giorno|data) (?:e|e'|siamo|abbiamo|e oggi|e domani)(?: oggi)?$"),
            Regex("^(?:(?:mi )?(?:dici|dimmi|dammi) )?(?:la )?data(?: di oggi)?$"),
            Regex("^quanti ne abbiamo(?: oggi)?$"),
            Regex("^(?:oggi )?che giorno e(?: oggi)?$"),
        )
        private val GIORNI = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
        private val MESI = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")

        /** I comandi innocui per [somiglia]: modello → che cosa fa. */
        private val MODELLI_INNOCUI = linkedMapOf(
            "che ore sono" to "ora", "che ore sono adesso" to "ora", "che ora e" to "ora",
            "che giorno e oggi" to "data", "che giorno e" to "data",
            "torna indietro" to "BACK", "vai alla home" to "HOME", "app recenti" to "RECENTS",
            "scorri giu" to "giu", "scorri su" to "su",
        )

        /** Riempitivi in fondo che non cambiano il comando (solo nella chiave, mai nel testo di un messaggio). */
        private val CODA = Regex("(?:[,\\s]+(?:adesso|subito|grazie|per favore|per piacere|allora|dai|ok|okay|boss))+$")

        /**
         * 0.3.3: la chiave per riconoscere i comandi: minuscole, senza accenti, senza punteggiatura
         * (resta l'apostrofo), senza riempitivi in fondo. «Che ora è, adesso?» → «che ora e».
         */
        internal fun chiave(f: String): String {
            var s = java.text.Normalizer.normalize(f.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")
            s = s.replace('’', '\'').replace(Regex("[?!.,;:«»\"()…]"), " ").replace(Regex("\\s+"), " ").trim()
            var prima: String
            do { prima = s; s = s.replace(CODA, "").trim() } while (s != prima)
            return s
        }

        /**
         * Una forma «a suono» dell'italiano, per confrontare parole storpiate: «che» e «ke» uguali,
         * «c/ch/q» = k, «gli» = L, «gn» = N, niente h, niente doppie, niente spazi.
         */
        internal fun fonetica(s: String): String {
            var t = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")
            t = t.replace(Regex("[^a-z ]"), " ")
            t = t.replace(Regex("ch(?=[ei])"), "k").replace(Regex("gh(?=[ei])"), "g")
                .replace(Regex("sc(?=[ei])"), "S").replace(Regex("c(?=[ei])"), "C").replace(Regex("g(?=[ei])"), "G")
                .replace("qu", "kw").replace("c", "k").replace("gn", "N").replace("gli", "L").replace("h", "")
                .replace("y", "i").replace("j", "i").replace("w", "v").replace("x", "ks").replace("z", "ts")
            t = t.replace(Regex("(.)\\1"), "$1").replace(" ", "")
            return t
        }

        /** Distanza di Levenshtein. */
        internal fun distanza(a: String, b: String): Int {
            var p = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val c = IntArray(b.length + 1)
                c[0] = i
                for (j in 1..b.length) c[j] = minOf(p[j] + 1, c[j - 1] + 1, p[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                p = c
            }
            return p[b.length]
        }

        /** 1 = uguali, 0 = niente in comune. */
        internal fun somiglianza(a: String, b: String): Double = 1.0 - distanza(a, b).toDouble() / maxOf(a.length, b.length, 1)

        /** Il destinatario «io»: «me», «me stesso», «a me», «la mia chat», «boss». */
        private val A_ME = Regex("^(?:a\\s+)?(?:me|me stesso|me stessa|me medesimo|mio|mia|boss|(?:la\\s+)?mia chat|(?:la\\s+)?chat con me(?:\\s+stess[oa])?)$", IC)

        /** Parole che Whisper o il dettato spezzano o storpiano. */
        private val RICUCI: List<Pair<Regex, String>> = listOf(
            Regex("\\b(?:whats ?app|what'?s ?app|wh?ats?app?|wh?azz?app?|wass?app?|uats?app?|uozz?app?|vatsapp?|watts app)\\b", IC) to "WhatsApp",
            Regex("\\byou ?tube\\b", IC) to "YouTube",
            Regex("\\be-mail\\b", IC) to "mail",
            Regex("\\bmeil\\b", IC) to "mail",
            Regex("\\bgoogle maps\\b", IC) to "Maps",
            Regex("\\bin via\\b", IC) to "invia",
        )

        private val INIZIO = Regex(
            "^\\s*(?:(?:hey|ehi|ei|ehy|ok|okay|ciao|e)\\s+)?(?:jarvis|giarvis|jarvi|jervis|gervis|j\\.?\\s?boss|jay\\s?boss|gei\\s?boss|jei\\s?boss|ci\\s?boss|g\\s?boss|boss)[\\s,.:!]*" +
                "|^\\s*(?:ehi|ok|okay|senti|allora|dai|per favore|per piacere|ora|adesso|subito|ehm|eh|uhm|mmh|mm|beh|scusa|ascolta|ascoltami|dimmi un po'|ah|oh)[\\s,.]+" +
                "|^\\s*(?:puoi|potresti|riesci a|vorrei che|voglio che|ti chiedo di)\\s+" +
                "|^\\s*mi\\s+(?=(?:apri|cerchi|mandi|scrivi|chiami|porti|dici)\\b)",
            IC,
        )
        private val CORTESIA = Regex("[,\\s]+(?:per favore|per piacere|grazie)\\b[,]?", IC)

        /** Dove finisce il destinatario e comincia il testo. */
        private val SEPARATORI: List<Regex> = listOf(
            Regex("\\s*:\\s*"),
            Regex("\\s*,\\s*"),
            Regex("\\s+(?:che|dicendo|dicendogli|dicendole|scrivendo|scrivendogli|con scritto|con il testo|con testo|e scrivi|e digli|e dille|testo)\\s+", IC),
            Regex("\\s+(?=con\\s+(?:l'|l )?oggetto\\b)", IC),
        )

        private val NOMI_APP = mapOf(
            "youtube" to "YouTube", "spotify" to "Spotify", "maps" to "Maps", "google maps" to "Maps",
            "amazon" to "Amazon", "netflix" to "Netflix", "instagram" to "Instagram", "tiktok" to "TikTok",
            "play store" to "Play Store", "gmail" to "Gmail", "whatsapp" to "WhatsApp", "telegram" to "Telegram",
            "x" to "X", "twitter" to "X",
        )

        internal fun nomeAppMail(scelta: String): String = when {
            scelta.contains("gmail", ignoreCase = true) -> "Gmail"
            scelta.contains("samsung", ignoreCase = true) -> "Samsung Email"
            else -> "Mail"
        }

        internal fun soloCifre(s: String) = s.filter { it.isDigit() || it == '+' }

        /**
         * Un numero detto nella frase: «333 1234567», «+39 333 123 4567», «al 3331234567».
         * Almeno 6 cifre e nient'altro che cifre, spazi, trattini, punti e un + iniziale.
         */
        internal fun cifre(s: String): String? {
            val t = s.trim().removePrefix("numero").trim()
            if (!Regex("^\\+?[0-9][0-9 .\\-/]{4,}[0-9]$").matches(t)) return null
            val n = t.filter { it.isDigit() || it == '+' }
            return n.takeIf { it.count(Char::isDigit) >= 6 }
        }

        /** Un indirizzo detto nella frase: «mario.rossi@example.com» o «mario punto rossi chiocciola gmail punto com». */
        internal fun indirizzo(s: String): String? {
            var t = s.trim().lowercase()
            if (t.contains("chiocciola")) {
                t = t.replace(Regex("\\s*chiocciola\\s*"), "@").replace(Regex("\\s*punto\\s*"), ".")
                    .replace(Regex("\\s*trattino basso\\s*"), "_").replace(Regex("\\s*trattino\\s*"), "-").replace(" ", "")
            }
            return t.takeIf { Regex("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$").matches(it) }
        }

        internal fun maiuscola(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

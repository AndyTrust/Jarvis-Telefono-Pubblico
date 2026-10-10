package com.jarvis.telefono.mani

import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Le azioni «a intent» (1.2.0, Boss 2026-10-07: «manda un WhatsApp a X» apre la VERA app
 * con la bozza già scritta). Qui dentro non c'è niente di Android: solo stringhe, così la
 * composizione si prova sulla JVM. [EseguiIntent] trasforma il piano in un Intent vero.
 *
 * Nessun piano manda niente da solo: WhatsApp, mail e SMS si aprono con il testo pronto, e
 * l'invio resta un tocco su «Invia» che passa dal [CancelloInvio].
 */
data class PianoIntent(
    val azione: String,
    val uri: String? = null,
    /** In ordine di preferenza: il primo installato vince. Vuoto = l'app predefinita del telefono. */
    val pacchetti: List<String> = emptyList(),
    val extraTesti: Map<String, String> = emptyMap(),
    val extraListe: Map<String, List<String>> = emptyMap(),
    val extraNumeri: Map<String, Long> = emptyMap(),
    /** 0.5.0: extra interi e sì/no (sveglia e timer vogliono int e boolean, non long). */
    val extraInteri: Map<String, Int> = emptyMap(),
    val extraSiNo: Map<String, Boolean> = emptyMap(),
    val mime: String? = null,
    /** Cosa si dice a Claude e nel registro: mai il testo del messaggio. */
    val descrizione: String,
)

object ComponiIntent {

    const val VIEW = "android.intent.action.VIEW"
    const val SENDTO = "android.intent.action.SENDTO"
    const val SEND = "android.intent.action.SEND"
    const val DIAL = "android.intent.action.DIAL"
    const val INSERT = "android.intent.action.INSERT"
    const val SET_ALARM = "android.intent.action.SET_ALARM"
    const val SET_TIMER = "android.intent.action.SET_TIMER"
    const val EXTRA_HOUR = "android.intent.extra.alarm.HOUR"
    const val EXTRA_MINUTES = "android.intent.extra.alarm.MINUTES"
    const val EXTRA_LENGTH = "android.intent.extra.alarm.LENGTH"
    const val EXTRA_MESSAGE = "android.intent.extra.alarm.MESSAGE"
    const val EXTRA_SKIP_UI = "android.intent.extra.alarm.SKIP_UI"

    const val EXTRA_EMAIL = "android.intent.extra.EMAIL"
    const val EXTRA_CC = "android.intent.extra.CC"
    const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"

    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val TELEGRAM = "org.telegram.messenger"
    const val GMAIL = "com.google.android.gm"
    const val SAMSUNG_EMAIL = "com.samsung.android.email.provider"
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    const val CHROME = "com.android.chrome"
    const val GEMINI = "com.google.android.apps.bard"
    const val YOUTUBE = "com.google.android.youtube"

    /** Percent-encoding da URL (spazio = %20, non «+»: wa.me e mailto mostrerebbero i «+»). */
    fun codifica(testo: String): String =
        URLEncoder.encode(testo, "UTF-8").replace("+", "%20")

    /**
     * Il numero come lo vuole WhatsApp: solo cifre, con il prefisso internazionale, senza «+».
     * «333 123 4567» e «0039 333…» diventano «39333…». Null se non c'è un numero plausibile.
     */
    fun numeroInternazionale(numero: String, prefisso: String = "39"): String? {
        val pulito = numero.trim()
        val conPiu = pulito.startsWith("+")
        var cifre = pulito.filter { it.isDigit() }
        if (cifre.length < 6) return null
        if (!conPiu && cifre.startsWith("00")) return cifre.drop(2).takeIf { it.length >= 6 }
        if (conPiu) return cifre
        // Numero nazionale italiano: cellulari 3xx, fissi 0xx. Già col prefisso: 39 + 9/10 cifre.
        if (cifre.startsWith(prefisso) && cifre.length >= 11) return cifre
        cifre = prefisso + cifre
        return cifre
    }

    /** Il numero per telefono e SMS: si tiene il «+», si tolgono spazi, trattini e parentesi. */
    fun numeroLocale(numero: String): String? {
        val t = numero.trim()
        val cifre = t.filter { it.isDigit() }
        if (cifre.length < 3) return null
        return if (t.startsWith("+")) "+$cifre" else cifre
    }

    fun whatsapp(numero: String, testo: String, business: Boolean = false): PianoIntent {
        val n = numeroInternazionale(numero) ?: throw IllegalArgumentException("Numero non valido per WhatsApp: \"$numero\"")
        val uri = "https://wa.me/$n" + if (testo.isNotEmpty()) "?text=${codifica(testo)}" else ""
        val pacchetti = if (business) listOf(WHATSAPP_BUSINESS, WHATSAPP) else listOf(WHATSAPP, WHATSAPP_BUSINESS)
        return PianoIntent(VIEW, uri, pacchetti, descrizione = "WhatsApp verso +$n con la bozza nel campo (${testo.length} caratteri)")
    }

    /**
     * La mail con destinatario, oggetto e corpo. Il mailto: porta tutto nell'indirizzo e
     * negli extra: Gmail legge gli extra, altre app (Samsung) leggono l'indirizzo.
     * [pacchetto] vuoto = l'app di posta predefinita del telefono.
     */
    fun mail(
        a: List<String>,
        oggetto: String,
        corpo: String,
        cc: List<String> = emptyList(),
        pacchetto: String? = null,
    ): PianoIntent {
        val destinatari = a.map { it.trim() }.filter { it.isNotEmpty() }
        require(destinatari.all { it.contains('@') }) { "Indirizzo mail non valido: ${destinatari.joinToString()}" }
        val parametri = buildList {
            if (cc.isNotEmpty()) add("cc=" + codifica(cc.joinToString(",")))
            if (oggetto.isNotEmpty()) add("subject=" + codifica(oggetto))
            if (corpo.isNotEmpty()) add("body=" + codifica(corpo))
        }
        val uri = "mailto:" + destinatari.joinToString(",") { codifica(it).replace("%40", "@") } +
            if (parametri.isEmpty()) "" else "?" + parametri.joinToString("&")
        val testi = buildMap {
            if (oggetto.isNotEmpty()) put(EXTRA_SUBJECT, oggetto)
            // Samsung Email legge il corpo sia dal mailto: sia dall'extra e lo scriveva due volte
            // («Prova bozzaProva bozza», provato il 2026-10-07): a lei basta l'indirizzo.
            if (corpo.isNotEmpty() && pacchetto != SAMSUNG_EMAIL) put(EXTRA_TEXT, corpo)
        }
        val liste = buildMap {
            if (destinatari.isNotEmpty()) put(EXTRA_EMAIL, destinatari)
            if (cc.isNotEmpty()) put(EXTRA_CC, cc)
        }
        return PianoIntent(
            SENDTO, uri, listOfNotNull(pacchetto), testi, liste,
            descrizione = "mail a ${destinatari.joinToString()} con oggetto e corpo nella bozza",
        )
    }

    fun sms(numero: String, testo: String): PianoIntent {
        val n = numeroLocale(numero) ?: throw IllegalArgumentException("Numero non valido per l'SMS: \"$numero\"")
        return PianoIntent(
            SENDTO, "smsto:$n", extraTesti = mapOf("sms_body" to testo),
            descrizione = "SMS a $n con la bozza nel campo (${testo.length} caratteri)",
        )
    }

    /** Apre il tastierino con il numero: la chiamata parte con il tocco su «Chiama». */
    fun chiama(numero: String): PianoIntent {
        val n = numeroLocale(numero) ?: throw IllegalArgumentException("Numero non valido: \"$numero\"")
        return PianoIntent(DIAL, "tel:" + n.replace("+", "%2B"), descrizione = "tastierino con il numero $n")
    }

    /**
     * 0.5.0: la sveglia messa davvero (AlarmClock.ACTION_SET_ALARM con SKIP_UI): l'Orologio la salva senza
     * chiedere niente. Prima si apriva solo l'Orologio e la sveglia la metteva Boss.
     */
    fun sveglia(ora: Int, minuti: Int, etichetta: String = "JBoss"): PianoIntent {
        require(ora in 0..23 && minuti in 0..59) { "Ora non valida: $ora:$minuti" }
        return PianoIntent(
            SET_ALARM, extraInteri = mapOf(EXTRA_HOUR to ora, EXTRA_MINUTES to minuti),
            extraTesti = if (etichetta.isNotBlank()) mapOf(EXTRA_MESSAGE to etichetta) else emptyMap(),
            extraSiNo = mapOf(EXTRA_SKIP_UI to true),
            descrizione = "sveglia alle %02d:%02d".format(ora, minuti),
        )
    }

    /** 0.5.0: il timer fatto partire davvero (AlarmClock.ACTION_SET_TIMER con SKIP_UI). */
    fun timer(secondi: Int, etichetta: String = "JBoss"): PianoIntent {
        require(secondi in 1..86_399) { "Durata non valida: $secondi s" }
        return PianoIntent(
            SET_TIMER, extraInteri = mapOf(EXTRA_LENGTH to secondi),
            extraTesti = if (etichetta.isNotBlank()) mapOf(EXTRA_MESSAGE to etichetta) else emptyMap(),
            extraSiNo = mapOf(EXTRA_SKIP_UI to true),
            descrizione = "timer di $secondi secondi",
        )
    }

    /** Telegram: con l'utente apre la sua chat; con solo il testo apre la scelta della chat dentro Telegram. */
    fun telegram(utente: String?, testo: String): PianoIntent {
        val nome = utente?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }
        return if (nome != null) {
            PianoIntent(VIEW, "https://t.me/$nome", listOf(TELEGRAM), descrizione = "chat Telegram di @$nome")
        } else {
            PianoIntent(
                SEND, null, listOf(TELEGRAM), extraTesti = mapOf(EXTRA_TEXT to testo), mime = "text/plain",
                descrizione = "Telegram con il testo da condividere: si sceglie la chat",
            )
        }
    }

    /**
     * Ricerca Google (Boss 2026-10-07): la pagina dei risultati nell'app Google se c'è,
     * altrimenti nel browser. I risultati poi si leggono con read_screen.
     */
    fun cercaGoogle(domanda: String, nelBrowser: Boolean = false): PianoIntent {
        require(domanda.isNotBlank()) { "Ricerca vuota" }
        val uri = "https://www.google.com/search?q=" + codifica(domanda.trim())
        val pacchetti = if (nelBrowser) listOf(CHROME) else listOf(GOOGLE_APP, CHROME)
        return PianoIntent(VIEW, uri, pacchetti, descrizione = "ricerca Google: ${domanda.trim()}")
    }

    /**
     * 0.5.0 (KO del 08/10: «cerca su YouTube …» non trovava il campo di ricerca nell'app): la pagina dei risultati
     * si apre direttamente nell'app YouTube, senza toccare niente.
     */
    fun youtube(domanda: String): PianoIntent {
        require(domanda.isNotBlank()) { "Ricerca vuota" }
        return PianoIntent(VIEW, "https://www.youtube.com/results?search_query=" + codifica(domanda.trim()), listOf(YOUTUBE),
            descrizione = "ricerca YouTube: ${domanda.trim()}")
    }

    /** «ilsole24ore.com», «il sito di Ryanair.com»: è un indirizzo di sito? */
    fun sembraSito(t: String): Boolean = Regex("^(https?://)?(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}(/\\S*)?$", RegexOption.IGNORE_CASE).matches(t.trim())

    fun link(url: String): PianoIntent {
        val u = url.trim().let { if (it.contains("://")) it else "https://$it" }
        return PianoIntent(VIEW, u, descrizione = "link $u")
    }

    /** Mappe: cerca un posto, o con [naviga] parte il navigatore verso il posto. */
    fun mappe(dove: String, naviga: Boolean = false): PianoIntent =
        if (naviga) {
            PianoIntent(VIEW, "google.navigation:q=" + codifica(dove), listOf(GOOGLE_MAPS), descrizione = "navigatore verso $dove")
        } else {
            PianoIntent(VIEW, "geo:0,0?q=" + codifica(dove), descrizione = "mappa su $dove")
        }

    /**
     * Un evento nel calendario, aperto in modifica (si salva con un tocco). [inizio] e [fine]
     * come «2026-10-08T20:00», «2026-10-08 20:00» o «2026-10-08» (tutto il giorno).
     */
    fun calendario(
        titolo: String,
        inizio: String,
        fine: String? = null,
        luogo: String = "",
        note: String = "",
        zona: ZoneId = ZoneId.of("Europe/Rome"),
    ): PianoIntent {
        val (inizioMs, tuttoIlGiorno) = istante(inizio, zona)
        val fineMs = fine?.takeIf { it.isNotBlank() }?.let { istante(it, zona).first }
            ?: (inizioMs + if (tuttoIlGiorno) 86_400_000L else 3_600_000L)
        require(fineMs >= inizioMs) { "La fine viene prima dell'inizio" }
        val testi = buildMap {
            put("title", titolo)
            if (luogo.isNotEmpty()) put("eventLocation", luogo)
            if (note.isNotEmpty()) put("description", note)
        }
        val numeri = buildMap {
            put("beginTime", inizioMs)
            put("endTime", fineMs)
            if (tuttoIlGiorno) put("allDay", 1L)
        }
        return PianoIntent(
            INSERT, "content://com.android.calendar/events", extraTesti = testi, extraNumeri = numeri,
            descrizione = "evento «$titolo» il $inizio",
        )
    }

    /** Millisecondi e «tutto il giorno». Lancia IllegalArgumentException se la data non si legge. */
    fun istante(testo: String, zona: ZoneId): Pair<Long, Boolean> {
        val t = testo.trim().replace(' ', 'T')
        runCatching {
            return LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zona).toInstant().toEpochMilli() to false
        }
        runCatching {
            return LocalDate.parse(t, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(zona).toInstant().toEpochMilli() to true
        }
        throw IllegalArgumentException("Data non leggibile: \"$testo\" (serve 2026-10-08T20:00 o 2026-10-08)")
    }
}

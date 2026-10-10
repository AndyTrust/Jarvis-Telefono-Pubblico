package com.jarvis.telefono.mani

import java.text.Normalizer

/**
 * Quale app aprire quando Boss dice un nome (1.2.0: tutte le app del telefono, non solo
 * quelle con un alias). Niente Android: riceve l'elenco delle app installate e, se Boss ha
 * dato «Accesso all'uso», l'ultimo uso di ciascuna.
 */
object CatalogoApp {

    data class App(val nome: String, val pacchetto: String)

    /**
     * I nomi detti a voce che corrispondono a pacchetti noti, in ordine di preferenza. Si usano solo quelli
     * installati, quindi l'elenco vale per ogni marca (Samsung, Pixel, Xiaomi, OnePlus, Motorola…). Per la posta
     * vince l'app predefinita del sistema (vedi [cerca]).
     */
    /** Le app di posta più comuni, senza preferenza di marca: si usa la prima installata se non c'è una predefinita. */
    val POSTA: List<String> = listOf(
        "com.google.android.gm",                // Gmail (quasi tutti i telefoni con Google)
        "com.microsoft.office.outlook",         // Outlook
        "com.samsung.android.email.provider",   // Samsung Email
        "com.android.email",                    // posta di sistema AOSP / alcune marche
        "ch.protonmail.android",                // Proton Mail
    )

    /** Le parole che vogliono dire «la posta», qualunque app sia. */
    private val PAROLE_POSTA = setOf("posta", "mail", "email", "e mail", "e-mail")

    val NOTI: Map<String, List<String>> = mapOf(
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "amazon" to listOf("com.amazon.mShop.android.shopping"),
        "alexa" to listOf("com.amazon.dee.app"),
        "booking" to listOf("com.booking"),
        "airbnb" to listOf("com.airbnb.android"),
        "ryanair" to listOf("com.ryanair.cheapflights"),
        "revolut" to listOf("com.revolut.revolut"),
        "paypal" to listOf("com.paypal.android.p2pmobile"),
        "keep" to listOf("com.google.android.keep"),
        "grok" to listOf("ai.x.grok"),
        "perplexity" to listOf("ai.perplexity.app.android"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "github" to listOf("com.github.android"),
        "passbolt" to listOf("com.passbolt.mobile.android"),
        "authenticator" to listOf("com.google.android.apps.authenticator2"),
        "promemoria" to listOf("com.samsung.android.app.reminder"),
        "whatsapp business" to listOf("com.whatsapp.w4b"),
        "gmail" to listOf("com.google.android.gm"),
        "posta" to POSTA,
        "mail" to POSTA,
        "email" to POSTA,
        // Su Samsung l'etichetta di Samsung Email è «E-mail» (normalizzata «e mail»).
        "e mail" to POSTA,
        "samsung email" to listOf("com.samsung.android.email.provider"),
        "samsung mail" to listOf("com.samsung.android.email.provider"),
        "samsung e mail" to listOf("com.samsung.android.email.provider"),
        "chrome" to listOf("com.android.chrome"),
        "browser" to listOf("com.android.chrome", "com.sec.android.app.sbrowser"),
        "internet" to listOf("com.sec.android.app.sbrowser", "com.android.chrome"),
        "google" to listOf("com.google.android.googlequicksearchbox"),
        "gemini" to listOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox"),
        "youtube" to listOf("com.google.android.youtube"),
        "maps" to listOf("com.google.android.apps.maps"),
        "mappe" to listOf("com.google.android.apps.maps"),
        "navigatore" to listOf("com.google.android.apps.maps", "com.waze"),
        "waze" to listOf("com.waze"),
        "spotify" to listOf("com.spotify.music"),
        "instagram" to listOf("com.instagram.android"),
        "facebook" to listOf("com.facebook.katana"),
        "messenger" to listOf("com.facebook.orca"),
        "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
        "linkedin" to listOf("com.linkedin.android"),
        "x" to listOf("com.twitter.android"),
        "twitter" to listOf("com.twitter.android"),
        "tiktok" to listOf("com.zhiliaoapp.musically"),
        "calendario" to listOf("com.samsung.android.calendar", "com.google.android.calendar"),
        "agenda" to listOf("com.samsung.android.calendar", "com.google.android.calendar"),
        "messaggi" to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"),
        "sms" to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"),
        "telefono" to listOf("com.samsung.android.dialer", "com.google.android.dialer"),
        "chiamate" to listOf("com.samsung.android.dialer", "com.google.android.dialer"),
        "rubrica" to listOf("com.samsung.android.app.contacts", "com.google.android.contacts"),
        "notes" to listOf("com.samsung.android.app.notes"),
        "contatti" to listOf("com.samsung.android.app.contacts", "com.google.android.contacts"),
        "foto" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d"),
        "galleria" to listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
        "fotocamera" to listOf("com.sec.android.app.camera"),
        "impostazioni" to listOf("com.android.settings"),
        "play store" to listOf("com.android.vending"),
        "drive" to listOf("com.google.android.apps.docs"),
        "file" to listOf("com.sec.android.app.myfiles"),
        "orologio" to listOf("com.sec.android.app.clockpackage"),
        "sveglia" to listOf("com.sec.android.app.clockpackage"),
        "note" to listOf("com.samsung.android.app.notes"),
        "calcolatrice" to listOf("com.sec.android.app.popupcalculator"),
        "outlook" to listOf("com.microsoft.office.outlook"),
        "teams" to listOf("com.microsoft.teams"),
        "onedrive" to listOf("com.microsoft.skydrive"),
        "claude" to listOf("com.anthropic.claude"),
        "chatgpt" to listOf("com.openai.chatgpt"),
        "jarvis" to listOf("com.jarvis.telefono"),
        "jboss" to listOf("com.jarvis.telefono"),
    )

    /** Parole che indicano una categoria più che un nome: si cerca nell'etichetta. */
    private val CATEGORIE: Map<String, List<String>> = mapOf(
        "banca" to listOf("bank", "banca", "intesa", "unicredit", "fineco", "bper", "poste", "bancoposta", "revolut", "n26", "hype", "credem", "mediolanum", "isybank", "bnl", "widiba", "paypal"),
    )

    fun normalizza(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex(" +"), " ")
            .trim()

    private fun distanza(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prec = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prec + if (a[i - 1] == b[j - 1]) 0 else 1)
                prec = t
            }
        }
        return d[b.length]
    }

    /** Quanto [nome] (detto da Boss) somiglia all'etichetta [etichetta]: 0 = per niente. */
    fun punteggio(nome: String, etichetta: String): Int {
        val q = normalizza(nome)
        val e = normalizza(etichetta)
        if (q.isEmpty() || e.isEmpty()) return 0
        val qs = q.replace(" ", "")
        val es = e.replace(" ", "")
        return when {
            e == q || es == qs -> 100
            e.startsWith(q) || es.startsWith(qs) -> 90
            e.split(' ').any { it == q } -> 85
            e.contains(q) || es.contains(qs) -> 70
            q.split(' ').all { t -> e.split(' ').any { it.startsWith(t) } } -> 60
            qs.length >= 4 && distanza(qs, es) <= maxOf(1, qs.length / 4) -> 50
            qs.length >= 4 && e.split(' ').any { it.length >= 4 && distanza(qs, it) <= 1 } -> 45
            else -> 0
        }
    }

    /**
     * Le app che corrispondono a [nome], la migliore per prima. [usoRecente] (pacchetto →
     * ultimo uso in ms) scioglie i pareggi: vince quella usata più di recente.
     */
    fun cerca(
        nome: String,
        installate: List<App>,
        usoRecente: Map<String, Long> = emptyMap(),
        /** L'app di posta predefinita del sistema (null = nessuna o chiede ogni volta). */
        postaPredefinita: String? = null,
    ): List<App> {
        val q = normalizza(nome)
        if (q.isEmpty()) return emptyList()
        val perPacchetto = installate.associateBy { it.pacchetto }
        if (q in PAROLE_POSTA && postaPredefinita != null) {
            perPacchetto[postaPredefinita]?.let { pred ->
                return listOf(pred) + (POSTA.mapNotNull { perPacchetto[it] } - pred)
            }
        }
        NOTI[q]?.mapNotNull { perPacchetto[it] }?.takeIf { it.isNotEmpty() }?.let { return it }
        CATEGORIE[q]?.let { chiavi ->
            return installate
                .filter { a -> chiavi.any { normalizza(a.nome).contains(it) || a.pacchetto.contains(it) } }
                .sortedByDescending { usoRecente[it.pacchetto] ?: 0L }
        }
        perPacchetto[nome.trim()]?.let { return listOf(it) }
        return installate
            .map { it to punteggio(q, it.nome) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<App, Int>> { it.second }.thenByDescending { usoRecente[it.first.pacchetto] ?: 0L })
            .map { it.first }
    }

    /** L'elenco per Claude: usate di recente prima, poi in ordine alfabetico. */
    fun ordinaPerUso(installate: List<App>, usoRecente: Map<String, Long>): List<App> =
        installate.sortedWith(
            compareByDescending<App> { usoRecente[it.pacchetto] ?: 0L }.thenBy { normalizza(it.nome) }
        )
}

/**
 * I «profili» delle app più usate (Boss 2026-10-07): dove stanno il campo di ricerca, il
 * campo del testo e il pulsante d'invio, con id e testi da provare in ordine. Gli id cambiano
 * con gli aggiornamenti delle app: per questo ogni chiave ha dei ripieghi per descrizione e
 * testo, e quello che non è qui lo fa la navigazione generica (elementi numerati).
 */
data class ProfiloApp(
    val pacchetto: String,
    val nome: String,
    /** Cosa toccare per aprire la ricerca dentro l'app (id «:id/x» o etichetta). */
    val ricerca: List<String> = emptyList(),
    /** Il campo dove si scrive la ricerca o il messaggio. */
    val campo: List<String> = emptyList(),
    /** Il pulsante d'invio (messaggi, mail, prompt). */
    val invio: List<String> = emptyList(),
    val note: String = "",
)

object ProfiliApp {
    val TUTTI: List<ProfiloApp> = listOf(
        // 0.5.0: «chiama X» passa dal pannello Invia/Annulla; dopo il sì il telefono tocca il tasto verde.
        ProfiloApp(
            "com.google.android.dialer", "Telefono",
            invio = listOf(":id/dialpad_voice_call_button", ":id/dialpad_floating_action_button", "Chiama", "Call"),
        ),
        ProfiloApp(
            "com.whatsapp", "WhatsApp",
            ricerca = listOf(":id/menuitem_search", ":id/my_search_bar", "Cerca", "Search", "Chiedi a Meta AI o cerca"),
            campo = listOf(":id/entry", ":id/search_input", "Messaggio", "Message"),
            invio = listOf(":id/send", "Invia", "Send"),
            note = "invia_bozza dopo componi tipo whatsapp: il testo è già nel campo :id/entry",
        ),
        ProfiloApp(
            "com.whatsapp.w4b", "WhatsApp Business",
            ricerca = listOf(":id/menuitem_search", ":id/my_search_bar", "Cerca", "Search"),
            campo = listOf(":id/entry", ":id/search_input", "Messaggio"),
            invio = listOf(":id/send", "Invia", "Send"),
        ),
        ProfiloApp(
            "com.google.android.gm", "Gmail",
            ricerca = listOf(":id/open_search", ":id/search_actionbar_query_text", "Cerca nella posta", "Search in mail"),
            // :id/open_search è già il campo (verificato il 2026-10-07).
            campo = listOf(":id/open_search_view_edit_text", ":id/search_actionbar_query_text", ":id/to", ":id/subject", ":id/body"),
            invio = listOf(":id/send", "Invia", "Send"),
        ),
        ProfiloApp(
            "com.samsung.android.email.provider", "Samsung Email",
            ricerca = listOf("Cerca", "Search"),
            campo = listOf("Cerca", "Oggetto", "Subject"),
            invio = listOf("Invia", "Send"),
        ),
        ProfiloApp(
            "com.android.chrome", "Chrome",
            ricerca = listOf(":id/search_box_text", ":id/url_bar", "Cerca o digita URL", "Search or type URL"),
            campo = listOf(":id/url_bar", ":id/search_box_text"),
            note = "per una ricerca usa cerca_google; per un sito usa componi tipo link",
        ),
        ProfiloApp(
            "com.google.android.youtube", "YouTube",
            ricerca = listOf(":id/menu_search", "Cerca", "Search"),
            campo = listOf(":id/search_edit_text", "Cerca su YouTube", "Search YouTube"),
        ),
        ProfiloApp(
            "com.google.android.apps.maps", "Maps",
            ricerca = listOf(":id/search_omnibox_text_box", "Cerca qui", "Search here"),
            campo = listOf(":id/search_omnibox_edit_text", "Cerca qui", "Search here"),
            note = "per una destinazione usa componi tipo mappe (naviga=true per il navigatore)",
        ),
        ProfiloApp(
            "com.instagram.android", "Instagram",
            ricerca = listOf(":id/search_tab", "Cerca ed esplora", "Search and explore", "Cerca"),
            campo = listOf(":id/action_bar_search_edit_text", ":id/row_thread_composer_edittext", "Cerca", "Messaggio..."),
            invio = listOf(":id/row_thread_composer_send_button_container", "Invia", "Send"),
        ),
        ProfiloApp(
            "org.telegram.messenger", "Telegram",
            ricerca = listOf("Cerca", "Search"),
            campo = listOf("Cerca chat", "Cerca", "Search"),
            invio = listOf("Invia", "Send"),
        ),
        ProfiloApp(
            "com.spotify.music", "Spotify",
            ricerca = listOf("Cerca qualcosa da ascoltare", "What do you want to listen to?", ":id/search_tab", "Cerca", "Search"),
            campo = listOf(":id/query", "Cosa vuoi ascoltare?", "What do you want to listen to?"),
            note = "verificato il 2026-10-07: scheda Cerca, poi la barra «Cerca qualcosa da ascoltare»",
        ),
        ProfiloApp(
            "com.samsung.android.calendar", "Calendario",
            ricerca = listOf("Cerca", "Search"),
            note = "per creare un evento usa componi tipo calendario",
        ),
        ProfiloApp(
            "com.google.android.calendar", "Google Calendar",
            ricerca = listOf(":id/action_search", "Cerca", "Search"),
            note = "per creare un evento usa componi tipo calendario",
        ),
        ProfiloApp(
            "com.samsung.android.messaging", "Messaggi",
            ricerca = listOf("Cerca", "Search"),
            campo = listOf(":id/message_edit_text", "Inserisci messaggio", "Enter message"),
            invio = listOf(":id/send_button", "Invia", "Send"),
        ),
        ProfiloApp(
            "com.google.android.apps.messaging", "Messaggi Google",
            ricerca = listOf(":id/action_zero_state_search", ":id/zero_state_search_box_auto_complete", "Cerca messaggi", "Cerca", "Search"),
            campo = listOf(":id/zero_state_search_box_auto_complete", "Cerca messaggi"),
            invio = listOf(":id/send_message_button_icon", "Invia SMS", "Send SMS"),
        ),
        ProfiloApp(
            "com.samsung.android.dialer", "Telefono",
            ricerca = listOf("Cerca", "Search"),
            // 0.5.0: il tasto verde, toccato dal telefono solo dopo il sì di Boss (invia_bozza).
            invio = listOf(":id/dialButton", ":id/dial_button", ":id/call_button", ":id/dialpad_floating_action_button", "Chiama", "Call"),
            note = "per chiamare: componi tipo chiama, poi invia_bozza (pannello Invia/Annulla, poi il tocco sul tasto verde)",
        ),
        ProfiloApp(
            "com.google.android.apps.photos", "Foto",
            ricerca = listOf("Cerca", "Search", ":id/search_box"),
            campo = listOf(":id/search_box", "Cerca nelle tue foto", "Search your photos"),
        ),
        ProfiloApp(
            "com.sec.android.gallery3d", "Galleria",
            ricerca = listOf(":id/action_search", "Cerca", "Search"),
        ),
        ProfiloApp(
            "com.twitter.android", "X",
            ricerca = listOf("Cerca su X", "Search X", "Cerca", "Search", "Esplora", "Explore"),
            campo = listOf(":id/query_view", "Cerca", "Search"),
            invio = listOf(":id/button_tweet", ":id/tweet_button", "Posta", "Post", "Rispondi", "Reply"),
            note = "Esplora, poi la barra di ricerca. Posta/Rispondi pubblicano: passano dal cancello",
        ),
        ProfiloApp(
            "com.facebook.katana", "Facebook",
            ricerca = listOf("Cerca", "Search", "Cerca su Facebook"),
            campo = listOf("Cerca su Facebook", "Cerca", "Search"),
            invio = listOf("Pubblica", "Post", "Invia", "Send"),
        ),
        ProfiloApp(
            "com.facebook.orca", "Messenger",
            ricerca = listOf("Cerca", "Search"),
            campo = listOf("Cerca", "Search", "Messaggio", "Aa"),
            invio = listOf("Invia", "Send"),
        ),
        ProfiloApp(
            "com.linkedin.android", "LinkedIn",
            ricerca = listOf("Cerca persone, offerte di lavoro e altro", "Cerca", "Search"),
            campo = listOf(":id/search_bar_text", "Cerca", "Search"),
            invio = listOf("Pubblica", "Post", "Invia", "Send"),
        ),
        ProfiloApp(
            "com.openai.chatgpt", "ChatGPT",
            campo = listOf("Chiedi qualsiasi cosa", "Ask anything", "Messaggio", "Message"),
            invio = listOf("Invia", "Send", "Invia messaggio", "Send message"),
            note = "prompt a un'IA: nessuna conferma",
        ),
        ProfiloApp(
            "com.anthropic.claude", "Claude",
            campo = listOf("Scrivi a Claude", "Chat with Claude", "Messaggio", "Message"),
            invio = listOf("Invia", "Send", "Invia messaggio", "Send message"),
            note = "prompt a un'IA: nessuna conferma",
        ),
        ProfiloApp(
            "com.google.android.apps.docs", "Drive",
            ricerca = listOf("Cerca in Drive", "Search in Drive", "Cerca", "Search"),
            campo = listOf("Cerca in Drive", "Cerca", "Search"),
        ),
        ProfiloApp(
            "com.google.android.keep", "Keep",
            ricerca = listOf("Cerca nelle note", "Search your notes", "Cerca"),
            campo = listOf("Cerca nelle note", "Cerca"),
        ),
        ProfiloApp(
            "com.samsung.android.app.notes", "Samsung Notes",
            ricerca = listOf("Cerca", "Search"),
            campo = listOf("Cerca", "Search"),
        ),
        ProfiloApp(
            "com.amazon.mShop.android.shopping", "Amazon",
            ricerca = listOf("Cerca su Amazon.it", "Cerca", "Search"),
            campo = listOf("Cerca su Amazon.it", "Cerca", "Search"),
            invio = listOf("Acquista ora", "Buy Now", "Effettua ordine", "Place your order"),
            note = "Acquista ora/Effettua ordine passano dal cancello",
        ),
        ProfiloApp(
            "com.booking", "Booking",
            ricerca = listOf("Inserisci la destinazione", "Cerca", "Search"),
            campo = listOf("Inserisci la destinazione", "Cerca"),
        ),
        ProfiloApp(
            "com.google.android.apps.bard", "Gemini",
            campo = listOf("Chiedi a Gemini", "Ask Gemini", "Scrivi un prompt", "Enter a prompt here"),
            invio = listOf("Invia messaggio", "Send message", "Invia", "Send"),
            note = "usa gemini_chiedi",
        ),
        ProfiloApp(
            "com.google.android.googlequicksearchbox", "Google",
            ricerca = listOf(":id/googleapp_search_box", "Cerca", "Search"),
            campo = listOf(":id/googleapp_search_box", "Cerca", "Search"),
            note = "per una ricerca usa cerca_google",
        ),
    )

    private val perPacchetto = TUTTI.associateBy { it.pacchetto }

    fun di(pacchetto: String?): ProfiloApp? = pacchetto?.let { perPacchetto[it] }
}

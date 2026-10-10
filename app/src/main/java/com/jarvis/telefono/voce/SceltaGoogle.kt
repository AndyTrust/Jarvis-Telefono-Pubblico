package com.jarvis.telefono.voce

/**
 * Le decisioni del dettato con il riconoscimento di Google (1.1.8), senza Android: si provano sulla JVM.
 *
 * I codici sono quelli di `android.speech.SpeechRecognizer.ERROR_*` (ricopiati qui perché le prove
 * girano senza android.jar). Le «vie» si provano in ordine; una via che fallisce PRIMA che arrivi
 * qualunque testo, con un errore di disponibilità (rete, lingua, servizio occupato), passa alla
 * successiva; finite le vie si ripiega su Whisper.
 */
object SceltaGoogle {

    const val ERRORE_RETE_TEMPO = 1          // ERROR_NETWORK_TIMEOUT
    const val ERRORE_RETE = 2                // ERROR_NETWORK
    const val ERRORE_AUDIO = 3               // ERROR_AUDIO
    const val ERRORE_SERVER = 4              // ERROR_SERVER
    const val ERRORE_CLIENT = 5              // ERROR_CLIENT
    const val ERRORE_SILENZIO = 6            // ERROR_SPEECH_TIMEOUT
    const val ERRORE_NESSUNA_CORRISPONDENZA = 7 // ERROR_NO_MATCH
    const val ERRORE_OCCUPATO = 8            // ERROR_RECOGNIZER_BUSY
    const val ERRORE_PERMESSI = 9            // ERROR_INSUFFICIENT_PERMISSIONS
    const val ERRORE_TROPPE_RICHIESTE = 10   // ERROR_TOO_MANY_REQUESTS (API 31)
    const val ERRORE_SERVER_SCOLLEGATO = 11  // ERROR_SERVER_DISCONNECTED (API 31)
    const val ERRORE_LINGUA_NON_SUPPORTATA = 12 // ERROR_LANGUAGE_NOT_SUPPORTED (API 31)
    const val ERRORE_LINGUA_NON_PRESENTE = 13   // ERROR_LANGUAGE_UNAVAILABLE (API 31)

    /** In ordine di prova: sul telefono (API 31+), Google preferendo il pacchetto offline, Google in rete. */
    enum class Via(val etichetta: String) {
        DISPOSITIVO("google-dispositivo"),
        GOOGLE_OFFLINE("google-offline"),
        GOOGLE("google"),
    }

    enum class Azione {
        /** Si prova la via successiva (o Whisper se erano finite). */
        PROSSIMA_VIA,
        /** È arrivato del testo provvisorio: vale come risultato. */
        CONSEGNA_PARZIALE,
        NESSUN_PARLATO,
        MICROFONO_NEGATO,
        MICROFONO_GUASTO,
        /** Errore con niente in mano e nessuna via da provare: si chiude senza testo. */
        FALLITO,
    }

    private val DISPONIBILITA = setOf(
        ERRORE_RETE_TEMPO, ERRORE_RETE, ERRORE_SERVER, ERRORE_CLIENT, ERRORE_OCCUPATO,
        ERRORE_TROPPE_RICHIESTE, ERRORE_SERVER_SCOLLEGATO, ERRORE_LINGUA_NON_SUPPORTATA, ERRORE_LINGUA_NON_PRESENTE,
    )

    /**
     * Cosa fare a un `onError`.
     * - [testoVisto]: è già arrivato un risultato provvisorio non vuoto (lo si consegna invece di buttarlo);
     * - [parlatoIniziato]: `onBeginningOfSpeech` è arrivato (allora l'utente ha già parlato su questa via:
     *   cambiare via gli farebbe ripetere tutto, meglio chiudere);
     * - [chiusuraChiesta]: l'utente ha già toccato di nuovo il microfono (ERROR_CLIENT qui è la chiusura).
     */
    fun classifica(codice: Int, testoVisto: Boolean, parlatoIniziato: Boolean, chiusuraChiesta: Boolean): Azione = when {
        testoVisto -> Azione.CONSEGNA_PARZIALE
        codice == ERRORE_PERMESSI -> Azione.MICROFONO_NEGATO
        codice == ERRORE_AUDIO -> Azione.MICROFONO_GUASTO
        codice == ERRORE_SILENZIO || codice == ERRORE_NESSUNA_CORRISPONDENZA -> Azione.NESSUN_PARLATO
        chiusuraChiesta -> Azione.NESSUN_PARLATO
        codice in DISPONIBILITA && !parlatoIniziato -> Azione.PROSSIMA_VIA
        else -> Azione.FALLITO
    }

    /**
     * Le vie da provare, partendo da quella che ha funzionato l'ultima volta ([buona]) così il secondo
     * dettato non riperde tempo sulle vie che non vanno. [dispositivo]: il telefono ha il riconoscimento
     * sul dispositivo (API 31+).
     */
    fun vie(dispositivo: Boolean, buona: Via?): List<Via> {
        val tutte = Via.entries.filter { it != Via.DISPOSITIVO || dispositivo }
        if (buona == null || buona !in tutte) return tutte
        return tutte.dropWhile { it != buona }
    }

    /** Il testo da un Bundle RESULTS_RECOGNITION già estratto: la prima ipotesi, ripulita. */
    fun primo(ipotesi: List<String>?): String = ipotesi?.firstOrNull()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
}

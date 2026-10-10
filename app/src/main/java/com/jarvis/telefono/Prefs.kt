package com.jarvis.telefono

import android.content.Context

// Le preferenze del telefono. Jarvis Telefono (passo A): nessun indirizzo, nessun token, nessun server.
object Prefs {
    private const val FILE = "jarvis_prefs"
    private const val KEY_DEBUG = "debug_sbloccato"
    private const val KEY_ATTIVO = "jarvis_attivo"
    private const val KEY_PRIMO_AVVIO = "primo_avvio_fatto"
    private const val KEY_VOCE_SINTETICA = "voce_sintetica"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Modo tecnico (vedi Debug.kt): acceso solo con la password dell'utente,
    // e resta acceso finché non lo spegne. Da spento l'app non manda
    // diagnosi a nessuno.
    fun isDebug(context: Context): Boolean = prefs(context).getBoolean(KEY_DEBUG, false)

    fun setDebug(context: Context, acceso: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEBUG, acceso).apply()
    }

    // l'utente ha acceso Jarvis? Serve perché l'app deve **vivere nel telefono**: se
    // Android le toglie la memoria, se il telefono si riavvia o se qualcuno ferma
    // il processo, Jarvis deve tornare su da solo. Prima della 0.7.2 si guardava
    // `JarvisService.isRunning`, che è una variabile in memoria: morto il
    // processo tornava false, e Jarvis restava spento in silenzio finché l'utente non
    // riapriva l'app. Questa invece resta scritta sul telefono.
    // Si spegne solo quando è lui a spegnerlo (ACTION_STOP), mai da sola.
    fun isAttivo(context: Context): Boolean = prefs(context).getBoolean(KEY_ATTIVO, false)

    fun setAttivo(context: Context, acceso: Boolean) {
        prefs(context).edit().putBoolean(KEY_ATTIVO, acceso).apply()
    }

    // Alla prima apertura l'app chiede l'esclusione dalla batteria e la
    // (0.2.0: niente più sovrapposizione) (MainActivity). Una volta sola: poi stanno nelle Impostazioni.
    fun isPrimoAvvioFatto(context: Context): Boolean = prefs(context).getBoolean(KEY_PRIMO_AVVIO, false)

    fun setPrimoAvvioFatto(context: Context, fatto: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRIMO_AVVIO, fatto).apply()
    }

    // La voce sintetica (l'utente, 2026-10-04): a voce Jarvis dice solo l'esito, in
    // al massimo 2 frasi, senza comandi, percorsi e indirizzi (voce/PerLaVoce.kt).
    // Il testo a schermo resta intero. Di base accesa; dalla 0.6.1 si cambia in
    // Impostazioni → Voce («Risposte brevi a voce»).
    fun isVoceSintetica(context: Context): Boolean = prefs(context).getBoolean(KEY_VOCE_SINTETICA, true)

    fun setVoceSintetica(context: Context, accesa: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOCE_SINTETICA, accesa).apply()
    }

    // 1.2.0 (Boss 2026-10-07): l'interruttore di emergenza delle mani. Acceso = ogni azione
    // sullo schermo e ogni app aperta da Jarvis viene rifiutata dal telefono. Si accende dal
    // riquadro delle impostazioni rapide, dal pannello di conferma o dalla VPS; si spegne
    // solo dal telefono (riquadro), mai da remoto.
    private const val KEY_MANI_FERME = "mani_ferme"

    fun isManiFerme(context: Context): Boolean = prefs(context).getBoolean(KEY_MANI_FERME, false)

    fun setManiFerme(context: Context, ferme: Boolean) {
        prefs(context).edit().putBoolean(KEY_MANI_FERME, ferme).apply()
    }

    // 1.2.2 (Boss 07/10): l'ascolto della parola. Dalla 0.6.1 lo cambia Impostazioni → Voce
    // («Ascolta «Hey Boss» sempre»), insieme ad «ascolto_sempre_acceso» della configurazione.
    private const val KEY_PAROLA_ATTIVA = "parola_attiva"

    fun isParolaAttiva(context: Context): Boolean = prefs(context).getBoolean(KEY_PAROLA_ATTIVA, true)

    fun setParolaAttiva(context: Context, attiva: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAROLA_ATTIVA, attiva).apply()
    }

    // 0.6.1 (Impostazioni → Aspetto): tema «sistema», «chiaro» o «scuro», e le animazioni ridotte.
    private const val KEY_TEMA = "tema"
    private const val KEY_ANIMAZIONI_RIDOTTE = "animazioni_ridotte"
    const val TEMA_SISTEMA = "sistema"
    const val TEMA_CHIARO = "chiaro"
    const val TEMA_SCURO = "scuro"

    fun getTema(context: Context): String =
        prefs(context).getString(KEY_TEMA, TEMA_SISTEMA)?.takeIf { it == TEMA_CHIARO || it == TEMA_SCURO } ?: TEMA_SISTEMA

    fun setTema(context: Context, tema: String) {
        prefs(context).edit().putString(KEY_TEMA, tema).apply()
    }

    fun isAnimazioniRidotte(context: Context): Boolean = prefs(context).getBoolean(KEY_ANIMAZIONI_RIDOTTE, false)

    fun setAnimazioniRidotte(context: Context, ridotte: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANIMAZIONI_RIDOTTE, ridotte).apply()
    }

    // 09/10 (Boss: «dalle notifiche posso spegnerlo o metterlo in pausa, quindi non ascolta»): acceso, pausa
    // o spento. Resta scritto: il popup chiuso, il processo ucciso o il riavvio non lo cambiano.
    private const val KEY_MODO_VOCE = "modo_voce"

    fun getModoVoce(context: Context): com.jarvis.telefono.voce.ModoVoce =
        com.jarvis.telefono.voce.ModoVoce.da(prefs(context).getString(KEY_MODO_VOCE, null))

    fun setModoVoce(context: Context, modo: com.jarvis.telefono.voce.ModoVoce) {
        prefs(context).edit().putString(KEY_MODO_VOCE, modo.chiave).apply()
    }

    // 1.2.2: dove Boss ha spostato la bolla di stato (-1 = posto di partenza, in alto al centro).
    private const val KEY_BOLLA_X = "bolla_x"
    private const val KEY_BOLLA_Y = "bolla_y"

    fun getBollaX(context: Context): Int = prefs(context).getInt(KEY_BOLLA_X, -1)
    fun getBollaY(context: Context): Int = prefs(context).getInt(KEY_BOLLA_Y, -1)

    fun setBollaPosizione(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_BOLLA_X, x).putInt(KEY_BOLLA_Y, y).apply()
    }
}

package com.jarvis.telefono.vps

/**
 * Gli strumenti del cervello della VPS e chi li esegue su JBoss (0.3.4, 2026-10-07).
 *
 * jarvis-agent (server/phoneTools.js, 27 strumenti) manda `tool_call {command: {action, …}}`: `action` è già il
 * nome che PhoneActionExecutor.handle conosce (le mani sono le stesse dell'app 1.2.x), tranne `registro_azioni`
 * che phoneTools.js manda già come `registro`. Nessun adattatore sui campi: sono identici.
 *
 * Un'azione fuori da questa mappa non arriva alle mani: torna un errore chiaro alla VPS (mai un finto «fatto»).
 * Kotlin puro, provato in CervelloVpsTest (i 27 nomi di phoneTools.js letti il 2026-10-07).
 */
object MappaStrumenti {

    /** Nome dello strumento sulla VPS → `action` che arriva al telefono. */
    val DALLA_VPS: Map<String, String> = linkedMapOf(
        "componi" to "componi",
        "invia_bozza" to "invia_bozza",
        "cerca_google" to "cerca_google",
        "gemini_chiedi" to "gemini_chiedi",
        "apri_app" to "apri_app",
        "elenca_app" to "elenca_app",
        "cerca_in_app" to "cerca_in_app",
        "cerca_contatto" to "cerca_contatto",
        "read_screen" to "read_screen",
        "tocca" to "tocca",
        "pressione_lunga" to "pressione_lunga",
        "tap" to "tap",
        "scrivi" to "scrivi",
        "type_text" to "type_text",
        "scorri" to "scorri",
        "swipe" to "swipe",
        "attendi" to "attendi",
        "invio_tastiera" to "invio_tastiera",
        "key" to "key",
        "open_app" to "open_app",
        "screenshot" to "screenshot",
        "compila_accesso" to "compila_accesso",
        "registro_azioni" to "registro",
        "emergenza" to "emergenza",
        "stato_tecnico" to "stato_tecnico",
        "modo_tecnico" to "modo_tecnico",
        "request_send_confirmation" to "request_send_confirmation",
    )

    /** Le `action` che PhoneActionExecutor.handle esegue (più i due alias storici). */
    val SUL_TELEFONO: Set<String> = DALLA_VPS.values.toSet() + setOf("leggi_schermo", "tocca_elemento")

    /** Le azioni che spediscono qualcosa: passano SEMPRE dal pannello Invia/Annulla del telefono. */
    val INVII: Set<String> = setOf("invia_bozza", "request_send_confirmation")

    fun supportata(action: String): Boolean = action in SUL_TELEFONO

    fun nonSupportata(action: String): String =
        "Lo strumento «$action» non esiste su JBoss: non ho fatto niente. Usa gli strumenti del telefono elencati (componi, apri_app, cerca_google, read_screen…)."

    /** Quanto aspettare le mani: la bozza aspetta Boss fino a 2 minuti, Gemini fino a attesa_s + 30 s. */
    fun attesaMs(comando: org.json.JSONObject): Long = when (comando.optString("action")) {
        in INVII -> 135_000L
        "gemini_chiedi" -> (comando.optInt("attesa_s", 45).coerceIn(5, 120) + 30) * 1000L
        "attendi" -> 20_000L
        else -> 30_000L
    }
}

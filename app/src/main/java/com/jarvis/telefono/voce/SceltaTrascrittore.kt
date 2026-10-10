package com.jarvis.telefono.voce

import android.content.Context

/**
 * 0.3.3: chi trascrive la frase dopo la parola. Il predefinito sta in [Opzioni.trascrittore];
 * si cambia dal banco ADB (`trascrittore google|whisper`) senza toccare le schermate.
 *
 * - «google»: riconoscimento di Google sul telefono applicato all'audio già catturato
 *   ([TrascrittoreGoogle]); se non c'è o fallisce, Whisper.
 * - «whisper»: solo Whisper small (come fino alla 0.3.2).
 */
object SceltaTrascrittore {
    const val GOOGLE = "google"
    const val WHISPER = "whisper"
    private const val PREFS = "trascrittore"
    private const val CHIAVE = "scelta"

    fun valida(s: String): String? = s.trim().lowercase().takeIf { it == GOOGLE || it == WHISPER }

    fun di(context: Context, opzioni: Opzioni = Opzioni()): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHIAVE, null)?.let { valida(it) } ?: opzioni.trascrittore

    fun imposta(context: Context, scelta: String) {
        val v = valida(scelta) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHIAVE, v).apply()
    }

    /** Torna al predefinito di [Opzioni]. */
    fun azzera(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CHIAVE).apply()
    }
}

package com.jarvis.telefono.postino

import android.content.Context

/**
 * 0.3.0: il numero della posta per la pillola del Postino in Home. Solo numero e ora: niente mittenti, niente oggetti.
 * 09/10: è [StatoPostino.numeroUnico] (le mail da fare del resoconto), lo stesso della pagina e della chat di JBoss;
 * prima era la somma delle «nuove» delle caselle, un altro conto («24 nuove» in Home, «6 mail» nella pagina).
 */
object ConteggioPostino {
    private const val PREFS = "postino_conteggio"

    fun salva(c: Context, nuove: Int, quando: Long = System.currentTimeMillis()) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("nuove", nuove).putLong("quando", quando).apply()
    }

    /** (nuove, quando) dell'ultimo resoconto, o null se non ce n'è ancora uno. */
    fun ultimo(c: Context): Pair<Int, Long>? {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val q = p.getLong("quando", 0L)
        return if (q == 0L) null else p.getInt("nuove", 0) to q
    }
}

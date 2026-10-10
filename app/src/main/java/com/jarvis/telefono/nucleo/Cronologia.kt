package com.jarvis.telefono.nucleo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper

/**
 * La cronologia delle richieste e delle risposte, sul telefono (SQLite, `cronologia.db`).
 * Niente esce dal telefono (allowBackup=false nel Manifest). È la base della memoria del passo B:
 * la tabella ha già chi ha deciso (cervello) e quanto ci ha messo.
 */
class Cronologia private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "cronologia.db", null, 3) {

    data class Voce(
        val id: Long,
        val quando: Long,
        /** «boss» o «jarvis». */
        val chi: String,
        val testo: String,
        /** Per le risposte: il cervello che ha deciso («regole»). */
        val cervello: String = "",
        /** «ok», «non capito», «errore», «annullato», «voce», «scritto»… */
        val esito: String = "",
        /** Millisecondi dalla frase alla fine dell'azione (solo risposte). */
        val ms: Long = 0,
        /**
         * 0.2.0: l'agente a cui Jarvis ha passato il lavoro («postino», «mani»…, scelto da
         * [com.jarvis.telefono.agenti.Competenze]); vuoto = ha fatto Jarvis stesso.
         */
        val agenteEsecutore: String = "",
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE scambi (id INTEGER PRIMARY KEY AUTOINCREMENT, quando INTEGER NOT NULL, chi TEXT NOT NULL, " +
                "testo TEXT NOT NULL, cervello TEXT NOT NULL DEFAULT '', esito TEXT NOT NULL DEFAULT '', ms INTEGER NOT NULL DEFAULT 0, " +
                "agente_esecutore TEXT NOT NULL DEFAULT '')",
        )
        db.execSQL("CREATE INDEX scambi_quando ON scambi(quando)")
    }

    // 0.2.0: la colonna dell'agente esecutore. Le righe di prima restano, con l'esecutore vuoto.
    // (La versione 2 ha vissuto solo nelle prove del 07/10 con la colonna «agente»: si copia.)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE scambi ADD COLUMN agente_esecutore TEXT NOT NULL DEFAULT ''")
            if (oldVersion == 2) db.execSQL("UPDATE scambi SET agente_esecutore = agente")
        }
    }

    private val ascoltatori = mutableListOf<() -> Unit>()
    private val principale = Handler(Looper.getMainLooper())

    @Synchronized
    fun aggiungi(chi: String, testo: String, cervello: String = "", esito: String = "", ms: Long = 0, agenteEsecutore: String = ""): Long {
        val id = writableDatabase.insert("scambi", null, ContentValues().apply {
            put("quando", System.currentTimeMillis())
            put("chi", chi)
            put("testo", testo)
            put("cervello", cervello)
            put("esito", esito)
            put("ms", ms)
            put("agente_esecutore", agenteEsecutore)
        })
        // Si tengono le ultime 2000 righe: abbastanza per la memoria, poco spazio.
        writableDatabase.execSQL("DELETE FROM scambi WHERE id <= (SELECT MAX(id) FROM scambi) - 2000")
        avvisa()
        return id
    }

    @Synchronized
    fun ultime(n: Int = 50): List<Voce> {
        val out = ArrayList<Voce>()
        readableDatabase.rawQuery(
            "SELECT id, quando, chi, testo, cervello, esito, ms, agente_esecutore FROM scambi ORDER BY id DESC LIMIT ?",
            arrayOf(n.coerceIn(1, 500).toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += Voce(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6), c.getString(7))
            }
        }
        return out.reversed()
    }

    @Synchronized
    fun svuota() {
        writableDatabase.delete("scambi", null, null)
        avvisa()
    }

    /** [f] sul thread principale a ogni riga nuova. Restituisce chi smette di ascoltare. */
    fun osserva(f: () -> Unit): () -> Unit {
        synchronized(ascoltatori) { ascoltatori += f }
        return { synchronized(ascoltatori) { ascoltatori -= f } }
    }

    private fun avvisa() {
        val copia = synchronized(ascoltatori) { ascoltatori.toList() }
        principale.post { copia.forEach { runCatching { it() } } }
    }

    companion object {
        const val BOSS = "boss"
        const val JARVIS = "jarvis"

        @Volatile
        private var istanza: Cronologia? = null

        fun di(context: Context): Cronologia =
            istanza ?: synchronized(this) { istanza ?: Cronologia(context).also { istanza = it } }
    }
}

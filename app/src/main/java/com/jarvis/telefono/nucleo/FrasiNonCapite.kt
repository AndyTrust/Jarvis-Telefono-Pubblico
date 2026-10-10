package com.jarvis.telefono.nucleo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 0.3.3: l'archivio delle frasi che il cervello a regole NON ha capito, con data e origine
 * («voce», «scritto», «prova»), per migliorare le regole guardando le frasi vere di Boss.
 *
 * Resta sul telefono (database a parte, `non-capite.db`; allowBackup=false nel Manifest): niente rete,
 * niente log del testo. Tiene al massimo [MASSIMO] righe, le più vecchie se ne vanno.
 * Da ADB (`non_capite`) si legge solo il conteggio.
 */
class FrasiNonCapite private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "non-capite.db", null, 1) {

    data class Riga(val quando: Long, val testo: String, val origine: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE frasi (id INTEGER PRIMARY KEY AUTOINCREMENT, quando INTEGER NOT NULL, testo TEXT NOT NULL, origine TEXT NOT NULL DEFAULT '')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun aggiungi(testo: String, origine: String, quando: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.insert("frasi", null, ContentValues().apply {
            put("quando", quando); put("testo", testo.take(500)); put("origine", origine)
        })
        db.execSQL("DELETE FROM frasi WHERE id NOT IN (SELECT id FROM frasi ORDER BY id DESC LIMIT $MASSIMO)")
    }

    @Synchronized
    fun ultime(n: Int = 50): List<Riga> =
        readableDatabase.rawQuery("SELECT quando, testo, origine FROM frasi ORDER BY id DESC LIMIT ?", arrayOf(n.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Riga(c.getLong(0), c.getString(1), c.getString(2))) }
        }

    @Synchronized
    fun conta(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM frasi", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    companion object {
        const val MASSIMO = 500

        @Volatile
        private var unica: FrasiNonCapite? = null

        fun di(context: Context): FrasiNonCapite =
            unica ?: synchronized(this) { unica ?: FrasiNonCapite(context).also { unica = it } }
    }
}

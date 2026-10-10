package com.jarvis.telefono.vps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * I lavori mandati alla VPS e i loro eventi, sul telefono (SQLite, `lavori-vps.db`, accanto a
 * `cronologia.db`). Niente esce dal telefono (allowBackup=false). Ultimi 300 lavori, ultimi 3000
 * eventi per lavoro (come il tetto della VPS).
 */
class RegistroLavori private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "lavori-vps.db", null, 2), ArchivioLavori {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE lavori (id TEXT PRIMARY KEY, agente TEXT NOT NULL, titolo TEXT NOT NULL, testo TEXT NOT NULL, " +
                "stato TEXT NOT NULL, esito TEXT, riassunto TEXT, risultato TEXT, creato INTEGER NOT NULL, finito INTEGER NOT NULL DEFAULT 0, " +
                "ultimo_evento INTEGER NOT NULL DEFAULT 0, ultimo TEXT NOT NULL DEFAULT '', conferma TEXT, conferma_mandata TEXT, " +
                "modello TEXT NOT NULL DEFAULT 'sonnet', origine TEXT NOT NULL DEFAULT '')",
        )
        db.execSQL("CREATE INDEX lavori_creato ON lavori(creato)")
        db.execSQL(
            "CREATE TABLE eventi (id TEXT NOT NULL, n INTEGER NOT NULL, kind TEXT NOT NULL, ts INTEGER NOT NULL, " +
                "testo TEXT NOT NULL, dati TEXT NOT NULL DEFAULT '{}', PRIMARY KEY (id, n))",
        )
    }

    // 09/10 (versione 2): da dove è partito il lavoro, per la scheda della delega nella chat di JBoss.
    // I lavori di prima restano, con l'origine vuota.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE lavori ADD COLUMN origine TEXT NOT NULL DEFAULT ''")
    }

    @Synchronized
    override fun lavoro(id: String): LavoroLocale? =
        readableDatabase.rawQuery("SELECT * FROM lavori WHERE id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) leggi(c) else null }

    @Synchronized
    override fun salva(l: LavoroLocale) {
        writableDatabase.insertWithOnConflict("lavori", null, ContentValues().apply {
            put("id", l.id); put("agente", l.agente); put("titolo", l.titolo); put("testo", l.testo); put("stato", l.stato)
            put("esito", l.esito); put("riassunto", l.riassunto); put("risultato", l.risultato); put("creato", l.creato)
            put("finito", l.finito); put("ultimo_evento", l.ultimoEvento); put("ultimo", l.ultimo)
            put("conferma", l.conferma?.let { confermaJson(it) }); put("conferma_mandata", l.confermaMandata); put("modello", l.modello)
            put("origine", l.origine)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun elenco(limite: Int): List<LavoroLocale> =
        readableDatabase.rawQuery("SELECT * FROM lavori ORDER BY creato DESC LIMIT ?", arrayOf(limite.coerceIn(1, 500).toString())).use { c ->
            buildList { while (c.moveToNext()) add(leggi(c)) }
        }

    @Synchronized
    override fun aperti(): List<LavoroLocale> =
        readableDatabase.rawQuery("SELECT * FROM lavori WHERE stato != ? ORDER BY creato", arrayOf(LavoroLocale.FINITO)).use { c ->
            buildList { while (c.moveToNext()) add(leggi(c)) }
        }

    @Synchronized
    override fun aggiungiEvento(e: EventoLavoro): Boolean {
        val r = writableDatabase.insertWithOnConflict("eventi", null, ContentValues().apply {
            put("id", e.id); put("n", e.n); put("kind", e.kind); put("ts", e.ts); put("testo", e.testo); put("dati", e.dati)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return r != -1L
    }

    @Synchronized
    override fun eventi(id: String, daN: Int): List<EventoLavoro> =
        readableDatabase.rawQuery("SELECT id, n, kind, ts, testo, dati FROM eventi WHERE id = ? AND n > ? ORDER BY n", arrayOf(id, daN.toString())).use { c ->
            buildList { while (c.moveToNext()) add(EventoLavoro(c.getString(0), c.getInt(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5))) }
        }

    /** Tiene gli ultimi 300 lavori (e i loro eventi). */
    @Synchronized
    fun pulisci() {
        val db = writableDatabase
        db.execSQL("DELETE FROM eventi WHERE id IN (SELECT id FROM lavori WHERE stato = ? ORDER BY creato DESC LIMIT -1 OFFSET 300)", arrayOf(LavoroLocale.FINITO))
        db.execSQL("DELETE FROM lavori WHERE id IN (SELECT id FROM lavori WHERE stato = ? ORDER BY creato DESC LIMIT -1 OFFSET 300)", arrayOf(LavoroLocale.FINITO))
    }

    private fun leggi(c: android.database.Cursor): LavoroLocale {
        fun s(k: String) = c.getColumnIndexOrThrow(k).let { if (c.isNull(it)) null else c.getString(it) }
        fun l(k: String) = c.getLong(c.getColumnIndexOrThrow(k))
        val id = s("id")!!
        return LavoroLocale(
            id = id, agente = s("agente")!!, titolo = s("titolo")!!, testo = s("testo")!!, stato = s("stato")!!,
            esito = s("esito"), riassunto = s("riassunto"), risultato = s("risultato"), creato = l("creato"), finito = l("finito"),
            ultimoEvento = l("ultimo_evento").toInt(), ultimo = s("ultimo").orEmpty(),
            conferma = s("conferma")?.let { runCatching { ConfermaVps.da(id, JSONObject(it), JSONObject(it).optString("domanda")) }.getOrNull() },
            confermaMandata = s("conferma_mandata"), modello = s("modello") ?: "sonnet", origine = s("origine").orEmpty(),
        )
    }

    private fun confermaJson(c: ConfermaVps): String = JSONObject()
        .put("azione_id", c.azioneId).put("azione", c.azione).put("destinatario", c.destinatario).put("anteprima", c.anteprima)
        .put("motivo", c.motivo).put("scade_ts", c.scadeTs).put("domanda", c.domanda).toString()

    companion object {
        @Volatile private var istanza: RegistroLavori? = null
        fun di(context: Context): RegistroLavori =
            istanza ?: synchronized(this) { istanza ?: RegistroLavori(context).also { istanza = it } }
    }
}

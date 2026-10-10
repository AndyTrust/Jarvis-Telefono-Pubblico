package com.jarvis.telefono.agenti

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Gli agenti salvati sul telefono (SQLite, `agenti.db`, 0.2.0): fissati, ordine scelto da Boss,
 * autonomia, istruzioni di sistema. Niente esce dal telefono (allowBackup=false).
 *
 * Letture e scritture su un thread a parte; le risposte arrivano sul thread principale.
 * Alla prima apertura si scrivono i [CatalogoAgenti.PREDEFINITI]; un agente nuovo nel catalogo
 * si aggiunge da solo, senza toccare le scelte già fatte.
 */
class ArchivioAgenti private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "agenti.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE agenti (id TEXT PRIMARY KEY, nome TEXT NOT NULL, missione TEXT NOT NULL, " +
                "dove TEXT NOT NULL, autonomia TEXT NOT NULL, autonomia_bloccata INTEGER NOT NULL, " +
                "fissato INTEGER NOT NULL, ordine INTEGER NOT NULL, istruzioni TEXT NOT NULL DEFAULT '')",
        )
        CatalogoAgenti.PREDEFINITI.forEach { db.insert("agenti", null, valori(it)) }
    }

    // 2: Boss (07/10) vuole la Home pulita: di partenza nessun agente fissato. La versione 1 è
    // vissuta solo nelle prove di oggi con Postino, Ricercatore e Mani fissati: si tolgono.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("UPDATE agenti SET fissato = 0, ordine = 0")
    }

    private val lavoro = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-agenti") }
    private val principale = Handler(Looper.getMainLooper())
    private val ascoltatori = mutableListOf<(List<Agente>) -> Unit>()

    /** L'ultima squadra letta o salvata: chi decide le conferme la legge senza aspettare il database. */
    @Volatile private var ultimi: List<Agente>? = null

    /** Tutti gli agenti, nell'ordine del catalogo. */
    fun carica(fatto: (List<Agente>) -> Unit) {
        lavoro.execute {
            val l = runCatching { leggi() }.getOrElse { CatalogoAgenti.PREDEFINITI }
            ultimi = l
            principale.post { fatto(l) }
        }
    }

    /**
     * L'autonomia scelta per [id], letta subito (fuori dal thread principale va bene anche alla prima volta).
     * Senza agente noto: «chiedi prima».
     */
    fun autonomiaDi(id: String?): Autonomia {
        if (id == null) return Autonomia.CHIEDI_PRIMA
        val l = ultimi ?: runCatching { leggi() }.getOrNull()?.also { ultimi = it } ?: return Autonomia.CHIEDI_PRIMA
        return l.firstOrNull { it.id == id }?.autonomia ?: Autonomia.CHIEDI_PRIMA
    }

    /** Cambia gli agenti con [f], salva e avvisa chi guarda. */
    fun cambia(f: (List<Agente>) -> List<Agente>) {
        lavoro.execute {
            val prima = runCatching { leggi() }.getOrElse { return@execute }
            val dopo = Fissati.rinumera(f(prima))
            if (dopo == prima) return@execute
            val db = writableDatabase
            db.beginTransaction()
            try {
                dopo.forEach { db.insertWithOnConflict("agenti", null, valori(it), SQLiteDatabase.CONFLICT_REPLACE) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            // Solo id e scelte, niente testo: per capire chi ha cambiato la squadra e quando.
            android.util.Log.i("JarvisAgenti", "squadra: fissati=" + Fissati.fissati(dopo).joinToString(",") { it.id } +
                " autonomia=" + dopo.filter { it.autonomia == Autonomia.DA_SOLO }.joinToString(",") { it.id }.ifEmpty { "-" })
            ultimi = dopo
            val copia = synchronized(ascoltatori) { ascoltatori.toList() }
            principale.post { copia.forEach { runCatching { it(dopo) } } }
        }
    }

    /** [f] sul thread principale a ogni cambio. Restituisce chi smette di ascoltare. */
    fun osserva(f: (List<Agente>) -> Unit): () -> Unit {
        synchronized(ascoltatori) { ascoltatori += f }
        return { synchronized(ascoltatori) { ascoltatori -= f } }
    }

    private fun leggi(): List<Agente> {
        val salvati = HashMap<String, Agente>()
        readableDatabase.rawQuery(
            "SELECT id, nome, missione, dove, autonomia, autonomia_bloccata, fissato, ordine, istruzioni FROM agenti",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val base = CatalogoAgenti.predefinito(id) ?: continue
                salvati[id] = base.copy(
                    nome = c.getString(1),
                    missione = c.getString(2),
                    dove = runCatching { Dove.valueOf(c.getString(3)) }.getOrDefault(base.dove),
                    autonomia = runCatching { Autonomia.valueOf(c.getString(4)) }.getOrDefault(Autonomia.CHIEDI_PRIMA),
                    // 0.6.1: il blocco lo decide il catalogo (regola di sicurezza), non il database.
                    autonomiaBloccata = base.autonomiaBloccata,
                    fissato = c.getInt(6) != 0,
                    ordine = c.getInt(7),
                    istruzioni = c.getString(8),
                )
            }
        }
        val mancanti = CatalogoAgenti.PREDEFINITI.filter { it.id !in salvati }
        if (mancanti.isNotEmpty()) {
            val db = writableDatabase
            mancanti.forEach { db.insertWithOnConflict("agenti", null, valori(it), SQLiteDatabase.CONFLICT_IGNORE); salvati[it.id] = it }
        }
        // Una regola di sicurezza non si scavalca dal database: bloccato resta «chiedi prima».
        return CatalogoAgenti.ID.mapNotNull { salvati[it] }.map { a ->
            if (a.autonomiaBloccata && a.autonomia == Autonomia.DA_SOLO) a.copy(autonomia = Autonomia.CHIEDI_PRIMA) else a
        }
    }

    private fun valori(a: Agente) = ContentValues().apply {
        put("id", a.id)
        put("nome", a.nome)
        put("missione", a.missione)
        put("dove", a.dove.name)
        put("autonomia", a.autonomia.name)
        put("autonomia_bloccata", if (a.autonomiaBloccata) 1 else 0)
        put("fissato", if (a.fissato) 1 else 0)
        put("ordine", a.ordine)
        put("istruzioni", a.istruzioni)
    }

    companion object {
        @Volatile
        private var istanza: ArchivioAgenti? = null

        fun di(context: Context): ArchivioAgenti =
            istanza ?: synchronized(this) { istanza ?: ArchivioAgenti(context).also { istanza = it } }
    }
}

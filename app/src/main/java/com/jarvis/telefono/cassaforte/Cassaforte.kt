package com.jarvis.telefono.cassaforte

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Dove stanno i byte cifrati. Sul telefono un file nella cartella privata; nelle prove la memoria o una cartella temporanea. */
interface Deposito {
    fun leggi(): ByteArray?
    fun scrivi(b: ByteArray)
    fun cancella()
    /** Mette da parte un file che non si decifra più (chiave persa): non si sovrascrive alla cieca. */
    fun accantona() {}
}

class DepositoFile(private val file: File) : Deposito {
    override fun leggi(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun scrivi(b: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { it.write(b); it.fd.sync() }
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "cassaforte non scritta" } }
    }

    override fun cancella() { file.delete(); File(file.parentFile, file.name + ".tmp").delete() }

    override fun accantona() {
        if (file.isFile) file.renameTo(File(file.parentFile, file.name + ".illeggibile-" + System.currentTimeMillis()))
    }
}

class DepositoMemoria : Deposito {
    @Volatile var byte: ByteArray? = null
    override fun leggi() = byte
    override fun scrivi(b: ByteArray) { byte = b.copyOf() }
    override fun cancella() { byte = null }
}

/**
 * La Cassaforte di JBoss (0.3.1, 2026-10-07): account di posta, cervello, VPS, Google e altri valori, cifrati
 * con AES-256-GCM e la chiave nell'Android Keystore. Un solo file `filesDir/cassaforte.bin`.
 *
 * - Niente Room, niente SharedPreferences in chiaro, niente backup (allowBackup=false + estrazione esclusa).
 * - I log dicono solo quante voci e di che genere, mai i valori.
 * - Se il file non si decifra (es. app reinstallata: la chiave del Keystore è nuova) si mette da parte e si riparte vuoti.
 */
class Cassaforte(private val cifratore: Cifratore, private val deposito: Deposito, private val log: (String) -> Unit = {}) {
    private val lock = Any()
    @Volatile private var voci: LinkedHashMap<String, Voce>? = null

    /** true se all'ultima apertura il file c'era ma non si è potuto decifrare. */
    @Volatile var eraIlleggibile = false
        private set

    private fun carica(): LinkedHashMap<String, Voce> {
        voci?.let { return it }
        val m = LinkedHashMap<String, Voce>()
        val b = deposito.leggi()
        if (b != null) {
            val testo = runCatching { String(cifratore.decifra(b), Charsets.UTF_8) }.getOrNull()
            if (testo == null) {
                eraIlleggibile = true
                deposito.accantona()
                log("cassaforte: file non decifrabile, messo da parte; si riparte vuoti")
            } else {
                val a = runCatching { JSONObject(testo).optJSONArray("voci") }.getOrNull() ?: JSONArray()
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { Voce.daJson(it) }?.let { m[it.id] = it }
            }
        }
        voci = m
        return m
    }

    private fun salvaTutto(m: Map<String, Voce>) {
        val a = JSONArray()
        m.values.forEach { a.put(it.json()) }
        val chiaro = JSONObject().put("versione", 1).put("voci", a).toString().toByteArray(Charsets.UTF_8)
        deposito.scrivi(cifratore.cifra(chiaro))
        chiaro.fill(0)
    }

    fun elenco(): List<Voce> = synchronized(lock) { carica().values.toList() }

    fun leggi(id: String): Voce? = synchronized(lock) { carica()[id] }

    inline fun <reified T : Voce> tutte(): List<T> = elenco().filterIsInstance<T>()

    fun mail(): List<AccountMail> = tutte()
    fun vps(): Vps? = leggi(Vps.ID) as? Vps
    fun cervello(p: ProviderCervello): Cervello? = leggi("cervello-" + p.chiave) as? Cervello

    /** Aggiunge o sostituisce (stesso id). */
    fun salva(v: Voce) = synchronized(lock) {
        require(v.id.matches(Regex("[a-z0-9][a-z0-9-]{1,40}"))) { "id non valido" }
        val m = LinkedHashMap(carica())
        m[v.id] = v
        salvaTutto(m)
        voci = m
        log("cassaforte: salvata una voce ${v.genere.chiave} (${m.size} in tutto)")
    }

    fun elimina(id: String): Boolean = synchronized(lock) {
        val m = LinkedHashMap(carica())
        val c = m.remove(id) != null
        if (c) { salvaTutto(m); voci = m; log("cassaforte: tolta una voce (${m.size} restano)") }
        c
    }

    /** Svuota tutto (anche il file). La chiave del Keystore resta: la toglie [CifratoreKeystore.cancellaChiave]. */
    fun svuota() = synchronized(lock) { deposito.cancella(); voci = LinkedHashMap(); log("cassaforte: svuotata") }

    /** Il riassunto per lo schermo e i log: generi e etichette, nessun valore. */
    fun riassunto(): String = elenco().groupBy { it.genere.chiave }.entries.joinToString(", ") { "${it.key}: ${it.value.size}" }.ifEmpty { "vuota" }

    fun esporta(frase: CharArray): String = synchronized(lock) {
        val a = JSONArray(); carica().values.forEach { a.put(it.json()) }
        val chiaro = JSONObject().put("versione", 1).put("voci", a).toString().toByteArray(Charsets.UTF_8)
        try { Esportazione.esporta(chiaro, frase) } finally { chiaro.fill(0) }
    }

    /** Importa un'esportazione: le voci con lo stesso id si sostituiscono. Restituisce quante voci sono entrate. */
    fun importa(testo: String, frase: CharArray): Int = synchronized(lock) {
        val chiaro = Esportazione.importa(testo, frase)
        val a = JSONObject(String(chiaro, Charsets.UTF_8)).optJSONArray("voci") ?: JSONArray()
        chiaro.fill(0)
        val m = LinkedHashMap(carica())
        var n = 0
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { Voce.daJson(it) }?.let { m[it.id] = it; n++ }
        salvaTutto(m); voci = m
        log("cassaforte: importate $n voci")
        n
    }

    companion object {
        const val NOME_FILE = "cassaforte.bin"
        private const val TAG = "JarvisCassaforte"

        @Volatile private var unica: Cassaforte? = null

        fun di(context: Context): Cassaforte = unica ?: synchronized(this) {
            unica ?: Cassaforte(
                CifratoreKeystore(),
                DepositoFile(File(context.applicationContext.filesDir, NOME_FILE)),
            ) { Log.i(TAG, it) }.also { unica = it }
        }
    }
}

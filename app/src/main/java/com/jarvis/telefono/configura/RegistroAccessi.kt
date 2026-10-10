package com.jarvis.telefono.configura

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Il registro degli accessi ai segreti (Impostazioni → Sicurezza): chi ha mostrato, esportato, importato, cancellato
 * o mandato alla VPS una voce della cassaforte, e quando. Solo l'etichetta della voce, MAI il valore.
 * File in chiaro `filesDir/registro-accessi.json` (non ha segreti), ultime [MASSIMO] righe.
 */
class RegistroAccessi(private val file: File?, private val adesso: () -> Long = System::currentTimeMillis) {
    data class Riga(val quando: Long, val azione: String, val voce: String)

    private val righe = ArrayList<Riga>()
    private val lock = Any()

    init {
        file?.takeIf { it.isFile }?.let { f ->
            runCatching {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { righe += Riga(it.optLong("t"), it.optString("a"), it.optString("v")) }
            }
        }
    }

    fun segna(azione: String, voce: String) = synchronized(lock) {
        righe += Riga(adesso(), azione.take(40), voce.take(60))
        while (righe.size > MASSIMO) righe.removeAt(0)
        file?.let { f ->
            val a = JSONArray()
            righe.forEach { a.put(JSONObject().put("t", it.quando).put("a", it.azione).put("v", it.voce)) }
            runCatching { f.writeText(a.toString()) }
        }
    }

    /** Dalla più recente. */
    fun ultime(n: Int = 20): List<Riga> = synchronized(lock) { righe.takeLast(n).reversed() }

    companion object {
        const val MASSIMO = 200
        @Volatile private var unico: RegistroAccessi? = null

        fun di(c: Context): RegistroAccessi = unico ?: synchronized(this) {
            unico ?: RegistroAccessi(File(c.applicationContext.filesDir, "registro-accessi.json")).also { unico = it }
        }

        fun testo(r: Riga): String = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date(r.quando)) + " · " + r.azione + ": " + r.voce
    }
}

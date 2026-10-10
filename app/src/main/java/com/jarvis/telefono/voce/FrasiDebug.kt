package com.jarvis.telefono.voce

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.3.3: lo strumento di misura della voce. Se acceso (`debug_audio on` dal banco ADB) salva le
 * ultime [MASSIMO] frasi catturate dopo la parola o il tasto, come WAV 16 kHz mono 16 bit, in
 * `/sdcard/Android/data/com.jarvis.telefono/files/frasi-debug/` (raggiungibile con `adb pull`).
 *
 * - SPENTO di default: senza `debug_audio on` non si scrive niente.
 * - Sono frasi di Boss: si cancellano da sole dopo [VITA_MS] (24 ore) e a `debug_audio off`.
 *   Da lì vanno solo sul Mac di Boss, mai in git né altrove.
 * - Per ogni frase: `<ora>-crudo.wav` (tutto il microfono dalla parola in poi, pre-roll compreso,
 *   prima di ogni filtro), `<ora>-whisper.wav` (quello che è andato al trascrittore) e `<ora>.txt`
 *   (motore, tempi e testo trascritto: il testo resta in questa cartella, non nel logcat).
 */
object FrasiDebug {
    const val MASSIMO = 10
    const val VITA_MS = 24L * 60 * 60 * 1000
    private const val PREFS = "frasi_debug"
    private const val CHIAVE = "acceso_fino_a"

    fun cartella(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, "frasi-debug") }

    /** Acceso solo se `debug_audio on` è stato dato nelle ultime 24 ore. */
    fun acceso(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(CHIAVE, 0L) > System.currentTimeMillis()

    fun imposta(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(CHIAVE, if (on) System.currentTimeMillis() + VITA_MS else 0L).apply()
        if (!on) cartella(context)?.listFiles()?.forEach { it.delete() }
    }

    /** Toglie i file più vecchi di 24 ore e tiene solo le ultime [MASSIMO] frasi. */
    fun pulisci(context: Context, adesso: Long = System.currentTimeMillis()) {
        val c = cartella(context) ?: return
        val file = c.listFiles().orEmpty()
        file.filter { adesso - it.lastModified() > VITA_MS }.forEach { it.delete() }
        val frasi = c.listFiles().orEmpty().map { it.name.substringBefore('-').substringBefore('.') }.distinct().sortedDescending()
        frasi.drop(MASSIMO).forEach { vecchia -> c.listFiles().orEmpty().filter { it.name.startsWith(vecchia) }.forEach { it.delete() } }
    }

    /** Salva una frase se il debug è acceso. Mai sul thread del microfono. */
    fun salva(context: Context, crudo: FloatArray, trascritto: FloatArray, righe: List<String>) {
        if (!acceso(context)) return
        val c = cartella(context) ?: return
        c.mkdirs()
        val nome = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ITALY).format(Date())
        scriviWav(File(c, "$nome-crudo.wav"), crudo)
        scriviWav(File(c, "$nome-whisper.wav"), trascritto)
        File(c, "$nome.txt").writeText(righe.joinToString("\n", postfix = "\n"))
        pulisci(context)
    }

    /** WAV PCM 16 bit mono 16 kHz. */
    fun scriviWav(f: File, pcm: FloatArray, rate: Int = Opzioni.RATE) {
        val dati = TrascrittoreGoogleWav.pcm16(pcm)
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            fun i32(v: Int) { r.write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())) }
            fun i16(v: Int) { r.write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())) }
            r.write("RIFF".toByteArray()); i32(36 + dati.size); r.write("WAVE".toByteArray())
            r.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
            r.write("data".toByteArray()); i32(dati.size); r.write(dati)
        }
    }

    /** Legge un WAV PCM 16 bit mono (quelli scritti qui o preparati sul Mac a 16 kHz). null se il formato non va. */
    fun leggiWav(f: File): FloatArray? {
        val b = f.readBytes()
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        var i = 12
        var canali = 1; var bit = 16; var rate = 16000
        while (i + 8 <= b.size) {
            val id = String(b, i, 4)
            val n = (b[i + 4].toInt() and 0xff) or ((b[i + 5].toInt() and 0xff) shl 8) or ((b[i + 6].toInt() and 0xff) shl 16) or ((b[i + 7].toInt() and 0xff) shl 24)
            if (id == "fmt ") {
                canali = (b[i + 10].toInt() and 0xff) or ((b[i + 11].toInt() and 0xff) shl 8)
                rate = (b[i + 12].toInt() and 0xff) or ((b[i + 13].toInt() and 0xff) shl 8) or ((b[i + 14].toInt() and 0xff) shl 16)
                bit = (b[i + 22].toInt() and 0xff) or ((b[i + 23].toInt() and 0xff) shl 8)
            }
            if (id == "data") {
                if (canali != 1 || bit != 16 || rate != Opzioni.RATE) return null
                val inizio = i + 8
                val fine = minOf(b.size, inizio + n)
                return FloatArray((fine - inizio) / 2) { k ->
                    val lo = b[inizio + 2 * k].toInt() and 0xff
                    val hi = b[inizio + 2 * k + 1].toInt()
                    ((hi shl 8) or lo).toShort() / 32768f
                }
            }
            i += 8 + n + (n and 1)
        }
        return null
    }
}

/** La conversione float → PCM 16 bit, pura (si prova sulla JVM); la usa anche [TrascrittoreGoogle]. */
object TrascrittoreGoogleWav {
    fun pcm16(pcm: FloatArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}

/**
 * 0.3.3: il guadagno prima della trascrizione. Boss lontano dal telefono arriva debole (picco 0,05-0,1):
 * si porta il picco a [obiettivo], con al massimo [massimo] volte di guadagno, mai abbassando.
 * Pura: si prova sulla JVM.
 */
object Guadagno {
    fun normalizza(pcm: FloatArray, obiettivo: Float = 0.7f, massimo: Float = 10f): FloatArray {
        var picco = 0f
        for (v in pcm) { val a = if (v < 0) -v else v; if (a > picco) picco = a }
        if (picco <= 1e-4f) return pcm
        val g = (obiettivo / picco).coerceAtMost(massimo)
        if (g <= 1f) return pcm
        return FloatArray(pcm.size) { (pcm[it] * g).coerceIn(-1f, 1f) }
    }
}
